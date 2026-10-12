#!/usr/bin/env bash
# Puts a patched copy of libXray's Xray-core next to it (<libXray>/xray-core) and points
# libXray's go.mod at it.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIBXRAY="$1"
TFO="github.com/database64128/tfo-go/v2@v2.4.2"

cd "$LIBXRAY"
src="$(go mod download -json github.com/xtls/xray-core | python3 -c 'import json,sys; print(json.load(sys.stdin)["Dir"])')"
rm -rf xray-core
cp -R "$src" xray-core
chmod -R u+w xray-core
python3 "$HERE/apply.py" xray-core
(cd xray-core && go get "$TFO")
go mod edit -replace github.com/xtls/xray-core=./xray-core
go get "$TFO"
go mod tidy
