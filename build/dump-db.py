"""Dumps any SQLite database's schema and rows (diagnostics).

    python build/dump-db.py <db> [rowLimit]
"""
import sqlite3
import sys
from pathlib import Path

path = Path(sys.argv[1])
limit = int(sys.argv[2]) if len(sys.argv) > 2 else 12
conn = sqlite3.connect('file:%s?mode=ro' % path.as_posix(), uri=True)
tables = [r[0] for r in conn.execute(
    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
print('database: %s' % path)
print('tables  : %s' % ', '.join(tables))
for table in tables:
    columns = [r[1] for r in conn.execute('PRAGMA table_info(%s)' % table)]
    total = conn.execute('SELECT COUNT(*) FROM %s' % table).fetchone()[0]
    print('\n== %s (%d rows) %s' % (table, total, columns))
    for row in conn.execute('SELECT * FROM %s LIMIT %d' % (table, limit)):
        text = repr(row)
        print('   %s' % (text[:300] + ('…' if len(text) > 300 else '')))
