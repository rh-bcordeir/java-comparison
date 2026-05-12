#!/usr/bin/env bash
# Builds every artifact needed by benchmarks/run-all.sh in one shot:
#   - JVM jars for all four apps (imperative, reactive, spring-mvc, spring-webflux)
#   - Native -runner binaries for the two Quarkus apps (imperative, reactive)
#
# Spring apps stay JVM-only — see README "Architecture" section for rationale.
# Native build uses podman (configured in each Quarkus pom.xml); first run pulls
# the Mandrel builder image (~1 GB).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "==> [1/2] Building JVM artifacts for all apps"
mvn clean package -DskipTests

echo
echo "==> [2/2] Building native binaries for Quarkus apps (imperative, reactive)"
mvn -pl imperative-app,reactive-app package -Pnative -DskipTests \
    -Dquarkus.native.container-runtime=podman

echo
echo "==> Done. Artifacts:"
ls -lh imperative-app/target/quarkus-app/quarkus-run.jar \
       reactive-app/target/quarkus-app/quarkus-run.jar \
       spring-mvc-app/target/spring-mvc-app-*.jar \
       spring-webflux-app/target/spring-webflux-app-*.jar \
       imperative-app/target/imperative-app-*-runner \
       reactive-app/target/reactive-app-*-runner 2>/dev/null || true
