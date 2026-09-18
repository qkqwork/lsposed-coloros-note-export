"""Reports what LSPosed knows about one package (read-only).

    python build/lsp-state.py build/lsp/modules_config.db com.dsh.noteexport
"""
import sqlite3
import sys
from pathlib import Path

db = Path(sys.argv[1])
package = sys.argv[2] if len(sys.argv) > 2 else 'com.dsh.noteexport'

conn = sqlite3.connect('file:%s?mode=ro' % db.as_posix(), uri=True)
like = '%' + package + '%'
for table, column in (('modules', 'module_pkg_name'),
                      ('modules_state', 'module_pkg_name'),
                      ('scope', 'module_pkg_name')):
    try:
        rows = conn.execute('SELECT * FROM %s WHERE %s LIKE ?' % (table, column),
                            (like,)).fetchall()
    except sqlite3.Error as exc:
        print('%-14s error: %s' % (table, exc))
        continue
    print('%-14s %d row(s) for %s' % (table, len(rows), package))
    for row in rows:
        print('    ', row)
print('total modules registered: %d'
      % conn.execute('SELECT COUNT(*) FROM modules').fetchone()[0])
