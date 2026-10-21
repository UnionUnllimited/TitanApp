#!/usr/bin/env bash
# Puts a patched copy of libXray's Xray-core where libXray's "local" build takes it from
# (<libXray>/../Xray-core) and patches libXray's ping. Build with `build/main.py apple
# gomobile local`: without "local" libXray drops any replace and downloads Xray-core.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIBXRAY="$(cd "$1" && pwd)"
XRAY="$(dirname "$LIBXRAY")/Xray-core"
TFO="github.com/database64128/tfo-go/v2@v2.4.2"

cd "$LIBXRAY"
src="$(go mod download -json github.com/xtls/xray-core | python3 -c 'import json,sys; print(json.load(sys.stdin)["Dir"])')"
rm -rf "$XRAY"
cp -R "$src" "$XRAY"
chmod -R u+w "$XRAY"
python3 "$HERE/apply.py" "$XRAY"
(cd "$XRAY" && go get "$TFO")
python3 "$HERE/apply_libxray.py" "$LIBXRAY"

# Check the patch on this machine (Apple's TCP stack when run on macOS): TFO dials, what
# may go in a SYN, and that the kernel's record of a dial is readable.
cp "$HERE/titan_tfo_test.go" "$HERE/tfo_safe_test.go" "$XRAY/transport/internet/"
(cd "$XRAY" && go test -count=1 -v -run TestTitanTFO ./transport/internet/)
rm "$XRAY/transport/internet/titan_tfo_test.go" "$XRAY/transport/internet/tfo_safe_test.go"

# Several instances in one process (the app's parallel pings): a dialerProxy hop comes from
# the dialing instance, also through mux.
cp "$HERE/titan_obm_test.go" "$XRAY/core/"
(cd "$XRAY" && go test -count=1 -v -run TestTitanOwnHop ./core/)
rm "$XRAY/core/titan_obm_test.go"
