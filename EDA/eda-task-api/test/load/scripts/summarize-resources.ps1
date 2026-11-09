param(
    [Parameter(Mandatory = $true)][string]$RunFolder,
    [string]$PrometheusFolder = '',
    [double]$ExpectedIntervalSeconds = 1
)

# PowerShell 5.1. Summarizes saved evidence only; does not contact services.
# Means are arithmetic means of samples, not time-weighted means.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$culture = [Globalization.CultureInfo]::InvariantCulture
$warnings = New-Object 'System.Collections.Generic.List[string]'
$rows = New-Object 'System.Collections.Generic.List[object]'
$RunFolder = (Resolve-Path -LiteralPath $RunFolder).Path
if ([string]::IsNullOrWhiteSpace($PrometheusFolder)) {
    $PrometheusFolder = Join-Path $RunFolder 'prometheus'
}
$outputFolder = Join-Path $RunFolder 'resource-summary'
if (Test-Path -LiteralPath $outputFolder) {
    throw "Output already exists: $outputFolder. Keep it; use a new run folder."
}
if ($ExpectedIntervalSeconds -le 0) { throw 'ExpectedIntervalSeconds must be positive.' }

function Add-Note([string]$Message) {
    $warnings.Add($Message)
    Write-Warning $Message
}
function Get-Field($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}
function Read-Number($Value) {
    $number = [double]::Parse([string]$Value, [Globalization.NumberStyles]::Float, $culture)
    if ([double]::IsNaN($number) -or [double]::IsInfinity($number)) {
        throw "Non-finite value: $Value"
    }
    return $number
}
function Read-Matrix([string]$FileName) {
    $path = Join-Path $PrometheusFolder $FileName
    if (-not (Test-Path -LiteralPath $path)) {
        Add-Note "Missing $FileName; no zero value will be substituted."
        return
    }
    $document = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($document.status -ne 'success' -or $document.data.resultType -ne 'matrix') {
        throw "Expected a successful Prometheus matrix: $path"
    }
    $series = @($document.data.result)
    if ($series.Count -eq 0) {
        Add-Note "No series in $FileName."
        return
    }
    return $series
}
function Get-InstanceKey($Series) {
    $job = Get-Field $Series.metric 'job'
    $instance = Get-Field $Series.metric 'instance'
    if ([string]::IsNullOrWhiteSpace($job) -or [string]::IsNullOrWhiteSpace($instance)) {
        throw 'Raw metrics must retain job and instance labels. Do not use a sum query for this export.'
    }
    return "$job|$instance"
}
function Add-Summary([string]$Metric, [string]$Unit, $Points, [string]$Identity) {
    $ordered = @($Points | Sort-Object time)
    if ($ordered.Count -eq 0) {
        Add-Note "No valid in-window samples for $Metric."
        return
    }
    $uniqueTimes = @($ordered | Select-Object -ExpandProperty time -Unique)
    if ($uniqueTimes.Count -ne $ordered.Count) { throw "Duplicate timestamps for $Metric." }
    $largestGap = 0.0
    for ($i = 1; $i -lt $ordered.Count; $i++) {
        $gap = $ordered[$i].time - $ordered[$i - 1].time
        if ($gap -gt $largestGap) { $largestGap = $gap }
    }
    if ($ordered.Count -lt 3) { Add-Note "$Metric has fewer than 3 samples; interpretation is very limited." }
    if ($largestGap -gt 2.5 * $ExpectedIntervalSeconds) {
        Add-Note "$Metric has an observed gap of $largestGap seconds. Check collection gaps."
    }
    if (($ordered[0].time - $startSeconds) -gt 2.5 * $ExpectedIntervalSeconds -or
        ($endSeconds - $ordered[-1].time) -gt 2.5 * $ExpectedIntervalSeconds) {
        Add-Note "$Metric does not cover one or both edges of the JTL window closely."
    }
    $stats = $ordered | Measure-Object -Property value -Average -Maximum
    $rows.Add([pscustomobject]@{
        metric = $Metric
        unit = $Unit
        sampleCount = $ordered.Count
        meanSampled = ([double]$stats.Average).ToString('G17', $culture)
        maximumSampled = ([double]$stats.Maximum).ToString('G17', $culture)
        firstSampleUtc = [DateTimeOffset]::FromUnixTimeMilliseconds([long][Math]::Round($ordered[0].time * 1000)).ToString('o')
        lastSampleUtc = [DateTimeOffset]::FromUnixTimeMilliseconds([long][Math]::Round($ordered[-1].time * 1000)).ToString('o')
        windowStartUtc = $startUtc
        windowEndUtc = $endUtc
        maximumGapSeconds = $largestGap.ToString('G17', $culture)
        identity = $Identity
    })
}

$windowPath = Join-Path $RunFolder 'analysis\jtl-window.json'
if (-not (Test-Path -LiteralPath $windowPath)) { throw "Missing $windowPath. Run summarize-jtl.ps1 first." }
$window = Get-Content -LiteralPath $windowPath -Raw | ConvertFrom-Json
$startSeconds = (Read-Number $window.startMs) / 1000
$endSeconds = (Read-Number $window.endMs) / 1000
if ($endSeconds -le $startSeconds) { throw 'Invalid JTL window.' }
$startUtc = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$window.startMs).ToString('o')
$endUtc = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$window.endMs).ToString('o')
$metricIdentity = $null

$cpu = @(Read-Matrix 'cpu-raw.json')
if ($cpu.Count -gt 1) { throw 'Expected exactly one CPU series (one API instance).' }
if ($cpu.Count -eq 1) {
    $metricIdentity = Get-InstanceKey $cpu[0]
    $points = New-Object 'System.Collections.Generic.List[object]'
    foreach ($pair in $cpu[0].values) {
        $time = Read-Number $pair[0]
        if ($time -lt $startSeconds -or $time -gt $endSeconds) { continue }
        try { $value = Read-Number $pair[1] } catch { Add-Note "Invalid CPU sample at $time; excluded."; continue }
        if ($value -lt 0 -or $value -gt 1) {
            Add-Note "CPU fraction outside [0,1] at $time; excluded. Export raw process_cpu_usage, without * 100."
            continue
        }
        $points.Add([pscustomobject]@{ time = $time; value = $value * 100 })
    }
    Add-Summary 'api_process_cpu' 'percent' $points $metricIdentity
}

$memory = @(Read-Matrix 'jvm_memory-raw.json')
if ($memory.Count -gt 0) {
    $identities = @($memory | ForEach-Object { Get-InstanceKey $_ } | Select-Object -Unique)
    if ($identities.Count -ne 1) { throw 'JVM memory export contains more than one API instance/job.' }
    if ($null -ne $metricIdentity -and $metricIdentity -ne $identities[0]) {
        throw 'CPU and JVM memory refer to different instances/jobs.'
    }
    $metricIdentity = $identities[0]
    foreach ($series in $memory) {
        $area = Get-Field $series.metric 'area'
        $pool = Get-Field $series.metric 'id'
        if ($area -notin @('heap', 'nonheap') -or [string]::IsNullOrWhiteSpace($pool)) {
            throw 'JVM memory must be raw jvm_memory_used_bytes with area and id labels.'
        }
    }
    foreach ($area in @('heap', 'nonheap')) {
        $areaSeries = @($memory | Where-Object { $_.metric.area -eq $area })
        if ($areaSeries.Count -eq 0) { Add-Note "No JVM $area series."; continue }
        $poolIds = @($areaSeries | ForEach-Object { $_.metric.id } | Select-Object -Unique)
        if ($poolIds.Count -ne $areaSeries.Count) { throw "Duplicate JVM pool IDs in $area." }
        $snapshots = @{}
        foreach ($series in $areaSeries) {
            $seen = @{}
            foreach ($pair in $series.values) {
                $time = Read-Number $pair[0]
                if ($time -lt $startSeconds -or $time -gt $endSeconds) { continue }
                $key = $time.ToString('G17', $culture)
                if ($seen.ContainsKey($key)) { throw "Duplicate timestamp for JVM pool $($series.metric.id)." }
                $seen[$key] = $true
                try { $value = Read-Number $pair[1] } catch { Add-Note "Invalid JVM $area sample at $time; excluded."; continue }
                if ($value -lt 0) { Add-Note "Negative JVM $area sample at $time; excluded."; continue }
                if (-not $snapshots.ContainsKey($key)) {
                    $snapshots[$key] = [pscustomobject]@{ time = $time; total = 0.0; pools = 0 }
                }
                $snapshots[$key].total += $value
                $snapshots[$key].pools += 1
            }
        }
        $points = New-Object 'System.Collections.Generic.List[object]'
        foreach ($snapshot in $snapshots.Values) {
            if ($snapshot.pools -ne $areaSeries.Count) {
                Add-Note "Incomplete JVM $area snapshot at $($snapshot.time); excluded, not filled with zero."
                continue
            }
            $points.Add([pscustomobject]@{ time = $snapshot.time; value = $snapshot.total / 1000000 })
        }
        Add-Summary "jvm_memory_used_$area" 'MB_decimal' $points $metricIdentity
    }
}

$processPath = Join-Path $RunFolder 'api-resources.csv'
if (Test-Path -LiteralPath $processPath) {
    $processRows = @(Import-Csv -LiteralPath $processPath)
    $points = New-Object 'System.Collections.Generic.List[object]'
    $processIdentities = @{}
    foreach ($row in $processRows) {
        $stamp = Get-Field $row 'timestampUtc'
        if ([string]::IsNullOrWhiteSpace($stamp)) { $stamp = Get-Field $row 'timestamp' }
        if ([string]::IsNullOrWhiteSpace($stamp)) { throw 'api-resources.csv requires timestampUtc (or historical timestamp) with an explicit time zone.' }
        if ($stamp -notmatch '(Z|[+-]\d{2}:\d{2})$') { throw "Timestamp lacks time zone: $stamp" }
        $time = ([DateTimeOffset]::Parse($stamp, $culture)).ToUnixTimeMilliseconds() / 1000.0
        if ($time -lt $startSeconds -or $time -gt $endSeconds) { continue }
        $processId = Get-Field $row 'processId'
        $started = Get-Field $row 'processStartedUtc'
        if ([string]::IsNullOrWhiteSpace($started)) { $started = Get-Field $row 'processStartedAt' }
        if ([string]::IsNullOrWhiteSpace($processId)) { throw 'api-resources.csv requires processId.' }
        if ([string]::IsNullOrWhiteSpace($started)) { throw 'api-resources.csv requires processStartedUtc (or historical processStartedAt) to detect a restart.' }
        $processIdentities["pid=$processId;started=$started"] = $true
        try { $value = Read-Number $row.workingSetBytes } catch { Add-Note "Invalid WorkingSet sample at $stamp; excluded."; continue }
        if ($value -lt 0) { Add-Note "Negative WorkingSet sample at $stamp; excluded."; continue }
        $points.Add([pscustomobject]@{ time = $time; value = $value / 1000000 })
    }
    if ($processIdentities.Count -gt 1) { throw 'Multiple API processes or restarts inside the measurement window.' }
    Add-Summary 'api_process_working_set' 'MB_decimal' $points (@($processIdentities.Keys) -join '')
} else {
    Add-Note 'api-resources.csv absent: total resident process memory is not available from JVM pool metrics.'
}

if ($rows.Count -eq 0) { throw 'No valid resource summaries. Inspect warnings and raw evidence.' }
New-Item -ItemType Directory -Path $outputFolder | Out-Null
$rows | Export-Csv -LiteralPath (Join-Path $outputFolder 'resource-summary.csv') -NoTypeInformation -Encoding UTF8
[pscustomobject]@{
    createdUtc = [DateTimeOffset]::UtcNow.ToString('o')
    windowStartUtc = $startUtc
    windowEndUtc = $endUtc
    expectedIntervalSeconds = $ExpectedIntervalSeconds
    sourcePrometheusFolder = $PrometheusFolder
    statistic = 'Arithmetic mean and maximum of observed in-window samples; not time-weighted; peaks between samples are not observed.'
    units = 'MB_decimal = bytes / 1000000. JVM heap/nonheap is not total process RAM. CPU is raw process_cpu_usage multiplied by 100.'
    broker = 'Broker CPU/memory are not included. Preserve and analyze their raw collection separately; do not add unlike CPU denominators.'
    warnings = @($warnings.ToArray())
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputFolder 'resource-summary-metadata.json') -Encoding UTF8
Write-Output "Resource summary saved in: $outputFolder"
