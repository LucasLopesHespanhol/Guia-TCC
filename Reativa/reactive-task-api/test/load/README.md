# Coleta final guiada - Reativa

## Executar uma rodada

Com RabbitMQ,API,Prometheus e Grafana ligados, base sem tarefas e filas drenadas, no PowerShell na raiz da API:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\test\load\run-final.ps1 -Users 5000 -Repetition 1
```

O comando nao modifica a politica permanente da maquina. O script guarda uma copia do JMeterTest.jmx, parametriza usuarios/rampa/ciclos e define conexao5s/resposta60s na copia. Desativa listeners visuais na copia; nao altera o original.

Ele confere servicos/base/filas, inicia coletores ocultos, aquece5usuarios x20ciclos, verifica500amostras validas e basevazia, espera30s e exige pending0/consumidores1. Executa5000usuarios/rampa5s/3ciclos, observa20s de recuperacao, exporta metricas e gera os relatorios. Usa JDK17 e JMeter5.6.3 instalados. A JVM geradora usa heap1GiB conforme padrao do JMeter instalado; se houver erro do gerador, preservar e investigar. Prazo de protecao do gerador:600s por fase. Coletores:900s maximos com parada normal por arquivo-sinal ao terminar.

## Visualizar

O caminho impresso ao finalizar aponta para overview.html dentro de test/load/results/reactive-u5000-r01-DATA-HORA. Abra pelo Explorador do Windows:

- overview.html: tabelas por rota, tempo medio, vazao, erros, recursosCPU/JVM/RAM e linkGrafana com intervalo exato.
- html/index.html: relatorio oficial JMeter com estatisticas e graficos.
- analysis/jmeter-summary.csv: tabela para planilha, incluindo media geral e dos sucessos.
- resource-summary/resource-summary.csv: CPU,heap,nonheap,WorkingSet quando coletados. Medias amostrais e maximos observados, nao picos instantaneos garantidos.
- prometheus: series originais e consultas; nao precisa ler para consultar as tabelas.

Guarde tambem o console da API pelo IntelliJ para investigar erros. Arquivos brutos e logs permanecem como evidencia. Nao interpretar exit0 do gerador como todas operacoes corretas: olhar Error%/success. Relatorio pode conter503; nao apagar repeticoes ruins. Falhas de aquecimento interrompem antes da rodada medida, com evidencias preservadas. Erro de coleta/geracao doHTML nao apaga dados ja gravados.

## Proximas repeticoes

Antes de r02 e r03: conferir pending0 e Ready/Unacked0 da fila reactive.tasks.commands.capacity, guardar resultados, parar/iniciar a API para resetH2; manter broker/Prometheus/Grafana. Repetir mesmo comando alterando -Repetition2 ou3. A reinicializacao e manual e so deve ocorrer apos drenagem. Nao escolher apenas melhor rodada; tres ciclos nao equivalem a tres execucoes independentes. Nao iniciar outra carga GUI em paralelo.

O roteiro e os parametros sao candidatos ao protocolo final. A primeira execucao completa valida tambem o funcionamento operacional do coletor integrado. Foi validada a sintaxePS5.1, a leitura do plano pelo JMeter sem executar HTTP e a logica de resumo previamente em JTLhistorico/fixtures. Nenhuma nova carga foi executada ao instalar este roteiro.

CPU e memoria do broker estao em rabbit-resources.jsonl com definicoesDocker, separados daAPI; nao ha soma automatica de denominadores diferentes. Grafanaheap/nonheap e WorkingSet sao metricas distintas. EDAprecisa medir conclusao de negocio, nao comparar simples202 com sucesso reativo aposcommit.

## Correcao do roteiro - 02/11/2026

A tentativa reactive-u5000-r01-20261102-154522 parou no aquecimento:10amostras,5POST201aprovados e5GET200reprovados pela assertion. A carga5000nao iniciou. A serializacao XML formatada introduziu espacos no campo filename vazio da JSR223Assertion, que oJMeter interpretou como arquivo. Corrigido para serializacao compacta/normalizacao de camposvazios, preservando o scriptinline e o planooriginal. O codigo de saida do Start-Process ficava null noWindowsPS5.1; o roteiro agora retem Handle antes da espera e trata explicitamente codigoausente. Mesmacorrecao aplicada a geracaoHTML.

Verificado emWindowsPS5.1: processos com exit0e7 recuperados corretamente; JMeter carregou e preparou a assertion com filenamevazio e scriptpreservado; um cicloCRUD real passou5/5semfalhas, exit0 eHTML gerado. O ciclo pequeno nao removeu tarefasanteriores. Permanecem5tarefas da tentativa interrompida; antes da novaexecucao independente, conferirpending/filaszerados e reiniciarAPI pararesetH2. A tentativaoriginal foi preservada; nao e resultadofinal. Nenhuma nova carga5000 executada nessa correcao.
