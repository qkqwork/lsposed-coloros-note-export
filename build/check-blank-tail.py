"""Finds the last row of a picture that still has something on it.

    python build/check-blank-tail.py build/fontcheck/forced-dark.png 0D0D0D FFFFFF

Why a dark export kept its blank tail while a white one did not: the trimming
walks up from the last row and stops at the first row that is not the background,
so one stray pixel near the bottom blocks all of it. This prints where the last
non-background row is, and what is on it.
"""
import sys
from pathlib import Path

from PIL import Image

for name in sys.argv[1:2] + sys.argv[3:]:
    path = Path(name)
    image = Image.open(path).convert('RGB')
    width, height = image.size
    print('%s  %dx%d' % (path.name, width, height))

colours = sys.argv[2].split(',') if len(sys.argv) > 2 else ['0D0D0D']
backgrounds = [tuple(int(value[index:index + 2], 16) for index in (0, 2, 4)) for value in colours]

for top in range(0, height, 500):
    bottom = min(height, top + 500)
    strip = image.crop((0, top, width, bottom))
    pixels = list(strip.getdata())
    counts = {}
    for pixel in pixels:
        near = None
        for background in backgrounds:
            if all(abs(pixel[index] - background[index]) <= 12 for index in range(3)):
                near = background
                break
        key = near if near is not None else pixel
        counts[key] = counts.get(key, 0) + 1
    total = len(pixels)
    top_few = sorted(counts.items(), key=lambda item: -item[1])[:2]
    print('  y %6d-%-6d %s' % (top, bottom, ', '.join(
        '%s %.2f%%' % (colour, count * 100.0 / total) for colour, count in top_few)))
