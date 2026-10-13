#!/usr/bin/env bash
# Adds our protocol clients (package titan: sing-box and mieru) to libXray's build, so the
# one LibXray.xcframework (one Go runtime) has them too. Bind with ". ./titan".
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIBXRAY="$(cd "$1" && pwd)"
SINGBOX_VERSION=v1.13.21
MIERU_VERSION=v3.38.2

cd "$LIBXRAY"
rm -rf titan
mkdir titan
cp "$HERE"/*.go titan/
go get "github.com/sagernet/sing-box@$SINGBOX_VERSION" "github.com/enfein/mieru/v3@$MIERU_VERSION"

# sing-box with its go:linkname into x/net's HTTP/2 internals taken out (see the file).
SB="$(dirname "$LIBXRAY")/sing-box"
src="$(go mod download -json "github.com/sagernet/sing-box@$SINGBOX_VERSION" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Dir"])')"
rm -rf "$SB"
cp -R "$src" "$SB"
chmod -R u+w "$SB"
cp "$HERE/force_close.go.patched" "$SB/transport/v2rayhttp/force_close.go"
go mod edit -replace "github.com/sagernet/sing-box=$SB"
go mod tidy
