"""Checks that the pages were stacked without a visible join.

    python build/check-picture-seams.py build/device/long1.png 2412,2528,2528,2528,1162

For every seam the row-to-row difference across the join is compared with the
difference between neighbouring rows inside a page. A duplicated or dropped band
shows up as a seam whose difference is far from the typical one, and a long run
of identical rows (a blank filler) is reported as well.
"""
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

path = Path(sys.argv[1])
heights = [int(value) for value in sys.argv[2].split(',')]
image = Image.open(path).convert('L')
width, height = image.size


def row_difference(y):
    """Mean absolute difference between row y-1 and row y."""
    band = image.crop((0, y - 1, width, y + 1))
    top = band.crop((0, 0, width, 1))
    bottom = band.crop((0, 1, width, 2))
    return ImageStat.Stat(ImageChops.difference(top, bottom)).mean[0]


running = 0
seams = []
for value in heights[:-1]:
    running += value
    seams.append(running)

print('picture %dx%d, %d page(s)' % (width, height, len(heights)))
controls = [height // 8, height // 2, height * 7 // 8]
control_values = [row_difference(y) for y in controls]
control = sum(control_values) / len(control_values)
print('row difference inside pages: %.2f (at %s)' % (control, controls))

for y in seams:
    value = row_difference(y)
    ratio = value / control if control else 0
    verdict = 'smooth' if 0.2 <= ratio <= 5 else 'SUSPICIOUS'
    print('seam at y=%-6d difference %.2f  (%.2fx a normal row)  %s'
          % (y, value, ratio, verdict))

# A blank filler or a duplicated band would show as many identical rows.
run = 0
longest = 0
longest_at = 0
previous = None
for y in range(0, height, 4):
    row = image.crop((0, y, width, y + 1)).tobytes()
    if row == previous:
        run += 4
        if run > longest:
            longest = run
            longest_at = y - run
    else:
        run = 0
    previous = row
print('longest run of identical rows: %d at y=%d' % (longest, longest_at))
