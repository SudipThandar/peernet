#!/usr/bin/env bash
# Runs the pure-Rust loopback harness + Kotlin unit tests (Milestone 4+).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f core/Cargo.toml ]; then
  (cd core && cargo test --release)
fi
./gradlew testDebugUnitTest
