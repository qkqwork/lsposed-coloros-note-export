"""Lists what the notes hold, in the order the batch export walks them.

    python build/list-note-sizes.py backups/NoteBackup_20260918_203019 12

The long picture of a note is only comparable with its text if the note has any:
a note that is six pictures and no words produces a picture with no words in it,
which is easy to misread as a rendering fault. The database is read straight out
of the backup's tar, so no phone is needed, and the columns are taken from the
cursor rather than assumed, because ColorOS renames them between releases.
"""
import re
import sqlite3
import sys
import tarfile
from pathlib import Path

backup = Path(sys.argv[1])
count = int(sys.argv[2]) if len(sys.argv) > 2 else 12

with tarfile.open(backup / 'app-data.tar') as archive:
    member = next(m for m in archive.getmembers()
                  if m.name.endswith('databases/nearme_note.db'))
    data = archive.extractfile(member).read()

work = Path('build/tmp-nearme_note.db')
work.parent.mkdir(parents=True, exist_ok=True)
work.write_bytes(data)
db = sqlite3.connect(str(work))


def query(sql):
    cursor = db.execute(sql)
    columns = [description[0] for description in cursor.description]
    return columns, cursor.fetchall()


def pick(columns, candidates):
    for candidate in candidates:
        if candidate in columns:
            return columns.index(candidate)
    return None


folder_columns, folder_rows = query('select * from folders')
folder_name = pick(folder_columns, ['name', 'title', 'folder_name'])
folders = {}
if folder_name is not None:
    for row in folder_rows:
        folders[row[0]] = row[folder_name]

columns, rows = query('select * from rich_notes')
id_at = pick(columns, ['global_id', 'guid', 'id', 'local_id'])
title_at = pick(columns, ['title', 'raw_title'])
text_at = pick(columns, ['raw_text', 'html_text', 'text', 'content'])
plain_at = pick(columns, ['text'])
folder_at = pick(columns, ['folder_id', 'folder'])
update_at = pick(columns, ['update_time', 'modify_time', 'timestamp'])
recycle_at = pick(columns, ['recycle_time', 'deleted_time'])
paint_at = pick(columns, ['is_paint_pic'])

rows = sorted(rows, key=lambda row: (row[update_at] or 0) if update_at is not None else 0,
              reverse=True)
alive = [row for row in rows
         if recycle_at is None or not (row[recycle_at] or 0)]

print('%d notes (%d recycled) in %s' % (len(alive), len(rows) - len(alive), backup.name))
for row in alive[:count]:
    text = (row[text_at] or '') if text_at is not None else ''
    plain = re.sub(r'<[^>]+>', '', text)
    plain = re.sub(r'&nbsp;', ' ', plain).strip()
    pictures = len(re.findall(r'<img', text, re.I))
    folder = folders.get(row[folder_at], '?') if folder_at is not None else '?'
    paint = row[paint_at] if paint_at is not None else None
    print('  %-38s folder=%-14s words=%-6d pictures=%-3d paint=%-4s title=%r'
          % (row[id_at], folder, len(plain), pictures, paint, (row[title_at] or '')[:24]))
    print('      text: %r' % plain[:80])
