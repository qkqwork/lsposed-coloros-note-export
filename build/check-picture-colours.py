"""What colours a long picture is actually made of.

    python build/check-picture-colours.py build/fontcheck/before-001.png

An earlier export looked like "everything but the text": a page whose text is
drawn in the same colour as its background looks exactly like that, and the
colours say so at once. The title band, the body band and the whole picture are
summarised separately, and any colour that dominates a band is reported with the
share of pixels it covers.
"""
import sys
from collections import Counter
from pathlib import Path

from PIL import Image

path = Path(sys.argv[1])
image = Image.open(path).convert('RGB')
width, height = image.size
print('%s  %dx%d' % (path.name, width, height))

bands = [('title', 0, 200), ('body', 200, min(height, 1200)), ('whole', 0, height)]
for name, top, bottom in bands:
    strip = image.crop((0, top, width, bottom))
    pixels = list(strip.getdata())
    total = len(pixels)
    buckets = Counter()
    exact = Counter()
    for red, green, blue in pixels:
        lum = (red * 299 + green * 587 + blue * 114) // 1000
        if lum >= 245:
            buckets['white'] += 1
        elif lum >= 200:
            buckets['pale'] += 1
        elif lum >= 128:
            buckets['mid'] += 1
        elif lum >= 40:
            buckets['dark'] += 1
        else:
            buckets['black'] += 1
        if max(red, green, blue) - min(red, green, blue) > 40:
            buckets['colourful'] += 1
        exact[(red, green, blue)] += 1
    print('\n[%s] y %d-%d' % (name, top, bottom))
    for key in ('white', 'pale', 'mid', 'dark', 'black', 'colourful'):
        print('   %-10s %6.2f%%' % (key, buckets[key] * 100.0 / total))
    print('   most common: %s' % ', '.join(
        '%s %.1f%%' % (colour, count * 100.0 / total)
        for colour, count in exact.most_common(3)))
