"""Finds every call of a method in a dexdump listing, with the arguments it gets.

    python build/find-calls.py build/dex6.txt doPictureShare

Unlike build/extract-capture.py, which follows one class, this walks the whole
listing and reports which class makes the call and what the registers hold just
before it - the values a caller passes are the ones a driven call has to match.
"""
import io
import re
import sys
from collections import deque

dump = sys.argv[1]
needle = sys.argv[2]
context = 12

SIGNATURE = re.compile(r'\|\[[0-9a-f]+\]\s+(?P<sig>\S+?):(?P<type>\([^)]*\)\S+)')
CONTINUATION = re.compile(r'^\S')
ADDRESS = re.compile(r'^[0-9a-f]{6,}:\s')


def detect(path):
    with open(path, 'rb') as handle:
        head = handle.read(4)
    if head[:2] == b'\xff\xfe':
        return 'utf-16-le'
    if head[:2] == b'\xfe\xff':
        return 'utf-16-be'
    if head[:3] == b'\xef\xbb\xbf':
        return 'utf-8-sig'
    return 'utf-8'


def logical_lines(path):
    pending = []
    with io.open(path, 'r', encoding=detect(path), errors='replace') as handle:
        for raw in handle:
            line = raw.rstrip('\r\n')
            starts_record = ADDRESS.match(line) or line[:1].isspace()
            if not line.strip():
                if pending:
                    yield ''.join(pending)
                    pending = []
                yield ''
            elif starts_record or not pending:
                if pending:
                    yield ''.join(pending)
                pending = [line]
            else:
                pending.append(line)
    if pending:
        yield ''.join(pending)


window = deque(maxlen=context)
current = '?'
found = 0
for line in logical_lines(dump):
    signature = SIGNATURE.search(line)
    if signature:
        current = signature.group('sig') + ':' + signature.group('type')
    if 'invoke' in line and needle + ':' in line:
        found += 1
        print('\n---- in %s' % current)
        for entry in window:
            stripped = entry.strip()
            if stripped and not stripped.startswith(('code ', 'registers', 'ins ', 'outs ',
                                                     'insns ', 'access ', 'name ', 'type ',
                                                     'catches ', '#')):
                print('   ' + stripped[:170])
        print('   >>> ' + line.strip()[:200])
    window.append(line)

print('\n%d call(s) of %s' % (found, needle))
