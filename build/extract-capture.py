"""Reads a dexdump listing and prints how the app drives its picture capture.

    python build/extract-capture.py build/dex2.txt <class> <methods> [extra classes]

The methods named in the second argument are printed in full, and every invoke
of them inside the same class is printed together with the instructions that
build its arguments. That is how the capture entry point and the values the app
passes to it are recovered.

dexdump wraps long lines at 120 columns. A wrapped continuation starts at
column zero without an address, while a real record starts either with
"hexaddress: " at column zero or with whitespace, so the two can be told apart.
PowerShell's ">" writes UTF-16LE on this machine, so the encoding comes from the
byte order mark. The listing is streamed: it is a few hundred megabytes.
"""
import io
import re
import sys
from pathlib import Path

dump = Path(sys.argv[1])
target = sys.argv[2] if len(sys.argv) > 2 else \
    'com.nearme.note.activity.richedit.webview.WVNoteViewEditFragment'
names = (sys.argv[3] if len(sys.argv) > 3
         else 'doPictureCapture,doPictureShare').split(',')
extras = [c for c in (sys.argv[4] if len(sys.argv) > 4 else '').split(',') if c]

ADDRESS = re.compile(r'^[0-9a-f]{6,}:\s')
SIGNATURE = re.compile(r'\|\[[0-9a-f]+\]\s+(?P<sig>\S+?):(?P<type>\([^)]*\)\S+)')
CLASS_LINE = re.compile(r"^\s*Class descriptor\s+:\s*'(?P<name>[^']+)'")
NAME_LINE = re.compile(r"^\s*name\s+:\s*'(?P<name>[^']+)'")
TYPE_LINE = re.compile(r"^\s*type\s+:\s*'(?P<type>[^']*)'")
ACCESS_LINE = re.compile(r'^\s*access\s+:\s*(?P<access>.*)$')
CALL = re.compile(r'invoke-\S+\s+\{[^}]*\},\s+\S+?;\s*\.(?P<method>[^\s:]+):')


def plain(descriptor):
    return descriptor.lstrip('L').rstrip(';').replace('/', '.')


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


def main():
    declarations = []
    bodies = {}
    calls = []
    in_class = False
    extra_methods = {name: [] for name in extras}
    extra_class = None
    pending_name = None
    pending_access = None
    current_method = None
    current_body = []

    def flush():
        if current_method and current_body:
            bodies.setdefault(current_method, []).extend(current_body)

    for line in logical_lines(dump):
        match = CLASS_LINE.match(line)
        if match:
            flush()
            current_method, current_body = None, []
            plain_name = plain(match.group('name'))
            in_class = plain_name == target
            extra_class = plain_name if plain_name in extra_methods else None
            pending_name = None
            continue

        if in_class or extra_class:
            name = NAME_LINE.match(line)
            if name:
                pending_name = name.group('name')
            else:
                access = ACCESS_LINE.match(line)
                if access and pending_name:
                    pending_access = access.group('access')
                else:
                    kind = TYPE_LINE.match(line)
                    if kind and pending_name:
                        if in_class:
                            declarations.append(
                                (pending_name, kind.group('type'), (pending_access or '').strip()))
                        else:
                            extra_methods[extra_class].append(
                                (pending_name, kind.group('type'), (pending_access or '').strip()))
                        pending_name = None
                        pending_access = None

        signature = SIGNATURE.search(line)
        if signature:
            flush()
            current_method = signature.group('sig') + ':' + signature.group('type')
            current_body = [line]
        elif current_method is not None:
            current_body.append(line)

        if in_class and 'invoke' in line:
            call = CALL.search(line)
            if call and call.group('method') in names:
                calls.append((current_method, list(current_body), line))
    flush()

    print('== %s ==' % target)
    print('\n-- declarations of interest --')
    for method, kind, access in declarations:
        if method in names:
            print('  %-32s %-24s %s' % (method, access, kind))

    print('\n-- bodies --')
    for name in names:
        for method, body in bodies.items():
            if not method.startswith(target + '.' + name + ':'):
                continue
            print('\n### %s' % method)
            for entry in body:
                print('  ' + entry)

    print('\n== %d call site(s) ==' % len(calls))
    for index, (method, body, line) in enumerate(calls, 1):
        print('\n---- call %d in %s ----' % (index, method))
        for entry in body:
            print('  ' + entry)
        print('  >>> ' + line)

    for name in extras:
        print('\n== methods of %s ==' % name)
        for method, kind, access in extra_methods[name]:
            print('  %-32s %-24s %s' % (method, access, kind))


main()
