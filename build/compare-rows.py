"""Compares the same rows of two exports of one note.

    python build/compare-rows.py build/fontcheck/white2.png build/fontcheck/dark2.png 7800 8100

Two exports of the same note should hold the same content, whichever colour they
are drawn on. A row that is empty in one and full of text in the other means the
two did not stack the same pages, which is what this prints the colours for.
"""
import sys
from collections import Counter
from pathlib import Path

from PIL import Image

first, second, top, bottom = Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])

for path in (first, second):
    image = Image.open(path).convert('RGB')
    height = image.height
    strip = image.crop((0, min(top, height - 1), image.width, min(bottom, height)))
    colours = Counter(strip.getdata())
    total = sum(colours.values()) or 1
    print('%s  %dx%d  rows %d-%d' % (path.name, image.width, height, top, min(bottom, height)))
    print('   ' + ', '.join('%s %.1f%%' % (colour, count * 100.0 / total)
                            for colour, count in colours.most_common(4)))

# Where does the content of each picture end?
for path in (first, second):
    image = Image.open(path).convert('L')
    width, height = image.size
    last = 0
    for y in range(height - 1, max(0, height - 4000), -4):
        row = image.crop((0, y, width, y + 1)).tobytes()
        middle = row[width // 2]
        extremes = sum(1 for value in row if value < 60 or value > 195)
        if extremes > width * 0.02:
            last = y
            break
    print('%s: content reaches y=%d of %d' % (path.name, last, height))
