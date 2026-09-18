"""Prints one note's text, for comparing an exported picture with its content.

    python build/show-note-text.py build/tmp-nearme_note.db ff41698f

The output is written as UTF-8 to a file so the console's code page cannot
mangle the Chinese text.
"""
import re
import sqlite3
import sys
from pathlib import Path

db = sqlite3.connect(sys.argv[1])
prefix = sys.argv[2]
out = Path(sys.argv[3] if len(sys.argv) > 3 else 'build/note-text.txt')

cursor = db.execute("select global_id, title, text, raw_text, update_time "
                    "from rich_notes where global_id like ?", (prefix + '%',))
rows = cursor.fetchall()
columns = [description[0] for description in cursor.description]

with out.open('w', encoding='utf-8') as handle:
    handle.write('columns: %s\n' % ', '.join(columns))
    for row in rows:
        handle.write('guid=%s\ntitle=%r\nupdate_time=%s\n' % (row[0], row[1], row[4]))
        for label, value in (('text', row[2]), ('raw_text', row[3])):
            plain = re.sub(r'<[^>]+>', '', value or '')
            plain = re.sub(r'&nbsp;', ' ', plain)
            handle.write('%s: %d character(s)\n%s\n' % (label, len(plain), plain[:2000]))
        handle.write('-' * 60 + '\n')
print('wrote %s (%d row(s))' % (out, len(rows)))
