"""Turns a notes backup into something the desktop converter can read.

    python tools/export-notes-from-db.py <backup-dir> <out-dir>

It writes one .html file per note plus the pictures that note refers to, laid
out exactly the way the device presents them to the converter:

    out/<nnn>_<title>.html          the note's raw_text, verbatim
    out/files/<note id>/<id>_thumb.png   that note's pictures

test/run-word-test.ps1 then converts the whole directory to .docx on the JVM —
which is how the Word export gets exercised on real notes without a phone in the
loop.
"""
import json
import re
import shutil
import sqlite3
import sys
import tarfile
import tempfile
from pathlib import Path

# The same candidates NoteHtml tries on the device, in the same order.
CANDIDATES = ['{id}', '{id}.png', '{id}.jpg', '{id}.jpeg', '{id}.webp', '{id}.gif',
              '{id}_thumb', '{id}_thumb.png', '{id}_thumb.jpg', '{id}_thumb.jpeg',
              '{id}_thumb.webp']


def sanitize(value, limit=40):
    cleaned = re.sub(r'[\\/:*?"<>|\x00-\x1f]', ' ', value or '')
    cleaned = re.sub(r'\s+', ' ', cleaned).strip().rstrip('.')
    return (cleaned[:limit].strip() or '无标题')


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    backup = Path(sys.argv[1])
    out = Path(sys.argv[2])
    archive = backup / 'app-data.tar'
    if not archive.is_file():
        print('no app-data.tar in %s' % backup)
        return 2

    # Scratch space is made with a plain mkdir under build/: a tempfile.mkdtemp
    # directory is not writable in this confined shell.
    work = Path.cwd() / 'build' / 'notesdb-scratch'
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True, exist_ok=True)
    written = 0
    copied = 0
    missing = 0
    try:
        with tarfile.open(archive) as tar:
            # Pull the database out of the archive first.
            for member in tar.getmembers():
                name = member.name.lstrip('./')
                if name.startswith('databases/nearme_note.db'):
                    target = work / Path(name).name
                    source = tar.extractfile(member)
                    if source:
                        target.write_bytes(source.read())

            db_path = work / 'nearme_note.db'
            if not db_path.is_file():
                print('nearme_note.db is not in the archive')
                return 1

            conn = sqlite3.connect('file:%s?mode=ro' % db_path.as_posix(), uri=True)
            conn.row_factory = sqlite3.Row
            rows = conn.execute(
                "SELECT local_id, title, text, raw_text, recycle_time FROM rich_notes"
                " WHERE raw_text IS NOT NULL AND raw_text != ''"
                " ORDER BY update_time").fetchall()

            # Everything the note refers to lives under files/<note id>/ inside
            # the same archive; the pictures are copied out one by one.
            members = {m.name.lstrip('./'): m for m in tar.getmembers()}
            if out.exists():
                shutil.rmtree(out)
            (out / 'files').mkdir(parents=True, exist_ok=True)

            for index, row in enumerate(rows, start=1):
                note_id = row['local_id']
                # The title comes from the plain-text column when the note has
                # one: raw_text still carries HTML entities, which have no place
                # in a file name.
                title = row['title'] or first_line(row['text']) or first_line(row['raw_text'])
                name = '%03d_%s' % (index, sanitize(title or note_id))
                (out / (name + '.html')).write_text(row['raw_text'], encoding='utf-8')
                written += 1

                wanted = re.findall(r'<img[^>]*\bsrc=["\']([^"\']+)["\']',
                                    row['raw_text'], re.IGNORECASE)
                # Pictures are filed under the case name, because that is the
                # directory the converter is handed as the note's attachment
                # directory — on the device that role is played by files/<note id>.
                note_dir = out / 'files' / name
                for src in wanted:
                    if ':' in src[:12]:      # http:, data:, content:, file:
                        continue
                    for pattern in CANDIDATES:
                        member_name = 'files/%s/%s' % (note_id, pattern.format(id=src))
                        member = members.get(member_name)
                        if member is None:
                            continue
                        source = tar.extractfile(member)
                        if source is None:
                            continue
                        note_dir.mkdir(parents=True, exist_ok=True)
                        (note_dir / Path(member_name).name).write_bytes(source.read())
                        copied += 1
                        break
                    else:
                        missing += 1
            conn.close()
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print('notes written:     %d' % written)
    print('pictures copied:   %d' % copied)
    print('pictures missing:  %d' % missing)
    print('output:            %s' % out)
    return 0


def first_line(html):
    text = re.sub(r'<[^>]+>', ' ', html or '')
    text = re.sub(r'\s+', ' ', text).strip()
    return text[:30]


if __name__ == '__main__':
    sys.exit(main())
