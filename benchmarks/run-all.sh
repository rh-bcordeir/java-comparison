#!/usr/bin/env bash
# Quick Quarkus benchmark. Each (app, mode, scenario) gets a single 10s run at 100 VUs.
# Total runtime ~3 minutes. Prints a markdown table to stdout.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/benchmarks/scenarios/bench.js"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

VUS="${VUS:-100}"
# Same iteration count per scenario across all (app, mode) combos -> fair comparison.
HELLO_ITERATIONS="${HELLO_ITERATIONS:-50000}"
CPU_ITERATIONS="${CPU_ITERATIONS:-500}"
MEMORY_ITERATIONS="${MEMORY_ITERATIONS:-2000}"

iterations_for() {
  case "$1" in
    hello)  echo "$HELLO_ITERATIONS" ;;
    cpu)    echo "$CPU_ITERATIONS" ;;
    memory) echo "$MEMORY_ITERATIONS" ;;
  esac
}

# (app  mode  port  path_prefix)
COMBOS=(
  "imperative     jvm    8080 /imperative"
  "imperative     native 8080 /imperative"
  "reactive       jvm    8081 /reactive"
  "reactive       native 8081 /reactive"
  "spring-mvc     jvm    8082 /spring-mvc"
  "spring-webflux jvm    8083 /spring-webflux"
)
SCENARIOS=(hello cpu memory)

# CSV output — fresh each run. New schema matches what run-all.sh actually measures
# (single run per combo; no per-second sampling -> no mean RSS / CPU columns).
CSV="$ROOT/benchmarks/results/summary.csv"
mkdir -p "$(dirname "$CSV")"
echo "app,mode,scenario,reqs,duration_s,rate_per_sec,p50_ms,p95_ms,p99_ms,avg_ms,max_ms,failed_pct,startup_ms,peak_rss_mb" > "$CSV"

# results table rows accumulate here
ROWS=()

for combo in "${COMBOS[@]}"; do
  read -r app mode port prefix <<<"$combo"

  if [[ "$mode" == "jvm" ]]; then
    # Quarkus uses fast-jar layout; Spring Boot produces a single fat jar via repackage goal.
    qjar="$ROOT/$app-app/target/quarkus-app/quarkus-run.jar"
    fjar="$ROOT/$app-app/target/$app-app-1.0.0-SNAPSHOT.jar"
    if [[ -f "$qjar" ]]; then
      cmd=(java -jar "$qjar")
    elif [[ -f "$fjar" ]]; then
      cmd=(java -jar "$fjar")
    else
      echo "missing JVM jar for $app -- run: mvn -pl $app-app -am package -DskipTests" >&2; exit 1
    fi
  else
    bin="$ROOT/$app-app/target/$app-app-1.0.0-SNAPSHOT-runner"
    [[ -x "$bin" ]] || { echo "missing $bin  -- run: mvn -pl $app-app -am package -Pnative -DskipTests" >&2; exit 1; }
    cmd=("$bin")
  fi

  for scn in "${SCENARIOS[@]}"; do
    # Defensive: free the port from any leftover process
    fuser -k "$port/tcp" 2>/dev/null || true
    sleep 0.3

    log="$TMP/$app-$mode-$scn.log"
    "${cmd[@]}" >"$log" 2>&1 &
    pid=$!

    # wait for ready (but bail if our process died)
    ready=0
    for _ in $(seq 1 60); do
      if ! kill -0 "$pid" 2>/dev/null; then
        echo "ERROR: $app-$mode died during startup. Log:" >&2
        tail -5 "$log" >&2
        exit 1
      fi
      curl -sf "http://localhost:$port$prefix/hello" >/dev/null && { ready=1; break; }
      sleep 0.1
    done
    [[ "$ready" == 1 ]] || { echo "ERROR: $app-$mode never became ready" >&2; kill "$pid"; exit 1; }

    # Quarkus: "started in 0.731s."  Spring Boot: "Started Application in 1.234 seconds"
    startup_ms=$(awk '
      match($0, /started in ([0-9]+\.[0-9]+)s/, a) { printf "%d", a[1]*1000; exit }
      match($0, /Started [A-Za-z]+ in ([0-9]+\.[0-9]+) seconds/, a) { printf "%d", a[1]*1000; exit }
    ' "$log")

    sjson="$TMP/$app-$mode-$scn.json"
    iters=$(iterations_for "$scn")
    k6 run --quiet \
        -e URL="http://localhost:$port$prefix/$scn" \
        -e VUS="$VUS" \
        -e ITERATIONS="$iters" \
        -e SUMMARY_OUT="$sjson" \
        "$SCRIPT" >/dev/null

    # Peak RSS from /proc (kernel-tracked high water mark, in kB)
    peak_kb=$(awk '/VmHWM:/ {print $2}' /proc/$pid/status 2>/dev/null || echo 0)
    peak_mb=$(( peak_kb / 1024 ))

    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true

    # Extract metrics. Each variant runs the SAME number of iterations -> req/s reflects fairness.
    read -r reqs duration_s rate p50 p95 p99 avg_l max_l failed < <(python3 -c "
import json
m = json.load(open('$sjson'))
d = m['http_req_duration']['values']
reqs = int(m['http_reqs']['values']['count'])
rate = m['http_reqs']['values']['rate']
duration_s = reqs / rate if rate > 0 else 0
failed = m.get('http_req_failed', {}).get('values', {}).get('rate', 0) * 100
print(f\"{reqs} {duration_s:.2f} {rate:.1f} {d['med']:.2f} {d['p(95)']:.2f} {d['p(99)']:.2f} {d['avg']:.2f} {d['max']:.2f} {failed:.2f}\")
")
    echo "${app},${mode},${scn},${reqs},${duration_s},${rate},${p50},${p95},${p99},${avg_l},${max_l},${failed},${startup_ms},${peak_mb}" >> "$CSV"
    ROWS+=("| ${app} | ${mode} | ${scn} | ${reqs} | ${duration_s} | ${rate} | ${p95} | ${p99} | ${startup_ms} | ${peak_mb} |")
    printf '  ✓ %-10s %-7s %-5s  reqs=%-6s in %5ss  rate=%6s/s  p95=%6sms  p99=%6sms  startup=%4sms  rss=%4sMB\n' \
        "$app" "$mode" "$scn" "$reqs" "$duration_s" "$rate" "$p95" "$p99" "$startup_ms" "$peak_mb"
  done
done

echo
echo "## Results (VUs=$VUS; iterations: hello=$HELLO_ITERATIONS, cpu=$CPU_ITERATIONS, memory=$MEMORY_ITERATIONS)"
echo
echo "Every (app × mode) variant ran the **same number of iterations** for a given scenario,"
echo "so 'duration' and 'req/s' are directly comparable within each scenario row group."
echo
echo "| app | mode | scenario | reqs | duration s | req/s | p95 ms | p99 ms | startup ms | peak RSS MB |"
echo "|---|---|---|---:|---:|---:|---:|---:|---:|---:|"
printf '%s\n' "${ROWS[@]}"
echo
echo "CSV written to: $CSV"
