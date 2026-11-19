"""Packs the exported map PNGs to 128-colour palettes for Assets.xcassets/RouteMap.imageset.

Usage: python3 quantize.py <in-dir> <out-dir>
"""
import pathlib
import sys

from PIL import Image

src, dst = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
dst.mkdir(parents=True, exist_ok=True)
for f in sorted(src.glob("route-map-*@*x.png")):
    img = Image.open(f).convert("RGB")
    img.quantize(colors=128, method=Image.Quantize.MEDIANCUT).save(
        dst / f.name, optimize=True)
    print(f.name, (dst / f.name).stat().st_size)
