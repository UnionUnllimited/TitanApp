#!/usr/bin/env bash
# Downloads geoip.dat / geosite.dat bundled into the APK (used by routing rules
# like geosite:category-ru or geoip:ru in your Remnawave Xray JSON template).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets"
BASE="${GEO_BASE_URL:-https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download}"

mkdir -p "$DEST"
for f in geoip.dat geosite.dat; do
  curl -fL --retry 3 -o "$DEST/$f" "$BASE/$f"
done
ls -lh "$DEST"/*.dat
