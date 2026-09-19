package io.github.qkqwork.noteexport;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs the whole notebook into one zip, so it can be put back by hand.
 *
 * <p>This runs inside the Notes app, which is the only process that may read the
 * app's own data directory — so a complete backup needs no root, no storage
 * permission and no adb: the database and the attachments are read where they
 * live and streamed straight into a zip in the shared Downloads folder.
 *
 * <p>Restoring is deliberately not offered. Putting the files back means
 * replacing another app's private data, which needs root and a stopped app, and
 * a button that claims to do it safely would be a lie. What the zip does carry
 * is a readme naming every path and command, and the screen says the same thing
 * in short.
 *
 * <p>The zip mirrors the shape of the data directory — {@code databases/},
 * {@code files/}, {@code shared_prefs/}, {@code no_backup/} — so restoring is
 * "copy these four directories back", not "sort this out".
 */
final class NoteBackup {

    private static final String TAG = Main.TAG;

    /** The directories of the data dir that hold something worth keeping. */
    private static final String[] ROOTS = {"databases", "files", "shared_prefs", "no_backup"};

    /** The readme written into the zip, and named in the result. */
    private static final String README = "备份说明.txt";

    /** How many files may be read before the notification is updated again. */
    private static final int NOTIFY_EVERY = 25;

    private NoteBackup() {
    }

    /** One file on its way into the zip. */
    private static final class Entry {
        final File source;
        final String path;

        Entry(File source, String path) {
            this.source = source;
            this.path = path;
        }
    }

    static NoteExporter.Result run(Context context) {
        File dataDir = context.getDataDir();
        if (dataDir == null || !dataDir.isDirectory()) {
            return new NoteExporter.Result(false, "备份失败：读不到便签应用的数据目录", "");
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "便签备份_" + stamp + ".zip";

        List<Entry> entries = new ArrayList<>();
        long[] bytes = new long[1];
        Set<String> skip = new LinkedHashSet<>();
        String databaseNote = snapshotDatabase(context, entries, bytes, skip);
        for (String root : ROOTS) {
            File dir = new File(dataDir, root);
            if (!dir.exists()) {
                continue;
            }
            collect(dir, root, entries, bytes, skip);
        }
        if (entries.isEmpty()) {
            return new NoteExporter.Result(false,
                    "备份失败：便签应用的 data 目录里没有可备份的内容", "");
        }

        int notes = NoteStore.noteCount(context);
        Progress.start(entries.size());
        ProgressNotifier.start(context, entries.size());

        ExportSink sink = null;
        OutputStream raw = null;
        ZipOutputStream zip = null;
        try {
            sink = ExportSink.open(context, ExportRequest.DIR, name, "application/zip");
            raw = sink.stream();
            zip = new ZipOutputStream(new java.io.BufferedOutputStream(raw, 64 * 1024));
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);

            writeReadme(context, zip, notes, entries.size(), bytes[0], databaseNote, stamp);

            int done = 0;
            for (Entry entry : entries) {
                if (Progress.cancelled()) {
                    Log.i(TAG, "backup: stopped at " + done + " of " + entries.size());
                    zip.close();
                    zip = null;
                    sink.abort();
                    ProgressNotifier.finish(context, "备份已取消，没有留下半成品", false);
                    return new NoteExporter.Result(false,
                            "备份已取消（已读 " + done + " 个文件），zip 已丢弃", "");
                }
                copy(entry, zip);
                done++;
                Progress.step(done, entry.path);
                if (done % NOTIFY_EVERY == 0 || done == entries.size()) {
                    ProgressNotifier.progress(context, done, entries.size(), entry.path);
                }
            }
            zip.close();
            zip = null;
            sink.finish();
            raw = null;

            String path = ExportRequest.DIR + "/" + name;
            Log.i(TAG, "backup: wrote " + path + " (" + entries.size() + " files)");
            String message = "已备份 " + notes + " 条便签：" + databaseNote + "，"
                    + entries.size() + " 个文件，共 " + megabytes(bytes[0]) + "\n"
                    + "位置：" + path + "\n"
                    + "恢复（模块不提供，需要 root）：把包里的 databases/、files/、"
                    + "shared_prefs/、no_backup/ 覆盖回 "
                    + "/data/data/com.coloros.note/ —— 完整命令见包内「" + README + "」";
            ProgressNotifier.finish(context, "便签备份完成\n" + path, true);
            return new NoteExporter.Result(true, message, path, null);
        } catch (Throwable t) {
            Log.e(TAG, "backup failed", t);
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                    // the zip is being thrown away anyway
                }
            }
            if (sink != null) {
                sink.abort();
            }
            ProgressNotifier.finish(context, "便签备份未完成：" + t, false);
            return new NoteExporter.Result(false, "备份失败：" + t, "");
        } finally {
            Progress.finish();
        }
    }

    /**
     * Gets a consistent copy of the database and adds it to the list.
     *
     * <p>The database is live — the app is using it — so copying the file while
     * it is being written can produce something that will not open. SQLite's own
     * {@code VACUUM INTO} writes a clean, complete copy from a read transaction
     * instead, which is exactly what a backup wants. Where that is not available
     * (an older SQLite), the database and its write-ahead log are copied as they
     * are, together, which is a complete set.
     *
     * @return the phrase the summary uses about the database
     */
    private static String snapshotDatabase(Context context, List<Entry> entries, long[] bytes,
            Set<String> skip) {
        File dbFile = context.getDatabasePath("nearme_note.db");
        if (!dbFile.isFile()) {
            return "没有数据库文件";
        }
        File dir = new File(context.getCacheDir(), "backup");
        dir.mkdirs();
        File snapshot = new File(dir, "nearme_note.db");
        boolean clean = false;
        SQLiteDatabase db = null;
        try {
            if (snapshot.exists() && !snapshot.delete()) {
                Log.w(TAG, "backup: could not clear the old snapshot");
            }
            db = SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            db.execSQL("VACUUM INTO '" + snapshot.getAbsolutePath().replace("'", "''") + "'");
            clean = snapshot.isFile();
        } catch (Throwable t) {
            Log.w(TAG, "backup: VACUUM INTO unavailable (" + t + "), copying the files instead");
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                    // nothing useful to do
                }
            }
        }

        if (clean) {
            entries.add(new Entry(snapshot, "databases/nearme_note.db"));
            bytes[0] += snapshot.length();
            // The live database and its write-ahead log are left out: the
            // snapshot is the same data, consistent, in one file. Everything
            // else in databases/ is still collected below.
            skip.add("databases/nearme_note.db");
            skip.add("databases/nearme_note.db-wal");
            skip.add("databases/nearme_note.db-shm");
            Log.i(TAG, "backup: database snapshotted with VACUUM INTO");
            return "数据库为一致性快照（" + megabytes(snapshot.length()) + "）";
        }

        collect(dbFile, "databases/" + dbFile.getName(), entries, bytes, skip);
        for (String suffix : new String[] {"-wal", "-shm"}) {
            File extra = new File(dbFile.getPath() + suffix);
            if (extra.isFile()) {
                collect(extra, "databases/" + extra.getName(), entries, bytes, skip);
            }
        }
        return "数据库原样复制（含 -wal/-shm）";
    }

    /** Adds every file under {@code file}, at {@code prefix} inside the zip. */
    private static void collect(File file, String prefix, List<Entry> entries, long[] bytes,
            Set<String> skip) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            java.util.Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
            for (File child : children) {
                collect(child, prefix + "/" + child.getName(), entries, bytes, skip);
            }
            return;
        }
        if (!file.isFile() || skip.contains(prefix) || alreadyThere(entries, prefix)) {
            return;
        }
        entries.add(new Entry(file, prefix));
        bytes[0] += file.length();
    }

    private static boolean alreadyThere(List<Entry> entries, String path) {
        for (Entry entry : entries) {
            if (entry.path.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static void copy(Entry entry, ZipOutputStream zip) throws Exception {
        ZipEntry target = new ZipEntry(entry.path);
        // Timestamps are kept: a backup whose files all look brand new is harder
        // to reason about than one that shows when each picture was last written.
        target.setTime(entry.source.lastModified());
        zip.putNextEntry(target);
        InputStream in = new FileInputStream(entry.source);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        zip.closeEntry();
    }

    /** The readme: every path and command a manual restore needs. */
    private static void writeReadme(Context context, ZipOutputStream zip, int notes, int files,
            long bytes, String databaseNote, String stamp) throws Exception {
        String version = "?";
        String noteVersion = "?";
        try {
            // Asked for by name, not by getPackageName(): this code runs inside
            // the Notes app, so the process's own package is that app, not this
            // module, and the readme would credit the wrong version to both.
            version = context.getPackageManager()
                    .getPackageInfo(ConfigContract.MODULE_PKG, 0).versionName;
        } catch (Throwable ignored) {
            // the version is a nicety here, not the point
        }
        try {
            noteVersion = context.getPackageManager()
                    .getPackageInfo(ConfigContract.NOTES_PKG, 0).versionName;
        } catch (Throwable ignored) {
            // nor is the other app's
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ColorOS 便签备份\r\n");
        sb.append("由 ColorOS Note Export（LSPosed 模块）生成\r\n");
        sb.append("模块版本：").append(version)
                .append("　便签应用版本：").append(noteVersion).append("\r\n");
        sb.append("备份时间：").append(stamp).append("\r\n");
        sb.append("内容：").append(notes).append(" 条便签；").append(databaseNote)
                .append("；").append(files).append(" 个文件，共 ")
                .append(megabytes(bytes)).append("\r\n");
        sb.append("\r\n");
        sb.append("包内目录结构与便签应用的数据目录一一对应：\r\n");
        sb.append("  databases/      →  /data/data/com.coloros.note/databases/\r\n");
        sb.append("  files/          →  /data/data/com.coloros.note/files/（每条便签一个目录，装它的附件）\r\n");
        sb.append("  shared_prefs/   →  /data/data/com.coloros.note/shared_prefs/\r\n");
        sb.append("  no_backup/      →  /data/data/com.coloros.note/no_backup/\r\n");
        sb.append("\r\n");
        sb.append("怎么恢复（模块不提供恢复功能，需要 root 或 adb，请自行操作）：\r\n");
        sb.append("  1) 解压这个 zip，得到上面四个目录。\r\n");
        sb.append("  2) 先停掉便签应用：\r\n");
        sb.append("       adb shell am force-stop com.coloros.note\r\n");
        sb.append("  3) 把现在的数据目录改名留一份（不要直接覆盖）：\r\n");
        sb.append("       adb shell su -c 'mv /data/data/com.coloros.note"
                + " /data/data/com.coloros.note.before_restore'\r\n");
        sb.append("       adb shell su -c 'mkdir -p /data/data/com.coloros.note'\r\n");
        sb.append("  4) 把解压出来的四个目录推回手机并拷进便签应用的 data 目录：\r\n");
        sb.append("       adb push databases files shared_prefs no_backup /sdcard/\r\n");
        sb.append("       adb shell su -c 'cp -r /sdcard/databases /sdcard/files"
                + " /sdcard/shared_prefs /sdcard/no_backup /data/data/com.coloros.note/'\r\n");
        sb.append("       adb shell su -c 'chown -R $(stat -c %u:%g"
                + " /data/data/com.coloros.note) /data/data/com.coloros.note/*'\r\n");
        sb.append("       adb shell su -c 'restorecon -R /data/data/com.coloros.note'\r\n");
        sb.append("  5) 打开便签应用核对条数。确认无误后再删掉旧目录：\r\n");
        sb.append("       adb shell su -c 'rm -rf /data/data/com.coloros.note.before_restore'\r\n");
        sb.append("\r\n");
        sb.append("注意：\r\n");
        sb.append("  · /data/data 只有 root 能写，所以恢复必须 root 或 adb。\r\n");
        sb.append("  · 便签应用版本不要低于备份时的版本（见上），跨大版本恢复可能失败。\r\n");
        sb.append("  · 如果便签开着 OPPO 云同步，恢复后可能把云端数据合并回来：\r\n");
        sb.append("    先断网打开确认条数，确认无误再联网。\r\n");

        zip.putNextEntry(new ZipEntry(README));
        Writer writer = new OutputStreamWriter(zip, "UTF-8");
        writer.write(sb.toString());
        writer.flush();
        zip.closeEntry();
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
