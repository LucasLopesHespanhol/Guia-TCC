# Compatible with Windows PowerShell 5.1. This script only observes one process.
# Example API: .\collect-process-resources.ps1 -RunFolder 'C:\results\run-01'
# Example JMeter: add -ProcessId 1234 -FileName 'generator-resources.csv'
# Stop with Ctrl+C; the default duration is 900 seconds (15 minutes).
[CmdletBinding(DefaultParameterSetName = 'ByPort')]
param(
    [Parameter(Mandatory = $true)]
    [string] $RunFolder,
    [Parameter(ParameterSetName = 'ByPort')]
    [ValidateRange(1, 65535)]
    [int] $Port = 8080,
    [Parameter(Mandatory = $true, ParameterSetName = 'ByProcess')]
    [ValidateRange(1, 2147483647)]
    [int] $ProcessId,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*\.csv$')]
    [string] $FileName = 'api-resources.csv',
    [ValidateRange(1, 86400)]
    [int] $DurationSeconds = 900,
    [string] $StopFile = ""
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$invariant = [Globalization.CultureInfo]::InvariantCulture
$utf8 = New-Object System.Text.UTF8Encoding($false)
$folder = Get-Item -LiteralPath $RunFolder
if (-not $folder.PSIsContainer) { throw 'RunFolder must be an existing directory.' }
$outputPath = Join-Path $folder.FullName $FileName
$metadataPath = [IO.Path]::ChangeExtension($outputPath, '.metadata.json')
if ((Test-Path -LiteralPath $outputPath) -or (Test-Path -LiteralPath $metadataPath)) {
    throw 'Output already exists. Use a new run directory or a different FileName.'
}

if ($PSCmdlet.ParameterSetName -eq 'ByPort') {
    $owners = @(Get-NetTCPConnection -LocalPort $Port -State Listen |
        Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -ne 1) {
        throw 'Expected exactly one process listening on the specified port.'
    }
    $ProcessId = [int] $owners[0]
}
$initialProcess = Get-Process -Id $ProcessId
$processStartedUtc = $initialProcess.StartTime.ToUniversalTime()
$logicalCpuCount = [Environment]::ProcessorCount
$logicalCpuSource = 'Environment.ProcessorCount'
try {
    $computer = Get-CimInstance -ClassName Win32_ComputerSystem
    $logicalCpuCount = [int] $computer.NumberOfLogicalProcessors
    $logicalCpuSource = 'Win32_ComputerSystem.NumberOfLogicalProcessors'
} catch {
    Write-Warning 'Could not read system logical CPU count; using Environment.ProcessorCount.'
}

$metadata = [ordered]@{
    collector = 'single-windows-process'
    startedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    finishedUtc = $null
    processId = $ProcessId
    processName = $initialProcess.ProcessName
    processStartedUtc = $processStartedUtc.ToString('o', $invariant)
    selectedBy = $PSCmdlet.ParameterSetName
    port = $(if ($PSCmdlet.ParameterSetName -eq 'ByPort') { $Port } else { $null })
    logicalCpuCount = $logicalCpuCount
    logicalCpuCountSource = $logicalCpuSource
    requestedDurationSeconds = $DurationSeconds
    requestedDelayAfterSampleSeconds = 1
    workingSetUnit = 'bytes; Windows working set, not JVM heap'
    privateBytesUnit = 'bytes; private committed virtual memory, not resident RAM'
    cpuTotalUnit = 'seconds accumulated across process threads; use sample deltas'
    numberFormat = 'invariant decimal point'
    sampleCount = 0
    status = 'collecting'
    error = $null
}

$writer = $null
$metadataCreated = $false
$clock = [Diagnostics.Stopwatch]::StartNew()
try {
    # CreateNew prevents accidental replacement even if another collector starts now.
    $metaStream = [IO.File]::Open($metadataPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    $metaStream.Dispose()
    $metadataCreated = $true
    [IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json -Depth 6), $utf8)
    $stream = [IO.File]::Open($outputPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $writer = New-Object System.IO.StreamWriter($stream, $utf8)
    $writer.AutoFlush = $true
    $writer.WriteLine('timestampUtc,processId,processStartedUtc,workingSetBytes,privateBytes,cpuTotalSeconds')
    Write-Host ('Collecting process {0} for up to {1}s. Ctrl+C stops early.' -f $ProcessId, $DurationSeconds)
    Write-Host ('Output: {0}' -f $outputPath)
    $metadata.status = 'interrupted'
    while ($clock.Elapsed.TotalSeconds -lt $DurationSeconds) {
        if ($StopFile -and (Test-Path -LiteralPath $StopFile)) {
            $metadata.status = "stop-requested"
            break
        }
        $sampleProcess = Get-Process -Id $ProcessId -ErrorAction Stop
        $sampleStart = $sampleProcess.StartTime.ToUniversalTime()
        if ($sampleStart.Ticks -ne $processStartedUtc.Ticks) {
            throw 'Process identity changed. Collection stopped instead of mixing executions.'
        }
        $values = @(
            [DateTimeOffset]::UtcNow.ToString('o'),
            $ProcessId.ToString($invariant),
            $processStartedUtc.ToString('o', $invariant),
            $sampleProcess.WorkingSet64.ToString($invariant),
            $sampleProcess.PrivateMemorySize64.ToString($invariant),
            $sampleProcess.TotalProcessorTime.TotalSeconds.ToString('G17', $invariant)
        )
        $writer.WriteLine(($values -join ','))
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
