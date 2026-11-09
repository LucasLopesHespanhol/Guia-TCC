[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$WindowPath,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$PrometheusUrl = 'http://localhost:9090',
    [string]$Job = 'reactive-task-api',
    [ValidateRange(0, 300)][int]$ContextSeconds = 20,
    [ValidateRange(1, 300)][int]$TimeoutSeconds = 30
)

# User-run read-only export. It does not start services or generate application load.
# Run after the context interval has elapsed; a future interval is not collected retroactively.
$ErrorActionPreference = 'Stop'
$invariant = [System.Globalization.CultureInfo]::InvariantCulture
$utf8Bom = New-Object System.Text.UTF8Encoding($true)
if (Test-Path -LiteralPath $OutputDirectory) {
    throw 'OutputDirectory already exists. Use a new directory; existing evidence is never overwritten.'
}
if ($Job -notmatch '^[A-Za-z0-9:_-]+$') { throw 'Job contains unsupported characters.' }
$baseUri = [uri]$PrometheusUrl
if (-not $baseUri.IsAbsoluteUri -or $baseUri.Scheme -notin @('http', 'https')) {
    throw 'PrometheusUrl must be an absolute HTTP(S) URL.'
}
$window = Get-Content -LiteralPath $WindowPath -Raw | ConvertFrom-Json
foreach ($property in @('startMs', 'endMs')) {
    if ($window.PSObject.Properties.Name -cnotcontains $property) {
        throw "Window file has no $property. Generate it with summarize-jmeter.ps1."
    }
}
$startMs = [long]$window.startMs
$endMs = [long]$window.endMs
if ($endMs -le $startMs -or $startMs -lt 0) { throw 'Invalid JTL observation window.' }
$fromMs = $startMs - (1000L * $ContextSeconds)
$toMs = $endMs + (1000L * $ContextSeconds)
$epoch = [DateTimeOffset]::Parse('1970-01-01T00:00:00Z', $invariant)
if ($epoch.AddMilliseconds($toMs) -gt [DateTimeOffset]::UtcNow) {
    throw 'The requested post-test context is still in the future. Wait and rerun this export.'
}
$from = ($fromMs / 1000.0).ToString('0.000', $invariant)
$to = ($toMs / 1000.0).ToString('0.000', $invariant)
# Range selectors are left-open: one additional millisecond includes samples exactly at fromMs.
$range = ($toMs - $fromMs + 1).ToString($invariant) + 'ms'
$selector = '{job="' + $Job + '"}'
$queries = @(
    @{ name = 'cpu'; rangeQuery = "process_cpu_usage$selector * 100"; rawMetric = 'process_cpu_usage'; rangeUnit = 'percent'; rawUnit = 'ratio 0-1' },
    @{ name = 'jvm_memory'; rangeQuery = "sum by (area) (jvm_memory_used_bytes$selector) / 1000000"; rawMetric = 'jvm_memory_used_bytes'; rangeUnit = 'MB decimal by area'; rawUnit = 'bytes per memory pool' },
    @{ name = 'pending'; rangeQuery = "app_messaging_rpc_pending$selector"; rawMetric = 'app_messaging_rpc_pending'; rangeUnit = 'requests'; rawUnit = 'requests' },
    @{ name = 'consumers'; rangeQuery = "app_messaging_command_consumers$selector"; rawMetric = 'app_messaging_command_consumers'; rangeUnit = 'consumers'; rawUnit = 'consumers' },
    @{ name = 'pool_acquired'; rangeQuery = "r2dbc_pool_acquired_connections$selector"; rawMetric = 'r2dbc_pool_acquired_connections'; rangeUnit = 'connections'; rawUnit = 'connections' },
    @{ name = 'pool_pending'; rangeQuery = "r2dbc_pool_pending_connections$selector"; rawMetric = 'r2dbc_pool_pending_connections'; rangeUnit = 'connections'; rawUnit = 'connections' },
    @{ name = 'pool_allocated'; rangeQuery = "r2dbc_pool_allocated_connections$selector"; rawMetric = 'r2dbc_pool_allocated_connections'; rangeUnit = 'connections'; rawUnit = 'connections' },
    @{ name = 'up'; rangeQuery = "up$selector"; rawMetric = 'up'; rangeUnit = '0 or 1'; rawUnit = '0 or 1' }
)
$destination = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $destination -ErrorAction Stop | Out-Null
$records = New-Object 'System.Collections.Generic.List[object]'
$problems = New-Object 'System.Collections.Generic.List[string]'
$base = $PrometheusUrl.TrimEnd('/')

foreach ($query in $queries) {
    foreach ($mode in @('range', 'raw')) {
        if ($mode -eq 'range') {
            $expression = $query.rangeQuery
            $url = $base + '/api/v1/query_range?query=' + [uri]::EscapeDataString($expression) +
                '&start=' + $from + '&end=' + $to + '&step=1s&limit=0'
            $unit = $query.rangeUnit
        } else {
            $expression = $query.rawMetric + $selector + '[' + $range + ']'
            $url = $base + '/api/v1/query?query=' + [uri]::EscapeDataString($expression) +
                '&time=' + $to + '&limit=0'
            $unit = $query.rawUnit
        }
        $filename = $query.name + '-' + $mode + '.json'
        $entry = [ordered]@{
            name = $query.name; mode = $mode; query = $expression; url = $url
            file = $filename; unit = $unit; exportedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            status = 'pending'; series = 0; points = 0; warnings = @(); infos = @()
        }
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $TimeoutSeconds
            $body = [string]$response.Content
            # Preserve the response JSON text; do not reconstruct or filter data points.
            [System.IO.File]::WriteAllText((Join-Path $destination $filename), $body, $utf8Bom)
            $data = $body | ConvertFrom-Json
            if ($data.status -ne 'success') { throw "Prometheus returned status '$($data.status)': $($data.error)" }
            if ($data.data.resultType -ne 'matrix') { throw "Unexpected result type: $($data.data.resultType)" }
            $entry.series = @($data.data.result).Count
            foreach ($series in @($data.data.result)) { $entry.points += @($series.values).Count }
            if ($data.warnings) { $entry.warnings = @($data.warnings) }
            if ($data.infos) { $entry.infos = @($data.infos) }
            $entry.status = 'success'
            if ($entry.series -eq 0 -or $entry.points -eq 0) {
                $entry.status = 'empty'
                $problems.Add("$($query.name)/$mode returned no samples.")
                Write-Warning "$($query.name)/${mode}: empty result saved."
            }
            if ($entry.warnings.Count -gt 0 -or $entry.infos.Count -gt 0) {
                $entry.status = 'review-required'
                $problems.Add("$($query.name)/$mode contains Prometheus warnings or infos; inspect the saved response.")
                Write-Warning "$($query.name)/${mode}: Prometheus notices require review."
            }
            Write-Output "$($query.name)/${mode}: $($entry.series) series, $($entry.points) points."
        } catch {
            $entry.status = 'error'
            $entry.error = $_.Exception.Message
            $problems.Add("$($query.name)/${mode}: $($_.Exception.Message)")
            # Preserve HTTP error bodies where PowerShell exposes them.
            $errorBody = $null
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $errorBody = $_.ErrorDetails.Message }
            if (-not $errorBody -and $_.Exception.Response) {
                try {
                    $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                    $errorBody = $reader.ReadToEnd()
                    $reader.Dispose()
                } catch { }
            }
            if ($errorBody) {
                [System.IO.File]::WriteAllText((Join-Path $destination ($query.name + '-' + $mode + '-http-error.txt')), [string]$errorBody, $utf8Bom)
            }
            Write-Warning "$($query.name)/${mode}: export error; details will be preserved in metadata."
        }
        $records.Add([pscustomobject]$entry)
    }
}
$metadata = [ordered]@{
    schemaVersion = 1; prometheusUrl = $PrometheusUrl; job = $Job
    sourceWindow = (Resolve-Path -LiteralPath $WindowPath).Path
    testStartMs = $startMs; testEndMs = $endMs
    contextStartMs = $fromMs; contextEndMs = $toMs; contextSeconds = $ContextSeconds
    evaluationStep = '1s'
    notes = @(
        'Range-query timestamps are evaluation times and may repeat the latest scrape. Raw range-vector queries retain original stored sample timestamps.',
        'Context margins are for inspection. Restrict numerical test results to testStartMs through testEndMs.',
        'JVM heap/nonheap usage is not total resident RAM. CPU percent is the application process CPU metric, not whole-machine or RabbitMQ CPU.',
        'No result limits or manual truncation are applied. Inspect warnings, infos, missing intervals and up before treating the export as complete.'
    )
    queries = @($records.ToArray()); problems = @($problems.ToArray())
}
[System.IO.File]::WriteAllText((Join-Path $destination 'queries-metadata.json'),
    ($metadata | ConvertTo-Json -Depth 12), $utf8Bom)
if ($problems.Count -gt 0) {
    throw "Export requires review: $($problems.Count) problem(s). All obtained responses and metadata were preserved in $destination."
}
Write-Output "Prometheus export saved in: $destination"
