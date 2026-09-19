"""Looks for a watermark at the bottom of a picture.

    python build/check-watermark.py build/fontcheck/native-0915.png build/fontcheck/batch/002.png

A watermark is a divider line across the picture with a line of text under it, so
the last few hundred rows are described row by row: how much of the row is ink,
whether the row is one long uniform run (a line), and whether it holds the short
runs a line of text is made of. Comparing the app's own share picture with an
export of the same note says whether the watermark is part of what the app draws
in its share card, or part of what the module copies out of the editor.
"""
import sys
from collections import Counter
from pathlib import Path

from PIL import Image

for name in sys.argv[1:]:
    path = Path(name)
    if not path.exists():
        print('%s: missing' % path)
        continue
    image = Image.open(path).convert('RGB')
    width, height = image.size
    print('%s  %dx%d' % (path.name, width, height))
    for top in range(max(0, height - 400), height, 40):
        bottom = min(height, top + 40)
        strip = image.crop((0, top, width, bottom))
        pixels = list(strip.getdata())
        colours = Counter(pixels)
        background, background_count = colours.most_common(1)[0]
        ink = sum(count for colour, count in colours.items()
                  if max(abs(colour[i] - background[i]) for i in range(3)) > 40)
        # A divider is a row that is one colour all the way across, and that
        # colour is not the background.
        line = False
        for y in range(strip.height):
            row = [strip.getpixel((x, y)) for x in range(0, width, 8)]
            first = row[0]
            if all(max(abs(pixel[i] - first[i]) for i in range(3)) <= 12 for pixel in row) \
                    and max(abs(first[i] - background[i]) for i in range(3)) > 40:
                line = True
                break
        print('   y %6d-%-6d bg=%-15s ink=%5.1f%%%s'
              % (top, bottom, str(background), ink * 100.0 / len(pixels),
                 '  <-- uniform row (a divider?)' if line else ''))
