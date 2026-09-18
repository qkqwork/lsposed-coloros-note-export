"""Reads a pulled copy of the ColorOS Notes database and reports what is in it.

    adb shell su -c 'cp /data/data/com.coloros.note/databases/nearme_note.db* /sdcard/'
    adb pull /sdcard/nearme_note.db* .
    python tools/inspect-notes-db.py nearme_note.db report.txt

This is the schema check the module's own exporter depends on: the columns it
reads, the codes it filters on, and — most importantly — what a note body really
looks like in `raw_text`, which the converter was written against from an
inference and never from a sample.
"""
import sqlite3
import sys
from pathlib import Path

EXPECTED = {
    'rich_notes': ['local_id', 'folder_id', 'title', 'text', 'raw_text', 'state',
                   'encrypted', 'deleted', 'create_time', 'update_time'],
    'folders': ['guid', 'name'],
}

SAMPLES = 2
SAMPLE_CHARS = 1200


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    db_path = Path(sys.argv[1])
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 else None

    # The -wal file travels with the database, so opening it normally replays it.
    conn = sqlite3.connect('file:%s?mode=ro' % db_path.as_posix(), uri=True)
    conn.row_factory = sqlite3.Row
    lines = []

    def say(text=''):
        lines.append(str(text))

    say('database: %s (%d bytes)' % (db_path, db_path.stat().st_size))
    tables = [row[0] for row in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    say('tables: %d' % len(tables))
    say()

    for table in tables:
        columns = [row[1] for row in conn.execute('PRAGMA table_info(%s)' % table)]
        try:
            count = conn.execute('SELECT COUNT(*) FROM %s' % table).fetchone()[0]
        except sqlite3.Error as exc:
            count = 'error: %s' % exc
        say('%s  rows=%s' % (table, count))
        say('    columns: %s' % ', '.join(columns))
        if table in EXPECTED:
            missing = [c for c in EXPECTED[table] if c not in columns]
            say('    module expects: %s' % ('all present' if not missing
                                            else 'MISSING ' + ', '.join(missing)))
        say()

    if 'rich_notes' in tables:
        columns = [row[1] for row in conn.execute('PRAGMA table_info(rich_notes)')]
        say('--- distributions ---')
        for column in ('state', 'encrypted', 'deleted'):
            if column in columns:
                values = conn.execute(
                    'SELECT %s AS v, COUNT(*) AS n FROM rich_notes GROUP BY %s'
                    ' ORDER BY n DESC' % (column, column)).fetchall()
                say('  %s: %s' % (column, ', '.join('%s=%s' % (r['v'], r['n'])
                                                    for r in values)))
        if 'raw_text' in columns:
            empty = conn.execute("SELECT COUNT(*) FROM rich_notes"
                                 " WHERE raw_text IS NULL OR raw_text=''").fetchone()[0]
            say('  raw_text empty: %d' % empty)

        say()
        say('--- folders ---')
        if 'folders' in tables:
            for row in conn.execute('SELECT * FROM folders LIMIT 20'):
                keys = row.keys()
                name = row['name'] if 'name' in keys else '?'
                guid = row['guid'] if 'guid' in keys else '?'
                say('  %s  %s' % (guid, name))

        say()
        say('--- note body samples ---')
        order = 'update_time' if 'update_time' in columns else 'rowid'
        for row in conn.execute('SELECT * FROM rich_notes ORDER BY %s DESC LIMIT %d'
                                % (order, SAMPLES)):
            say('=' * 70)
            for key in row.keys():
                value = row[key]
                if key in ('raw_text', 'text'):
                    continue
                say('%s = %r' % (key, value))
            raw = row['raw_text'] if 'raw_text' in row.keys() else None
            text = row['text'] if 'text' in row.keys() else None
            say('raw_text (%s chars):' % (len(raw) if raw else 0))
            say(indent((raw or '(empty)')[:SAMPLE_CHARS]))
            say('text (%s chars):' % (len(text) if text else 0))
            say(indent((text or '(empty)')[:400]))
            say()

    conn.close()
    report = '\n'.join(lines)
    if out_path:
        out_path.write_text(report, encoding='utf-8')
        print('wrote %s (%d bytes)' % (out_path, out_path.stat().st_size))
    else:
        print(report)
    return 0


def indent(value):
    return '    ' + value.replace('\n', '\n    ')


if __name__ == '__main__':
    sys.exit(main())
