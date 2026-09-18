"""Rewrites an APK as a clean, standard zip.

    python tools/repack-apk.py in.apk out.apk

The build appends classes.dex and the assets with .NET's ZipArchive, which
produces a perfectly installable APK. This exists to rule that writer out when
a module installs and runs but is never loaded by LSPosed: every entry is copied
with its original compression method into a fresh archive written by Python's
zipfile, which is the most conservative writer available here.
"""
import sys
import zipfile
from pathlib import Path


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    source = Path(sys.argv[1])
    target = Path(sys.argv[2])
    if target.exists():
        target.unlink()

    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, 'w') as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            out.internal_attr = info.internal_attr
            out.create_system = info.create_system
            dst.writestr(out, data)
            method = 'stored' if info.compress_type == zipfile.ZIP_STORED else 'deflated'
            print('  %-34s %9d  %s' % (info.filename, len(data), method))

    print('repacked %s -> %s (%d bytes)' % (source, target, target.stat().st_size))
    return 0


if __name__ == '__main__':
    sys.exit(main())
