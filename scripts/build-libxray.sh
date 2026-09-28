#!/usr/bin/env bash
# Builds libXray.aar (Xray-core for Android) and puts it into app/libs/.
# Requires: git, go (1.24+), python3, Android NDK (ANDROID_NDK_HOME or ANDROID_HOME/ndk/*).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="${LIBXRAY_REF:-main}"   # pin to a release tag, e.g. LIBXRAY_REF=v26.9.9
WORK="${WORK_DIR:-$ROOT/.build/libXray}"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -n "${ANDROID_NDK_LATEST_HOME:-}" ]]; then
    export ANDROID_NDK_HOME="$ANDROID_NDK_LATEST_HOME"
  elif [[ -d "${ANDROID_HOME:-}/ndk" ]]; then
    export ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)"
  else
    echo "ANDROID_NDK_HOME is not set and no NDK found" >&2; exit 1
  fi
fi
echo "Using NDK: $ANDROID_NDK_HOME"

rm -rf "$WORK"
git clone --depth 1 --branch "$REF" https://github.com/XTLS/libXray.git "$WORK"
export PATH="$PATH:$(go env GOPATH)/bin"
(cd "$WORK" && python3 build/main.py android)

mkdir -p "$ROOT/app/libs"
cp "$WORK/libXray.aar" "$ROOT/app/libs/libXray.aar"
echo "OK: app/libs/libXray.aar"
