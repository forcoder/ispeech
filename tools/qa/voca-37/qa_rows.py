#!/usr/bin/env python3
"""键帽行检测：按 y 统计近白像素占比，连续高占比行 = 一个键帽行。
用法: qa_rows.py <png> <y0> <y1>   (输出每行的起止、高度px/dp、行间距)"""
import sys
from PIL import Image

px = Image.open(sys.argv[1]).convert('RGB').load()
y0, y1 = int(sys.argv[2]), int(sys.argv[3])
DPI = float(sys.argv[4]) if len(sys.argv) > 4 else 440.0
xs = range(8, 1076, 12)
frac = []
for y in range(y0, y1):
    n = sum(1 for x in xs if min(px[x, y]) >= 250)
    frac.append(n / 90.0)
rows, s = [], None
for i, f in enumerate(frac):
    y = y0 + i
    if f > 0.35 and s is None:
        s = y
    elif f <= 0.35 and s is not None:
        rows.append((s, y)); s = None
if s is not None: rows.append((s, y1))
den = DPI / 160.0
print("ROWS\t%s\tden=%.2f px/dp" % (sys.argv[1].split('/')[-1], den))
for a, b in rows:
    print("  [%4d,%4d) h=%3dpx %5.1fdp" % (a, b, b - a, (b - a) / den))
for (a, b), (c, d) in zip(rows, rows[1:]):
    print("  pitch=%3dpx %5.1fdp  gap=%dpx" % (c - a, (c - a) / den, c - b))
