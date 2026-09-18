"""Checks that the exported long pictures actually contain the note.

    python build/check-long-images.py build/device/images/tohxl

A renderer that silently draws nothing produces files that look perfectly fine
by size and name. These measurements catch that: a picture with almost no
non-white pixels, or one with no colour at all although the note had photos, is
a failure whatever the log said.
"""
import sys
from pathlib import Path

from PIL import Image


def analyse(path):
    image = Image.open(path).convert('RGB')
    width, height = image.size
    # Work on a sample so a 40000px picture does not take forever.
    step = max(1, height // 400)
    sample = image.crop((0, 0, width, min(height, 400 * step)))
    pixels = list(sample.getdata())
    total = len(pixels)
    non_white = sum(1 for p in pixels if p[0] < 245 or p[1] < 245 or p[2] < 245)
    coloured = sum(1 for p in pixels
                   if max(p) - min(p) > 24)          # anything with real hue
    return width, height, non_white / total, coloured / total


def main():
    root = Path(sys.argv[1])
    files = sorted(root.glob('*.png'))
    if not files:
        print('no png files in %s' % root)
        return 2
    print('%-40s %10s %8s %8s  %s' % ('file', 'size', 'ink', 'colour', 'verdict'))
    problems = 0
    for path in files:
        width, height, ink, colour = analyse(path)
        verdict = 'ok'
        if ink < 0.002:
            verdict = 'FAR TOO EMPTY'
            problems += 1
        elif ink < 0.01:
            verdict = 'suspiciously empty'
        print('%-40s %5dx%-4d %7.1f%% %7.1f%%  %s'
              % (path.name[:40], width, height, ink * 100, colour * 100, verdict))
    print()
    print('%d picture(s) checked, %d clearly empty' % (len(files), problems))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
