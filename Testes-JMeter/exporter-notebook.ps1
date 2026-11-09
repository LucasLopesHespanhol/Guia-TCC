# Mini-exportador do notebook para o Prometheus, sem instalar nada.
# Responde em http://127.0.0.1:9182/metrics com CPU e memoria do Windows, lidas por CIM
$ErrorActionPreference = 'Stop'
$porta = 9182
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $porta)
$listener.Start()
Write-Host "Exportador do notebook em http://127.0.0.1:$porta/metrics (feche esta janela para parar)"
$total = [double](Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
while ($true) {
    $cliente = $listener.AcceptTcpClient()
    try {
        $stream = $cliente.GetStream()
        $leitor = [System.IO.StreamReader]::new($stream)
        while (($linha = $leitor.ReadLine()) -ne $null -and $linha -ne '') { }   # descarta o cabecalho da requisicao
        $cpu   = [double](Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'").PercentProcessorTime
        $livre = [double](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1024
        $inv = [Globalization.CultureInfo]::InvariantCulture
        $corpo = @(
            '# HELP notebook_cpu_percent CPU total do Windows (%).', '# TYPE notebook_cpu_percent gauge',
            ('notebook_cpu_percent ' + $cpu.ToString($inv)),
            '# HELP notebook_memory_total_bytes Memoria fisica total.', '# TYPE notebook_memory_total_bytes gauge',
            ('notebook_memory_total_bytes ' + $total.ToString('F0', $inv)),
            '# HELP notebook_memory_used_bytes Memoria fisica em uso (total - livre).', '# TYPE notebook_memory_used_bytes gauge',
            ('notebook_memory_used_bytes ' + ($total - $livre).ToString('F0', $inv))
        ) -join "`n"
        $corpo += "`n"
        $bytes = [Text.Encoding]::UTF8.GetBytes($corpo)
        $cab = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 200 OK`r`nContent-Type: text/plain; version=0.0.4`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
        $stream.Write($cab, 0, $cab.Length); $stream.Write($bytes, 0, $bytes.Length); $stream.Flush()
    } catch { Write-Host "Aviso: $_" } finally { $cliente.Close() }
}
