"""Reads a dexdump listing and prints how the app calls its picture capture.

    python build/extract-capture.py build/dex2.txt com/nearme/note/activity/richedit/webview/WVNoteViewEditFragment

dexdump wraps its output at 120 columns, which splits method signatures and
invoke instructions in half, so the listing is unwrapped first. The interesting
parts are the definition of the capture method and every call site of it,
together with the instructions that build the arguments.
"""
import re
import sys
from pathlib import Path

dump = Path(sys.argv[1])
target = sys.argv[2] if len(sys.argv) > 2 else 'WVNoteViewEditFragment'
names = sys.argv[3].split(',') if len(sys.argv) > 3 else ['doPictureCapture', 'doPictureShare']

ADDRESS = re.compile(r'^\s*[0-9a-f]{6,}:\s')
FIELD = re.compile(r'^\s{2,}\S')
CONTINUATION = re.compile(r'^\S')

lines = []
for raw in dump.read_text(encoding='utf-8', errors='replace').splitlines():
    if not raw.strip():
        lines.append('')
        continue
    if CONTINUATION.match(raw) and lines and lines[-1]:
        lines[-1] += raw
    else:
        lines.append(raw.rstrip())

print('== %d logical lines ==' % len(lines))

# The class's own methods, so the real signature is visible.
print('\n== methods of %s ==' % target)
for i, line in enumerate(lines):
    if 'Class descriptor' in line and "'L%s;'" % target in line:
        for j in range(i + 1, min(i + 4000, len(lines))):
            if 'Class descriptor' in lines[j]:
                break
            if re.match(r'\s+name\s+:', lines[j]):
                name = lines[j].split("'")[1]
                kind = lines[j + 1] if j + 1 < len(lines) else ''
                if any(n in name for n in names):
                    print('  %-40s %s' % (name, kind.strip()))
        break

# Every call site, with the instructions that set the arguments up.
for wanted in names:
    print('\n== call sites of %s ==' % wanted)
    seen = 0
    for i, line in enumerate(lines):
        if 'invoke' not in line or '.%s:' % wanted not in line:
            continue
        if target not in line:
            continue
        seen += 1
        print('\n---- call %d (line %d) ----' % (seen, i + 1))
        start = i
        while start > 0 and lines[start - 1].strip() and not lines[start - 1].strip().startswith('#'):
            start -= 1
        for j in range(max(start, i - 45), i + 1):
            print(lines[j])
    print('\ntotal call sites: %d' % seen)
