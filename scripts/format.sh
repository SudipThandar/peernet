#!/usr/bin/env bash
# Formats Rust (cargo fmt) and Kotlin (spotless/ktlint added later).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f core/Cargo.toml ]; then
  (cd core && cargo fmt --all)
fi
