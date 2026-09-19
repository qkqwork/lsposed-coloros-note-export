package com.qkqwork.noteexport;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the ColorOS Notes database from inside the Notes process.
 *
 * <p>Verified against ColorOS 16 / Notes 16.x:
 * <pre>
 *   nearme_note.db
 *     rich_notes(local_id, folder_id, title, text, raw_text, state,
 *                encrypted, deleted, create_time, update_time, ...)
 *     folders(guid, name, ...)
 * </pre>
 *
 * <p>Every read degrades instead of failing: if a release renames or drops a
 * column the export still runs with what is there and the reason is written to
 * the log, rather than silently producing an empty archive.
 */
public final class NoteStore {

    private static final String TAG = Main.TAG;
    private static final String DB_NAME = "nearme_note.db";
    private static final String TABLE_NOTES = "rich_notes";
    private static final String TABLE_FOLDERS = "folders";

    public static final String DIR_RECYCLED = "回收站";
    public static final String DIR_UNKNOWN = "其他";

    private NoteStore() {
    }

    /** Notes grouped per category, in the order they should be written out. */
    public static final class Snapshot {
        public final List<Note> notes = new ArrayList<>();
        public int totalNotes;
        public int encrypted;
        public int recycled;
        public int deleted;
    }

    public static Snapshot read(Context context, boolean includeRecycled) {
        Snapshot snapshot = new Snapshot();
        File dbFile = context.getDatabasePath(DB_NAME);
        if (!dbFile.isFile()) {
            Log.w(TAG, "database not found: " + dbFile);
            return snapshot;
        }

        SQLiteDatabase db = openReadable(dbFile);
        try {
            Map<String, String> folders = readFolders(db);
            Log.i(TAG, "folders: " + folders.size());
            readNotes(context, db, folders, snapshot, includeRecycled);
        } catch (Throwable t) {
            Log.e(TAG, "could not read notes", t);
        } finally {
            try {
                db.close();
            } catch (Throwable ignored) {
                // closing a read-only handle cannot fail in a way that matters
            }
        }
        Log.i(TAG, "notes: total=" + snapshot.totalNotes
                + " exported=" + snapshot.notes.size()
                + " encrypted=" + snapshot.encrypted
                + " recycled=" + snapshot.recycled
                + " deleted=" + snapshot.deleted);
        return snapshot;
    }

    /**
     * The database is in WAL mode; read-only is the right intent, but if that
     * combination is refused, the Notes process is entitled to open it writable
     * anyway. Only SELECTs are ever issued either way.
     */
    private static SQLiteDatabase openReadable(File dbFile) {
        try {
            return SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) {
            Log.w(TAG, "read-only open refused, falling back to read-write: " + t);
            return SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
        }
    }

    /** guid -> category name, exactly as the Notes app shows it. */
    private static Map<String, String> readFolders(SQLiteDatabase db) {
        Map<String, String> result = new HashMap<>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT guid, name FROM " + TABLE_FOLDERS, null);
            while (cursor.moveToNext()) {
                String guid = cursor.getString(0);
                String name = cursor.getString(1);
                if (!TextUtils.isEmpty(guid) && !TextUtils.isEmpty(name)) {
                    result.put(guid, name);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not read folders: " + t);
        } finally {
            close(cursor);
        }
        return result;
    }

    /**
     * Two passes on purpose: metadata first, bodies one at a time afterwards.
     * A single note with a huge body would otherwise blow the CursorWindow and
     * take the whole export down with it.
     */
    private static void readNotes(Context context, SQLiteDatabase db,
            Map<String, String> folders, Snapshot snapshot, boolean includeRecycled) {
        Cursor cursor = null;
        try {
            cursor = queryNotes(db);
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (TextUtils.isEmpty(id)) {
                    continue;
                }
                snapshot.totalNotes++;

                boolean encrypted = cursor.getInt(4) != 0;
                if (encrypted) {
                    snapshot.encrypted++;
                    continue;
                }
                // deleted != 0 means the row is already gone from the user's view.
                if (cursor.getInt(5) != 0) {
                    snapshot.deleted++;
                    continue;
                }
                // A note is in the recycle bin when it has a recycling time.
                // The state column is not usable for this: on ColorOS 16 it was
                // observed reporting 2 for a note the user was still reading,
                // while recycle_time is set exactly for the recycled one.
                boolean recycled = cursor.getLong(3) > 0;
                if (recycled) {
                    snapshot.recycled++;
                    if (!includeRecycled) {
                        continue;
                    }
                }

                Note note = new Note();
                note.id = id;
                note.title = emptyIfNull(cursor.getString(2));
                note.encrypted = false;
                note.recycled = recycled;
                note.createTime = cursor.getLong(6);
                note.updateTime = cursor.getLong(7);
                if (recycled) {
                    note.folder = DIR_RECYCLED;
                } else {
                    String name = folders.get(cursor.getString(1));
                    note.folder = TextUtils.isEmpty(name) ? DIR_UNKNOWN : name;
                }
                snapshot.notes.add(note);
            }
        } catch (Throwable t) {
            Log.e(TAG, "could not enumerate notes", t);
        } finally {
            close(cursor);
        }

        for (Note note : snapshot.notes) {
            readBody(context, db, note);
        }
    }

    /**
     * Newer Notes releases have added columns rather than removed them, but a
     * missing one must not cost the whole export, so fall back to the handful of
     * columns the app has always had.
     */
    private static Cursor queryNotes(SQLiteDatabase db) {
        try {
            return db.rawQuery("SELECT local_id, folder_id, title, recycle_time,"
                    + " encrypted, deleted, create_time, update_time FROM " + TABLE_NOTES
                    + " ORDER BY update_time DESC", null);
        } catch (Throwable t) {
            Log.w(TAG, "full column set failed (" + t + "), retrying minimal query");
            Log.w(TAG, "schema: " + describeSchema(db));
            // No recycling information in this fallback: every row is treated as
            // a normal note, because dropping a note the user still has is a far
            // worse outcome than exporting one they deleted.
            return db.rawQuery("SELECT local_id, folder_id, NULL, 0, 0,"
                    + " 0, 0, 0 FROM " + TABLE_NOTES, null);
        }
    }

    private static void readBody(Context context, SQLiteDatabase db, Note note) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT text, raw_text FROM " + TABLE_NOTES
                    + " WHERE local_id = ?", new String[] {note.id});
            if (cursor.moveToFirst()) {
                note.text = emptyIfNull(cursor.getString(0));
                // The body references its pictures by attachment id, so the
                // references are turned into real paths here, once, for every
                // consumer downstream.
                note.html = NoteHtml.resolveImages(emptyIfNull(cursor.getString(1)),
                        attachmentDir(context, note));
            }
        } catch (Throwable t) {
            // Better a readable placeholder than a missing note.
            Log.w(TAG, "body of " + note.id + " unreadable: " + t);
            note.text = "（这条便签的正文过大，未能读取）";
            note.html = "";
        } finally {
            close(cursor);
        }
    }

    /**
     * Whether the note has anything in files/&lt;local_id&gt;/.
     *
     * <p>Attachments are not referenced by the database at all: the app keeps
     * them under a directory named after the note id.
     */
    public static File attachmentDir(Context context, Note note) {
        File dir = new File(context.getFilesDir(), note.id);
        return dir.isDirectory() ? dir : null;
    }

    public static List<File> attachmentFiles(Context context, Note note) {
        List<File> result = new ArrayList<>();
        File dir = attachmentDir(context, note);
        if (dir == null) {
            return result;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            if (file.isFile() && file.length() > 0) {
                result.add(file);
            }
        }
        return result;
    }

    /**
     * Dumps the real schema into the log. This is the safety net for a Notes
     * release that renames a column: instead of an empty export the user gets a
     * line in logcat naming the columns that actually exist.
     */
    public static String describeSchema(SQLiteDatabase db) {
        StringBuilder sb = new StringBuilder();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'", null);
            while (cursor.moveToNext()) {
                String table = cursor.getString(0);
                sb.append(table).append('(');
                Cursor columns = null;
                try {
                    columns = db.rawQuery("PRAGMA table_info(" + table + ")", null);
                    boolean first = true;
                    while (columns.moveToNext()) {
                        if (!first) {
                            sb.append(", ");
                        }
                        first = false;
                        sb.append(columns.getString(1));
                    }
                } catch (Throwable ignored) {
                    sb.append("?");
                } finally {
                    close(columns);
                }
                sb.append("); ");
            }
        } catch (Throwable t) {
            sb.append("schema unreadable: ").append(t);
        } finally {
            close(cursor);
        }
        return sb.toString();
    }

    private static void close(Cursor cursor) {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
        }
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
