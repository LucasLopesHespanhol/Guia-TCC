
# Softwares e etapas necessárias

Descrição para preparar o ambiente e rodar os testes nas arquiteturas :)

OBS: Uso windows 11 :(

## 1. Java 17

A versão do java utilizado foi a 17, então caso não tenha é necessário instalar o JDK.

Para verificar é possível rodando no terminal:

```http
java -version
```

## 2. Docker

É preciso instalar o docker na máquina para execução do serviço do RabbitMQ.

O instalador pode ser baixado em: [Docker](https://docs.docker.com/desktop/setup/install/windows-install/)

Para o uso do docker é necessário instalar uma imagem Linux, recomendo o WSL podendo ser instalado usando o seguinte comando no terminal:

```http
wsl --install
```
Após isso pode iniciar a instalação do Docker.

#### 2.1 Subir os containers

Na raiz de cada API existe um compose referente ao RabbitMQ da aplicação.

Para subir o serviço utilize o comando:

```http
 docker compose up -d
```

Não deixe deixe os dois containers rodando ao mesmo tempo por conta do consumo de recursos.

## 3. Apache JMeter - Ferramenta de teste

O instalador pode ser baixado em: [JMeter](https://jmeter.apache.org/download_jmeter.cgi)

Recomendo baixar o binaries .zip, que foi o que utilizei. Baixe e extraia a pasta, o \bin será usado para rodar o .bat com o arquivo de teste .jmx

## 4. Prometheus

Ferramenta responsável pela coleta do uso de CPU e RAm da API e da máquina. Em conjunto com o Spring Actuator claro.

O instalador pode ser baixado em: [Prometheus](https://prometheus.io/download/)

Quando extraiar, dentro da pasta terá um arquivo chamado prometheus.yml, abra e substitua por isso:

```h
global:
  scrape_interval: 1s
  scrape_timeout: 1s

scrape_configs:
  - job_name: reactive-task-api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:8080']

  - job_name: eda-task-api
    metrics_path: /actuator/prometheus
    scrape_interval: 1s
    scrape_timeout: 1s
    static_configs:
      - targets: ['127.0.0.1:8081']

  - job_name: notebook
    scrape_interval: 2s
    scrape_timeout: 2s
    static_configs:
      - targets: ['127.0.0.1:9182']
```

## 5. Grafana

Ferramenta que liga os dados coletados pelo prometheus a dashboards para uma melhor visualização.

O instalador pode ser baixado em: [Grafana](https://grafana.com/grafana/download?edition=oss&platform=windows)

Eu baixei o binaries .zip, extraia a pasta.

Para rodar o Grafana, use o cmd e vá até a pasta bin do que .zip que extraiu, lá use o comando:


```
.\grafana server
```

Assim você vai iniciar o software, quando entrar você poderá importar o dashboard utilizado no teste, localizado dentro do repositório em Testes-JMeter/GrafanaJson.

# Realizar os testes

Antes de mais nada, verifique se na raiz do projeto ha um arquivo .env

Nele deve conter a password de acesso ao serviço do RabbitMQ. Caso não tenha crie o .env e coloque:

```
RABBITMQ_PASSWORD=default
```

Essa senha deve ser igual a que aparece no application.properties.

```
spring.rabbitmq.password=default
```

### Passo a passo:

Após subir os containers usando o comando comentado a cima, você pode abrir o docker desktop e ver quais foram criados, não deixe ambos rodando, deixe somente aquele que for usar, EDA ou Reativa.

Agora sobre a API, recomendo e testei usando CMD já que o intellij se mostrava consumir recursos que acabaram faltando na máquina.

Vá até o caminho da API e gere o jar com o seguinte comando:

```
mvnw.cmd -q -DskipTests package
```

Depois entre na pasta target usando e verifique se o arquivo .jar da aplicação foi gerado. Para iniciar a aplicação use:

```
 java -jar .\<nome-do-jar>.jar
```

Assim API e Docker estarão rodando.

Depois vá ate a pasta onde extraiu o prometheus e dê dois cliques nele, assim o software irá iniciar. Rode em conjunto dando dois cliques o MétricasMáquinas.cmd na pasta Testes-JMeter, ele é baseado no [Windows_Exporter](https://github.com/prometheus-community/windows_exporter) permite o prometheus coletar as métricas da máquina pela porta 9182.


Você pode ir até onde extraiu o prometheus e clicar no executável, ou abrir cmd na pasta e rodar:

```
.\prometheus.exe
```
Não feche ele enquanto realiza os testes, ele guarda todas as métricas que serão visualizadas no Grafana.

Já o Grafana como é só para visualizar os dashboards você pode iniciar só depois dos testes e colocar o tempo de início e fim do teste, caso queria acompanhar em tempo real, vá ate a pasta que extraiu, entre em bin e execute:

```
.\grafana server
```

### Teste de carga

Vá ate o caminho onde os testes estão: Testes-JMeter. E abra o CMD.

```
set HEAP=-Xms1g -Xmx2g 
```

Isso porque o JMeter usa um um valor default de 1g para memoria e estava dando o erro (OutOfMemoryError) para mim.

Nessa pasta é possível ver quatro arquivos .jmx feitos para ser utilizados pelo JMeter, EDA-Inicial.jmx, EDA.jmx, Reativa-inicial.jmx e Reativa.jmx

Eu recomendo fazer o teste inicial e depois o completo, o inicial é só um teste com 1 usuário. E depois rodar o completo.

Na pasta resultados está o teste que eu fiz e usei como base para os resultados discutidos no artigo. Antes de rodar os testes é importante apagar os que já estão dentro da pasta resultados.

#### Teste EDA

Teste inicial EDA:

```
<Caminho da pasta do JMeter>\bin\jmeter.bat -n -t EDA-Inicial.jmx -Jhttpsampler.max_bytes_to_store_per_request=20000 -l resultados\Eda\Inicial\resultado.jtl
```

Teste EDA completo/final:

```
<Caminho da pasta do JMeter>\bin\jmeter.bat -n -t EDA.jmx -Jhttpsampler.max_bytes_to_store_per_request=20000 -l resultados\Eda\resultado.jtl -e -o resultados\Eda\html
```

Teste inicial Reativa:

```
<Caminho da pasta do JMeter>\bin\jmeter.bat -n -t Reativa-inicial.jmx -Jhttpsampler.max_bytes_to_store_per_request=20000 -l resultados\Reativa\Inicial\resultado.jtl
```

Teste Reativa completo/final:

```
<Caminho da pasta do JMeter>\bin\jmeter.bat -n -t Reativa.jmx -Jhttpsampler.max_bytes_to_store_per_request=20000 -l resultados\Reativa\resultado.jtl -e -o resultados\Reativa\html
```

Acredito que é isso oremos para tudo dar certo 😁 não me reprovem pfv 🙏
