"""Prints the bottom of a picture as coarse ASCII, so a watermark can be seen.

    python build/show-tail.py build/fontcheck/native-0915.png 400

Row by row, a "." is background, "-" is light ink and "#" is dark ink, sampled
every few pixels. A watermark shows up as a full width line with a short, centred
line of text under it; the note's own text shows up as wide, left aligned rows.
"""
import sys
from pathlib import Path

from PIL import Image

path = Path(sys.argv[1])
rows = int(sys.argv[2]) if len(sys.argv) > 2 else 400
columns = 78

image = Image.open(path).convert('RGB')
width, height = image.size
top = max(0, height - rows)
print('%s  %dx%d  (bottom %d rows)' % (path.name, width, height, height - top))

step_y = max(1, (height - top) // 44)
step_x = max(1, width // columns)
for y in range(top, height, step_y):
    line = []
    for x in range(0, width, step_x):
        red, green, blue = image.getpixel((x, y))
        luminance = (red * 299 + green * 587 + blue * 114) // 1000
        if luminance >= 200:
            line.append('-')
        elif luminance <= 70:
            line.append('#')
        else:
            line.append('.')
    print('%6d %s' % (y, ''.join(line)))
