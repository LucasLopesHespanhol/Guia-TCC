[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InputPath,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string[]]$Labels = @('Post', 'Get', 'GetList', 'Put', 'Delete')
)

# User-run analysis only. No application calls and no test execution.
$ErrorActionPreference = 'Stop'
$invariant = [System.Globalization.CultureInfo]::InvariantCulture
$utf8Bom = New-Object System.Text.UTF8Encoding($true)

function Read-Number([string]$Text, [string]$Field, [int]$RowNumber) {
    $value = 0.0
    if (-not [double]::TryParse($Text, [System.Globalization.NumberStyles]::Float,
            $invariant, [ref]$value) -or [double]::IsNaN($value) -or
            [double]::IsInfinity($value) -or $value -lt 0) {
        throw "Invalid non-negative number in $Field at CSV record $RowNumber."
    }
    return $value
}

function Number-Text($Value) {
    if ($null -eq $Value) { return '' }
    return ([double]$Value).ToString('0.######', $invariant)
}

function Average-Of($Items, [string]$Property) {
    if ($Items.Count -eq 0) { return $null }
    return ($Items | Measure-Object -Property $Property -Average).Average
}

function P95-Of($Items) {
    if ($Items.Count -eq 0) { return $null }
    $ordered = @($Items | Sort-Object Elapsed | ForEach-Object { $_.Elapsed })
    $index = [int][math]::Ceiling(0.95 * $ordered.Count) - 1
    return $ordered[$index]
}

if (Test-Path -LiteralPath $OutputDirectory) {
    throw 'OutputDirectory already exists. Choose a new directory; previous evidence is preserved.'
}
if ($Labels.Count -eq 0 -or @($Labels | Select-Object -Unique).Count -ne $Labels.Count) {
    throw 'Labels must contain distinct, non-empty operation names.'
}
foreach ($label in $Labels) {
    if ([string]::IsNullOrWhiteSpace($label) -or $label -ceq 'TOTAL') {
        throw 'Empty labels and the reserved label TOTAL are not allowed.'
    }
}
$source = (Resolve-Path -LiteralPath $InputPath).Path
$rows = @(Import-Csv -LiteralPath $source -Delimiter ',')
if ($rows.Count -eq 0) { throw 'The JTL has no sample records.' }
$required = @('timeStamp', 'elapsed', 'label', 'success', 'Latency')
foreach ($field in $required) {
    if ($rows[0].PSObject.Properties.Name -cnotcontains $field) {
        throw "Required CSV field is missing: $field. Use a CSV JTL with epoch-millisecond timestamps."
    }
}

$parsed = New-Object 'System.Collections.Generic.List[object]'
$record = 1
foreach ($row in $rows) {
    $record++
    if ($Labels -cnotcontains $row.label) {
        throw "Unexpected label '$($row.label)' at record $record. Do not mix HTTP children and synthetic parent operations. Select an appropriate input and explicit Labels."
    }
    $stamp = Read-Number $row.timeStamp 'timeStamp' $record
    if ([math]::Floor($stamp) -ne $stamp -or $stamp -gt 253402300799999) {
        throw "timeStamp must be epoch milliseconds at record $record."
    }
    $elapsed = Read-Number $row.elapsed 'elapsed' $record
    $latency = Read-Number $row.Latency 'Latency' $record
    if ($row.success -notin @('true', 'false')) {
        throw "success must be true or false at record $record."
    }
    $parsed.Add([pscustomobject]@{
        Label = $row.label
        StartMs = $stamp
        EndMs = $stamp + $elapsed
        Elapsed = $elapsed
        Latency = $latency
        Success = ($row.success -eq 'true')
    })
}

$samples = @($parsed.ToArray())
$globalStart = ($samples | Measure-Object StartMs -Minimum).Minimum
$globalEnd = ($samples | Measure-Object EndMs -Maximum).Maximum
$globalSeconds = ($globalEnd - $globalStart) / 1000.0
if ($globalSeconds -le 0) { throw 'The global observation window has zero duration.' }
$summary = New-Object 'System.Collections.Generic.List[object]'

foreach ($label in @($Labels) + @('TOTAL')) {
    $group = if ($label -ceq 'TOTAL') { @($samples) } else {
        @($samples | Where-Object { $_.Label -ceq $label })
    }
    if ($group.Count -eq 0) {
        Write-Warning "No samples for label '$label'; no fabricated zero-valued row will be written."
        continue
    }
    $successes = @($group | Where-Object Success)
    $failed = $group.Count - $successes.Count
    $labelStart = ($group | Measure-Object StartMs -Minimum).Minimum
    $labelEnd = ($group | Measure-Object EndMs -Maximum).Maximum
    $labelSeconds = ($labelEnd - $labelStart) / 1000.0
    $jmeterThroughput = if ($labelSeconds -gt 0) { $group.Count / $labelSeconds } else { $null }
    $summary.Add([pscustomobject][ordered]@{
        label = $label
        samples = $group.Count
        successful = $successes.Count
        failed = $failed
        errorPercent = Number-Text (100.0 * $failed / $group.Count)
        elapsedAverageAllMs = Number-Text (Average-Of $group 'Elapsed')
        elapsedAverageSuccessfulMs = Number-Text (Average-Of $successes 'Elapsed')
        elapsedP95AllMs = Number-Text (P95-Of $group)
        elapsedP95SuccessfulMs = Number-Text (P95-Of $successes)
        latencyAverageAllMs = Number-Text (Average-Of $group 'Latency')
        latencyAverageSuccessfulMs = Number-Text (Average-Of $successes 'Latency')
        labelWindowSeconds = Number-Text $labelSeconds
        jmeterThroughputPerSecond = Number-Text $jmeterThroughput
        globalWindowSeconds = Number-Text $globalSeconds
        successfulPerGlobalSecond = Number-Text ($successes.Count / $globalSeconds)
    })
}

$epoch = [DateTimeOffset]::Parse('1970-01-01T00:00:00Z', $invariant)
$window = [ordered]@{
    schemaVersion = 1
    sourceJtl = $source
    sourceSha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    labels = $Labels
    samples = $samples.Count
    failed = @($samples | Where-Object { -not $_.Success }).Count
    startMs = [long]$globalStart
    endMs = [long][math]::Ceiling($globalEnd)
    startUtc = $epoch.AddMilliseconds($globalStart).ToString('o', $invariant)
    endUtc = $epoch.AddMilliseconds($globalEnd).ToString('o', $invariant)
    durationSeconds = $globalSeconds
    timestampConvention = 'JMeter timeStamp is sample start, epoch milliseconds; sample end = timeStamp + elapsed.'
    percentileMethod = 'Nearest rank: sorted sample at ceiling(0.95 * N).'
    throughputNotes = 'JMeter throughput includes all samples over each label own window. Successful throughput uses the same global window for every row. TOTAL is a mix of the explicitly allowed operation labels.'
    completionNotes = 'success is the JMeter assertion result, not independent proof of commit. In R1, successful CRUD HTTP responses confirm completion. For EDA, summarize separately prepared completed-business parent samples; never count acceptance or polling as completed operations.'
}

$destination = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $destination -ErrorAction Stop | Out-Null
$csvText = (@($summary.ToArray() | ConvertTo-Csv -NoTypeInformation) -join "`r`n") + "`r`n"
[System.IO.File]::WriteAllText((Join-Path $destination 'jmeter-summary.csv'), $csvText, $utf8Bom)
[System.IO.File]::WriteAllText((Join-Path $destination 'jtl-window.json'),
    ($window | ConvertTo-Json -Depth 8), $utf8Bom)
$summary.ToArray() | Format-Table label, samples, successful, failed, errorPercent, elapsedAverageAllMs, jmeterThroughputPerSecond -AutoSize
Write-Output "Analysis saved in: $destination"
