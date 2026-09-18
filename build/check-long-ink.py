"""Ink profile of a long picture, band by band.

    python build/check-long-ink.py build/device/long1.png 2528

Reports how much of each band is not background, so a band that came out blank
(or was filled in twice) is obvious. "Ink" is any pixel darker than 200, which
is what text and pictures look like on a white note.
"""
import sys
from pathlib import Path

from PIL import Image

path = Path(sys.argv[1])
band = int(sys.argv[2]) if len(sys.argv) > 2 else 2528

image = Image.open(path).convert('L')
width, height = image.size
print('picture %dx%d' % (width, height))

dark = image.point(lambda value: 255 if value < 200 else 0)
for top in range(0, height, band):
    bottom = min(height, top + band)
    strip = dark.crop((0, top, width, bottom))
    histogram = strip.histogram()
    ink = histogram[255]
    total = strip.width * strip.height
    print('y %6d - %6d  ink %5.2f%%  %s' % (top, bottom, ink * 100.0 / total,
                                            'blank' if ink * 100.0 / total < 0.2 else ''))
