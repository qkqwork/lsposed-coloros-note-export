"""Finds the editor activity that hosts the note's WebView capture.

    python build/find-editor-activity.py build/noteapk

The long picture is produced by the note editor (WVNoteViewEditFragment via
WVCaptureScreenHelper), and the share screen only reads it back out of a cache.
Driving a batch therefore means opening the editor for each note, so this looks
for the activity that hosts that fragment and for the capture entry points.
"""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'build/noteapk')
blob = b''.join(f.read_bytes() for f in sorted(root.glob('*.dex')))


def classes(pattern, label):
    found = sorted({m.decode('utf-8', 'replace')
                    for m in re.findall(pattern, blob)})
    print('== %s (%d)' % (label, len(found)))
    for name in found[:40]:
        print('   %s' % name)
    print()


classes(rb'Lcom/nearme/note/activity/[A-Za-z0-9_/$]*Edit[A-Za-z0-9_/$]*;',
        'activities with "Edit" in the name')
classes(rb'Lcom/nearme/note/activity/richedit/[A-Za-z0-9_/$]+;',
        'richedit package, top level')
classes(rb'L[A-Za-z0-9_/$]*WVNoteViewEditFragment[A-Za-z0-9_/$]*;',
        'the fragment and its inner classes')

print('== names mentioning a capture trigger ==')
words = set()
for match in re.findall(rb'[A-Za-z0-9_$]{5,48}', blob):
    text = match.decode('utf-8', 'replace')
    if any(k in text for k in ('doPictureCapture', 'PictureCapture', 'pictureCapture',
                               'onPicture', 'captureWebView', 'CaptureCallback',
                               'getCaptureFilePath', 'getCapturePath', 'cacheBitmap')):
        words.add(text)
for text in sorted(words)[:40]:
    print('   %s' % text)
