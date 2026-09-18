"""Stacks the app's own page files over different backgrounds and measures text.

    python build/test-page-background.py build/fontcheck/pages 0D0D0D FFFFFF FAFAFA

Each colour is tried as the backing of the pages, and the result is measured for
the short runs of contrasting pixels that glyphs are made of. If a page is white
text on nothing, stacking it on white hides the text and stacking it on the
colour the app itself uses brings it back - which is the whole difference between
an export that looks empty and one that reads.
"""
import sys
from pathlib import Path

from PIL import Image

folder = Path(sys.argv[1])
colours = sys.argv[2:] or ['0D0D0D', 'FFFFFF']
pages = sorted(folder.glob('*.png'), key=lambda path: path.name)
print('%d page(s): %s' % (len(pages), ', '.join(page.name for page in pages)))
if not pages:
    sys.exit(1)

width = max(Image.open(page).size[0] for page in pages)
height = sum(Image.open(page).size[1] for page in pages)


def measure(image, label):
    grey = image.convert('L')
    step = max(1, grey.height // 600)
    runs = 0
    rows = 0
    dark = 0
    samples = 0
    for top in range(0, grey.height, step):
        row = grey.crop((0, top, grey.width, top + 1)).tobytes()
        run = 0
        for value in row:
            samples += 1
            if value < 128:
                dark += 1
                run += 1
            else:
                if 1 <= run <= 40:
                    runs += 1
                run = 0
        if 1 <= run <= 40:
            runs += 1
        rows += 1

    # A glyph is a short run of one polarity surrounded by the other, so both
    # polarities are counted: what matters is the alternation, not the colour.
    light_runs = 0
    for top in range(0, grey.height, step):
        row = grey.crop((0, top, grey.width, top + 1)).tobytes()
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

    print('  %-8s dark runs/row %.2f   light runs/row %.2f   dark %.1f%%'
          % (label, runs / float(rows), light_runs / float(rows),
             dark * 100.0 / max(1, samples)))


for colour in colours:
    rgb = tuple(int(colour[index:index + 2], 16) for index in (0, 2, 4))
    canvas = Image.new('RGBA', (width, height), rgb + (255,))
    top = 0
    for page in pages:
        sheet = Image.open(page).convert('RGBA')
        canvas.alpha_composite(sheet, (0, top))
        top += sheet.height
    out = folder.parent / ('stacked-%s.png' % colour)
    canvas.convert('RGB').save(out)
    print('%s -> %s' % (colour, out.name))
    measure(canvas, colour)
