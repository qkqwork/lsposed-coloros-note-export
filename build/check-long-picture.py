"""Checks the long picture the module exported.

    python build/check-long-picture.py build/device/long1.png 2412,2528,2528,2528,1162

Prints the picture's size and saves a downscaled crop around every seam so the
join between the pages the app rendered can be looked at, plus the top and the
bottom of the picture. Seams are given as the heights of the pages that were
stacked, in order; they must add up to the picture's height for the stack to be
a faithful copy of the note.
"""
import sys
from pathlib import Path

from PIL import Image

path = Path(sys.argv[1])
heights = [int(value) for value in sys.argv[2].split(',')] if len(sys.argv) > 2 else []
out = path.parent

image = Image.open(path)
width, height = image.size
print('picture: %s  %dx%d  %d bytes' % (path.name, width, height, path.stat().st_size))

if heights:
    print('pages: %s  total %d  picture %d  %s'
          % (heights, sum(heights), height,
             'exact' if sum(heights) == height else 'MISMATCH'))
    bounds = []
    running = 0
    for value in heights[:-1]:
        running += value
        bounds.append(running)
    for index, y in enumerate(bounds, 1):
        top = max(0, y - 130)
        crop = image.crop((0, top, width, min(height, y + 130)))
        crop = crop.resize((crop.width // 2, crop.height // 2), Image.LANCZOS)
        target = out / ('seam%d.png' % index)
        crop.save(target)
        print('seam %d at y=%d (page %d of %d) -> %s' % (index, y, index, len(heights), target.name))

top = image.crop((0, 0, width, 500)).resize((width // 2, 250), Image.LANCZOS)
top.save(out / 'long-top.png')
bottom = image.crop((0, height - 500, width, height)).resize((width // 2, 250), Image.LANCZOS)
bottom.save(out / 'long-bottom.png')
print('top and bottom crops written next to the picture')
