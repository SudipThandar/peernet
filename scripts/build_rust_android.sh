#!/usr/bin/env bash
# Builds the Rust core .so files into app/src/main/jniLibs for all ABIs.
# CI performs the same steps; this script is for reference only.
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p app/src/main/jniLibs
cd core
cargo ndk \
  -t arm64-v8a -t armeabi-v7a -t x86_64 \
  --platform 26 \
  -o ../app/src/main/jniLibs \
  build --release
