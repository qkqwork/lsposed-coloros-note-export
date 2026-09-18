"""Counts the strokes of a picture whatever colour they are.

    python build/check-text-runs.py build/fontcheck/after-0915.png

Text is short runs of one polarity surrounded by the other, on a light note and on
a dark one alike, so both polarities are counted. A page of a note with words on
it shows several short runs per row; a page whose text is drawn in the colour of
its own background shows none, whichever way round it is.
"""
import sys
from pathlib import Path

from PIL import Image

for name in sys.argv[1:]:
    path = Path(name)
    image = Image.open(path).convert('L')
    step = max(1, image.height // 800)
    dark_runs = 0
    light_runs = 0
    rows = 0
    for top in range(0, image.height, step):
        row = image.crop((0, top, image.width, top + 1)).tobytes()
        run = 0
        for value in row:
            if value < 128:
                run += 1
            else:
                if 1 <= run <= 40:
                    dark_runs += 1
                run = 0
        if 1 <= run <= 40:
            dark_runs += 1
        run = 0
        for value in row:
            if value >= 128:
                run += 1
            else:
                if 1 <= run <= 40:
                    light_runs += 1
                run = 0
        if 1 <= run <= 40:
            light_runs += 1
        rows += 1
    strokes = max(dark_runs, light_runs)
    verdict = 'has text' if strokes / float(rows) >= 1.0 else 'looks empty'
    print('%-28s %4dx%-6d dark/row %6.2f  light/row %6.2f  -> %s'
          % (path.name, image.width, image.height, dark_runs / float(rows),
             light_runs / float(rows), verdict))
