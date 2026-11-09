# Compatible with Windows PowerShell 5.1. Requires a running Docker container.
# Example: .\collect-broker-resources.ps1 -RunFolder 'C:\results\run-01'
# Stop with Ctrl+C. Default: 900 seconds, plus any bounded in-flight query.
# Docker stats can take multiple seconds: timestamps record actual query timing.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $RunFolder,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]*$')]
    [string] $ContainerName = 'reactive-task-api-rabbitmq-1',
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*\.jsonl$')]
    [string] $FileName = 'broker-resources.jsonl',
    [ValidateRange(1, 86400)]
    [int] $DurationSeconds = 900,
    [string] $StopFile = ""
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$folder = Get-Item -LiteralPath $RunFolder
if (-not $folder.PSIsContainer) { throw 'RunFolder must be an existing directory.' }
$outputPath = Join-Path $folder.FullName $FileName
$metadataPath = [IO.Path]::ChangeExtension($outputPath, '.metadata.json')
if ((Test-Path -LiteralPath $outputPath) -or (Test-Path -LiteralPath $metadataPath)) {
    throw 'Output already exists. Use a new run directory or a different FileName.'
}
$dockerPath = (Get-Command docker.exe -CommandType Application -ErrorAction Stop).Source

function Invoke-DockerReadOnly {
    param([Parameter(Mandatory = $true)] [string] $Arguments)
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $dockerPath
    $startInfo.Arguments = $Arguments
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $nativeProcess = New-Object System.Diagnostics.Process
    $nativeProcess.StartInfo = $startInfo
    try {
        if (-not $nativeProcess.Start()) { throw 'Docker client could not start.' }
        $stdoutTask = $nativeProcess.StandardOutput.ReadToEndAsync()
        $stderrTask = $nativeProcess.StandardError.ReadToEndAsync()
        if (-not $nativeProcess.WaitForExit(15000)) {
            $nativeProcess.Kill()
            throw 'Docker query exceeded 15 seconds. Collection stopped.'
        }
        $nativeProcess.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($nativeProcess.ExitCode -ne 0) {
            throw ('Docker query failed with exit code {0}: {1}' -f $nativeProcess.ExitCode, $stderr.Trim())
        }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) { Write-Warning $stderr.Trim() }
        return $stdout.Trim()
    } finally {
        if ($null -ne $nativeProcess) {
            try { if (-not $nativeProcess.HasExited) { $nativeProcess.Kill() } } catch { }
            $nativeProcess.Dispose()
        }
    }
}

function Get-ContainerIdentity {
    # Selected fields only: never dump container environment variables or secrets.
    $raw = Invoke-DockerReadOnly ('inspect --type container --format "{{.Id}}|{{.State.StartedAt}}|{{.State.Running}}" ' + $ContainerName)
    $parts = @($raw -split '\|')
    if ($parts.Count -ne 3 -or $parts[2] -ne 'true') {
        throw 'Expected one running container. Collection stopped.'
    }
    return [pscustomobject]@{ id = $parts[0]; startedAt = $parts[1] }
}

$identity = Get-ContainerIdentity
$metadata = [ordered]@{
    collector = 'docker-stats-no-stream'
    startedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    finishedUtc = $null
    containerName = $ContainerName
    containerId = $identity.id
    containerStartedAt = $identity.startedAt
    requestedDurationSeconds = $DurationSeconds
    requestedDelayAfterSampleSeconds = 1
    queryTimeoutSeconds = 15
    cadence = 'Actual intervals depend on Docker query duration; not a 1 Hz sampler.'
    cpuUnit = 'Docker CPU percentage; denominator differs from normalized process_cpu_usage.'
    memoryUnit = 'Docker MemUsage string with unit; preserve cache/platform semantics.'
    sampleCount = 0
    status = 'collecting'
    error = $null
}
$writer = $null
$metadataCreated = $false
$clock = [Diagnostics.Stopwatch]::StartNew()
try {
    $metaStream = [IO.File]::Open($metadataPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    $metaStream.Dispose()
    $metadataCreated = $true
    [IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json -Depth 6), $utf8)
    $stream = [IO.File]::Open($outputPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $writer = New-Object System.IO.StreamWriter($stream, $utf8)
    $writer.AutoFlush = $true
    Write-Host ('Collecting container {0} for about {1}s. Ctrl+C stops early.' -f $ContainerName, $DurationSeconds)
    Write-Host ('Output: {0}' -f $outputPath)
    $metadata.status = 'interrupted'
    while ($clock.Elapsed.TotalSeconds -lt $DurationSeconds) {
        if ($StopFile -and (Test-Path -LiteralPath $StopFile)) {
            $metadata.status = "stop-requested"
            break
        }
        $queryStarted = [DateTimeOffset]::UtcNow
        $queryClock = [Diagnostics.Stopwatch]::StartNew()
        $rawStats = Invoke-DockerReadOnly ('stats --no-stream --format "{{json .}}" ' + $ContainerName)
        $queryClock.Stop()
        $queryFinished = [DateTimeOffset]::UtcNow
        if ([string]::IsNullOrWhiteSpace($rawStats)) { throw 'Docker stats returned no data.' }
        $stats = $rawStats | ConvertFrom-Json
        if ($null -eq $stats.PSObject.Properties['ID'] -or
            $null -eq $stats.PSObject.Properties['CPUPerc'] -or
            $null -eq $stats.PSObject.Properties['MemUsage']) {
            throw 'Docker stats JSON is missing required fields.'
        }
        $currentIdentity = Get-ContainerIdentity
        if ($currentIdentity.id -ne $identity.id -or $currentIdentity.startedAt -ne $identity.startedAt) {
            throw 'Container was recreated or restarted. Collection stopped without mixing executions.'
        }
        if (-not $identity.id.StartsWith([string] $stats.ID)) {
            throw 'Docker stats does not match the selected container identity.'
        }
        $record = [ordered]@{
            timestampStartedUtc = $queryStarted.ToString('o')
            timestampFinishedUtc = $queryFinished.ToString('o')
            queryDurationMilliseconds = $queryClock.Elapsed.TotalMilliseconds
            containerId = $identity.id
            containerStartedAt = $identity.startedAt
            stats = $stats
        }
        $writer.WriteLine(($record | ConvertTo-Json -Depth 8 -Compress))
        $metadata.sampleCount++
        $remaining = $DurationSeconds - $clock.Elapsed.TotalSeconds
        if ($remaining -gt 0) {
            Start-Sleep -Milliseconds ([int] [Math]::Min(1000, [Math]::Ceiling($remaining * 1000)))
        }
    }
    if ($metadata.status -ne 'stop-requested') { $metadata.status = 'duration-complete' }
} catch {
    $metadata.status = 'failed'
    $metadata.error = $_.Exception.Message
    throw
} finally {
    if ($null -ne $writer) { $writer.Dispose() }
    $clock.Stop()
    $metadata.finishedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    if ($metadataCreated) {
        [IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json -Depth 6), $utf8)
    }
    Write-Host ('Collection ended: {0}; samples: {1}.' -f $metadata.status, $metadata.sampleCount)
}
