"""Reads the tables of a font file and the families of a fonts.xml.

    python build/inspect-font.py build/fontcheck/SysFont-Regular.ttf
    python build/inspect-font.py --xml build/fontcheck/fonts.xml sans-serif

The point is to see what kind of font the module installs: an sfnt version of
"ttcf" is a collection (which needs an index in fonts.xml), "OTTO" is CFF
outlines, and a "fvar" table means a variable font whose axes must match the
ones the configuration asks for. A mismatch between the two is exactly what
leaves a WebView with no glyphs while the rest of the system still draws text.
"""
import struct
import sys

path = sys.argv[1]
if path == '--xml':
    xml = sys.argv[2]
    wanted = sys.argv[3] if len(sys.argv) > 3 else None
    import re
    text = open(xml, encoding='utf-8', errors='replace').read()
    families = re.findall(r'<family[^>]*name="([^"]+)"[^>]*>(.*?)</family>', text, re.S)
    print('%d families in %s' % (len(families), xml))
    for name, body in families:
        if wanted and wanted not in name:
            continue
        files = re.findall(r'<font[^>]*>([^<\s]+)', body)
        axes = re.findall(r'<axis\s+tag="([^"]+)"[^>]*>', body)
        print('  %-28s files=%s axes=%s' % (name, sorted(set(files))[:4], sorted(set(axes))[:8]))
    sys.exit(0)

data = open(path, 'rb').read()
print('%s  %d bytes' % (path, len(data)))
tag = data[:4]
kind = {b'\x00\x01\x00\x00': 'TrueType outlines (glyf)',
        b'OTTO': 'CFF outlines',
        b'ttcf': 'font collection (TTC)',
        b'true': 'Apple TrueType',
        b'wOFF': 'WOFF', b'wOF2': 'WOFF2'}.get(tag, 'unknown %r' % tag)
print('  sfnt version: %s' % kind)

if tag == b'ttcf':
    count = struct.unpack('>I', data[8:12])[0]
    print('  collection holds %d fonts, versions %s' % (count, data[4:8]))
    offsets = struct.unpack('>%dI' % count, data[12:12 + 4 * count])
    print('  first font table directory at %d' % offsets[0])
    start = offsets[0]
else:
    start = 0

num_tables = struct.unpack('>H', data[start + 4:start + 6])[0]
tables = {}
for index in range(num_tables):
        entry = start + 12 + index * 16
        name = data[entry:entry + 4].decode('latin-1')
        offset, length = struct.unpack('>II', data[entry + 8:entry + 16])
        tables[name] = (offset, length)
print('  tables: %s' % ' '.join(sorted(tables)))

if 'fvar' in tables:
    offset, _ = tables['fvar']
    axes_count, axis_size, instances, instance_size = struct.unpack('>HHHH', data[offset + 8:offset + 16])
    print('  variable font: %d axis/axes, %d instance(s)' % (axes_count, instances))
    for index in range(axes_count):
        entry = offset + 16 + index * axis_size
        axis_tag = data[entry:entry + 4].decode('latin-1')
        minimum, default, maximum = struct.unpack('>iii', data[entry + 4:entry + 16])
        print('    %-6s min=%.2f default=%.2f max=%.2f'
              % (axis_tag, minimum / 65536.0, default / 65536.0, maximum / 65536.0))
else:
    print('  no fvar table: this is a static font')
