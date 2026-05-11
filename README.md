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
| `memory` | `/{mode}/memory` | Allocate + fill 10 MB `byte[]`, return checksum — stresses allocator and GC |

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
- **memory**: allocates a 10 MB `byte[]`, fills it byte-by-byte with `i % 256`, computes a checksum, returns `{bytes_allocated, checksum, elapsed_ms}`. The checksum is what defeats dead-code elimination — without it, escape analysis would let the JIT remove the allocation entirely. Reactive variants offload the fill loop to the worker pool (the loop is several ms of CPU + memory bandwidth — would stall the event loop). This scenario stresses allocator throughput, GC behavior, and heap growth dynamics.
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
| Iterations — memory | 2,000 (each = 10 MB allocation → ~20 GB total allocations per variant) |
| Runs per (app, mode, scenario) | 1 |
| Total measured runs | 18 (6 variants × 3 scenarios) |

The runner script `benchmarks/run-all.sh` for each (app × mode × scenario): frees the port, starts the app, waits for `/hello` to respond, captures startup time from the app log, runs k6, reads peak RSS from `/proc/<pid>/status` (`VmHWM`, kernel-tracked high-water mark), kills the app. Prints a single markdown results table at the end. No CSV, no per-run files.

```bash
./benchmarks/run-all.sh
# Optional override:
# VUS=200 HELLO_ITERATIONS=200000 CPU_ITERATIONS=2000 MEMORY_ITERATIONS=5000 ./benchmarks/run-all.sh
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
| reactive | native | **1.73** | **28,914** | **9.6** | **19.4** | 14 | 96 |
| imperative | native | 1.86 | 26,874 | 9.7 | 18.5 | **14** | 102 |
| imperative | jvm | 2.31 | 21,611 | 13.3 | 29.0 | 668 | 296 |
| reactive | jvm | 3.59 | 13,942 | 23.4 | 51.2 | 658 | 227 |
| spring-webflux | jvm | 6.45 | 7,757 | 36.5 | 66.0 | 1,761 | 449 |
| spring-mvc | jvm | 7.16 | 6,985 | 39.5 | 71.3 | 1,980 | 397 |

### `/cpu` — Fibonacci(35) on the server (500 requests)

| app | mode | duration s | req/s | p95 ms | p99 ms | startup ms | peak RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| imperative | jvm | **3.55** | **141** | 1390 | 1634 | 583 | 149 |
| reactive | jvm | 6.01 | 83 | **1368** | **1430** | 597 | 124 |
| spring-webflux | jvm | 6.53 | 77 | 1681 | 1801 | 1,365 | 312 |
| spring-mvc | jvm | 7.23 | 69 | 3193 | 3752 | 2,212 | 222 |
| imperative | native | 7.99 | 63 | 3203 | 3935 | **17** | 103 |
| reactive | native | 9.60 | 52 | 2621 | 2735 | 13 | **53** |

### `/memory` — allocate + fill 10 MB byte[] (2,000 requests; stresses allocator & GC)

| app | mode | duration s | req/s | p95 ms | p99 ms | startup ms | peak RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| imperative | jvm | **3.38** | **591** | 400 | 539 | 577 | 3,312 |
| spring-mvc | jvm | 4.37 | 457 | 463 | 608 | 2,143 | 3,241 |
| reactive | jvm | 4.47 | 447 | **374** | **395** | 755 | 7,312 ⚠ |
| spring-webflux | jvm | 6.08 | 329 | 904 | 1,094 | 1,698 | 1,769 |
| imperative | native | 7.37 | 272 | 691 | 833 | 13 | 948 |
| reactive | native | 9.59 | 209 | 691 | 732 | 19 | **332** |

(Bold = best in column for that scenario.)

---

## Analysis

### Quarkus is significantly faster than Spring Boot on the JVM (`/hello`)

JVM-to-JVM only (same playing field):

| Variant | req/s | Δ vs Spring MVC |
|---|---:|---:|
| Quarkus imperative | 21,611 | **+209%** |
| Quarkus reactive | 13,942 | **+100%** |
| Spring WebFlux | 7,757 | **+11%** |
| Spring MVC | 6,985 | baseline |

Quarkus imperative JVM hits ~3× the throughput of Spring MVC. Quarkus's build-time framework optimizations (no runtime classpath scanning, AOT-resolved CDI graph, RESTEasy Reactive's compile-time routing) drastically reduce per-request overhead.

Note also that **Spring WebFlux barely beats Spring MVC** on `/hello` (only +11%) — Netty + Reactor is supposed to dominate lightweight requests. The WebFlux/Reactor pipeline overhead (each request becomes a `Mono` subscription chain) nearly cancels its theoretical lightweight advantage. WebFlux's design point is high-concurrency I/O fanout, not minimum per-request cost.

### Native wins `/hello` here — JIT warmup story

In a 50,000-request burst, native `/hello` finishes in **1.73 s (reactive) or 1.86 s (imperative) vs Spring MVC's 7.16 s** — Quarkus reactive native is ~4× faster than Spring MVC.

The reason native beats JVM here is **JIT warmup**: at ~21–28k req/s, the run is over before HotSpot has fully tiered-up the hot paths. Native has no warmup phase. For long-lived services with hours of traffic, JVM catches up (this is well-documented across the industry, and our earlier 30-second runs showed it). For **short-burst, FaaS, scale-to-zero, or scheduled-job** workloads, the warmup time is dead time you actually pay for, and native wins.

### CPU — imperative JVM dominant; native loses big

| Variant | req/s | p99 ms |
|---|---:|---:|
| Quarkus imperative JVM | **141** | 1634 |
| Quarkus reactive JVM | 83 | **1430** ← tightest tail |
| Spring WebFlux | 77 | 1801 |
| Spring MVC | 69 | 3752 |
| Quarkus imperative native | 63 | 3935 |
| Quarkus reactive native | 52 | 2735 |

Quarkus imperative JVM wins outright at 141 req/s — no worker-pool dispatch overhead, just raw blocking calls. Reactive JVM trades 41% throughput for the tightest tail latency (p99 = 1.43 s, ~12% better than imperative). **Native is 50–63% slower than imperative JVM** because the JIT's profile-guided optimization of Fibonacci's recursive hot path is absent in AOT.

### Memory allocation — the GC tradeoff laid bare

| Variant | req/s | Peak RSS MB | Notes |
|---|---:|---:|---|
| Quarkus imperative JVM | **591** | 3,312 | G1 maxes parallelism for allocation |
| Spring MVC | 457 | 3,241 | competitive throughput, similar RSS |
| Quarkus reactive JVM | 447 | **7,312** ⚠ | G1 grew heap aggressively under pressure |
| Spring WebFlux | 329 | 1,769 | Netty's allocator more constrained |
| Quarkus imperative native | 272 | 948 | Serial GC: single-threaded, slow but compact |
| Quarkus reactive native | 209 | **332** | smallest RSS by 5–22× |

This is the most informative scenario in the suite. Two findings:

**JVM is ~2–3× faster at allocation than native.** G1 (the JVM default) is parallel and concurrent. GraalVM native uses **Serial GC by default** — single-threaded mark-sweep-compact. Under heavy allocation pressure, the GC implementation dominates throughput.

**Native uses dramatically less memory under the same pressure.** Quarkus reactive native peaked at 332 MB RSS while running 2,000 × 10 MB allocations; reactive JVM peaked at **7.3 GB** for the same work (G1 happily grows heap when allocation is fast and no `-Xmx` is set). That's a **22× RSS difference for identical workload**.

You can tune both sides: bump native to G1 via `-Dquarkus.native.additional-build-args=-H:+UseG1GC` (production builds) to claw back native allocation throughput at the cost of RSS, or constrain JVM with `-Xmx256m` to flip the tradeoff the other way. The defaults reflect the deployment philosophy of each: JVM optimizes for throughput on a server, native for density on serverless/edge.

### Startup & idle RSS: Spring's persistent overhead

| Framework | Startup (ms, range) | Peak RSS on `/hello` (MB) |
|---|---:|---:|
| Quarkus native | **13–19** | 96–102 |
| Quarkus JVM | 577–755 | 227–296 |
| Spring Boot JVM | 1,365–2,212 | 397–449 |

Spring Boot JVM startup is **~3× Quarkus JVM and ~110× Quarkus native**. Spring's peak RSS on `/hello` is ~1.5× Quarkus JVM and ~4× Quarkus native. For Kubernetes deployments with many replicas, or scale-to-zero serverless, these numbers compound.

### Tradeoffs summary

| If you optimize for… | Pick |
|---|---|
| Cold-start time (FaaS, scale-to-zero) | **Quarkus native** (13–19 ms) |
| Memory density at rest | **Quarkus reactive native** (96 MB on /hello) |
| Memory density *under load* | **Quarkus reactive native** (332 MB even under 20 GB of allocation pressure) |
| Short-burst throughput | **Quarkus native** (no warmup tax) |
| Allocation-heavy workloads (parsing, transforming large payloads) | **Quarkus imperative JVM** (591 req/s on /memory) |
| CPU-bound work | **Quarkus imperative JVM** |
| CPU tail latency | **Quarkus reactive JVM** (dedicated pool) |
| JVM peak throughput on lightweight endpoints | **Quarkus imperative JVM** |
| Already on Spring, blocking is fine | **Spring MVC** |
| Already on Spring, need true backpressure / streaming | **Spring WebFlux** |

### Failures

**Zero** failed requests across all 18 runs (~315,000 total requests).

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
