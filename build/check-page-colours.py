"""What exact colours and alphas the lower pages of a note are drawn in.

    python build/check-page-colours.py build/fontcheck/pages/c*.png

The first page of a note was pure white text, and repainting it worked; the lower
pages were not repainted at all, which is why they were trimmed away as if the
note had ended. This prints the colours and the alpha of the opaque pixels of
every page, so the difference between the pages is visible.
"""
import sys
from collections import Counter
from pathlib import Path

from PIL import Image

for index, name in enumerate(sys.argv[1:]):
    path = Path(name)
    image = Image.open(path).convert('RGBA')
    width, height = image.size
    colours = Counter()
    alphas = Counter()
    textish = Counter()
    for y in range(0, height, 3):
        for x in range(0, width, 3):
            red, green, blue, alpha = image.getpixel((x, y))
            alphas['transparent' if alpha < 16 else ('solid' if alpha > 240 else 'partly')] += 1
            if alpha < 16:
                continue
            colours[(red, green, blue)] += 1
            if red > 200 and green > 200 and blue > 200:
                textish[(red, green, blue)] += 1
    solid = sum(colours.values()) or 1
    print('%s  %dx%d' % (path.name, width, height))
    print('   alpha: %s' % ', '.join('%s=%d' % item for item in alphas.most_common()))
    print('   opaque colours: %s' % ', '.join(
        '%s %.1f%%' % (colour, count * 100.0 / solid) for colour, count in colours.most_common(3)))
    print('   lightest colours: %s' % ', '.join(
        '%s %d' % (colour, count) for colour, count in textish.most_common(3)))
