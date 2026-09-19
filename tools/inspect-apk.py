"""Reports the packaging facts that decide whether an APK can install at all.

Android requires resources.arsc to be stored uncompressed and aligned, and an
Xposed module needs its assets/xposed_init plus the module meta-data; a build
that silently loses any of those only fails later, on the phone.
"""
import sys
import zipfile

apk = sys.argv[1]
with zipfile.ZipFile(apk) as zf:
    print('entries:')
    for info in zf.infolist():
        method = 'stored' if info.compress_type == zipfile.ZIP_STORED else 'deflated'
        print('  %-28s %8d -> %8d  %s' % (info.filename, info.file_size,
                                          info.compress_size, method))
    names = set(zf.namelist())
    checks = [
        ('AndroidManifest.xml', 'AndroidManifest.xml' in names),
        ('resources.arsc', 'resources.arsc' in names),
        ('classes.dex', 'classes.dex' in names),
        ('assets/xposed_init', 'assets/xposed_init' in names),
        ('no bundled Xposed API classes', not any(
            n.startswith('de/robv/') for n in names)),
    ]
    print('\nchecks:')
    for label, ok in checks:
        print('  [%s] %s' % ('ok' if ok else 'FAIL', label))

    arsc = zf.getinfo('resources.arsc')
    print('  [%s] resources.arsc is stored uncompressed'
          % ('ok' if arsc.compress_type == zipfile.ZIP_STORED else 'FAIL'))

    if 'assets/xposed_init' in names:
        print('\nxposed_init:', zf.read('assets/xposed_init').decode('utf-8').strip())

    if 'classes.dex' in names:
        dex = zf.read('classes.dex')
        print('classes.dex magic:', dex[:4].decode('latin-1'),
              'size:', len(dex))
        for needle in [b'com/qkqwork/noteexport/Main',
                       b'com/qkqwork/noteexport/HtmlToWord',
                       b'com/qkqwork/noteexport/DocxWriter',
                       b'com/qkqwork/noteexport/LongImageRenderer']:
            print('  [%s] %s' % ('ok' if needle in dex else 'FAIL',
                                 needle.decode('latin-1')))
