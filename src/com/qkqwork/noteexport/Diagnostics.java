package com.qkqwork.noteexport;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Writes a report about the environment the module is running in.
 *
 * <p>The export depends on facts that can only be observed on the device: which
 * provider the Notes app actually exports, whether its database still has the
 * columns this module reads, and — most importantly — what a note body really
 * looks like in {@code rich_notes.raw_text}. Every one of those was inferred
 * from a reference module rather than seen; this report replaces the guesswork
 * with the device's own answer, without needing a debugger or a second install.
 *
 * <p>Nothing here writes to the Notes app or changes it in any way, and the
 * report contains note text, so it is treated like an export: the caller is
 * checked first (see {@code Main}) and the file is easy to delete.
 */
final class Diagnostics {

    private static final String TAG = Main.TAG;
    private static final String REPORT_DIR = "便签导出";
    private static final String DB_NAME = "nearme_note.db";
    private static final String TABLE_NOTES = "rich_notes";
    private static final String TABLE_FOLDERS = "folders";

    /** How much of a note body to quote verbatim. */
    private static final int SAMPLE_LIMIT = 600;
    private static final int SAMPLE_NOTES = 3;

    static final class Result {
        final boolean ok;
        final String message;
        final String path;

        Result(boolean ok, String message, String path) {
            this.ok = ok;
            this.message = message;
            this.path = path;
        }
    }

    private Diagnostics() {
    }

    static Result run(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("ColorOS 便签导出模块 · 环境诊断报告\n");
        sb.append("生成时间：").append(timestamp()).append('\n');
        sb.append("================================================\n\n");

        section(sb, "1. 模块自身", moduleInfo());
        section(sb, "2. 系统与 ROM", deviceInfo());
        section(sb, "3. 便签应用", notesAppInfo(context));
        section(sb, "4. 便签应用声明的 ContentProvider", providerInfo(context));
        section(sb, "5. 数据库与表结构", databaseInfo(context));
        section(sb, "6. 便签正文样例（raw_text 的真实形态）", sampleNotes(context));
        section(sb, "7. 附件目录样例", attachmentInfo(context));
        section(sb, "8. 已知风险的自检", selfCheck(context));

        String name = "诊断_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".txt";
        try {
            ExportSink sink = ExportSink.open(context, REPORT_DIR, name, "text/plain");
            try {
                OutputStream out = sink.stream();
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
                sink.finish();
            } catch (Throwable t) {
                sink.abort();
                throw t;
            }
            Log.i(TAG, "diagnostics written to " + sink.path());
            return new Result(true, "诊断报告已写入 " + sink.path(), sink.path());
        } catch (Throwable t) {
            Log.e(TAG, "could not write the diagnostics report", t);
            // Still useful: the report is in the log even when the file fails.
            Log.i(TAG, sb.toString());
            return new Result(false, "诊断失败：" + t, "");
        }
    }

    // ----------------------------------------------------------------- pieces

    private static String moduleInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("模块包名： ").append(ConfigContract.MODULE_PKG).append('\n');
        String hooked = Main.hookedProviderClass;
        sb.append("已 hook 的 Provider 类： ")
                .append(hooked == null ? "（一个都没成功 —— 触发链路不可用）" : hooked)
                .append('\n');
        sb.append("便签进程 PID： ").append(android.os.Process.myPid()).append('\n');
        // Which authorities the settings screen will try, from ConfigContract.
        sb.append("尝试过的 authority 候选：\n");
        for (String authority : ConfigContract.NOTES_AUTHORITIES) {
            sb.append("  - ").append(authority).append('\n');
        }
        return sb.toString();
    }

    private static String deviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("厂商/品牌/型号： ").append(Build.MANUFACTURER).append(" / ")
                .append(Build.BRAND).append(" / ").append(Build.MODEL).append('\n');
        sb.append("设备代号： ").append(Build.DEVICE).append('\n');
        sb.append("Android 版本： ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        sb.append("ROM 版本： ").append(Build.DISPLAY).append('\n');
        sb.append("Build ID / 指纹： ").append(Build.ID).append('\n');
        sb.append("               ").append(Build.FINGERPRINT).append('\n');
        return sb.toString();
    }

    private static String notesAppInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(ConfigContract.NOTES_PKG, 0);
            sb.append("包名： ").append(ConfigContract.NOTES_PKG).append('\n');
            sb.append("版本名： ").append(info.versionName).append('\n');
            long code = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            sb.append("版本号： ").append(code).append('\n');
            sb.append("安装时间： ").append(timestamp(info.firstInstallTime)).append('\n');
            sb.append("更新时间： ").append(timestamp(info.lastUpdateTime)).append('\n');
            ApplicationInfo app = info.applicationInfo;
            if (app != null) {
                sb.append("APK 路径： ").append(app.sourceDir).append('\n');
                sb.append("targetSdk： ").append(app.targetSdkVersion).append('\n');
                sb.append("数据目录： ").append(app.dataDir).append('\n');
            }
        } catch (Throwable t) {
            sb.append("读取失败： ").append(t).append('\n');
        }
        return sb.toString();
    }

    /**
     * The providers the Notes app declares. This is the piece the module guesses
     * at today: the trigger only works when the authority it queries is exported
     * and answers.
     */
    private static String providerInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(ConfigContract.NOTES_PKG,
                    PackageManager.GET_PROVIDERS);
            ProviderInfo[] providers = info.providers;
            if (providers == null || providers.length == 0) {
                sb.append("该应用没有声明任何 provider？\n");
                return sb.toString();
            }
            for (ProviderInfo provider : providers) {
                sb.append("authority： ").append(provider.authority).append('\n');
                sb.append("  类名：   ").append(provider.name).append('\n');
                sb.append("  exported=").append(provider.exported)
                        .append("  readPermission=").append(provider.readPermission)
                        .append("  writePermission=").append(provider.writePermission)
                        .append('\n');
                boolean guessed = false;
                for (String candidate : ConfigContract.NOTES_AUTHORITIES) {
                    if (candidate.equals(provider.authority)) {
                        guessed = true;
                    }
                }
                sb.append("  模块候选列表中有它： ").append(guessed ? "是" : "否").append('\n');
            }
        } catch (Throwable t) {
            sb.append("读取失败： ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String databaseInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        File dbFile = context.getDatabasePath(DB_NAME);
        sb.append("数据库路径： ").append(dbFile.getPath()).append('\n');
        sb.append("是否存在： ").append(dbFile.isFile() ? "是" : "否").append('\n');
        if (dbFile.isFile()) {
            sb.append("大小： ").append(dbFile.length()).append(" 字节\n");
        }
        File dir = dbFile.getParentFile();
        if (dir != null) {
            File[] files = dir.listFiles();
            if (files != null) {
                List<String> names = new ArrayList<>();
                for (File file : files) {
                    if (file.getName().startsWith(DB_NAME)) {
                        names.add(file.getName() + "(" + file.length() + ")");
                    }
                }
                sb.append("同目录相关文件： ").append(names).append('\n');
            }
        }

        SQLiteDatabase db = null;
        try {
            db = openReadable(dbFile);
            sb.append("\n完整表结构：\n").append(NoteStore.describeSchema(db)).append('\n');
            sb.append("\n行数统计：\n");
            sb.append("  ").append(TABLE_NOTES).append("： ")
                    .append(count(db, "SELECT COUNT(*) FROM " + TABLE_NOTES)).append('\n');
            sb.append("  ").append(TABLE_FOLDERS).append("： ")
                    .append(count(db, "SELECT COUNT(*) FROM " + TABLE_FOLDERS)).append('\n');
            sb.append("  state=2（回收站）： ")
                    .append(count(db, "SELECT COUNT(*) FROM " + TABLE_NOTES
                            + " WHERE state=2")).append('\n');
            sb.append("  encrypted!=0： ")
                    .append(count(db, "SELECT COUNT(*) FROM " + TABLE_NOTES
                            + " WHERE encrypted!=0")).append('\n');
            sb.append("  deleted!=0： ")
                    .append(count(db, "SELECT COUNT(*) FROM " + TABLE_NOTES
                            + " WHERE deleted!=0")).append('\n');
            sb.append("  raw_text 为空或 NULL： ")
                    .append(count(db, "SELECT COUNT(*) FROM " + TABLE_NOTES
                            + " WHERE raw_text IS NULL OR raw_text=''")).append('\n');
            sb.append("  text 为空或 NULL： ")
                    .append(count(db, "SELECT COUNT(*) FROM " + TABLE_NOTES
                            + " WHERE text IS NULL OR text=''")).append('\n');
        } catch (Throwable t) {
            sb.append("打开数据库失败： ").append(t).append('\n');
            sb.append("（这通常意味着本模块没能进入便签进程，或数据库名变了）\n");
        } finally {
            close(db);
        }
        return sb.toString();
    }

    /**
     * Quotes the newest notes' bodies verbatim. The converter in this module was
     * written against an inferred idea of what the Notes editor stores; these
     * samples are what decide whether that idea was right.
     */
    private static String sampleNotes(Context context) {
        StringBuilder sb = new StringBuilder();
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = openReadable(context.getDatabasePath(DB_NAME));
            cursor = db.rawQuery("SELECT local_id, title, LENGTH(raw_text), LENGTH(text),"
                    + " raw_text, text FROM " + TABLE_NOTES
                    + " ORDER BY update_time DESC LIMIT " + SAMPLE_NOTES, null);
            int index = 0;
            while (cursor.moveToNext()) {
                index++;
                sb.append("---- 样例 ").append(index).append(" ----\n");
                sb.append("local_id： ").append(cursor.getString(0)).append('\n');
                sb.append("title： ").append(cursor.getString(1)).append('\n');
                sb.append("raw_text 长度： ").append(cursor.getInt(2)).append('\n');
                sb.append("text 长度： ").append(cursor.getInt(3)).append('\n');
                sb.append("raw_text 前 ").append(SAMPLE_LIMIT).append(" 字符：\n");
                sb.append(indent(truncate(cursor.getString(4), SAMPLE_LIMIT))).append('\n');
                sb.append("text 前 200 字符：\n");
                sb.append(indent(truncate(cursor.getString(5), 200))).append('\n');
                sb.append('\n');
            }
            if (index == 0) {
                sb.append("数据库里一条便签都没有。\n");
            }
        } catch (Throwable t) {
            sb.append("读取样例失败： ").append(t).append('\n');
        } finally {
            close(cursor);
            close(db);
        }
        return sb.toString();
    }

    private static String attachmentInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        File filesDir = context.getFilesDir();
        sb.append("files 目录： ").append(filesDir == null ? "?" : filesDir.getPath()).append('\n');
        if (filesDir == null || !filesDir.isDirectory()) {
            sb.append("目录不存在。\n");
            return sb.toString();
        }
        File[] dirs = filesDir.listFiles();
        if (dirs == null || dirs.length == 0) {
            sb.append("目录为空（可能确实没有附件）。\n");
            return sb.toString();
        }
        sb.append("子目录/文件数量： ").append(dirs.length).append('\n');
        int shown = 0;
        for (File dir : dirs) {
            if (shown >= 3) {
                break;
            }
            if (!dir.isDirectory()) {
                sb.append("  [文件] ").append(dir.getName())
                        .append(" (").append(dir.length()).append(")\n");
                continue;
            }
            shown++;
            File[] children = dir.listFiles();
            sb.append("  [目录] ").append(dir.getName())
                    .append(" 内 ").append(children == null ? 0 : children.length)
                    .append(" 个文件：\n");
            if (children != null) {
                int listed = 0;
                for (File child : children) {
                    if (listed++ >= 8) {
                        sb.append("      …\n");
                        break;
                    }
                    sb.append("      ").append(child.getName())
                            .append("  ").append(child.length()).append(" 字节\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Turns the assumptions this module makes into explicit yes/no answers, so a
     * mismatch is visible in the report instead of showing up as an empty export.
     */
    private static String selfCheck(Context context) {
        StringBuilder sb = new StringBuilder();
        SQLiteDatabase db = null;
        try {
            db = openReadable(context.getDatabasePath(DB_NAME));
            sb.append("表 rich_notes 存在： ")
                    .append(hasTable(db, TABLE_NOTES) ? "是" : "否 ← 严重，导出会失败").append('\n');
            sb.append("表 folders 存在： ")
                    .append(hasTable(db, TABLE_FOLDERS) ? "是" : "否").append('\n');
            for (String column : new String[] {"local_id", "folder_id", "title", "text",
                    "raw_text", "state", "encrypted", "deleted", "create_time",
                    "update_time"}) {
                sb.append("列 rich_notes.").append(column).append(" 存在： ")
                        .append(hasColumn(db, TABLE_NOTES, column) ? "是" : "否 ← 需要改代码")
                        .append('\n');
            }
        } catch (Throwable t) {
            sb.append("自检失败： ").append(t).append('\n');
        } finally {
            close(db);
        }

        List<String> missing = new ArrayList<>();
        for (String authority : ConfigContract.NOTES_AUTHORITIES) {
            if (!authorityExists(context, authority)) {
                missing.add(authority);
            }
        }
        sb.append("模块候选 authority 中设备上不存在的： ")
                .append(missing.isEmpty() ? "（无，全部存在）" : missing.toString()).append('\n');
        sb.append("（只要其中任意一个被勾选为 hook 目标即可；报告第 4 节列出了全部真实 provider）\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static boolean authorityExists(Context context, String authority) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(ConfigContract.NOTES_PKG,
                    PackageManager.GET_PROVIDERS);
            if (info.providers == null) {
                return false;
            }
            for (ProviderInfo provider : info.providers) {
                if (authority.equals(provider.authority)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // treated as absent
        }
        return false;
    }

    private static SQLiteDatabase openReadable(File dbFile) {
        try {
            return SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) {
            return SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
        }
    }

    private static boolean hasTable(SQLiteDatabase db, String table) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'"
                    + " AND name=?", new String[] {table});
            return cursor.moveToFirst();
        } catch (Throwable t) {
            return false;
        } finally {
            close(cursor);
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(1))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // reported as missing
        } finally {
            close(cursor);
        }
        return false;
    }

    private static long count(SQLiteDatabase db, String sql) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, null);
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Throwable ignored) {
            return -1;
        } finally {
            close(cursor);
        }
        return -1;
    }

    private static void section(StringBuilder sb, String title, String body) {
        sb.append(title).append('\n').append("------------------------------------------------\n");
        sb.append(body).append('\n');
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "（null）";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n…（共 " + value.length() + " 字符，已截断）";
    }

    private static String indent(String value) {
        return "    " + value.replace("\n", "\n    ");
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String timestamp(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
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

    private static void close(SQLiteDatabase db) {
        if (db != null) {
            try {
                db.close();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
        }
    }
}
