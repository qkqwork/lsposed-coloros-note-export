"""Looks for the short dark strokes that text is made of.

    python build/check-text-ink.py build/fontcheck/002_0915.png

Text is many short dark runs separated by light gaps, one row of a character at a
time; a photograph is few long runs and large dark areas. Counting short runs per
row therefore says whether a page of a note actually has glyphs on it, which is
the question a picture that "looks empty of text" raises.
"""
import sys
from pathlib import Path

from PIL import Image

path = Path(sys.argv[1])
image = Image.open(path).convert('L')
width, height = image.size
print('%s  %dx%d' % (path.name, width, height))

step = 2
short_runs = 0
rows_with_text = 0
rows = 0
dark_pixels = 0
for top in range(0, height, step):
    row = image.crop((0, top, width, top + 1)).tobytes()
    runs = 0
    run = 0
    for value in row:
        if value < 160:
            run += 1
            dark_pixels += 1
        else:
            if 1 <= run <= 40:
                runs += 1
            run = 0
    if 1 <= run <= 40:
        runs += 1
    short_runs += runs
    if runs >= 8:
        rows_with_text += 1
    rows += 1

print('rows sampled: %d' % rows)
print('short dark runs: %d (%.2f per row)' % (short_runs, short_runs / float(rows)))
print('rows that look like text lines: %d (%.1f%%)'
      % (rows_with_text, rows_with_text * 100.0 / rows))
print('dark pixels: %.2f%%' % (dark_pixels * 100.0 / (rows * step * width)))
