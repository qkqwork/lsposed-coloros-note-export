"""Prints the columns of the notes database, so the exporter's queries can match.

    python build/show-note-columns.py build/tmp-nearme_note.db
"""
import sqlite3
import sys

db = sqlite3.connect(sys.argv[1] if len(sys.argv) > 1 else 'build/tmp-nearme_note.db')
for table in [row[0] for row in db.execute(
        "select name from sqlite_master where type='table' order by name")]:
    columns = [row[1] for row in db.execute('pragma table_info(%s)' % table)]
    print('%-28s %s' % (table, ', '.join(columns)))
