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
# ("ios" alone also builds the simulator in newer gomobile, which Cronet has no Intel build for.)
sed -i '' 's|"ios,iossimulator,macos,maccatalyst"|"ios/arm64"|' "$WORK/build/app/apple_gomobile.py"
grep -q '"ios/arm64",' "$WORK/build/app/apple_gomobile.py" || { echo "failed to patch the gomobile target" >&2; exit 1; }
# pingBatch takes at most 5 outbounds per call and calls run one at a time: a whole
# subscription goes in one call instead (one Xray instance, every probe in parallel).
sed -i '' 's|maxPingBatchConfigs = 5$|maxPingBatchConfigs = 256|' "$WORK/xray/ping_batch.go"
grep -q 'maxPingBatchConfigs = 256' "$WORK/xray/ping_batch.go" || { echo "failed to patch pingBatch" >&2; exit 1; }
# Calls may run side by side (each starts its own Xray instance): the lock only checks that
# no managed instance runs. The app keeps a few servers in flight and shows each one's
# result as soon as it's measured, instead of one huge burst that the network drops.
python3 - "$WORK/xray/ping_batch.go" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = """	coreServerMu.Lock()
	defer coreServerMu.Unlock()
	if coreServer != nil {
		return nil, errors.New("pingBatch requires an isolated process without a managed Xray instance")
	}
"""
new = """	coreServerMu.Lock()
	managed := coreServer != nil
	coreServerMu.Unlock()
	if managed {
		return nil, errors.New("pingBatch requires an isolated process without a managed Xray instance")
	}
"""
if s.count(old) != 1:
    sys.exit("ping_batch.go: the lock in PingBatchWithLocation not found")
open(p, "w").write(s.replace(old, new))
PY
# Real TCP Fast Open on iOS: Xray-core is patched (scripts/ios-patches/xray-tfo) to dial
# tcpFastOpen hosts through tfo-go, which sends the first data in the SYN via connectx().
"$ROOT/scripts/ios-patches/xray-tfo/vendor.sh" "$WORK"
# Xray within the tunnel's memory: the TUN's gVisor stack (smaller TCP buffers, UDP flows
# ended with the stack), the transports (smaller windows, pools closed on a restart) and the
# observatory (a few probes at once). iOS only (xray-tfo is Windows' too), on the copy
# vendor.sh just made.
XRAY="$(dirname "$WORK")/Xray-core"
MEM="$ROOT/scripts/ios-patches/xray-mem"
python3 "$MEM/apply.py" "$XRAY"
if grep -qs titanCloseAll "$XRAY/proxy/tun/udp_fullcone.go"; then
  cp "$MEM/titan_udp_close_test.go" "$XRAY/proxy/tun/"
  (cd "$XRAY" && go test -count=1 -v -run TestTitanUDPClose ./proxy/tun/)
  rm "$XRAY/proxy/tun/titan_udp_close_test.go"
fi
if [ -f "$XRAY/transport/internet/titan_mem.go" ]; then
  # quic-go keeping to Xray's receive windows under Chrome parroting (Hysteria, XHTTP/3).
  "$MEM/vendor_quic.sh" "$WORK"
  TESTS="app/observatory/burst/titan_pacing_test.go transport/internet/splithttp/titan_h2_test.go
    transport/internet/grpc/titan_grpc_test.go transport/internet/hysteria/titan_quic_test.go"
  for t in $TESTS; do cp "$MEM/$(basename "$t")" "$XRAY/$t"; done
  (cd "$XRAY" && go test -count=1 -v -run 'TestTitan(Pacing|H2|GRPC|QUICWindows)' \
    ./app/observatory/burst/ ./transport/internet/splithttp/ ./transport/internet/grpc/ ./transport/internet/hysteria/)
  for t in $TESTS; do rm "$XRAY/$t"; done
fi
# Our protocol clients (sing-box: TUIC / AnyTLS / ShadowTLS / SSH, and mieru) in the same
# framework: one Go runtime per app. Package titan, bound next to libXray's.
"$ROOT/scripts/ios-patches/titan/vendor.sh" "$WORK"
python3 - "$WORK/build/app/apple_gomobile.py" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '"15.0",\n'
if s.count(old) != 1:
    sys.exit("apple_gomobile.py: gomobile command not found")
s = s.replace(old, '"15.0", "-tags", "with_quic,with_utls,with_naive_outbound", "-o", "LibXray.xcframework", ".", "./titan",\n')
open(p, "w").write(s)
PY
(cd "$WORK" && python3 build/main.py apple gomobile local)

# Naive runs on Chromium's network stack (Cronet, sing-box's naive outbound): its static
# library isn't part of the Go archive, the app links it itself (project.yml).
CRONET_DIR="$(cd "$WORK" && go list -m -f '{{.Dir}}' github.com/sagernet/cronet-go/lib/ios_arm64)"
[ -f "$CRONET_DIR/libcronet.a" ] || { echo "libcronet.a not found" >&2; exit 1; }

mkdir -p "$ROOT/ios/Frameworks"
rm -rf "$ROOT/ios/Frameworks/LibXray.xcframework"
cp -R "$WORK/LibXray.xcframework" "$ROOT/ios/Frameworks/"
cp "$CRONET_DIR/libcronet.a" "$ROOT/ios/Frameworks/LibXray.xcframework/libcronet.a"
echo "OK: ios/Frameworks/LibXray.xcframework"
