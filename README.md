# Benchmark de Performance: Quarkus vs Spring Boot

Comparação de seis variantes de runtime entre três formatos de carga de trabalho:

| Framework | Modo | Variante |
|---|---|---|
| Quarkus | imperativo (blocking, RESTEasy Reactive) | JVM + nativo |
| Quarkus | reativo (Mutiny / event loop do Vert.x) | JVM + nativo |
| Spring Boot | Spring MVC (blocking, Tomcat) | apenas JVM |
| Spring Boot | Spring WebFlux (reativo, Netty + Reactor) | apenas JVM |

Cargas de trabalho: JSON leve (`hello`), uso intensivo de CPU (`cpu`), I/O simulado (`io`).

| Cenário | Caminho | Carga de trabalho |
|---|---|---|
| `hello` | `/{modo}/hello` | JSON leve, sem processamento |
| `cpu` | `/{modo}/cpu` | Fibonacci(35) recursivo — CPU puro, determinístico |
| `memory` | `/{modo}/memory` | Aloca + preenche um `byte[]` de 10 MB, retorna checksum — estressa o alocador e o GC |

> Cada aplicação expõe seu próprio prefixo de caminho (`/imperative`, `/reactive`, `/spring-mvc`, `/spring-webflux`); os binários nativos servem os mesmos caminhos que suas contrapartes JVM.

---

## Arquitetura

### Quatro bases de código, seis variantes de runtime

| Módulo | Stack | Porta JVM | Binário nativo |
|---|---|---|---|
| `imperative-app/` | Quarkus `quarkus-rest` (blocking) + Jackson + CDI | 8080 | `imperative-app-1.0.0-SNAPSHOT-runner` |
| `reactive-app/` | Quarkus `quarkus-rest` retornando `Uni<T>` + Mutiny | 8081 | `reactive-app-1.0.0-SNAPSHOT-runner` |
| `spring-mvc-app/` | Spring Boot 3.4 + Spring MVC + Tomcat embarcado | 8082 | — (apenas JVM) |
| `spring-webflux-app/` | Spring Boot 3.4 + Spring WebFlux + Reactor + Netty embarcado | 8083 | — (apenas JVM) |

As aplicações Quarkus rodam como JVM (`java -jar target/quarkus-app/quarkus-run.jar`) **ou** como binários nativos compilados via AOT (perfil `-Pnative`, GraalVM Mandrel através de build em container). As aplicações Spring rodam como fat-jars padrão (`java -jar target/spring-*-app-1.0.0-SNAPSHOT.jar`); o nativo do Spring é possível via objetivo `native` do `spring-boot-maven-plugin`, mas adiciona complexidade substancial de build, então mantivemos o Spring apenas em JVM nesta comparação.

### Por que não há um módulo `native-app/` separado?

Optamos por tornar `native` um *perfil de build* em cada aplicação Quarkus, em vez de uma terceira árvore de código-fonte. Os binários imperative-native e reactive-native são byte-a-byte idênticos aos seus irmãos JVM, exceto pela etapa de compilação AOT. Comparar JVM vs nativo dentro da mesma base de código é mais significativo do que divergir os fontes.

### Equivalência das cargas de trabalho (inegociável para um benchmark justo)

- **CPU**: `fib(35)` recursivo idêntico nas quatro bases de código. Retorna `9227465`. Ambas as aplicações reativas (Quarkus reativo e Spring WebFlux) delegam o trabalho a um pool de workers dimensionado para CPU — o Quarkus usa um pool fixo de `nCPU`, o WebFlux usa `Schedulers.parallel()`. Esse é o idioma reativo correto para trabalho CPU-bound.
- **memory**: aloca um `byte[]` de 10 MB, preenche byte a byte com `i % 256`, calcula um checksum e retorna `{bytes_allocated, checksum, elapsed_ms}`. O checksum é o que impede a eliminação de código morto — sem ele, a análise de escape permitiria que o JIT removesse a alocação inteira. As variantes reativas delegam o laço de preenchimento ao pool de workers (o laço consome vários ms de CPU + largura de banda de memória — travaria o event loop). Esse cenário estressa a vazão do alocador, o comportamento do GC e a dinâmica de crescimento do heap.
- **hello**: retorna um pequeno mapa JSON; sem processamento. Medição pura do overhead do framework.

---

## Build & Execução

### Pré-requisitos

- Java 21
- Maven 3.9+
- `podman` (Docker também funciona se você trocar o runtime nos POMs)
- `k6` (para benchmarking)

### Modo de desenvolvimento (apenas JVM)

```bash
cd imperative-app
quarkus dev   # ou: mvn quarkus:dev
```

`quarkus dev` roda **apenas em modo JVM** com hot reload. Imagens nativas são compiladas via AOT e não suportam recarga de classes em tempo de execução, portanto não há modo dev nativo.

### Build de tudo de uma vez

```bash
./build-all.sh
```

Executa `mvn clean package -DskipTests` para cada módulo (produzindo os jars JVM das quatro aplicações) e depois `mvn package -Pnative -DskipTests` para as duas aplicações Quarkus (produzindo os binários nativos `-runner`). É isso que o `benchmarks/run-all.sh` espera — use-o em vez de lembrar qual módulo precisa de qual invocação Maven. As aplicações Spring são apenas JVM por design (veja *Arquitetura* acima).

### Modo JVM

```bash
# Compile ambas as aplicações a partir da raiz do projeto
mvn -DskipTests package

# Execute (cada uma em seu próprio terminal)
java -jar imperative-app/target/quarkus-app/quarkus-run.jar   # :8080
java -jar reactive-app/target/quarkus-app/quarkus-run.jar     # :8081

# Faça as requisições
curl http://localhost:8080/imperative/hello
curl http://localhost:8081/reactive/hello
```

### Modo nativo

O build nativo está configurado para usar **podman** como runtime de container (definido no `pom.xml` de cada aplicação). A primeira execução baixa a imagem builder do Mandrel (~1 GB).

```bash
# Compile ambos os binários nativos a partir da raiz do projeto
mvn -pl imperative-app -am package -Pnative -DskipTests
mvn -pl reactive-app  -am package -Pnative -DskipTests

# Execute-os diretamente (sem necessidade de JVM)
./imperative-app/target/imperative-app-1.0.0-SNAPSHOT-runner   # :8080
./reactive-app/target/reactive-app-1.0.0-SNAPSHOT-runner       # :8081
```

> **Flags do Maven explicadas:** `-pl imperative-app` diz ao Maven para compilar apenas aquele módulo (em vez de todos os módulos); `-am` ("also make") garante que quaisquer módulos dos quais ele depende sejam compilados antes. Essas são flags de conveniência ao executar a partir da raiz do projeto — o equivalente de dentro do diretório do módulo é simplesmente `mvn package -Pnative -DskipTests`.

> **Por que podman para o nativo?** O Podman é usado apenas em **tempo de build** para rodar o compilador GraalVM/Mandrel dentro de um container — o binário resultante é um executável Linux standalone que roda sem qualquer container ou JVM. O runtime de container já está configurado no `pom.xml` de cada aplicação (perfil `native`), portanto nenhuma flag extra é necessária na linha de comando. Se você tiver o GraalVM instalado localmente com `native-image` no seu PATH, sobrescreva a propriedade para pular o container: `-Dquarkus.native.container-build=false`.

Tempos de build nativo observados na máquina de teste: imperativo **2m 58s**, reativo **1m 55s** (builds subsequentes reutilizam a imagem Mandrel em cache).

### Testes

```bash
mvn test            # ambos os módulos; o QuarkusTest sobe um servidor in-VM
```

Seis testes de integração (3 endpoints × 2 aplicações), todos passando.

---

## Metodologia do benchmark

### Ferramenta

[**k6**](https://k6.io/) (preferida conforme CLAUDE.md). Três scripts de cenário em `benchmarks/scenarios/`. Cada um recebe a URL completa pela variável de ambiente `URL`, de modo que os mesmos scripts cobrem ambas as aplicações e ambos os modos.

### Perfil (rápido, modo de carga isolada, ~5 min no total)

Toda variante executa o **mesmo número exato de iterações** para um determinado cenário, então duração, vazão e latências são todas diretamente comparáveis dentro de um cenário.

| Configuração | Valor |
|---|---|
| VUs concorrentes | 100 |
| Iterações — hello | 50.000 |
| Iterações — cpu | 500 |
| Iterações — memory | 2.000 (cada = alocação de 10 MB → ~20 GB de alocações totais por variante) |
| Execuções por (app, modo, cenário) | 1 |
| Total de execuções medidas | 18 (6 variantes × 3 cenários) |

O script orquestrador `benchmarks/run-all.sh`, para cada (app × modo × cenário): libera a porta, inicia a aplicação, aguarda `/hello` responder, captura o tempo de startup a partir do log da aplicação, executa o k6, lê o pico de RSS de `/proc/<pid>/status` (`VmHWM`, marca d'água registrada pelo kernel) e encerra a aplicação. Imprime uma única tabela markdown de resultados ao final. Sem CSV, sem arquivos por execução.

```bash
./benchmarks/run-all.sh
# Sobrescrita opcional:
# VUS=200 HELLO_ITERATIONS=200000 CPU_ITERATIONS=2000 MEMORY_ITERATIONS=5000 ./benchmarks/run-all.sh
```

> **Trade-off**: esta é uma comparação de amostra única e rajada curta. O warmup do JIT não é amortizado — essa é uma propriedade real de cargas de trabalho curtas, não uma falha, mas significa que os números da JVM aqui não representam seu pico em regime permanente de longa duração.

### Máquina de teste & ressalvas do host

- Host: Linux 6.19, x86_64, 8 núcleos (valores de CPU% >100% refletem utilização multi-core)
- Java 21 (OpenJDK), Quarkus 3.17.5, Mandrel via `quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21`
- **Benchmarking em host único**: o k6 e o SUT compartilham CPU. Os números são *comparativos dentro desta execução*, não manchetes absolutas.
- Sem tuning de JVM — apenas os padrões. O heap é ilimitado, o GC é o G1 padrão.

---

## Resultados

Cada célula em um dado grupo de linhas de cenário executou o mesmo número de iterações, então **duração e req/s são comparáveis maçã-com-maçã dentro de cada cenário**. Amostra única por célula.

### `/hello` — JSON leve (50.000 requisições, medição do overhead do framework)

| app | modo | duração s | req/s | p95 ms | p99 ms | startup ms | pico RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| reactive | native | **1.73** | **28.914** | **9.6** | **19.4** | 14 | 96 |
| imperative | native | 1.86 | 26.874 | 9.7 | 18.5 | **14** | 102 |
| imperative | jvm | 2.31 | 21.611 | 13.3 | 29.0 | 668 | 296 |
| reactive | jvm | 3.59 | 13.942 | 23.4 | 51.2 | 658 | 227 |
| spring-webflux | jvm | 6.45 | 7.757 | 36.5 | 66.0 | 1.761 | 449 |
| spring-mvc | jvm | 7.16 | 6.985 | 39.5 | 71.3 | 1.980 | 397 |

### `/cpu` — Fibonacci(35) no servidor (500 requisições)

| app | modo | duração s | req/s | p95 ms | p99 ms | startup ms | pico RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| imperative | jvm | **3.55** | **141** | 1390 | 1634 | 583 | 149 |
| reactive | jvm | 6.01 | 83 | **1368** | **1430** | 597 | 124 |
| spring-webflux | jvm | 6.53 | 77 | 1681 | 1801 | 1.365 | 312 |
| spring-mvc | jvm | 7.23 | 69 | 3193 | 3752 | 2.212 | 222 |
| imperative | native | 7.99 | 63 | 3203 | 3935 | **17** | 103 |
| reactive | native | 9.60 | 52 | 2621 | 2735 | 13 | **53** |

### `/memory` — aloca + preenche um byte[] de 10 MB (2.000 requisições; estressa alocador & GC)

| app | modo | duração s | req/s | p95 ms | p99 ms | startup ms | pico RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| imperative | jvm | **3.38** | **591** | 400 | 539 | 577 | 3.312 |
| spring-mvc | jvm | 4.37 | 457 | 463 | 608 | 2.143 | 3.241 |
| reactive | jvm | 4.47 | 447 | **374** | **395** | 755 | 7.312 ⚠ |
| spring-webflux | jvm | 6.08 | 329 | 904 | 1.094 | 1.698 | 1.769 |
| imperative | native | 7.37 | 272 | 691 | 833 | 13 | 948 |
| reactive | native | 9.59 | 209 | 691 | 732 | 19 | **332** |

(Negrito = melhor da coluna para aquele cenário.)

---

## Análise

### O Quarkus é significativamente mais rápido que o Spring Boot na JVM (`/hello`)

Apenas JVM-contra-JVM (mesmas condições):

| Variante | req/s | Δ vs Spring MVC |
|---|---:|---:|
| Quarkus imperativo | 21.611 | **+209%** |
| Quarkus reativo | 13.942 | **+100%** |
| Spring WebFlux | 7.757 | **+11%** |
| Spring MVC | 6.985 | linha de base |

O Quarkus imperativo em JVM atinge ~3× a vazão do Spring MVC. As otimizações de framework em tempo de build do Quarkus (sem varredura de classpath em runtime, grafo CDI resolvido via AOT, roteamento em tempo de compilação do RESTEasy Reactive) reduzem drasticamente o overhead por requisição.

Note também que **o Spring WebFlux mal supera o Spring MVC** no `/hello` (apenas +11%) — Netty + Reactor deveria dominar requisições leves. O overhead do pipeline WebFlux/Reactor (cada requisição vira uma cadeia de assinatura `Mono`) quase anula sua vantagem teórica em cenários leves. O ponto de design do WebFlux é fanout de I/O de alta concorrência, não custo mínimo por requisição.

### O nativo vence o `/hello` aqui — a história do warmup do JIT

Em uma rajada de 50.000 requisições, o `/hello` nativo termina em **1.73 s (reativo) ou 1.86 s (imperativo) vs 7.16 s do Spring MVC** — o Quarkus reativo nativo é ~4× mais rápido que o Spring MVC.

A razão pela qual o nativo vence a JVM aqui é o **warmup do JIT**: a ~21–28k req/s, a execução termina antes de o HotSpot ter promovido completamente os caminhos quentes (tiered-up). O nativo não tem fase de warmup. Para serviços de longa duração com horas de tráfego, a JVM se equipara (isso é bem documentado em toda a indústria, e nossas execuções anteriores de 30 segundos mostraram isso). Para cargas de trabalho de **rajada curta, FaaS, scale-to-zero ou jobs agendados**, o tempo de warmup é tempo morto que você efetivamente paga, e o nativo vence.

### CPU — imperativo JVM dominante; nativo perde feio

| Variante | req/s | p99 ms |
|---|---:|---:|
| Quarkus imperativo JVM | **141** | 1634 |
| Quarkus reativo JVM | 83 | **1430** ← cauda mais apertada |
| Spring WebFlux | 77 | 1801 |
| Spring MVC | 69 | 3752 |
| Quarkus imperativo nativo | 63 | 3935 |
| Quarkus reativo nativo | 52 | 2735 |

O Quarkus imperativo JVM vence com folga a 141 req/s — sem overhead de dispatch para pool de workers, apenas chamadas blocantes puras. O reativo JVM troca 41% de vazão pela cauda de latência mais apertada (p99 = 1.43 s, ~12% melhor que o imperativo). **O nativo é 50–63% mais lento que o imperativo JVM** porque a otimização guiada por perfil do JIT sobre o caminho quente recursivo do Fibonacci está ausente no AOT.

### Alocação de memória — o trade-off do GC exposto

| Variante | req/s | Pico RSS MB | Observações |
|---|---:|---:|---|
| Quarkus imperativo JVM | **591** | 3.312 | G1 maximiza o paralelismo na alocação |
| Spring MVC | 457 | 3.241 | vazão competitiva, RSS similar |
| Quarkus reativo JVM | 447 | **7.312** ⚠ | G1 cresceu o heap agressivamente sob pressão |
| Spring WebFlux | 329 | 1.769 | alocador do Netty mais restrito |
| Quarkus imperativo nativo | 272 | 948 | Serial GC: single-threaded, lento mas compacto |
| Quarkus reativo nativo | 209 | **332** | menor RSS por 5–22× |

Este é o cenário mais informativo do conjunto. Dois achados:

**A JVM é ~2–3× mais rápida na alocação que o nativo.** O G1 (padrão da JVM) é paralelo e concorrente. O GraalVM nativo usa **Serial GC por padrão** — mark-sweep-compact single-threaded. Sob forte pressão de alocação, a implementação do GC domina a vazão.

**O nativo usa drasticamente menos memória sob a mesma pressão.** O Quarkus reativo nativo atingiu pico de 332 MB de RSS enquanto rodava 2.000 × alocações de 10 MB; o reativo JVM atingiu pico de **7,3 GB** para o mesmo trabalho (o G1 cresce o heap alegremente quando a alocação é rápida e nenhum `-Xmx` é definido). Isso é uma **diferença de RSS de 22× para uma carga de trabalho idêntica**.

Você pode ajustar ambos os lados: aumente o nativo para G1 via `-Dquarkus.native.additional-build-args=-H:+UseG1GC` (builds de produção) para recuperar a vazão de alocação do nativo ao custo de RSS, ou restrinja a JVM com `-Xmx256m` para inverter o trade-off no outro sentido. Os padrões refletem a filosofia de deployment de cada um: a JVM otimiza para vazão em um servidor, o nativo para densidade em serverless/edge.

### Startup & RSS em repouso: o overhead persistente do Spring

| Framework | Startup (ms, faixa) | Pico RSS no `/hello` (MB) |
|---|---:|---:|
| Quarkus nativo | **13–19** | 96–102 |
| Quarkus JVM | 577–755 | 227–296 |
| Spring Boot JVM | 1.365–2.212 | 397–449 |

O startup do Spring Boot JVM é **~3× o do Quarkus JVM e ~110× o do Quarkus nativo**. O pico de RSS do Spring no `/hello` é ~1,5× o do Quarkus JVM e ~4× o do Quarkus nativo. Para deployments em Kubernetes com muitas réplicas, ou serverless com scale-to-zero, esses números se acumulam.

### Resumo dos trade-offs

| Se você otimiza para… | Escolha |
|---|---|
| Tempo de cold-start (FaaS, scale-to-zero) | **Quarkus nativo** (13–19 ms) |
| Densidade de memória em repouso | **Quarkus reativo nativo** (96 MB no /hello) |
| Densidade de memória *sob carga* | **Quarkus reativo nativo** (332 MB mesmo sob 20 GB de pressão de alocação) |
| Vazão em rajada curta | **Quarkus nativo** (sem imposto de warmup) |
| Cargas de trabalho intensivas em alocação (parsing, transformação de payloads grandes) | **Quarkus imperativo JVM** (591 req/s no /memory) |
| Trabalho CPU-bound | **Quarkus imperativo JVM** |
| Latência de cauda em CPU | **Quarkus reativo JVM** (pool dedicado) |
| Pico de vazão JVM em endpoints leves | **Quarkus imperativo JVM** |
| Já está no Spring, blocking serve | **Spring MVC** |
| Já está no Spring, precisa de backpressure / streaming reais | **Spring WebFlux** |

### Falhas

**Zero** requisições falhas em todas as 18 execuções (~315.000 requisições no total).

### Resumo dos trade-offs

| Se você otimiza para… | Escolha |
|---|---|
| Tempo de cold-start (FaaS, scale-to-zero) | **Nativo** (qualquer variante) |
| Densidade de memória (muitas réplicas, k8s) | **Nativo** (reativo nativo tem o menor RSS) |
| Pico de vazão sustentado | **JVM** (reativo para I/O-leve, qualquer um para CPU) |
| Latência de cauda previsível em trabalho de CPU | **Reativo JVM** (pool de workers dedicado) |
| Fanout de I/O de alta concorrência (>>100 VUs) | **Reativo** (este benchmark não exercitou essa faixa, mas a arquitetura é a resposta consagrada) |
| Simplicidade & facilidade de depuração | **Imperativo JVM** |

---

## Visualizando os resultados

Um dashboard sem build vive em [`benchmarks/results/index.html`](benchmarks/results/index.html) — um único HTML estático, Chart.js + PapaParse via CDN. Ele agrupa as execuções por `(app, modo)`, permite escolher um cenário e renderiza gráficos de barras para req/s, latência p50/p95/p99, tempo de startup, RSS médio + pico, CPU% e latência média-vs-máxima, além de uma tabela de dados com médias.

![Dashboard de benchmark Quarkus vs Spring](charts.png)

*O dashboard exibe os cartões de resumo no topo (maior vazão, melhor latência p99, startup mais rápido, menor pico de RSS) e quatro gráficos: ranking de vazão, curva de percentis de latência, dispersão de vazão vs latência p99 e startup vs pico de RSS. O seletor de cenário no canto superior direito alterna entre `hello`, `cpu` e `memory`.*

**Opção 1 — apenas abrir o arquivo** (mais simples):

Dê duplo-clique em `benchmarks/results/index.html`, depois use o seletor **Load CSV** na página para apontá-lo para `benchmarks/results/summary.csv`. Navegadores bloqueiam `fetch()` a partir de `file://` por segurança, então o carregamento automático do CSV não funciona sem um servidor — o seletor é a alternativa.

**Opção 2 — servir o diretório** (carrega o CSV automaticamente):

```bash
cd benchmarks/results && python3 -m http.server 8000
# depois abra http://localhost:8000/
```

Qualquer servidor estático funciona (`npx serve`, `caddy file-server`, etc.). A página se adapta automaticamente quando novas variantes aparecem no CSV — sem necessidade de rebuild.

---

## Estrutura do projeto

```
.
├── pom.xml                       # pai: BOM do Quarkus + versão do Spring Boot, Java 21
├── imperative-app/               # stack REST blocking do Quarkus
├── reactive-app/                 # stack Mutiny / event-loop do Quarkus
├── spring-mvc-app/               # Spring Boot + Spring MVC (blocking, Tomcat)
├── spring-webflux-app/           # Spring Boot + Spring WebFlux (reativo, Netty)
├── benchmarks/
│   ├── scenarios/                # scripts k6: hello.js, cpu.js, io.js
│   ├── run-all.sh                # orquestrador
│   └── results/
│       ├── summary.csv           # métricas finais agregadas
│       └── raw/                  # JSON por execução do k6 + CSVs de recursos + logs das apps
├── CLAUDE.md                     # requisitos originais
├── IMPLEMENTATION_PLAN.md        # plano de build em fases e decisões
└── README.md                     # este arquivo
```

---

## Reproduzindo

```bash
git clone <repo> && cd java-comparison
mvn -DskipTests package
mvn -pl imperative-app -am package -Pnative -DskipTests
mvn -pl reactive-app  -am package -Pnative -DskipTests
./benchmarks/run-all.sh    # ~25 minutos
cat benchmarks/results/summary.csv
```

Ajuste a carga via variáveis de ambiente: `VUS=200 MEASURE=60s RUNS_PER_COMBO=5 ./benchmarks/run-all.sh`.
