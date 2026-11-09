[CmdletBinding()]
param(
    [ValidateRange(1,5000)][int]$Users = 5000,
    [ValidateRange(1,60)][int]$RampSeconds = 5,
    [ValidateRange(1,10)][int]$Loops = 3,
    [ValidateRange(1,3)][int]$Repetition = 1,
    [ValidateRange(1,5)][int]$WarmupUsers = 5,
    [ValidateRange(1,20)][int]$WarmupLoops = 20,
    [ValidateRange(0,60)][int]$CooldownSeconds = 30,
    [ValidateRange(50,5000)][int]$PollMs = 250,
    [ValidateRange(1000,120000)][int]$DeadlineMs = 30000,
    [ValidateRange(0,600)][int]$DrainSeconds = 120,
    [string]$Container = 'eda-task-api-rabbitmq-1'
)

# Coleta da EDA executada pelo usuario. Mesmo protocolo de run-final.ps1 da Reativa:
# sem exclusao de dados, sem reinicio de servicos e sem repeticao automatica de escritas.
# Diferencas proprias da EDA: escritas medidas ate CONCLUIDA (Transaction Controllers),
# filas/DLQ do vhost "/" e metricas eda_* no lugar de app_messaging_*.
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$env:JAVA_HOME = $(if ($env:TCC_JAVA_HOME) { $env:TCC_JAVA_HOME } else { 'C:\Program Files\Java\jdk-17' })
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jmeterJar = $(if ($env:TCC_JMETER_HOME) { Join-Path $env:TCC_JMETER_HOME 'bin\ApacheJMeter.jar' } else { 'C:\Users\User\Tools\apache-jmeter-5.6.3\bin\ApacheJMeter.jar' })
# Heap do gerador JMeter: padrao 1g (protocolo original); TCC_JMETER_HEAP permite aumentar igualmente nas duas arquiteturas.
$jmeterHeap = $(if ($env:TCC_JMETER_HEAP) { $env:TCC_JMETER_HEAP } else { '1g' })
$sourcePlan = Join-Path $project 'test\EdaTest.jmx'
$configPath = Join-Path $project 'src\main\resources\application.properties'
$scripts = Join-Path $PSScriptRoot 'scripts'
$api = 'http://127.0.0.1:8081'
$job = 'eda-task-api'
$requestQueue = 'eda.requests.v1.q'
$factQueue = 'eda.facts.v1.q'
foreach ($file in @($java,$jmeterJar,$sourcePlan)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing: $file" }
}

function Gauge([string]$Name) {
    $q = [uri]::EscapeDataString($Name + '{job="' + $job + '"}')
    $r = Invoke-RestMethod "http://localhost:9090/api/v1/query?query=$q" -TimeoutSec 10
    if ($r.status -ne 'success' -or @($r.data.result).Count -ne 1) { throw "Metric unavailable: $Name" }
    return [double]::Parse([string]$r.data.result[0].value[1], [Globalization.CultureInfo]::InvariantCulture)
}
function Quote([string]$Value) { return '"' + $Value.Replace('"','\"') + '"' }
function Read-Queues {
    $output = @(docker exec $Container rabbitmqctl -q list_queues -p / name messages_ready messages_unacknowledged consumers)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect RabbitMQ queues.' }
    $table = @{}
    foreach ($line in $output) {
        $parts = ($line.Trim()) -split '\s+'
        if ($parts.Count -eq 4 -and $parts[1] -match '^\d+$') {
            $table[$parts[0]] = [pscustomobject]@{ready=[long]$parts[1];unacked=[long]$parts[2];consumers=[long]$parts[3]}
        }
    }
    foreach ($name in @($requestQueue,$factQueue,"$requestQueue.dlq","$factQueue.dlq")) {
        if (-not $table.ContainsKey($name)) { throw "Queue not found: $name" }
    }
    return [pscustomobject]@{text=($output -join "`r`n");table=$table}
}
function Queues-Drained($Queues) {
    foreach ($name in @($requestQueue,$factQueue)) {
        $q = $Queues.table[$name]
        if ($q.ready -ne 0 -or $q.unacked -ne 0) { return $false }
    }
    return $true
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
        "-JpollMs=$PollMs","-JdeadlineMs=$DeadlineMs",
        '-Jjmeter.save.saveservice.output_format=csv','-Jjmeter.save.saveservice.print_field_names=true',
        '-Jjmeter.save.saveservice.timestamp_format=ms','-Jsampleresult.timestamp.start=true',
        '-Jjmeter.save.saveservice.latency=true','-Jjmeter.save.saveservice.connect_time=true',
        '-Jjmeter.save.saveservice.assertion_results_failure_message=true','-Jsample_variables=taskId,subId,estado',
        '-l',(Join-Path $Folder 'samples.jtl'),'-j',(Join-Path $Folder 'jmeter.log'))
    $process = Start-Process $java -ArgumentList (($arguments | ForEach-Object {Quote $_}) -join ' ') `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Folder 'console.txt') `
        -RedirectStandardError (Join-Path $Folder 'stderr.txt')
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
function Split-Jtl([string]$Folder) {
    # Operacoes de negocio: Transaction Controllers (Post/Put/Delete) e leituras (Get/GetList).
    # Trafego HTTP: todas as amostras de requisicao (aceites, consultas de estado e leituras).
    $rows = @(Import-Csv (Join-Path $Folder 'samples.jtl'))
    $business = @($rows | Where-Object { $businessLabels -ccontains $_.label })
    $http = @($rows | Where-Object { $httpLabels -ccontains $_.label })
    $unknown = @($rows | Where-Object { ($businessLabels -cnotcontains $_.label) -and ($httpLabels -cnotcontains $_.label) })
    if ($unknown.Count -gt 0) { throw "Unexpected JTL labels: $((@($unknown | Select-Object -ExpandProperty label -Unique)) -join ',')" }
    $business | Export-Csv (Join-Path $Folder 'business.jtl') -NoTypeInformation -Encoding UTF8
    $http | Export-Csv (Join-Path $Folder 'http.jtl') -NoTypeInformation -Encoding UTF8
    return $business
}

# Plano suportado: Post, Get, [GetList], Put, Delete no primeiro nivel do Thread Group.
[xml]$sourceXml = Get-Content $sourcePlan -Raw
$groups = @($sourceXml.SelectNodes('//ThreadGroup'))
if ($groups.Count -ne 1 -or $groups[0].GetAttribute('enabled') -eq 'false') { throw 'Expected one enabled ThreadGroup.' }
$top = @($groups[0].NextSibling.ChildNodes | Where-Object {
    $_.NodeType -eq 'Element' -and $_.Name -in @('TransactionController','HTTPSamplerProxy') -and $_.GetAttribute('enabled') -ne 'false' })
$businessLabels = @($top | ForEach-Object { $_.GetAttribute('testname') })
if (($businessLabels -join ',') -notin @('Post,Get,Put,Delete','Post,Get,GetList,Put,Delete')) { throw 'Unsupported CRUD sequence; review expected sample counts.' }
$transactions = @($top | Where-Object { $_.Name -eq 'TransactionController' } | ForEach-Object { $_.GetAttribute('testname') })
if (($transactions -join ',') -ne 'Post,Put,Delete') { throw 'Expected Transaction Controllers Post, Put and Delete.' }
$httpLabels = @($sourceXml.SelectNodes('//HTTPSamplerProxy') | Where-Object { $_.GetAttribute('enabled') -ne 'false' } | ForEach-Object { $_.GetAttribute('testname') })
$operationsPerCycle = $businessLabels.Count
$consumerLine = @(Get-Content $configPath | Where-Object { $_ -match '^spring\.rabbitmq\.listener\.simple\.concurrency=' })
if ($consumerLine.Count -ne 1 -or $consumerLine[0] -notmatch '=([1-9][0-9]*)\s*$') { throw 'Expected numeric listener concurrency in properties.' }
$expectedConsumers = [int]$Matches[1]

Write-Host '1/7 Checking services, empty database, drained queues and no pending submissions...'
$health = Invoke-RestMethod "$api/actuator/health" -TimeoutSec 10
if ($health.status -ne 'UP' -or (Gauge 'up') -ne 1 -or (Gauge 'eda_submissions_registered') -ne 0) {
    throw 'API/Prometheus not ready or submissions still pending.'
}
$tasks = Invoke-RestMethod "$api/tarefas" -TimeoutSec 60
if (@($tasks | Where-Object {$null -ne $_}).Count -ne 0) {
    throw 'Database is not empty. Drain queues and restart API before an independent repetition; do not delete blindly.'
}
$grafana = Invoke-RestMethod 'http://localhost:3000/api/health' -TimeoutSec 10
if ($grafana.database -ne 'ok') { throw 'Grafana database unavailable.' }
$queuesBefore = Read-Queues
if (-not (Queues-Drained $queuesBefore)) { throw 'EDA queues have pending deliveries. Do not reset or repeat load yet.' }
$runId = 'eda-u{0}-r{1:00}-{2}' -f $Users,$Repetition,(Get-Date -Format 'yyyyMMdd-HHmmss')
$runFolder = Join-Path $PSScriptRoot "results\$runId"
New-Item -ItemType Directory -Path $runFolder | Out-Null
$stopFile = Join-Path $runFolder 'collectors.stop'
Write-Host "Results: $runFolder"
$apiId = Get-NetTCPConnection -LocalPort 8081 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique | Select-Object -First 1
$apiStart = (Get-Process -Id $apiId).StartTime.ToUniversalTime().ToString('o')
$health | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $runFolder 'health-before.json')
$queuesBefore.text | Set-Content (Join-Path $runFolder 'queues-before.txt')
docker inspect --type container --format '{{.HostConfig.NanoCpus}}|{{.HostConfig.Memory}}' $Container |
    Set-Content (Join-Path $runFolder 'broker-limits.txt')
Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors |
    ConvertTo-Json | Set-Content (Join-Path $runFolder 'cpu.json')
Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory |
    ConvertTo-Json | Set-Content (Join-Path $runFolder 'ram.json')
Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber |
    ConvertTo-Json | Set-Content (Join-Path $runFolder 'os.json')
Get-Content $configPath | Where-Object { $_ -notmatch '(?i)password|secret|token' } |
    Set-Content (Join-Path $runFolder 'application-redacted.properties')
Get-FileHash $sourcePlan,$configPath,(Join-Path $project 'pom.xml'),(Join-Path $project 'compose.yaml') |
    Select-Object Path,Algorithm,Hash | Export-Csv (Join-Path $runFolder 'source-hashes.csv') -NoTypeInformation
Copy-Item $sourcePlan (Join-Path $runFolder 'original-plan.jmx')
[xml]$xml = Get-Content $sourcePlan -Raw
foreach ($setting in @(@('ThreadGroup.num_threads','${__P(users,1)}'),@('ThreadGroup.ramp_time','${__P(ramp,1)}'),@('LoopController.loops','${__P(loops,1)}'))) {
    $node = $xml.SelectSingleNode("//*[@name='$($setting[0])']")
    if ($null -eq $node) { throw "Missing plan setting: $($setting[0])" }
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
foreach ($node in $xml.SelectNodes('//stringProp')) {
    if ([string]::IsNullOrWhiteSpace($node.InnerText)) { $node.InnerText = '' }
}
$executedPlan = Join-Path $runFolder 'executed-plan.jmx'
[IO.File]::WriteAllText($executedPlan, $xml.OuterXml, [Text.UTF8Encoding]::new($false))
$collectors = @()
$finished = $false
try {
    $collectors += Start-Collector 'collect-process-resources.ps1' @('-Port','8081') 'api'
    $collectors += Start-Collector 'collect-broker-resources.ps1' @('-FileName','rabbit-resources.jsonl','-ContainerName',$Container) 'rabbit'
    Start-Sleep -Seconds 4
    foreach ($c in $collectors) { $c.Refresh();if ($c.HasExited) {throw 'A collector stopped; inspect collector log before load.'} }
    Write-Host '2/7 Warming up (separate results)...'
    $warmFolder = Join-Path $runFolder 'warmup';New-Item -ItemType Directory -Path $warmFolder | Out-Null
    Run-JMeter $warmFolder $WarmupUsers 5 $WarmupLoops
    $warmRows = @(Split-Jtl $warmFolder)
    if ($warmRows.Count -ne ($WarmupUsers*$WarmupLoops*$operationsPerCycle) -or @($warmRows | Where-Object success -ne 'true').Count -gt 0) {
        throw 'Warmup failed; measured load was not started. Inspect preserved warmup results.'
    }
    $tasks = Invoke-RestMethod "$api/tarefas" -TimeoutSec 60
    if (@($tasks | Where-Object {$null -ne $_}).Count -gt 0) {throw 'Warmup left tasks in database.'}
    Write-Host "Waiting $CooldownSeconds seconds before measured phase..."
    Start-Sleep -Seconds $CooldownSeconds
    if ((Gauge 'eda_submissions_registered') -ne 0) {throw 'Pending submissions after warmup.'}
    if (-not (Queues-Drained (Read-Queues))) {throw 'EDA queues not drained after warmup.'}
    $startConsumers = Gauge 'eda_messaging_request_consumers'
    if ($startConsumers -ne $expectedConsumers) {throw "Expected $expectedConsumers request consumers from properties, observed $startConsumers. Check runtime overrides/restart."}
    Write-Host "3/7 Measured load: $Users users / $RampSeconds s ramp / $Loops loops / poll $PollMs ms / deadline $DeadlineMs ms..."
    Run-JMeter $runFolder $Users $RampSeconds $Loops
    $generatorEnd = [DateTimeOffset]::UtcNow
    Write-Host "4/7 Waiting for queues and submissions to drain (max $DrainSeconds s)..."
    $drainWatch = [Diagnostics.Stopwatch]::StartNew()
    $drained = $false
    while ($drainWatch.Elapsed.TotalSeconds -le $DrainSeconds) {
        try {
            if ((Queues-Drained (Read-Queues)) -and (Gauge 'eda_submissions_registered') -eq 0) { $drained = $true; break }
        } catch { Write-Warning $_.Exception.Message }
        Start-Sleep -Seconds 1
    }
    [ordered]@{generatorEndUtc=$generatorEnd.ToString('o');drained=$drained;secondsAfterGenerator=[math]::Round($drainWatch.Elapsed.TotalSeconds,1)} |
        ConvertTo-Json | Set-Content (Join-Path $runFolder 'drain.json')
    Write-Host '5/7 Collecting 20 seconds of recovery...'
    Start-Sleep -Seconds 20
    $after = Get-Process -Id $apiId
    if ($after.StartTime.ToUniversalTime().ToString('o') -ne $apiStart) {throw 'API process identity changed.'}
    try { $finalHealth = Invoke-RestMethod "$api/actuator/health" -TimeoutSec 10 }
    catch { $finalHealth = [pscustomobject]@{status='UNREACHABLE';detail=$_.Exception.Message};Write-Warning 'API health unavailable after load; preserve failure.' }
    $finalHealth | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $runFolder 'health-after.json')
    try {
        $queuesAfter = Read-Queues
        $queuesAfter.text | Set-Content (Join-Path $runFolder 'queues-after.txt')
        $dlqDelta = [ordered]@{}
        foreach ($name in @("$requestQueue.dlq","$factQueue.dlq")) { $dlqDelta[$name] = $queuesAfter.table[$name].ready - $queuesBefore.table[$name].ready }
        $dlqDelta | ConvertTo-Json | Set-Content (Join-Path $runFolder 'dlq-delta.json')
        if (-not (Queues-Drained $queuesAfter)) { Write-Warning 'Queues still have deliveries; review before next run.' }
    } catch { $_.Exception.Message | Set-Content (Join-Path $runFolder 'queues-after-review-required.txt');Write-Warning 'Queue state requires review before next run.' }
    $measuredRows = @(Split-Jtl $runFolder)
    [ordered]@{architecture='eda';users=$Users;rampSeconds=$RampSeconds;loops=$Loops;repetition=$Repetition;apiId=$apiId;apiStartedUtc=$apiStart;warmupUsers=$WarmupUsers;warmupLoops=$WarmupLoops;cooldownSeconds=$CooldownSeconds;pollMs=$PollMs;deadlineMs=$DeadlineMs;initialConsumers=$startConsumers;operationsPerCycle=$operationsPerCycle;businessLabels=$businessLabels;httpLabels=$httpLabels;maximumPlannedOperations=$Users*$Loops*$operationsPerCycle;businessSamples=$measuredRows.Count;clientConnectTimeoutMs=5000;clientResponseTimeoutMs=60000;apiStatusAfter=$finalHealth.status} |
        ConvertTo-Json | Set-Content (Join-Path $runFolder 'run-metadata.json')
    $finished = $true
} finally {
    [IO.File]::WriteAllText($stopFile,'stop')
    foreach ($c in $collectors) {
        if (-not $c.WaitForExit(25000)) { Write-Warning 'Collector still exiting; inspect its metadata.' }
    }
}
if (-not $finished) {throw 'Run did not complete; evidence was preserved.'}
Write-Host '6/7 Exporting and summarizing...'
& (Join-Path $scripts 'summarize-jmeter.ps1') -InputPath (Join-Path $runFolder 'business.jtl') -OutputDirectory (Join-Path $runFolder 'analysis') -Labels $businessLabels
& (Join-Path $scripts 'summarize-jmeter.ps1') -InputPath (Join-Path $runFolder 'http.jtl') -OutputDirectory (Join-Path $runFolder 'analysis-http') -Labels $httpLabels
& (Join-Path $scripts 'export-prometheus.ps1') -WindowPath (Join-Path $runFolder 'analysis\jtl-window.json') -OutputDirectory (Join-Path $runFolder 'prometheus') -Job $job
& (Join-Path $scripts 'summarize-resources.ps1') -RunFolder $runFolder
# Latencia de processamento no servidor (registro -> estado terminal), pelo timer eda_submission_completion.
$window = Get-Content (Join-Path $runFolder 'analysis\jtl-window.json') -Raw | ConvertFrom-Json
$evalTime = (($window.endMs / 1000.0) + 20).ToString('0.000',[Globalization.CultureInfo]::InvariantCulture)
$rangeSeconds = [int][math]::Ceiling(($window.endMs - $window.startMs)/1000.0) + 20
$sel = '{job="' + $job + '"}'
$completion = @()
foreach ($q in @(
    @{name='count';expr="sum by (tipo,estado) (increase(eda_submission_completion_seconds_count$sel[${rangeSeconds}s]))"},
    @{name='mean_ms';expr="1000 * sum by (tipo,estado) (increase(eda_submission_completion_seconds_sum$sel[${rangeSeconds}s])) / sum by (tipo,estado) (increase(eda_submission_completion_seconds_count$sel[${rangeSeconds}s]))"},
    @{name='p50_ms';expr="1000 * histogram_quantile(0.50, sum by (le,tipo,estado) (increase(eda_submission_completion_seconds_bucket$sel[${rangeSeconds}s])))"},
    @{name='p95_ms';expr="1000 * histogram_quantile(0.95, sum by (le,tipo,estado) (increase(eda_submission_completion_seconds_bucket$sel[${rangeSeconds}s])))"},
    @{name='p99_ms';expr="1000 * histogram_quantile(0.99, sum by (le,tipo,estado) (increase(eda_submission_completion_seconds_bucket$sel[${rangeSeconds}s])))"})) {
    try {
        $r = Invoke-RestMethod ("http://localhost:9090/api/v1/query?time=$evalTime&query=" + [uri]::EscapeDataString($q.expr)) -TimeoutSec 30
        foreach ($s in @($r.data.result)) { $completion += [pscustomobject]@{statistic=$q.name;tipo=$s.metric.tipo;estado=$s.metric.estado;value=$s.value[1]} }
    } catch { Write-Warning "Completion query $($q.name) failed: $($_.Exception.Message)" }
}
$completion | Export-Csv (Join-Path $runFolder 'analysis\server-completion.csv') -NoTypeInformation -Encoding UTF8
'Prometheus increase() extrapolates counters over the window; values are approximate. Histogram quantiles are bucket estimates. Window: measured JTL start to end + 20 s.' |
    Set-Content (Join-Path $runFolder 'analysis\server-completion-notes.txt')
# Relatorio HTML do JMeter a partir do JTL completo (Transaction Controllers e requisicoes).
# No HTML, o TOTAL mistura operacoes e requisicoes; usar analysis\ e analysis-http\ para os numeros.
$report = Start-Process $java -ArgumentList ((@('-Xmx1g','-jar',$jmeterJar,'-g',(Join-Path $runFolder 'samples.jtl'),'-o',(Join-Path $runFolder 'html')) | ForEach-Object {Quote $_}) -join ' ') `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runFolder 'html-generation.txt') -RedirectStandardError (Join-Path $runFolder 'html-generation-errors.txt')
$reportHandle = $report.Handle
$report.WaitForExit()
if ($null -eq $report.ExitCode -or $report.ExitCode -ne 0) {Write-Warning 'HTML generation failed; raw results were preserved.'}
Write-Host '7/7 Building overview...'
$link='http://localhost:3000/d/eda-task-api?from='+$window.startMs+'&to='+$window.endMs
$stats=Import-Csv (Join-Path $runFolder 'analysis\jmeter-summary.csv')
$httpStats=Import-Csv (Join-Path $runFolder 'analysis-http\jmeter-summary.csv')
$resources=Import-Csv (Join-Path $runFolder 'resource-summary\resource-summary.csv')
$drain=Get-Content (Join-Path $runFolder 'drain.json') -Raw | ConvertFrom-Json
$style='<style>body{font:16px Segoe UI;margin:32px;color:#183746}table{border-collapse:collapse;font-size:13px}td,th{border:1px solid #ccd;padding:8px}th{background:#e8f2f5}a{color:#087b91}</style>'
$body='<h1>Resultados da API orientada a eventos</h1><p>'+[System.Net.WebUtility]::HtmlEncode($runId)+'</p><p><a href="html/index.html">Relatorio JMeter (operacoes e requisicoes)</a> | <a href="'+$link+'">Grafana no intervalo do teste</a></p><p>Post/Put/Delete = do envio do pedido ate a consulta que observou CONCLUIDA (inclui o intervalo de consulta de '+$PollMs+' ms). O 202 isolado nao conta como operacao concluida. Drenagem apos o gerador: '+$drain.secondsAfterGenerator+' s (drenado='+$drain.drained+').</p><h2>Operacoes de negocio</h2>'
$body += ($stats | Select-Object @{n='Operacao';e={$_.label}},@{n='Tentativas';e={$_.samples}},@{n='Falhas';e={$_.failed}},@{n='Erros (%)';e={$_.errorPercent}},@{n='Tempo medio (ms)';e={$_.elapsedAverageAllMs}},@{n='P95 (ms)';e={$_.elapsedP95AllMs}},@{n='Concluidas/s (janela global)';e={$_.successfulPerGlobalSecond}} | ConvertTo-Html -Fragment | Out-String)
$body += '<h2>Trafego HTTP (aceites, consultas de estado e leituras)</h2>'+($httpStats | Select-Object @{n='Rotulo';e={$_.label}},@{n='Requisicoes';e={$_.samples}},@{n='Falhas';e={$_.failed}},@{n='Tempo medio (ms)';e={$_.elapsedAverageAllMs}},@{n='Vazao HTTP (req/s)';e={$_.jmeterThroughputPerSecond}} | ConvertTo-Html -Fragment | Out-String)
$body += '<h2>Processamento no servidor (registro ate estado terminal)</h2>'+($completion | ConvertTo-Html -Fragment | Out-String)
$body += '<h2>Recursos da API</h2>'+($resources | Select-Object @{n='Metrica';e={$_.metric}},@{n='Unidade';e={$_.unit}},@{n='Amostras';e={$_.sampleCount}},@{n='Media';e={$_.meanSampled}},@{n='Maximo amostrado';e={$_.maximumSampled}} | ConvertTo-Html -Fragment | Out-String)
ConvertTo-Html -Title 'EDA API results' -Head $style -Body $body | Set-Content (Join-Path $runFolder 'overview.html') -Encoding UTF8
Write-Host 'Completed. Errors, if any, remain in the report.'
Write-Host "OPEN: $(Join-Path $runFolder 'overview.html')"
Write-Host "Grafana: $link"
Write-Host 'Before next independent repetition: save API console, confirm drained queues and restart API.'
