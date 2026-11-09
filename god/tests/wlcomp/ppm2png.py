#!/usr/bin/env python3
"""ppm2png.py in.ppm out.png [factor]  - convert (and downscale by an integer factor) a P6 PPM to PNG, stdlib only."""
import sys, zlib, struct
d = open(sys.argv[1], 'rb').read(); f = int(sys.argv[3]) if len(sys.argv) > 3 else 1
parts = d.split(b'\n', 3); w, h = map(int, parts[1].split()); px = parts[3]
rows = []
for y in range(0, h - h % f, f):
    r = bytearray(b'\x00')
    for x in range(0, w - w % f, f):
        i = (y * w + x) * 3; r += px[i:i + 3]
    rows.append(bytes(r))
ow, oh = len(range(0, w - w % f, f)), len(rows)
def chunk(t, b): return struct.pack('>I', len(b)) + t + b + struct.pack('>I', zlib.crc32(t + b) & 0xffffffff)
open(sys.argv[2], 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', ow, oh, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b''.join(rows), 6)) + chunk(b'IEND', b''))
