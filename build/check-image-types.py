"""Checks that each embedded picture's bytes match the type the package claims.

A file named .png that actually holds JPEG bytes still passes every structural
check, and Word then draws an empty frame instead of the picture. The device's
attachment files are named *_thumb.png by the Notes app, so whether that name is
honest has to be verified rather than assumed.

    python build/check-image-types.py build/real-docx
"""
import glob
import sys
import zipfile

SIGNATURES = [
    (b'\x89PNG\r\n\x1a\n', 'png'),
    (b'\xff\xd8\xff', 'jpeg'),
    (b'GIF87a', 'gif'),
    (b'GIF89a', 'gif'),
    (b'BM', 'bmp'),
    (b'RIFF', 'webp'),
]

COMPATIBLE = {'png': {'png'}, 'jpeg': {'jpg', 'jpeg'}, 'gif': {'gif'},
              'bmp': {'bmp'}, 'webp': {'webp'}}


def kind(data):
    for signature, name in SIGNATURES:
        if data.startswith(signature):
            return name
    return 'unknown'


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else 'build/real-docx'
    totals = {}
    mismatches = []
    for path in sorted(glob.glob(directory + '/*.docx')):
        with zipfile.ZipFile(path) as zf:
            for part in zf.namelist():
                if not part.startswith('word/media/'):
                    continue
                data = zf.read(part)
                actual = kind(data)
                extension = part.rsplit('.', 1)[-1].lower()
                totals[actual] = totals.get(actual, 0) + 1
                if extension not in COMPATIBLE.get(actual, set()):
                    mismatches.append('%s: %s holds %s data (%d bytes)'
                                      % (path.rsplit('/', 1)[-1], part, actual, len(data)))
    print('pictures checked: %d' % sum(totals.values()))
    print('actual formats  : %s' % totals)
    if mismatches:
        print('\nMISMATCHES (%d):' % len(mismatches))
        for line in mismatches[:20]:
            print('  - %s' % line)
        return 1
    print('\nevery picture matches the type it is declared as')
    return 0


if __name__ == '__main__':
    sys.exit(main())
