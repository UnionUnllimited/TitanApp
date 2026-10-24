#!/usr/bin/env bash
# quic-go (Hysteria, XHTTP over HTTP/3) with its receive windows within Xray's caps and the
# connection's window updated next to stalled streams too (quic_go.py): a patched copy next to
# libXray's Xray-core, put in place of the module for
# the build (libXray) and for Xray-core's own tests. iOS only, like the rest of xray-mem.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIBXRAY="$(cd "$1" && pwd)"
XRAY="$(dirname "$LIBXRAY")/Xray-core"
QUIC="$(dirname "$LIBXRAY")/quic-go"

# The version Xray-core requires (libXray's is the same: it pins that Xray-core).
src="$(cd "$XRAY" && go mod download -json github.com/apernet/quic-go | python3 -c 'import json,sys; print(json.load(sys.stdin)["Dir"])')"
rm -rf "$QUIC"
cp -R "$src" "$QUIC"
chmod -R u+w "$QUIC"
python3 "$HERE/quic_go.py" "$QUIC"
(cd "$LIBXRAY" && go mod edit -replace "github.com/apernet/quic-go=../quic-go")
(cd "$XRAY" && go mod edit -replace "github.com/apernet/quic-go=../quic-go")
