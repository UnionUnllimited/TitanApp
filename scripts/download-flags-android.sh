#!/usr/bin/env bash
# Round flags on Android (FlagCircle, the VPN notification, the widget): the square flags of
# flag-icons (as on iOS and Windows) as PNGs in app/src/main/assets/flags/<code>.png. Run
# before the Gradle build; without it the app shows the flag emoji. Requires npm and
# rsvg-convert (librsvg; installed with apt on the Ubuntu runner if missing).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/flags"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# PNGs drawn by librsvg, not VectorDrawables or the SVGs themselves: converters get a part
# of flag-icons wrong (<use> with sizes, markers; the EU flag), librsvg draws them right.
# Assets, not res: codes such as gb-eng keep their hyphen, and nothing refers to them from R,
# so a build without them still compiles.
command -v rsvg-convert >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq librsvg2-bin >/dev/null; }

(cd "$TMP" && npm pack flag-icons@7 --silent >/dev/null && tar -xzf flag-icons-*.tgz)
SRC="$TMP/package/flags/1x1"

rm -rf "$OUT"
mkdir -p "$OUT"
n=0
for svg in "$SRC"/*.svg; do
  code="$(basename "$svg" .svg)"
  # 144 px: sharp in the largest circle (48 dp at 3x) and in the notification's large icon.
  rsvg-convert -w 144 -h 144 -o "$OUT/$code.png" "$svg"
  n=$((n + 1))
done
# A build never goes out without its flags unnoticed.
[ "$n" -ge 200 ] || { echo "only $n flags in flag-icons" >&2; exit 1; }
echo "$n flags in $OUT"
