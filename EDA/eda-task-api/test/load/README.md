# Coleta final - EDA (equivalente a Reativa/test/load)

Mesmo protocolo do `run-final.ps1` da Reativa: copia parametrizada do plano, aquecimento 5 x 20, espera 30 s, carga 5000 usuarios / rampa 5 s / 3 ciclos, recuperacao de 20 s, exportacao Prometheus, recursos da API (PID) e do broker (docker stats), resumo e overview.html. Scripts auxiliares copiados da Reativa sem alteracao, exceto `export-prometheus.ps1` (job `eda-task-api` e metricas `eda_*`/`hikaricp_*` no lugar de `app_messaging_*`/`r2dbc_pool_*`).

## O que muda na EDA

- Post, Put e Delete sao *Transaction Controllers*: pedido (202) + consultas a `/solicitacoes/{id}` a cada `PollMs` (250 ms) ate `CONCLUIDA`, com prazo `DeadlineMs` (30 s, igual ao prazo RPC da Reativa). `REJEITADA`, `FALHA` ou prazo esgotado contam como falha da operacao.
- `analysis/` = operacoes de negocio (comparar com a Reativa e com Alano). `analysis-http/` = trafego HTTP (aceites, consultas, leituras), custo proprio da EDA.
- `analysis/server-completion.csv` = tempo registro -> estado terminal medido no servidor (timer `eda_submission_completion`), sem o vies do intervalo de consulta.
- `drain.json` = tempo para esvaziar filas e pendencias depois do gerador. `dlq-delta.json` = mensagens novas nas DLQs.
- GetList esta no plano, desativado. Para o cenario CRUD5, ativar `GetList` em `EdaTest.jmx` e no `JMeterTest.jmx` da Reativa.

## Preparacao (uma vez)

1. Broker da EDA ligado e broker da Reativa parado: `docker compose up -d` na pasta da EDA; `docker compose stop` na pasta da Reativa.
2. Vhost de teste (somente para `TaskEventFlowIntegrationTest`):
   ```powershell
   docker exec eda-task-api-rabbitmq-1 rabbitmqctl add_vhost eda-test
   docker exec eda-task-api-rabbitmq-1 rabbitmqctl set_permissions -p eda-test tcc ".*" ".*" ".*"
   ```
3. Prometheus: acrescentar `infra/prometheus/eda-job.yml` em `scrape_configs` do `prometheus.yml`, validar com `promtool check config` e reiniciar o Prometheus.
4. Grafana: Dashboards > New > Import > `infra/grafana/eda-dashboard.json` (uid `eda-task-api`), escolhendo a fonte Prometheus.
5. Compilar e testar com Java 17 e `RABBITMQ_PASSWORD` na sessao: `.\mvnw.cmd clean package`.

## Validar o plano antes da carga

Com a API iniciada (base vazia), executar uma vez com 1 usuario e 1 ciclo, sem orquestrador, e conferir no JTL os rotulos Post, Post-aceite, Post-consulta, Get, Put, Put-aceite, Put-consulta, Delete, Delete-aceite, Delete-consulta, todos com sucesso:

```powershell
& 'C:\Users\lucas\.jdks\ms-17.0.20.1\bin\java.exe' -jar 'C:\Users\lucas\Tools\jmeter\bin\ApacheJMeter.jar' -n -t .\test\EdaTest.jmx -Jjmeter.save.saveservice.output_format=csv -l .\test\load\results\validacao-1u.jtl
```

Esse comando usa os valores literais do plano (5000/5/3). Para 1 usuario, abrir uma copia do plano na GUI, alterar Thread Group para 1/1/1 e salvar como `EdaTest-1u.jmx` (nao alterar o original), ou usar o orquestrador com `-Users 1 -Loops 1` apos a validacao.

## Executar uma rodada

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\test\load\run-final-eda.ps1 -Users 5000 -Repetition 1
```

Antes de r02 e r03: guardar o console da API, conferir filas drenadas e `eda_submissions_registered` = 0, parar e iniciar a API (reset do H2). Manter broker, Prometheus e Grafana. Alternar a ordem com a Reativa (ex.: R-E-E-R-R-E). Nao escolher a melhor rodada.

## Verificacao manual de indisponibilidade do broker (equivalente ao roteiro da Reativa)

API ligada; `docker compose stop rabbitmq`; POST /tarefas responde 202 e a solicitacao passa a `FALHA` (PUBLICATION_FAILED); GET /tarefas continua 200; `docker compose start rabbitmq`; novo POST chega a `CONCLUIDA` sem reiniciar a JVM. Registrar PID antes/depois e as respostas.

## Extracao opcional pelo H2 (antes de reiniciar a API)

H2 Console em http://127.0.0.1:8081/h2-console, JDBC `jdbc:h2:mem:edadb`, usuario `sa`:

```sql
CALL CSVWRITE('C:/caminho/da/rodada/submissions.csv',
  'SELECT tipo, estado, registrada_em, finalizada_em,
          DATEDIFF(''MILLISECOND'', registrada_em, finalizada_em) AS processamento_ms
     FROM task_submissions');
```

Estado: roteiro e plano preparados em 04/11/2026; sintaxe do XML verificada; nenhuma execucao do JMeter, do PowerShell ou da carga foi feita na preparacao.

## Atualizacao 04/11/2026 (notebook novo)

- `run-final-eda.ps1` e o `run-final.ps1` da Reativa: caminhos do Java/JMeter agora vem de `TCC_JAVA_HOME`/`TCC_JMETER_HOME`, com padrao `C:\Program Files\Java\jdk-17` e `C:\Users\User\Tools\apache-jmeter-5.6.3`. Heap do JMeter continua 1g por padrao (`TCC_JMETER_HEAP` permite mudar, igual nas duas arquiteturas).
- `EdaTest.jmx`: a condicao dos tres While passou de `${__groovy(...)}` para a variavel `${continuar}`, definida no "Inicia espera" e na assertion "Conclusao". Mesmo intervalo (250 ms) e mesmo prazo (30 s). A primeira consulta sempre ocorre, entao a operacao nao pode mais ser contada como sucesso sem observar `CONCLUIDA`. A versao anterior esta em `Sessao-20261104\backups\EDA\EdaTest.jmx`.
- Rodadas e relatorio da sessao: `..\..\..\..\Sessao-20261104\RELATORIO-SESSAO-20261104.md`.
