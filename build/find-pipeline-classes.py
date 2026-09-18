"""Lists candidate class names in the Notes dex for hooking the capture pipeline.

    python build/find-pipeline-classes.py build/noteapk

The share screen hangs at "正在生成" when it is started with only a note id, so
the question is what it is waiting for. These names say where to look: the
loader that fetches one note, and the capture helpers the share screen calls.
"""
import re
import sys
from pathlib import Path

PATTERNS = [
    rb'Lcom/[A-Za-z0-9_/$]*(?:[Cc]apture|[Ll]oader|[Rr]epository|[Dd]ataManager|'
    rb'[Nn]oteData|[Nn]oteManager|[Nn]oteModel|[Nn]oteInfo)[A-Za-z0-9_/$]*;',
    rb'Lcom/nearme/note/[A-Za-z0-9_/$]*(?:Db|DB|Store|Cache|Provider)[A-Za-z0-9_/$]*;',
]

INTERESTING = re.compile(
    rb'(loadNote|getNote|queryNote|noteById|getNoteByGuid|openNote|readNote|'
    rb'capture|Capture|Screenhot|screenShot|takeScreen|longImage|LongImage)')


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else 'build/noteapk')
    blob = b''.join(f.read_bytes() for f in sorted(root.glob('*.dex')))
    print('searched %d bytes' % len(blob))

    names = set()
    for pattern in PATTERNS:
        for match in re.findall(pattern, blob):
            names.add(match.decode('utf-8', 'replace'))

    print('\n== capture / loader / repository classes (%d) ==' % len(names))
    for name in sorted(names)[:60]:
        print('  %s' % name)

    print('\n== method-ish names mentioning load/capture ==')
    hits = set()
    for match in re.findall(rb'[A-Za-z0-9_$]{3,40}', blob):
        if INTERESTING.search(match):
            hits.add(match.decode('utf-8', 'replace'))
    for name in sorted(hits)[:60]:
        print('  %s' % name)
    return 0


if __name__ == '__main__':
    sys.exit(main())
