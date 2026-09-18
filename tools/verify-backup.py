"""Verifies a backup made by tools/backup-notes.sh.

    python tools/verify-backup.py backups/NoteBackup_20260911_223507

A backup that cannot be read is not a backup, so this checks the archive the
way a restore would: the checksums, the archive layout, and — by extracting the
database and opening it — that the notes are actually inside.
"""
import hashlib
import shutil
import sqlite3
import sys
import tarfile
import tempfile
from pathlib import Path

EXPECTED_ROOTS = {'databases', 'files', 'shared_prefs', 'no_backup', 'capture'}


def md5(path, chunk=1 << 20):
    digest = hashlib.md5()
    with path.open('rb') as fh:
        while True:
            block = fh.read(chunk)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    root = Path(sys.argv[1])
    problems = []

    print('backup: %s' % root)
    sums_file = root / 'md5sums.txt'
    if not sums_file.is_file():
        problems.append('md5sums.txt is missing')
        recorded = {}
    else:
        recorded = {}
        for line in sums_file.read_text(encoding='utf-8').splitlines():
            if not line.strip():
                continue
            value, name = line.split(None, 1)
            recorded[name.strip()] = value

    for name, expected in sorted(recorded.items()):
        target = root / name
        if not target.is_file():
            problems.append('%s is missing' % name)
            continue
        actual = md5(target)
        ok = actual == expected
        print('  [%s] %s  %d bytes' % ('ok' if ok else 'FAIL', name, target.stat().st_size))
        if not ok:
            problems.append('%s: md5 %s != recorded %s' % (name, actual, expected))

    archive = root / 'app-data.tar'
    if not archive.is_file():
        print('no app-data.tar, nothing more to check')
        return 1
    if not tarfile.is_tarfile(archive):
        problems.append('app-data.tar is not a readable tar')
        print('\n'.join('  - %s' % p for p in problems))
        return 1

    entries = []
    with tarfile.open(archive) as tar:
        for member in tar:
            entries.append(member)
    names = [m.name.lstrip('./') for m in entries]
    roots = {n.split('/')[0] for n in names if n and not n.startswith('.')}
    files = [m for m in entries if m.isfile()]
    print('\narchive: %d entries (%d files), roots: %s' % (len(entries), len(files),
                                                           ', '.join(sorted(roots))))
    missing = EXPECTED_ROOTS - roots
    if missing:
        problems.append('archive is missing: %s' % ', '.join(sorted(missing)))

    attachments = [m for m in files if m.name.lstrip('./').startswith('files/')]
    note_dirs = {m.name.lstrip('./').split('/')[1] for m in attachments}
    print('attachment files: %d in %d note directories' % (len(attachments), len(note_dirs)))

    # Read only the database and open it: the real proof that the notes are in.
    # The bytes are copied out of the archive rather than extracted, so no
    # ownership or permission metadata from the phone is applied to Windows.
    db_members = [m for m in entries
                  if m.name.lstrip('./').startswith('databases/nearme_note.db')]
    if not db_members:
        problems.append('nearme_note.db is not in the archive')
    else:
        scratch = Path.cwd() / 'build' / 'verify-scratch'
        shutil.rmtree(scratch, ignore_errors=True)
        scratch.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as tar:
            for member in db_members:
                source = tar.extractfile(member)
                if source is None:
                    continue
                (scratch / Path(member.name).name).write_bytes(source.read())
        db = scratch / 'nearme_note.db'
        if not db.is_file():
            problems.append('nearme_note.db could not be read out of the archive')
        else:
            try:
                # Read-only: the -wal file sits beside it and is replayed.
                conn = sqlite3.connect('file:%s?mode=ro' % db.as_posix(), uri=True)
                notes = conn.execute('SELECT COUNT(*) FROM rich_notes').fetchone()[0]
                folders = conn.execute('SELECT COUNT(*) FROM folders').fetchone()[0]
                attachment_rows = conn.execute('SELECT COUNT(*) FROM attachments').fetchone()[0]
                with_text = conn.execute(
                    "SELECT COUNT(*) FROM rich_notes WHERE raw_text IS NOT NULL"
                    " AND raw_text != ''").fetchone()[0]
                with_images = conn.execute(
                    "SELECT COUNT(*) FROM rich_notes WHERE raw_text LIKE '%<img%'").fetchone()[0]
                conn.close()
                print('database: %d notes, %d with text, %d with pictures,'
                      ' %d folders, %d attachment rows'
                      % (notes, with_text, with_images, folders, attachment_rows))
                if notes == 0:
                    problems.append('the archived database has no notes in it')
            except Exception as exc:
                problems.append('could not read the archived database: %s' % exc)

    for extra in ('restore.sh', 'manifest.txt', 'RESTORE.md'):
        if not (root / extra).is_file():
            problems.append('%s is missing' % extra)

    print()
    if problems:
        print('PROBLEMS:')
        for problem in problems:
            print('  - %s' % problem)
        return 1
    print('backup verified: checksums match, archive is complete, database reads back')
    return 0


if __name__ == '__main__':
    sys.exit(main())
