#!/usr/bin/env bash
# Round flags on iOS (FlagCircle): the square flags of flag-icons (as on Windows) into
# ios/Shared/Flags.xcassets, which the app and the widgets both build. Run before xcodegen;
# without it the app shows the flag emoji. Requires npm.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/ios/Shared/Flags.xcassets"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

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
  # 32 pt instead of the viewBox's 512: a Live Activity may not show an image larger than its
  # place. Kept as a vector, so drawn sharp at any size.
  sed 's/<svg /<svg width="32" height="32" /' "$svg" > "$dir/$code.svg"
  grep -q '<svg width="32" height="32" ' "$dir/$code.svg" || { echo "no <svg> in $code.svg" >&2; exit 1; }
  cat > "$dir/Contents.json" <<EOF
{
  "images" : [
    {
      "filename" : "$code.svg",
      "idiom" : "universal"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  },
  "properties" : {
    "preserves-vector-representation" : true
  }
}
EOF
  n=$((n + 1))
done
# The US stars are markers on a path's corners, which iOS (CoreSVG) may not draw: each star
# becomes a path of its own there.
python3 -I - "$OUT" <<'PY'
import glob, os, re, sys

def corners(d):
    """The vertices of a path of straight lines (M L H V Z), in order."""
    toks = re.findall(r"[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", d)
    pts, x, y, sx, sy, cmd, i = [], 0.0, 0.0, 0.0, 0.0, None, 0
    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd, i = t, i + 1
            if cmd in "Zz":
                x, y = sx, sy
                pts.append((x, y))
            continue
        if cmd is None or cmd not in "MmLlHhVv":
            raise ValueError("path command %s" % cmd)
        n = 2 if cmd in "MmLl" else 1
        v = [float(a) for a in toks[i:i + n]]
        i += n
        rel = cmd.islower()
        if cmd in "MmLl":
            x, y = (x + v[0], y + v[1]) if rel else (v[0], v[1])
            if cmd in "Mm":
                sx, sy, cmd = x, y, "l" if rel else "L"
        elif cmd in "Hh":
            x = x + v[0] if rel else v[0]
        else:
            y = y + v[0] if rel else v[0]
        pts.append((x, y))
    return pts

for p in sorted(glob.glob(os.path.join(sys.argv[1], "*", "*.svg"))):
    s = open(p).read()
    if "<marker" not in s:
        continue
    try:
        m = re.search(r'\s*<marker id="([^"]+)"[^>]*>\s*(<path [^>]*/>)\s*</marker>', s)
        line = re.search(r'<path fill="none" marker-mid="url\(#%s\)" d="([^"]+)"/>' % re.escape(m.group(1)), s)
        # marker-mid: on every corner but the first and the last (no viewBox, refX or orient here).
        stars = "".join(m.group(2).replace("<path ", '<path transform="translate(%g %g)" ' % c, 1)
                        for c in corners(line.group(1))[1:-1])
        s = s.replace(line.group(0), stars, 1).replace(m.group(0), "", 1)
    except Exception as e:
        print("::warning::%s: its markers are left as they are (%s)" % (os.path.basename(p), e))
        continue
    open(p, "w").write(s)
PY
# A build never goes out without its flags unnoticed.
[ "$n" -ge 200 ] || { echo "only $n flags in flag-icons" >&2; exit 1; }
echo "$n flags in $OUT"
