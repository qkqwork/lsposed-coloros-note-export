"""Draws the module's launcher icon.

Run once; the generated files are committed with the project:

    python tools/make-icon.py

It writes a legacy PNG per density (a launcher masks these itself, so they are
drawn edge to edge) plus the foreground layer of the adaptive icon, which is
what Android 8+ actually shows.
"""
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / 'res'

BRAND_TOP = (20, 184, 166)
BRAND_BOTTOM = (13, 116, 108)
PAGE = (255, 255, 255)
INK = (13, 116, 108)

LEGACY_SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
ADAPTIVE_SIZES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}


def gradient(size, top, bottom):
    """A vertical gradient, drawn at 1px wide and stretched: fast and smooth."""
    strip = Image.new('RGB', (1, size))
    for y in range(size):
        t = y / max(size - 1, 1)
        strip.putpixel((0, y), tuple(
            round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return strip.resize((size, size), Image.NEAREST)


def draw_page(draw, box, radius_ratio=0.08):
    """The note: a white page with three lines and a folded corner."""
    left, top, right, bottom = box
    width = right - left
    height = bottom - top
    radius = max(1, round(width * radius_ratio))
    draw.rounded_rectangle(box, radius=radius, fill=PAGE)

    # Folded top-right corner, cut back to the background colour.
    fold = round(width * 0.26)
    draw.polygon(
        [(right - fold, top), (right, top), (right, top + fold)],
        fill=BRAND_BOTTOM,
    )

    line_h = max(1, round(height * 0.045))
    gap = round(height * 0.145)
    first = top + round(height * 0.30)
    x0 = left + round(width * 0.16)
    for i in range(3):
        x1 = right - round(width * (0.16 if i < 2 else 0.42))
        y = first + i * gap
        draw.rounded_rectangle([x0, y, x1, y + line_h], radius=line_h // 2, fill=INK)


def legacy_icon(size):
    img = gradient(size, BRAND_TOP, BRAND_BOTTOM).convert('RGBA')
    draw = ImageDraw.Draw(img)
    margin = round(size * 0.20)
    left = margin
    top = round(size * 0.17)
    right = size - margin
    bottom = size - round(size * 0.17)
    draw_page(draw, (left, top, right, bottom))
    return img


def adaptive_foreground(size):
    """108dp canvas; only the middle 72dp is guaranteed to be visible."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    unit = size / 108.0
    left, top, right, bottom = (34 * unit, 24 * unit, 74 * unit, 84 * unit)
    draw_page(draw, (left, top, right, bottom), radius_ratio=0.09)
    return img


def main():
    for density, size in LEGACY_SIZES.items():
        target = RES / ('mipmap-%s' % density)
        target.mkdir(parents=True, exist_ok=True)
        legacy_icon(size).save(target / 'ic_launcher.png')
        print('wrote', target / 'ic_launcher.png')

    for density, size in ADAPTIVE_SIZES.items():
        target = RES / ('mipmap-%s' % density)
        target.mkdir(parents=True, exist_ok=True)
        adaptive_foreground(size).save(target / 'ic_launcher_foreground.png')
        print('wrote', target / 'ic_launcher_foreground.png')

    values = RES / 'values'
    values.mkdir(parents=True, exist_ok=True)
    (values / 'colors.xml').write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <color name="ic_launcher_background">#0D746C</color>\n'
        '</resources>\n',
        encoding='utf-8')

    anydpi = RES / 'mipmap-anydpi-v26'
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / 'ic_launcher.xml').write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n',
        encoding='utf-8')
    print('wrote', anydpi / 'ic_launcher.xml')


if __name__ == '__main__':
    main()
