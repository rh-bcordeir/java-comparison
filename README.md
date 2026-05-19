# Benchmark de Performance: Quarkus vs Spring Boot

Comparação de seis variantes de runtime entre quatro formatos de carga de trabalho:

| Framework | Modo | Variante |
|---|---|---|
| Quarkus | imperativo (blocking, RESTEasy Reactive) | JVM + nativo |
| Quarkus | reativo (Mutiny / event loop do Vert.x) | JVM + nativo |
| Spring Boot | Spring MVC (blocking, Tomcat) | apenas JVM |
| Spring Boot | Spring WebFlux (reativo, Netty + Reactor) | apenas JVM |

Cargas de trabalho: JSON leve (`hello`), uso intensivo de CPU (`cpu`), pressão de alocação (`memory`) e I/O simulado (`io`).

| Cenário | Caminho | Carga de trabalho |
|---|---|---|
| `hello` | `/{modo}/hello` | JSON leve, sem processamento |
| `cpu` | `/{modo}/cpu` | Fibonacci(35) recursivo — CPU puro, determinístico |
| `memory` | `/{modo}/memory` | Aloca + preenche um `byte[]` de 10 MB, retorna checksum — estressa o alocador e o GC |
| `io` | `/{modo}/io` | Atraso artificial de ~200 ms (simula chamada externa / espera de banco) — a variante reativa usa suspensão não-bloqueante |

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

### Perfil (rápido, modo de carga isolada, ~10 min no total)

Toda variante executa o **mesmo número exato de iterações** para um determinado cenário, então duração, vazão e latências são todas diretamente comparáveis dentro de um cenário.

| Configuração | Valor |
|---|---|
| VUs concorrentes | 100 |
| Iterações — hello | 50.000 |
| Iterações — cpu | 5.000 |
| Iterações — memory | 5.000 (cada = alocação de 10 MB → ~50 GB de alocações totais por variante) |
| Iterações — io | 5.000 |
| Execuções por (app, modo, cenário) | 1 |
| Total de execuções medidas | 24 (6 variantes × 4 cenários) |

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
- **Benchmarking em host único**: o k6 e o SUT compartilham CPU. Os números são *comparativos dentro desta execução*, não valores absolutos de referência.
- Sem tuning de JVM — apenas os padrões. O heap é ilimitado, o GC é o G1 padrão.

---

## Resultados

Cada célula em um dado grupo de linhas de cenário executou o mesmo número de iterações, então **duração e req/s são diretamente comparáveis dentro de cada cenário**. Amostra única por célula.

### `/hello` — JSON leve (50.000 requisições, medição do overhead do framework)

| app | modo | duração s | req/s | p95 ms | p99 ms | startup ms | pico RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| imperative | native | **1.23** | **40.516** | **6.72** | **12.80** | **13** | 102 |
| reactive | native | 1.24 | 40.362 | 7.11 | 13.94 | 13 | **96** |
| reactive | jvm | 2.25 | 22.258 | 14.18 | 31.81 | 680 | 261 |
| imperative | jvm | 2.41 | 20.745 | 13.97 | 30.80 | 644 | 270 |
| spring-mvc | jvm | 3.64 | 13.740 | 17.69 | 29.56 | 1.354 | 396 |
| spring-webflux | jvm | 4.76 | 10.500 | 24.50 | 45.92 | 1.257 | 451 |

### `/cpu` — Fibonacci(35) no servidor (5.000 requisições)

| app | modo | duração s | req/s | p95 ms | p99 ms | startup ms | pico RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| imperative | jvm | **52.57** | **95** | 1679 | 1870 | 581 | 224 |
| reactive | jvm | 53.20 | 94 | **1457** | **1573** | 578 | 200 |
| spring-mvc | jvm | 54.81 | 91 | 1551 | 1722 | 1.731 | 332 |
| spring-webflux | jvm | 58.54 | 85 | 1932 | 3465 | 1.502 | 383 |
| imperative | native | 74.63 | 67 | 2822 | 3480 | **10** | 117 |
| reactive | native | 74.71 | 67 | 2774 | 3401 | 20 | **70** |

### `/memory` — aloca + preenche um byte[] de 10 MB (5.000 requisições; estressa alocador & GC)

| app | modo | duração s | req/s | p95 ms | p99 ms | startup ms | pico RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| imperative | jvm | **7.76** | **645** | 355.35 | 527.10 | 605 | 3.476 |
| spring-mvc | jvm | 8.13 | 615 | **294.49** | **435.05** | 1.708 | 3.548 |
| reactive | jvm | 8.30 | 602 | 366.93 | 522.05 | 829 | 3.352 |
| spring-webflux | jvm | 9.04 | 553 | 422.76 | 514.32 | 1.320 | 2.699 |
| imperative | native | 17.18 | 291 | 617.93 | 750.70 | 16 | 1.079 |
| reactive | native | 18.00 | 278 | 682.56 | 867.25 | **11** | **889** |

### `/io` — atraso artificial de ~200 ms (5.000 requisições; mede o modelo de concorrência)

| app | modo | duração s | req/s | p95 ms | p99 ms | startup ms | pico RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| reactive | native | **10.08** | **496** | **203.23** | **210.38** | 19 | **85** |
| imperative | native | 10.08 | 496 | 205.16 | 217.33 | 19 | 118 |
| imperative | jvm | 10.09 | 496 | 204.21 | 216.11 | 697 | 203 |
| reactive | jvm | 10.13 | 494 | 206.96 | 221.86 | 703 | 181 |
| spring-mvc | jvm | 10.14 | 493 | 205.54 | 241.03 | 1.738 | 288 |
| spring-webflux | jvm | 10.18 | 491 | 208.39 | 242.26 | 1.209 | 425 |

(Negrito = melhor da coluna para aquele cenário.)

---

## Análise

### `/hello` — overhead puro de framework

Apenas JVM-contra-JVM: Quarkus reativo 22.258 req/s (**+62%**) e imperativo 20.745 (**+51%**) contra os 13.740 do Spring MVC; o Spring WebFlux fica em último, com 10.500 (**−24%**). O wiring em tempo de build do Quarkus (sem scan de classpath em runtime) corta o overhead por requisição. Reativo e imperativo empatam — sem trabalho real, o modelo de execução não importa. O WebFlux perde até do MVC: a cadeia de `Mono` por requisição custa mais do que rende quando não há I/O para sobrepor.

O nativo dobra a JVM (~40k vs ~21–22k req/s): a rajada termina antes de o JIT aquecer. Vantagem real para rajada curta, FaaS e scale-to-zero; irrelevante para serviços de longa duração, onde a JVM se equipara.

### `/cpu` — Fibonacci(35)

Com 5.000 iterações as variantes JVM ficam quase empatadas (85–95 req/s); o imperativo JVM lidera por pouco e o reativo JVM tem a cauda mais apertada (p99 1.573 ms) graças ao pool dedicado. O nativo fica ~30% atrás — o AOT não tem a otimização guiada por perfil que o JIT aplica ao caminho recursivo quente. CPU-bound é a carga em que o JIT compensa o custo do warmup.

### `/memory` — alocação e GC

A JVM faz ~2,2× a vazão do nativo (645 vs 291 req/s): G1 paralelo e concorrente contra o Serial GC single-threaded do GraalVM nativo. Em troca, o nativo usa ~3–4× menos memória (889–1.079 MB vs 2,7–3,5 GB) — sem `-Xmx`, o G1 cresce o heap livremente. A JVM otimiza vazão, o nativo otimiza densidade; ambos os lados são ajustáveis (`-H:+UseG1GC` no nativo, `-Xmx` na JVM).

### `/io` — atraso de ~200 ms

Com atraso fixo e 100 VUs, o teto é ~500 req/s e todas as seis variantes batem nele (491–496) — a carga é limitada pelo atraso, não pelo framework. A vantagem do reativo (não imobilizar uma thread por requisição bloqueada) só apareceria bem acima de 100 VUs, quando as variantes blocantes esgotam o pool de workers; aumente `VUS` para vê-la. O que `/io` revela aqui é eficiência de recursos: o reativo nativo serve a mesma carga com 85 MB de RSS contra 425 MB do Spring WebFlux.

### Startup & RSS

Startup: Quarkus nativo 13–20 ms, Quarkus JVM ~580–830 ms, Spring Boot JVM 1,2–1,7 s (~2× Quarkus JVM, ~90× Quarkus nativo). Pico de RSS no `/hello`: nativo ~96–102 MB, Quarkus JVM ~260–270 MB, Spring ~400–450 MB. Esses números se multiplicam pelo número de réplicas em Kubernetes e serverless.

### Falhas

**Zero** requisições falhas nas 24 execuções (~390.000 requisições no total).

### Resumo dos trade-offs

| Se você otimiza para… | Escolha |
|---|---|
| Cold-start (FaaS, scale-to-zero) | **Quarkus nativo** (13–20 ms) |
| Densidade de memória | **Quarkus nativo** (96 MB em repouso, 889 MB sob pressão de alocação) |
| Vazão em rajada curta | **Quarkus nativo** (sem imposto de warmup) |
| Vazão em alocação e CPU sustentados | **Quarkus imperativo JVM** |
| Latência de cauda em CPU | **Quarkus reativo JVM** (pool dedicado) |
| Vazão JVM em endpoints leves | **Quarkus reativo ou imperativo JVM** (empate técnico) |
| Fanout de I/O em alta concorrência (>>100 VUs) | **Reativo** (não exercitado neste perfil, mas é a arquitetura indicada) |
| Já está no Spring | **Spring MVC** (blocking) ou **WebFlux** (streaming / backpressure) |

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
./build-all.sh             # compila os jars JVM das 4 apps + os binários nativos do Quarkus
./benchmarks/run-all.sh    # ~25 minutos
cat benchmarks/results/summary.csv
```

O `build-all.sh` é a forma recomendada de compilar tudo: ele produz os jars JVM das quatro aplicações e os binários nativos `-runner` das duas aplicações Quarkus de uma só vez (veja a seção *Build de tudo de uma vez*).

Ajuste a carga via variáveis de ambiente: `VUS=200 MEASURE=60s RUNS_PER_COMBO=5 ./benchmarks/run-all.sh`.
