"""Removes the long dash runs from the layout's comment lines.

    python build/fix-xml-comments.py res/layout/activity_config.xml

An XML comment may not contain "--", and the separators used to mark sections in
that file are exactly that, so aapt2 rejects the whole file. The separators are
replaced with plain comments.
"""
import sys
from pathlib import Path

for name in sys.argv[1:]:
    path = Path(name)
    text = path.read_text(encoding='utf-8')
    lines = []
    changed = 0
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith('<!--') and '---' in stripped:
            changed += 1
            continue                      # a pure separator line: drop it
        lines.append(line)
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='')
    print('%s: dropped %d separator comment line(s)' % (path, changed))
