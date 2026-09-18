"""Finds the watermark views the Notes app uses when it shares a note as a picture.

    python build/find-watermark-targets.py build/noteapk

The module hides those views so the shared image has no ColorOS watermark. The
names come from the reference module, which was verified against this very
Notes version — but "verified elsewhere" is not "present here", so every name is
looked up in the installed app's own dex strings before any code relies on it.
"""
import re
import sys
from pathlib import Path

TARGETS = {
    'class': [r'Lcom/nearme/note/activity/edit/SaveImageAndShare;',
              r'SaveImageAndShare'],
    'fields': [r'\bmLogoLinearLayout\b', r'\bmLine\b', r'\bmWaterMark\b',
               r'\bmShareLogo\b', r'\bmShareLogoOriginal\b'],
    'methods': [r'\bsetLogo\b', r'\bcreateImageFile\b'],
    'layout ids': [r'\bcolor_os_logo\b', r'\bwater_mark\b', r'\blogo_ll\b',
                   r'\bwater_mark_ll\b', r'\bshare_logo\b'],
}


def dex_strings(data):
    """Every printable run of characters in the dex, which is where names live."""
    return set(re.findall(rb'[\x20-\x7e]{4,120}', data))


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else 'build/noteapk')
    files = sorted(root.glob('*.dex'))
    if not files:
        print('no dex files in %s' % root)
        return 2
    print('searching %d dex file(s), %d bytes' % (
        len(files), sum(f.stat().st_size for f in files)))

    blob = b''.join(f.read_bytes() for f in files)
    strings = dex_strings(blob)

    missing = []
    for group, patterns in TARGETS.items():
        print('\n== %s' % group)
        for pattern in patterns:
            hits = [s for s in strings if re.search(pattern.encode(), s)]
            name = pattern.replace(r'\b', '')
            print('  %-40s %s' % (name, 'FOUND (%d)' % len(hits) if hits else 'MISSING'))
            if hits and group in ('fields', 'methods', 'layout ids'):
                for hit in sorted(hits)[:6]:
                    print('        %s' % hit.decode('utf-8', 'replace'))
            if not hits:
                missing.append(name)

    print()
    if missing:
        print('NOT PRESENT in this build: %s' % ', '.join(missing))
        print('(anything listed here needs a fallback or must be dropped)')
    else:
        print('every reference-module target is present in this build')
    return 0 if not missing else 1


if __name__ == '__main__':
    sys.exit(main())
