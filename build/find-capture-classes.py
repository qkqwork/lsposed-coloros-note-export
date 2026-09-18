"""Prints the fully qualified class names around the capture pipeline.

    python build/find-capture-classes.py build/noteapk CaptureScreenUtils
"""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'build/noteapk')
needles = sys.argv[2:] or ['CaptureScreenUtils', 'CaptureState', 'CaptureHolder',
                           'CaptureElementInfo', 'HtmlCaptureCallback',
                           'CaptureListViewCallback', 'CaptureSuccessCause']

blob = b''.join(f.read_bytes() for f in sorted(root.glob('*.dex')))

for needle in needles:
    pattern = rb'L[A-Za-z0-9_/$]*' + needle.encode() + rb'[A-Za-z0-9_/$]*;'
    found = sorted({m.decode('utf-8', 'replace') for m in re.findall(pattern, blob)})
    print('== %s (%d)' % (needle, len(found)))
    for name in found:
        print('   %s' % name)
    print()

# The strings that describe the states the UI shows while it waits.
print('== state-ish strings ==')
words = set()
for match in re.findall(rb'[\x20-\x7e]{4,40}', blob):
    text = match.decode('utf-8', 'replace')
    if any(k in text for k in ('正在生成', 'CaptureState', 'captureState', 'onCaptureEnd',
                               'onCaptureStart', 'captureElements')):
        words.add(text)
for text in sorted(words)[:40]:
    print('   %s' % text)
