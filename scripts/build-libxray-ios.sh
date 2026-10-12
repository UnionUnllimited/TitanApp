#!/usr/bin/env bash
# Builds LibXray.xcframework (Xray-core for iOS, gomobile) into ios/Frameworks/.
# Requires macOS with Xcode, git, go (1.27+), python3.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="${LIBXRAY_REF:-v26.9.30}"
WORK="${WORK_DIR:-$ROOT/.build/libXray-ios}"

rm -rf "$WORK"
git clone --depth 1 --branch "$REF" https://github.com/XTLS/libXray.git "$WORK"
export PATH="$PATH:$(go env GOPATH)/bin"
# Devices only (no simulator / macOS slices): a third of the build time.
sed -i '' 's|"ios,iossimulator,macos,maccatalyst"|"ios"|' "$WORK/build/app/apple_gomobile.py"
grep -q '"ios",' "$WORK/build/app/apple_gomobile.py" || { echo "failed to patch the gomobile target" >&2; exit 1; }
# pingBatch takes at most 5 outbounds per call and calls run one at a time: a whole
# subscription goes in one call instead (one Xray instance, every probe in parallel).
sed -i '' 's|maxPingBatchConfigs = 5$|maxPingBatchConfigs = 256|' "$WORK/xray/ping_batch.go"
grep -q 'maxPingBatchConfigs = 256' "$WORK/xray/ping_batch.go" || { echo "failed to patch pingBatch" >&2; exit 1; }
# Real TCP Fast Open on iOS: Xray-core is patched (scripts/ios-patches/xray-tfo) to dial
# tcpFastOpen hosts through tfo-go, which sends the first data in the SYN via connectx().
"$ROOT/scripts/ios-patches/xray-tfo/vendor.sh" "$WORK"
(cd "$WORK" && python3 build/main.py apple gomobile)

mkdir -p "$ROOT/ios/Frameworks"
rm -rf "$ROOT/ios/Frameworks/LibXray.xcframework"
cp -R "$WORK/LibXray.xcframework" "$ROOT/ios/Frameworks/"
echo "OK: ios/Frameworks/LibXray.xcframework"
