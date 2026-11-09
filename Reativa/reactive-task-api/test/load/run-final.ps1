[CmdletBinding()]
param(
    [ValidateRange(1,5000)][int]$Users = 5000,
    [ValidateRange(1,60)][int]$RampSeconds = 5,
    [ValidateRange(1,10)][int]$Loops = 3,
    [ValidateRange(1,3)][int]$Repetition = 1,
    [ValidateRange(1,5)][int]$WarmupUsers = 5,
    [ValidateRange(1,20)][int]$WarmupLoops = 20,
    [ValidateRange(0,60)][int]$CooldownSeconds = 30
)

# User-run collection. No deletion, service restart or automatic retry of writes.
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$env:JAVA_HOME = $(if ($env:TCC_JAVA_HOME) { $env:TCC_JAVA_HOME } else { 'C:\Program Files\Java\jdk-17' })
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jmeterJar = $(if ($env:TCC_JMETER_HOME) { Join-Path $env:TCC_JMETER_HOME 'bin\ApacheJMeter.jar' } else { 'C:\Users\User\Tools\apache-jmeter-5.6.3\bin\ApacheJMeter.jar' })
# Heap do gerador JMeter: padrao 1g (protocolo original); TCC_JMETER_HEAP permite aumentar igualmente nas duas arquiteturas.
$jmeterHeap = $(if ($env:TCC_JMETER_HEAP) { $env:TCC_JMETER_HEAP } else { '1g' })
$sourcePlan = Join-Path $project 'test\JMeterTest.jmx'
$configPath = Join-Path $project 'src\main\resources\application.properties'
$scripts = Join-Path $PSScriptRoot 'scripts'
foreach ($file in @($java,$jmeterJar,$sourcePlan)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing: $file" }
}

function Gauge([string]$Name) {
    $q = [uri]::EscapeDataString($Name + '{job="reactive-task-api"}')
    $r = Invoke-RestMethod "http://localhost:9090/api/v1/query?query=$q" -TimeoutSec 10
    if ($r.status -ne 'success' -or @($r.data.result).Count -ne 1) { throw "Metric unavailable: $Name" }
    return [double]::Parse([string]$r.data.result[0].value[1], [Globalization.CultureInfo]::InvariantCulture)
}
function Quote([string]$Value) { return '"' + $Value.Replace('"','\"') + '"' }
function Check-Queues {
    $line = Get-Content $configPath | Where-Object {$_ -match '^app.messaging.command-queue='} | Select-Object -First 1
    if (-not $line) {throw 'Missing command queue name.'}
    $queueName = ($line -split '=',2)[1].Trim()
    $queueOutput = @(docker exec reactive-task-api-rabbitmq-1 rabbitmqctl -q list_queues -p reactive name messages_ready messages_unacknowledged)
    if ($LASTEXITCODE -ne 0) {throw 'Cannot inspect RabbitMQ queues.'}
    $match = @($queueOutput | Where-Object {$_ -match ('^'+[regex]::Escape($queueName)+'\s')})
    if ($match.Count -ne 1) {throw 'Configured command queue not found.'}
    $parts = $match[0] -split '\s+'
    if ($parts.Count -ne 3 -or [long]$parts[1] -ne 0 -or [long]$parts[2] -ne 0) {
        throw 'Command queue has pending deliveries. Do not reset or repeat load yet.'
    }
    return ($queueOutput -join "`r`n")
}
function Start-Collector([string]$Script, [string[]]$Extra, [string]$Name) {
    $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $scripts $Script),
        '-RunFolder',$runFolder,'-DurationSeconds','900','-StopFile',$stopFile) + $Extra
    return Start-Process powershell.exe -ArgumentList (($arguments | ForEach-Object {Quote $_}) -join ' ') `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runFolder "$Name-collector.txt") `
        -RedirectStandardError (Join-Path $runFolder "$Name-collector-errors.txt")
}
function Run-JMeter([string]$Folder, [int]$Count, [int]$Ramp, [int]$Cycles) {
    if (Test-Path (Join-Path $Folder 'samples.jtl')) { throw 'Results already exist.' }
    [DateTimeOffset]::UtcNow.ToString('o') | Set-Content (Join-Path $Folder 'start-utc.txt')
    $jmeterHeap | Set-Content (Join-Path $Folder 'generator-heap.txt')
    $arguments = @("-Xms$jmeterHeap","-Xmx$jmeterHeap",'-XX:MaxMetaspaceSize=256m','-jar',$jmeterJar,
        '-n','-t',$executedPlan,"-Jusers=$Count","-Jramp=$Ramp","-Jloops=$Cycles",
        '-Jjmeter.save.saveservice.output_format=csv','-Jjmeter.save.saveservice.print_field_names=true',
        '-Jjmeter.save.saveservice.timestamp_format=ms','-Jsampleresult.timestamp.start=true',
        '-Jjmeter.save.saveservice.latency=true','-Jjmeter.save.saveservice.connect_time=true',
        '-Jjmeter.save.saveservice.assertion_results_failure_message=true','-Jsample_variables=taskId',
        '-l',(Join-Path $Folder 'samples.jtl'),'-j',(Join-Path $Folder 'jmeter.log'))
    $process = Start-Process $java -ArgumentList (($arguments | ForEach-Object {Quote $_}) -join ' ') `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Folder 'console.txt') `
        -RedirectStandardError (Join-Path $Folder 'stderr.txt')
    # Retain the native process handle before exit (Windows PowerShell 5.1).
    $processHandle = $process.Handle
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        while (-not $process.HasExited) {
            if ($watch.Elapsed.TotalSeconds -gt 600) {
                Stop-Process -Id $process.Id
                throw 'Generator exceeded 600s; partial evidence preserved. Inspect pending writes before another run.'
            }
            Start-Sleep -Seconds 1
            $process.Refresh()
        }
        $process.WaitForExit()
        $exitCode = $process.ExitCode
        if ($null -eq $exitCode) { throw 'Could not retrieve JMeter exit code; inspect generator logs.' }
        [pscustomobject]@{processId=$process.Id;exitCode=$exitCode} |
            ConvertTo-Json | Set-Content (Join-Path $Folder 'generator-exit.json')
        if ($exitCode -ne 0) { throw "JMeter exit $exitCode. Inspect generator logs." }
    } finally {
        $process.Refresh()
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
        [DateTimeOffset]::UtcNow.ToString('o') | Set-Content (Join-Path $Folder 'end-utc.txt')
    }
    Get-Content (Join-Path $Folder 'console.txt') -Tail 5 | Write-Host
}

# Supported workload: one flat CRUD cycle, with optional GetList.
[xml]$sourceXml = Get-Content $sourcePlan -Raw
$groups = @($sourceXml.SelectNodes('//ThreadGroup'))
if ($groups.Count -ne 1 -or $groups[0].GetAttribute('enabled') -eq 'false') { throw 'Expected one enabled ThreadGroup.' }
$samplers = @($sourceXml.SelectNodes('//HTTPSamplerProxy') | Where-Object { $_.GetAttribute('enabled') -ne 'false' })
$labels = @($samplers | ForEach-Object { $_.GetAttribute('testname') })
if (($labels -join ',') -notin @('Post,Get,Put,Delete','Post,Get,GetList,Put,Delete')) { throw 'Unsupported CRUD sequence; review expected sample counts.' }
foreach ($sampler in $samplers) {
    if ($sampler.ParentNode -ne $groups[0].NextSibling) { throw 'Only a flat CRUD cycle is supported.' }
}
$operationsPerCycle = $samplers.Count
$consumerLine = @(Get-Content $configPath | Where-Object { $_ -match '^app\.messaging\.consumer-concurrency=' })
if ($consumerLine.Count -ne 1 -or $consumerLine[0] -notmatch '=([1-9][0-9]*)(?:-([1-9][0-9]*))?\s*$') { throw 'Expected numeric consumer concurrency in properties.' }
$expectedConsumers = [int]$Matches[1]
if ($Matches[2] -and [int]$Matches[2] -lt $expectedConsumers) { throw 'Invalid consumer range.' }

Write-Host '1/6 Checking services, empty database and no pending RPCs...'
$health = Invoke-RestMethod 'http://localhost:8080/actuator/health' -TimeoutSec 10
if ($health.status -ne 'UP' -or (Gauge 'up') -ne 1 -or (Gauge 'app_messaging_rpc_pending') -ne 0) {
    throw 'API/Prometheus not ready or RPCs still pending.'
}
$tasks = Invoke-RestMethod 'http://localhost:8080/tarefas' -TimeoutSec 60
if (@($tasks | Where-Object {$null -ne $_}).Count -ne 0) {
    throw 'Database is not empty. Drain queues and restart API before an independent repetition; do not delete blindly.'
}
$grafana = Invoke-RestMethod 'http://localhost:3000/api/health' -TimeoutSec 10
if ($grafana.database -ne 'ok') { throw 'Grafana database unavailable.' }
$queuesBefore = Check-Queues
$runId = 'reactive-u{0}-r{1:00}-{2}' -f $Users,$Repetition,(Get-Date -Format 'yyyyMMdd-HHmmss')
$runFolder = Join-Path $PSScriptRoot "results\$runId"
New-Item -ItemType Directory -Path $runFolder | Out-Null
$stopFile = Join-Path $runFolder 'collectors.stop'
Write-Host "Results: $runFolder"
$apiId = Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique | Select-Object -First 1
$apiStart = (Get-Process -Id $apiId).StartTime.ToUniversalTime().ToString('o')
$health | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $runFolder 'health-before.json')
$queuesBefore | Set-Content (Join-Path $runFolder 'queues-before.txt')
Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors |
    ConvertTo-Json | Set-Content (Join-Path $runFolder 'cpu.json')
Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory |
    ConvertTo-Json | Set-Content (Join-Path $runFolder 'ram.json')
Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber |
    ConvertTo-Json | Set-Content (Join-Path $runFolder 'os.json')
$configPath = Join-Path $project 'src\main\resources\application.properties'
Get-Content $configPath | Where-Object { $_ -notmatch '(?i)password|secret|token' } |
    Set-Content (Join-Path $runFolder 'application-redacted.properties')
Get-FileHash $sourcePlan,$configPath,(Join-Path $project 'pom.xml') |
    Select-Object Path,Algorithm,Hash | Export-Csv (Join-Path $runFolder 'source-hashes.csv') -NoTypeInformation
Copy-Item $sourcePlan (Join-Path $runFolder 'original-plan.jmx')
[xml]$xml = Get-Content $sourcePlan -Raw
foreach ($setting in @(@('ThreadGroup.num_threads','${__P(users,1)}'),@('ThreadGroup.ramp_time','${__P(ramp,1)}'),@('LoopController.loops','${__P(loops,1)}'))) {
    $node = $xml.SelectSingleNode("//*[@name='$($setting[0])']")
    if ($null -eq $node) { throw "Missing plan setting: $($setting[0])" }
    # JMeter intProp cannot hold property functions: replace with stringProp.
    $new = $xml.CreateElement('stringProp');$new.SetAttribute('name',$setting[0]);$new.InnerText=$setting[1]
    [void]$node.ParentNode.ReplaceChild($new,$node)
}
foreach ($listener in $xml.SelectNodes('//ResultCollector')) { $listener.SetAttribute('enabled','false') }
foreach ($sampler in $xml.SelectNodes('//HTTPSamplerProxy')) {
    foreach ($setting in @(@('HTTPSampler.connect_timeout','5000'),@('HTTPSampler.response_timeout','60000'))) {
        $node = $sampler.SelectSingleNode("*[@name='$($setting[0])']")
        if ($null -eq $node) {$node=$xml.CreateElement('stringProp');$node.SetAttribute('name',$setting[0]);[void]$sampler.AppendChild($node)}
        $node.InnerText=$setting[1]
    }
}
# Avoid pretty-print whitespace inside empty JMeter string properties.
foreach ($node in $xml.SelectNodes('//stringProp')) {
    if ([string]::IsNullOrWhiteSpace($node.InnerText)) { $node.InnerText = '' }
}
$executedPlan = Join-Path $runFolder 'executed-plan.jmx'
[IO.File]::WriteAllText($executedPlan, $xml.OuterXml, [Text.UTF8Encoding]::new($false))
$collectors = @()
$finished = $false
try {
    $collectors += Start-Collector 'collect-process-resources.ps1' @('-Port','8080') 'api'
    $collectors += Start-Collector 'collect-broker-resources.ps1' @('-FileName','rabbit-resources.jsonl') 'rabbit'
    Start-Sleep -Seconds 4
    foreach ($c in $collectors) { $c.Refresh();if ($c.HasExited) {throw 'A collector stopped; inspect collector log before load.'} }
    Write-Host '2/6 Warming up (separate results)...'
    $warmFolder = Join-Path $runFolder 'warmup';New-Item -ItemType Directory -Path $warmFolder | Out-Null
    Run-JMeter $warmFolder $WarmupUsers 5 $WarmupLoops
    $warmRows = @(Import-Csv (Join-Path $warmFolder 'samples.jtl'))
    if ($warmRows.Count -ne ($WarmupUsers*$WarmupLoops*$operationsPerCycle) -or @($warmRows | Where-Object success -ne 'true').Count -gt 0) {
        throw 'Warmup failed; measured load was not started. Inspect preserved warmup results.'
    }
    $tasks = Invoke-RestMethod 'http://localhost:8080/tarefas' -TimeoutSec 60
    if (@($tasks | Where-Object {$null -ne $_}).Count -gt 0) {throw 'Warmup left tasks in database.'}
    Write-Host "Waiting $CooldownSeconds seconds before measured phase..."
    Start-Sleep -Seconds $CooldownSeconds
    if ((Gauge 'app_messaging_rpc_pending') -ne 0) {throw 'Pending RPCs after warmup.'}
    $startConsumers = Gauge 'app_messaging_command_consumers'
    if ($startConsumers -ne $expectedConsumers) {throw "Expected $expectedConsumers command consumers from properties, observed $startConsumers. Check runtime overrides/restart."}
    Write-Host "3/6 Measured load: $Users users / $RampSeconds s ramp / $Loops loops..."
    Run-JMeter $runFolder $Users $RampSeconds $Loops
    Write-Host '4/6 Collecting 20 seconds of recovery...'
    Start-Sleep -Seconds 20
    $after = Get-Process -Id $apiId
    if ($after.StartTime.ToUniversalTime().ToString('o') -ne $apiStart) {throw 'API process identity changed.'}
    try { $finalHealth = Invoke-RestMethod 'http://localhost:8080/actuator/health' -TimeoutSec 10 }
    catch { $finalHealth = [pscustomobject]@{status='UNREACHABLE';detail=$_.Exception.Message};Write-Warning 'API health unavailable after load; preserve failure.' }
    $finalHealth | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $runFolder 'health-after.json')
    try {Check-Queues | Set-Content (Join-Path $runFolder 'queues-after.txt')}
    catch { $_.Exception.Message | Set-Content (Join-Path $runFolder 'queues-after-review-required.txt');Write-Warning 'Queue state requires review before next run.' }
    [ordered]@{users=$Users;rampSeconds=$RampSeconds;loops=$Loops;repetition=$Repetition;apiId=$apiId;apiStartedUtc=$apiStart;warmupUsers=$WarmupUsers;warmupLoops=$WarmupLoops;cooldownSeconds=$CooldownSeconds;initialConsumers=$startConsumers;operationsPerCycle=$operationsPerCycle;samplers=$labels;maximumPlannedSamples=$Users*$Loops*$operationsPerCycle;clientConnectTimeoutMs=5000;clientResponseTimeoutMs=60000;apiStatusAfter=$finalHealth.status} |
        ConvertTo-Json | Set-Content (Join-Path $runFolder 'run-metadata.json')
    $finished = $true
} finally {
    [IO.File]::WriteAllText($stopFile,'stop')
    foreach ($c in $collectors) {
        if (-not $c.WaitForExit(25000)) { Write-Warning 'Collector still exiting; inspect its metadata.' }
    }
}
if (-not $finished) {throw 'Run did not complete; evidence was preserved.'}
Write-Host '5/6 Exporting and summarizing...'
& (Join-Path $scripts 'summarize-jmeter.ps1') -InputPath (Join-Path $runFolder 'samples.jtl') -OutputDirectory (Join-Path $runFolder 'analysis')
& (Join-Path $scripts 'export-prometheus.ps1') -WindowPath (Join-Path $runFolder 'analysis\jtl-window.json') -OutputDirectory (Join-Path $runFolder 'prometheus')
& (Join-Path $scripts 'summarize-resources.ps1') -RunFolder $runFolder
$report = Start-Process $java -ArgumentList ((@('-Xmx1g','-jar',$jmeterJar,'-g',(Join-Path $runFolder 'samples.jtl'),'-o',(Join-Path $runFolder 'html')) | ForEach-Object {Quote $_}) -join ' ') `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runFolder 'html-generation.txt') -RedirectStandardError (Join-Path $runFolder 'html-generation-errors.txt')
$reportHandle = $report.Handle
$report.WaitForExit()
if ($null -eq $report.ExitCode -or $report.ExitCode -ne 0) {throw 'HTML generation failed; raw results were preserved.'}
$window=Get-Content (Join-Path $runFolder 'analysis\jtl-window.json') -Raw | ConvertFrom-Json
$link='http://localhost:3000/d/adx4js2?from='+$window.startMs+'&to='+$window.endMs
$stats=Import-Csv (Join-Path $runFolder 'analysis\jmeter-summary.csv')
$resources=Import-Csv (Join-Path $runFolder 'resource-summary\resource-summary.csv')
$style='<style>body{font:16px Segoe UI;margin:32px;color:#183746}table{border-collapse:collapse;font-size:13px}td,th{border:1px solid #ccd;padding:8px}th{background:#e8f2f5}a{color:#087b91}</style>'
$body='<h1>Resultados da API reativa</h1><p>'+[System.Net.WebUtility]::HtmlEncode($runId)+'</p><p><a href="html/index.html">Abrir relatorio JMeter e graficos</a> | <a href="'+$link+'">Abrir Grafana no intervalo exato do teste</a></p><p>Tempo medio usa elapsed. Vazao HTTP inclui falhas. Memoria JVM difere de RAM residente do processo. Medias e maximos de recursos sao amostrados. Preserve todas as evidencias.</p><h2>Requisicoes</h2>'
$body += ($stats | Select-Object @{n='Rota';e={$_.label}},@{n='Tentativas';e={$_.samples}},@{n='Falhas';e={$_.failed}},@{n='Erros (%)';e={$_.errorPercent}},@{n='Tempo medio (ms)';e={$_.elapsedAverageAllMs}},@{n='Media dos sucessos (ms)';e={$_.elapsedAverageSuccessfulMs}},@{n='Vazao HTTP (req/s)';e={$_.jmeterThroughputPerSecond}},@{n='Sucessos/s (janela global)';e={$_.successfulPerGlobalSecond}} | ConvertTo-Html -Fragment | Out-String)
$body += '<h2>Recursos da API</h2>'+($resources | Select-Object @{n='Metrica';e={$_.metric}},@{n='Unidade';e={$_.unit}},@{n='Amostras';e={$_.sampleCount}},@{n='Media';e={$_.meanSampled}},@{n='Maximo amostrado';e={$_.maximumSampled}} | ConvertTo-Html -Fragment | Out-String)
ConvertTo-Html -Title 'Reactive API results' -Head $style -Body $body | Set-Content (Join-Path $runFolder 'overview.html') -Encoding UTF8
Write-Host '6/6 Completed. Errors, if any, remain in the report.'
Write-Host "OPEN: $(Join-Path $runFolder 'overview.html')"
Write-Host "Grafana: $link"
Write-Host 'Before next independent repetition: save API console, check queues/pending and restart API after draining.'
