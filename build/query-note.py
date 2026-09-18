"""Ad-hoc look at one note, for diagnosing conversion problems.

    python build/query-note.py <db> <local_id>
"""
import sqlite3
import sys
from pathlib import Path

db = Path(sys.argv[1])
note_id = sys.argv[2]
conn = sqlite3.connect('file:%s?mode=ro' % db.as_posix(), uri=True)
conn.row_factory = sqlite3.Row
row = conn.execute(
    'SELECT local_id, title, text, raw_text, state, deleted, recycle_time'
    ' FROM rich_notes WHERE local_id = ?', (note_id,)).fetchone()
if row is None:
    print('no such note')
else:
    print('title        :', repr(row['title'])[:100])
    print('text length  :', len(row['text'] or ''))
    print('raw length   :', len(row['raw_text'] or ''))
    print('state/deleted/recycle_time:', row['state'], row['deleted'], row['recycle_time'])
    print('raw_text     :', repr(row['raw_text'])[:600])
    print('text         :', repr(row['text'])[:300])
