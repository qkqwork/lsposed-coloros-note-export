"""Renames the module's package from com.dsh.noteexport to com.qkqwork.noteexport.

    python build/rename-package.py com.dsh.noteexport com.qkqwork.noteexport

Source files move with their package, and every reference is rewritten: the
manifest's package and provider authority, the Xposed entry point in
assets/xposed_init, the constants the two processes share, the build script and
the test harness. Only files that actually mention the old name are touched.
"""
import shutil
import sys
from pathlib import Path

old = sys.argv[1] if len(sys.argv) > 1 else 'com.dsh.noteexport'
new = sys.argv[2] if len(sys.argv) > 2 else 'com.qkqwork.noteexport'

root = Path('.').resolve()
oldPath = Path(old.replace('.', '/'))
newPath = Path(new.replace('.', '/'))
oldDotted = old
newDotted = new

# 1. Move the package directory.
source = root / 'src' / oldPath
target = root / 'src' / newPath
if not source.is_dir():
    print('no source package at %s' % source)
    sys.exit(1)
if target.exists():
    print('%s already exists' % target)
    sys.exit(1)
target.parent.mkdir(parents=True, exist_ok=True)
shutil.move(str(source), str(target))
# Leave no empty directories behind.
parent = source.parent
while parent != root / 'src' and not any(parent.iterdir()):
    parent.rmdir()
    parent = parent.parent
print('moved %s -> %s' % (source.relative_to(root), target.relative_to(root)))

# 2. Rewrite every mention, in the files that hold one.
candidates = []
for pattern in ('src/**/*.java', 'stubs/**/*.java', 'test/**/*',
                'tools/**/*.py', '*.xml', '*.ps1', '*.md', 'assets/*'):
    candidates.extend(root.glob(pattern))

changed = []
for path in sorted(set(candidates)):
    if not path.is_file():
        continue
    if path.name == Path(__file__).name:
        continue
    try:
        text = path.read_text(encoding='utf-8')
    except (UnicodeDecodeError, OSError):
        continue
    if oldDotted not in text and old.replace('.', '/') not in text:
        continue
    updated = text.replace(oldDotted, newDotted).replace(old.replace('.', '/'),
                                                         new.replace('.', '/'))
    path.write_text(updated, encoding='utf-8', newline='')
    changed.append(path.relative_to(root))

print('%d file(s) rewritten:' % len(changed))
for path in changed:
    print('   %s' % path)
