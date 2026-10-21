#!/usr/bin/env bash
# Round flags on iOS (FlagCircle): the square flags of flag-icons (as on Windows) into
# ios/Shared/Flags.xcassets, which the app and the widgets both build. Run before xcodegen;
# without it the app shows the flag emoji. Requires npm and rsvg-convert (librsvg; installed
# with Homebrew on the macOS runner).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/ios/Shared/Flags.xcassets"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# PNGs drawn by librsvg, not the SVGs themselves: iOS's own SVG renderer gets a quarter of
# flag-icons wrong (<use> with sizes, markers): the EU flag came out all yellow.
command -v rsvg-convert >/dev/null || brew install librsvg >/dev/null

(cd "$TMP" && npm pack flag-icons@7 --silent >/dev/null && tar -xzf flag-icons-*.tgz)
SRC="$TMP/package/flags/1x1"

rm -rf "$OUT"
mkdir -p "$OUT"
printf '{\n  "info" : {\n    "author" : "xcode",\n    "version" : 1\n  }\n}\n' > "$OUT/Contents.json"
n=0
for svg in "$SRC"/*.svg; do
  code="$(basename "$svg" .svg)"
  dir="$OUT/flag-$code.imageset"
  mkdir -p "$dir"
  # 44 pt at 2x and 3x: sharp in the largest circle (the Live Activity's 46 pt), and small
  # enough for a Live Activity, which may refuse an image much larger than its place.
  rsvg-convert -w 88 -h 88 -o "$dir/$code@2x.png" "$svg"
  rsvg-convert -w 132 -h 132 -o "$dir/$code@3x.png" "$svg"
  cat > "$dir/Contents.json" <<EOF
{
  "images" : [
    { "idiom" : "universal", "scale" : "1x" },
    { "filename" : "$code@2x.png", "idiom" : "universal", "scale" : "2x" },
    { "filename" : "$code@3x.png", "idiom" : "universal", "scale" : "3x" }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
EOF
  n=$((n + 1))
done
# A build never goes out without its flags unnoticed.
[ "$n" -ge 200 ] || { echo "only $n flags in flag-icons" >&2; exit 1; }
echo "$n flags in $OUT"
