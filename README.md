# Quarkus vs Spring Boot Performance Benchmark

Comparison of six runtime variants across three workload shapes:

| Framework | Mode | Variant |
|---|---|---|
| Quarkus | imperative (blocking, RESTEasy Reactive) | JVM + native |
| Quarkus | reactive (Mutiny / Vert.x event loop) | JVM + native |
| Spring Boot | Spring MVC (blocking, Tomcat) | JVM only |
| Spring Boot | Spring WebFlux (reactive, Netty + Reactor) | JVM only |

Workloads: lightweight JSON (`hello`), CPU-bound (`cpu`), simulated I/O (`io`).

| Scenario | Path | Workload |
|---|---|---|
| `hello` | `/{mode}/hello` | Lightweight JSON, no work |
| `cpu` | `/{mode}/cpu` | Recursive Fibonacci(35) — pure CPU, deterministic |
| `io` | `/{mode}/io` | 50 ms simulated I/O (blocking sleep / non-blocking delay) |

> Each app exposes its own path prefix (`/imperative`, `/reactive`, `/spring-mvc`, `/spring-webflux`); native binaries serve the same paths as their JVM counterparts.

---

## Architecture

### Four codebases, six runtime variants

| Module | Stack | JVM port | Native binary |
|---|---|---|---|
| `imperative-app/` | Quarkus `quarkus-rest` (blocking) + Jackson + CDI | 8080 | `imperative-app-1.0.0-SNAPSHOT-runner` |
| `reactive-app/` | Quarkus `quarkus-rest` returning `Uni<T>` + Mutiny | 8081 | `reactive-app-1.0.0-SNAPSHOT-runner` |
| `spring-mvc-app/` | Spring Boot 3.4 + Spring MVC + embedded Tomcat | 8082 | — (JVM only) |
| `spring-webflux-app/` | Spring Boot 3.4 + Spring WebFlux + Reactor + embedded Netty | 8083 | — (JVM only) |

Quarkus apps run as JVM (`java -jar target/quarkus-app/quarkus-run.jar`) **or** as AOT-compiled native binaries (`-Pnative` profile, GraalVM Mandrel via container build). Spring apps run as standard fat-jars (`java -jar target/spring-*-app-1.0.0-SNAPSHOT.jar`); Spring native is possible via `spring-boot-maven-plugin`'s native goal but adds substantial build complexity, so we kept Spring JVM-only for this comparison.

### Why no separate `native-app/` module?

We chose to make `native` a *build profile* on each Quarkus app instead of a third source tree. The imperative-native and reactive-native binaries are byte-identical to their JVM siblings except for the AOT compilation step. Comparing JVM vs native within the same codebase is more meaningful than diverging the sources.

### Workload equivalence (this is non-negotiable for a fair benchmark)

- **CPU**: identical recursive `fib(35)` across all four codebases. Returns `9227465`. Both reactive apps (Quarkus reactive and Spring WebFlux) offload to a CPU-sized worker pool — Quarkus uses a fixed `nCPU` pool, WebFlux uses `Schedulers.parallel()`. That's the correct reactive idiom for CPU-bound work.
- **I/O**: 50 ms wait. Blocking apps (imperative, spring-mvc): `Thread.sleep(50)`. Reactive apps: Quarkus uses `Uni.delayIt().by(50ms)`; WebFlux uses `Mono.delay(50ms)`. All non-blocking schedulers, all 50 ms wall-clock.
- **hello**: returns a small JSON map; no work. Pure framework overhead measurement.

---

## Build & Run

### Prerequisites

- Java 21
- Maven 3.9+
- `podman` (Docker also works if you swap the runtime in the POMs)
- `k6` (for benchmarking)

### JVM mode

```bash
# Build both apps
mvn -DskipTests package

# Run (each in its own terminal)
java -jar imperative-app/target/quarkus-app/quarkus-run.jar   # :8080
java -jar reactive-app/target/quarkus-app/quarkus-run.jar     # :8081

# Hit them
curl http://localhost:8080/imperative/hello
curl http://localhost:8081/reactive/hello
```

### Native mode

Native build is configured to use **podman** as the container runtime (set in each app's `pom.xml`). First run pulls the Mandrel builder image (~1 GB).

```bash
# Build both native binaries
mvn -pl imperative-app -am package -Pnative -DskipTests
mvn -pl reactive-app  -am package -Pnative -DskipTests

# Run them directly
./imperative-app/target/imperative-app-1.0.0-SNAPSHOT-runner   # :8080
./reactive-app/target/reactive-app-1.0.0-SNAPSHOT-runner       # :8081
```

Native build times observed on the test host: imperative **2m 58s**, reactive **1m 55s** (subsequent builds reuse the cached Mandrel image).

### Tests

```bash
mvn test            # both modules; QuarkusTest brings up an in-VM server
```

Six integration tests (3 endpoints × 2 apps), all passing.

---

## Benchmark methodology

### Tool

[**k6**](https://k6.io/) (preferred per CLAUDE.md). Three scenario scripts under `benchmarks/scenarios/`. Each takes the full URL via the `URL` env var so the same scripts cover both apps and both modes.

### Profile (quick, iso-load mode, ~5 min total)

Every variant runs the **exact same number of iterations** for a given scenario, so duration, throughput, and latencies are all directly comparable within a scenario.

| Setting | Value |
|---|---|
| Concurrent VUs | 100 |
| Iterations — hello | 50,000 |
| Iterations — cpu | 500 |
| Iterations — io | 10,000 |
| Runs per (app, mode, scenario) | 1 |
| Total measured runs | 18 (6 variants × 3 scenarios) |

The runner script `benchmarks/run-all.sh` for each (app × mode × scenario): frees the port, starts the app, waits for `/hello` to respond, captures startup time from the app log, runs k6, reads peak RSS from `/proc/<pid>/status` (`VmHWM`, kernel-tracked high-water mark), kills the app. Prints a single markdown results table at the end. No CSV, no per-run files.

```bash
./benchmarks/run-all.sh
# Optional override:
# VUS=200 HELLO_ITERATIONS=200000 CPU_ITERATIONS=2000 IO_ITERATIONS=40000 ./benchmarks/run-all.sh
```

> **Trade-off**: this is a single-sample, short-burst comparison. JIT warmup is not amortised — that's a real property of short workloads, not a flaw, but it means the JVM numbers here are not its long-running steady-state peak.

### Test host & host caveats

- Host: Linux 6.19, x86_64, 8 cores (CPU% values >100% reflect multi-core utilisation)
- Java 21 (OpenJDK), Quarkus 3.17.5, Mandrel via `quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21`
- **Single-host benchmarking**: k6 and the SUT share CPU. Numbers are *comparative within this run*, not absolute headlines.
- No JVM tuning — defaults only. Heap is unbounded, GC is the default G1.

---

## Results

Every cell in a given scenario row group ran the same number of iterations, so **duration and req/s are apples-to-apples within each scenario**. Single sample per cell.

### `/hello` — lightweight JSON (50,000 requests, framework overhead measurement)

| app | mode | duration s | req/s | p95 ms | p99 ms | startup ms | peak RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| reactive | native | **1.38** | **36,192** | **8.0** | **14.7** | 16 | 96 |
| imperative | native | 2.02 | 24,716 | 10.7 | 21.7 | **11** | 105 |
| reactive | jvm | 2.87 | 17,417 | 16.7 | 35.7 | 599 | 266 |
| imperative | jvm | 4.22 | 11,853 | 26.2 | 53.1 | 909 | 318 |
| spring-mvc | jvm | 5.22 | 9,580 | 26.9 | 44.6 | 1,424 | 423 |
| spring-webflux | jvm | 6.83 | 7,317 | 40.4 | 69.4 | 1,686 | 452 |

### `/cpu` — Fibonacci(35) on the server (500 requests)

| app | mode | duration s | req/s | p95 ms | p99 ms | startup ms | peak RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| spring-mvc | jvm | **5.60** | **89** | 1824 | 2037 | 1,914 | 226 |
| reactive | jvm | 5.91 | 85 | **1260** | **1295** | 701 | 125 |
| imperative | jvm | 6.27 | 80 | 2170 | 2512 | 901 | 148 |
| reactive | native | 8.13 | 62 | 1794 | 1829 | 11 | **53** |
| spring-webflux | jvm | 8.49 | 59 | 2279 | 2453 | 2,316 | 278 |
| imperative | native | 10.35 | 48 | 4704 | 6310 | **12** | 105 |

### `/io` — 50 ms wait (10,000 requests, concurrency-limited at 100 VUs)

| app | mode | duration s | req/s | p95 ms | p99 ms | startup ms | peak RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| reactive | native | 5.08 | **1968** | 52.3 | **55.1** | 16 | 95 |
| imperative | native | 5.10 | 1961 | **51.7** | 62.2 | **11** | 126 |
| spring-mvc | jvm | 5.13 | 1948 | 52.8 | 60.8 | 1,778 | 327 |
| reactive | jvm | 5.14 | 1947 | 52.6 | 70.6 | 667 | 220 |
| imperative | jvm | 5.25 | 1905 | 53.2 | 90.8 | 928 | 225 |
| spring-webflux | jvm | 5.28 | 1893 | 60.5 | 81.1 | 1,831 | 479 |

(Bold = best in column for that scenario. I/O is concurrency-limited at 100 VUs × 50 ms ≈ 2000 req/s — that's the ceiling, not the implementation's.)

---

## Analysis

### Quarkus is significantly faster than Spring Boot on the JVM

On `/hello`, comparing JVM-to-JVM only (same playing field):

| Variant | req/s | Δ vs Spring MVC |
|---|---:|---:|
| Quarkus reactive | 17,417 | **+82%** |
| Quarkus imperative | 11,853 | **+24%** |
| Spring MVC | 9,580 | baseline |
| Spring WebFlux | 7,317 | **−24%** |

Quarkus reactive JVM hits ~1.8× the throughput of Spring MVC and ~2.4× Spring WebFlux. Quarkus's build-time framework optimisations (no runtime classpath scanning, AOT-resolved CDI graph, RESTEasy Reactive's compile-time routing) reduce per-request overhead substantially. Spring's reflective DI and runtime routing tax shows up here.

Spring WebFlux being **slower than Spring MVC** on `/hello` is surprising at first glance — Netty + Reactor is supposed to win on lightweight requests. The explanation is that for a trivial response, the WebFlux/Reactor pipeline overhead (each request becomes a `Mono` subscription chain) outweighs Tomcat's worker-thread dispatch. WebFlux's design point is high-concurrency I/O, not minimum per-request overhead.

### Native wins `/hello` here — JIT warmup story

In a 50,000-request burst, native `/hello` finishes in **1.38 s (reactive) or 2.02 s (imperative) vs Spring MVC's 5.22 s** — Quarkus reactive native is ~3.8× faster than Spring MVC and ~2.6× faster than Quarkus reactive JVM.

The reason native beats JVM in this benchmark is **JIT warmup**: at ~17–24k req/s, the run is over before HotSpot has fully tiered-up the hot paths. Native has no warmup phase, so it serves at steady-state from request 1. For long-lived services with hours of traffic, JVM catches up and may surpass native (this is well-documented across the industry, and our earlier 30-second runs showed it). For **short-burst, FaaS, scale-to-zero, or scheduled-job** workloads, the warmup time is dead time you actually pay for.

### CPU work — Spring MVC surprisingly competitive on the JVM

| Variant | req/s | p99 ms |
|---|---:|---:|
| Spring MVC | 89 | 2037 |
| Quarkus reactive JVM | 85 | **1295** ← tightest tail |
| Quarkus imperative JVM | 80 | 2512 |
| Quarkus reactive native | 62 | 1829 |
| Spring WebFlux | 59 | 2453 |
| Quarkus imperative native | 48 | 6310 ← worst |

For pure CPU, framework overhead barely matters — fib(35) dominates. Spring MVC's blocking-thread-per-request model handles this fine. Quarkus reactive JVM doesn't lead on throughput but has the **tightest p99 latency by ~30%**, because its dedicated `nCPU`-sized pool isolates CPU work from request dispatch. Native loses 30–60% on CPU because the JIT's profile-guided optimisation of the recursive Fibonacci hot path is absent.

### I/O is a wash — by design

All six variants land at 1893–1968 req/s and ~52 ms p95. At 100 VUs × 50 ms wait, throughput is **concurrency-limited** (`100 / 0.050 = 2000 req/s ceiling`), not implementation-limited. To make reactive's non-blocking I/O actually shine, you'd need 1000+ VUs against a blocking app with default worker pool sizing. That's a different benchmark.

### Memory & startup: Spring's biggest costs

| Framework | Startup (ms, range) | Peak RSS (MB, range) |
|---|---:|---:|
| Quarkus native | **11–21** | **53–126** |
| Quarkus JVM | 599–928 | 125–318 |
| Spring Boot JVM | 1,424–2,316 | 226–479 |

Spring Boot JVM startup is **~2× Quarkus JVM and ~100× Quarkus native**. Spring WebFlux's peak RSS (479 MB on `/io`) is **~9× Quarkus reactive native's 95 MB**. For dense deployments — Kubernetes with many replicas, scale-to-zero serverless, edge — these numbers add up to real money.

### Tradeoffs summary

| If you optimise for… | Pick |
|---|---|
| Cold-start time (FaaS, scale-to-zero) | **Quarkus native** (11–21 ms) |
| Memory density (many replicas) | **Quarkus reactive native** (53 MB peak on CPU) |
| Short-burst throughput | **Quarkus native** (no warmup tax) |
| JVM peak throughput on `/hello` | **Quarkus reactive JVM** |
| CPU tail latency, long-running | **Quarkus reactive JVM** (p99 ~1.3 s) |
| Simplest "boring" stack with broadest team familiarity | **Spring MVC** |
| High-concurrency reactive I/O fanout (>>100 VUs) | **Spring WebFlux or Quarkus reactive** (not exercised here) |
| You're already deeply on Spring | **Spring MVC** if blocking is acceptable, **WebFlux** only if you genuinely need backpressure-aware streaming |

### Failures

**Zero** failed requests across all 18 runs (~230,000 total requests).

### Tradeoffs summary

| If you optimise for… | Pick |
|---|---|
| Cold-start time (FaaS, scale-to-zero) | **Native** (any flavour) |
| Memory density (many replicas, k8s) | **Native** (reactive native lowest RSS) |
| Sustained peak throughput | **JVM** (reactive for I/O-light, either for CPU) |
| Predictable tail latency on CPU work | **Reactive JVM** (dedicated worker pool) |
| High-concurrency I/O fanout (>>100 VUs) | **Reactive** (this benchmark didn't exercise that range, but the architecture is the established answer) |
| Simplicity & debuggability | **Imperative JVM** |

---

## Project layout

```
.
├── pom.xml                       # parent: Quarkus BOM + Spring Boot version, Java 21
├── imperative-app/               # Quarkus blocking REST stack
├── reactive-app/                 # Quarkus Mutiny / event-loop stack
├── spring-mvc-app/               # Spring Boot + Spring MVC (blocking, Tomcat)
├── spring-webflux-app/           # Spring Boot + Spring WebFlux (reactive, Netty)
├── benchmarks/
│   ├── scenarios/                # k6 scripts: hello.js, cpu.js, io.js
│   ├── run-all.sh                # orchestrator
│   └── results/
│       ├── summary.csv           # aggregated final metrics
│       └── raw/                  # per-run k6 JSON + resource CSVs + app logs
├── CLAUDE.md                     # original requirements
├── IMPLEMENTATION_PLAN.md        # phased build plan and decisions
└── README.md                     # this file
```

---

## Reproducing

```bash
git clone <repo> && cd java-comparison
mvn -DskipTests package
mvn -pl imperative-app -am package -Pnative -DskipTests
mvn -pl reactive-app  -am package -Pnative -DskipTests
./benchmarks/run-all.sh    # ~25 minutes
cat benchmarks/results/summary.csv
```

Tweak load via env: `VUS=200 MEASURE=60s RUNS_PER_COMBO=5 ./benchmarks/run-all.sh`.
