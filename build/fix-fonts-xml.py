"""Rewrites a fonts.xml so that every font entry only asks for axes its font has.

    python build/fix-fonts-xml.py build/fontcheck/fonts.xml build/fontcheck \\
        build/fontcheck/fonts.fixed.xml

A font entry that sets an axis the font does not have is rejected by both the
framework and Chromium, and the family it belongs to silently loses that font.
The ColorOS 16 font module does exactly this: it points the whole sans-serif
family at a PingFang variable font and keeps the axis lines of the font it
replaced, so the family ends up with nothing that can be loaded. Anything
rendered through the legacy configuration - which is what a WebView reads -
then draws no text at all, while emoji still work because their family asks for
no axes.

The script only touches entries whose font file is available locally, so it can
be run against a partially downloaded font directory.
"""
import re
import struct
import sys
from pathlib import Path

source = Path(sys.argv[1])
font_dir = Path(sys.argv[2])
target = Path(sys.argv[3]) if len(sys.argv) > 3 else source.with_suffix('.fixed.xml')

FONT_ENTRY = re.compile(r'(<font\b[^>]*>)(?P<body>.*?)(</font>)', re.S)
AXIS = re.compile(r'<axis\s+tag="(?P<tag>[^"]+)"\s+stylevalue="(?P<value>[^"]+)"\s*/>')


def axes_of(path):
    """The variable-font axes of a font file, as tag -> (min, default, max)."""
    data = path.read_bytes()
    start = 0
    if data[:4] == b'ttcf':
        start = struct.unpack('>I', data[12:16])[0]
    count = struct.unpack('>H', data[start + 4:start + 6])[0]
    fvar = None
    for index in range(count):
        entry = start + 12 + index * 16
        if data[entry:entry + 4] == b'fvar':
            fvar = struct.unpack('>II', data[entry + 8:entry + 16])[0]
    if fvar is None:
        return None
    axis_count, axis_size = struct.unpack('>HH', data[fvar + 8:fvar + 12])
    found = {}
    for index in range(axis_count):
        entry = fvar + 16 + index * axis_size
        tag = data[entry:entry + 4].decode('latin-1')
        minimum, default, maximum = struct.unpack('>iii', data[entry + 4:entry + 16])
        found[tag] = (minimum / 65536.0, default / 65536.0, maximum / 65536.0)
    return found


text = source.read_text(encoding='utf-8')
cache = {}
changes = []
dropped = 0
clamped = 0


def fix(match):
    global dropped, clamped
    header, body, footer = match.group(1), match.group('body'), match.group(3)
    name = body.strip().split()[0] if body.strip() else ''
    if not name.endswith(('.ttf', '.ttc', '.otf')):
        return match.group(0)
    path = font_dir / name
    if not path.exists():
        return match.group(0)
    if name not in cache:
        cache[name] = axes_of(path)
    axes = cache[name]
    if name not in cache or axes is None:
        return match.group(0)

    def fix_axis(axis):
        global dropped, clamped
        tag = axis.group('tag')
        value = float(axis.group('value'))
        if tag not in axes:
            dropped += 1
            changes.append('%s: dropped axis %s (the font has no such axis)'
                           % (name, tag))
            return ''
        minimum, _, maximum = axes[tag]
        if value < minimum or value > maximum:
            fixed = max(minimum, min(maximum, value))
            clamped += 1
            changes.append('%s: axis %s %.0f -> %.0f (outside %.0f..%.0f)'
                           % (name, tag, value, fixed, minimum, maximum))
            value = fixed
        return '<axis tag="%s" stylevalue="%g" />' % (tag, value)

    new_body = AXIS.sub(fix_axis, body)
    if new_body == body:
        return match.group(0)
    # Removing an axis line leaves its blank line behind; tidy that up so the
    # result still looks like the file it was made from.
    new_body = re.sub(r'[ \t]+\n', '\n', new_body)
    new_body = re.sub(r'\n{3,}', '\n', new_body)
    return header + new_body + footer


fixed = FONT_ENTRY.sub(fix, text)
target.write_text(fixed, encoding='utf-8')

print('%s -> %s' % (source, target))
print('%d axis line(s) dropped, %d value(s) clamped' % (dropped, clamped))
for change in changes[:40]:
    print('  ' + change)
if len(changes) > 40:
    print('  ... and %d more' % (len(changes) - 40))
