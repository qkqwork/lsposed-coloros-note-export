"""Does an exported page have a transparent background?

    python build/check-alpha.py build/fontcheck/002_0915.png

The pages the editor renders are written out with an alpha channel. If their
background is transparent, then the text is there but drawn in the colour of the
note's theme - white text on white, for a dark-mode note - and only the pictures
and emoji stay visible. That is exactly what a picture that "has everything but
the text" turns out to be.
"""
import sys
from collections import Counter
from pathlib import Path

from PIL import Image

path = Path(sys.argv[1])
image = Image.open(path)
print('%s  mode=%s  size=%s' % (path.name, image.mode, image.size))

if 'A' not in image.mode:
    print('  no alpha channel: this picture is already composited')
    grey = image.convert('L')
    print('  most common luminance: %s'
          % ', '.join('%d %.1f%%' % (value, count * 100.0 / (image.size[0] * image.size[1]))
                      for value, count in Counter(grey.getdata()).most_common(3)))
    sys.exit(0)

rgba = image.convert('RGBA')
alpha = rgba.getchannel('A')
total = image.size[0] * image.size[1]
histogram = alpha.histogram()
print('  alpha 0 (fully transparent): %.2f%%' % (histogram[0] * 100.0 / total))
print('  alpha 255 (opaque):          %.2f%%' % (histogram[255] * 100.0 / total))

# What colour is the ink that is opaque? A dark-mode note writes light text.
opaque = Counter()
for pixel in rgba.getdata():
    if pixel[3] > 200:
        opaque[(pixel[0] // 16 * 16, pixel[1] // 16 * 16, pixel[2] // 16 * 16)] += 1
print('  most common opaque colours: %s'
      % ', '.join('%s %.2f%%' % (colour, count * 100.0 / total)
                  for colour, count in opaque.most_common(5)))
