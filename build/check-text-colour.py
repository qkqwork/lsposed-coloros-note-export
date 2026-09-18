"""Exactly what colour the text of a page is drawn in.

    python build/check-text-colour.py build/fontcheck/pages/cap0.png

Recolouring the text to suit a chosen background only makes sense if the text is
one exact colour and the pictures rarely use it. This counts the opaque pixels by
exact colour, and separately the pixels that sit in short strokes - which is what
text is, as opposed to the large areas a photograph is made of.
"""
import sys
from collections import Counter
from pathlib import Path

from PIL import Image

for name in sys.argv[1:]:
    path = Path(name)
    image = Image.open(path).convert('RGBA')
    width, height = image.size
    exact = Counter()
    stroke_colours = Counter()
    runs = Counter()
    for y in range(0, height, 2):
        row = [image.getpixel((x, y)) for x in range(width)]
        run = 0
        run_colour = None
        for pixel in row:
            red, green, blue, alpha = pixel
            if alpha < 16:
                if 1 <= run <= 40 and run_colour is not None:
                    stroke_colours[run_colour] += run
                    runs['light' if run_colour[0] >= 160 else 'dark'] += 1
                run = 0
                run_colour = None
                continue
            luminance = (red * 299 + green * 587 + blue * 114) // 1000
            band = 'light' if luminance >= 160 else ('dark' if luminance <= 90 else 'mid')
            if band == 'mid':
                if 1 <= run <= 40 and run_colour is not None:
                    stroke_colours[run_colour] += run
                    runs['light' if run_colour[0] >= 160 else 'dark'] += 1
                run = 0
                run_colour = None
                continue
            colour = (red, green, blue)
            if band == (run_colour[0] >= 160 and 'light' or 'dark') if run_colour else True:
                pass
            if run_colour is None:
                run_colour = colour
                run = 1
            elif run_colour == colour:
                run += 1
            else:
                if 1 <= run <= 40:
                    stroke_colours[run_colour] += run
                    runs['light' if run_colour[0] >= 160 else 'dark'] += 1
                run_colour = colour
                run = 1
        if 1 <= run <= 40 and run_colour is not None:
            stroke_colours[run_colour] += run
            runs['light' if run_colour[0] >= 160 else 'dark'] += 1
        for x in range(0, width, 3):
            red, green, blue, alpha = row[x]
            if alpha >= 200:
                exact[(red, green, blue)] += 1

    total = sum(exact.values()) or 1
    print('%s  %dx%d' % (path.name, width, height))
    print('  opaque colours: %s' % ', '.join(
        '%s %.2f%%' % (colour, count * 100.0 / total) for colour, count in exact.most_common(4)))
    print('  stroke runs: %s' % ', '.join('%s=%d' % (key, value) for key, value in runs.items()))
    print('  stroke colours: %s' % ', '.join(
        '%s %d px' % (colour, count) for colour, count in stroke_colours.most_common(4)))
