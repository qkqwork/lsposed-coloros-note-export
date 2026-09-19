package com.qkqwork.noteexport;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Where an export lands.
 *
 * <p>On Android 10 and later MediaStore lets an app drop files into the shared
 * Download folder with no storage permission at all, which matters because the
 * Notes app does not hold one. A relative path can contain subdirectories, so
 * the category folders come for free.
 *
 * <p>The pending flag is what makes a cancelled export invisible instead of
 * leaving a truncated file behind.
 */
public final class ExportSink {

    private static final String TAG = Main.TAG;

    private final Context context;
    private final ContentResolver resolver;
    private OutputStream stream;
    private Uri uri;
    private File file;
    private String displayPath;

    private ExportSink(Context context) {
        this.context = context;
        this.resolver = context.getContentResolver();
    }

    public String path() {
        return displayPath;
    }

    /** Joins path segments with '/', tolerating an empty leading segment. */
    public static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.length() == 0) {
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().replaceAll("\\s+", " ").trim();
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    /** A file name that is valid on every OS the user might copy it to. */
    public static String fileName(String value, String fallback) {
        String cleaned = sanitize(value);
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40).trim();
        }
        return cleaned.length() > 0 ? cleaned : fallback;
    }

    /**
     * Opens a destination file.
     *
     * @param relativeDir directory below the shared Downloads folder
     * @param name        file name including extension
     * @param mimeType    MIME type recorded in MediaStore
     */
    public static ExportSink open(Context context, String relativeDir, String name,
            String mimeType) throws Exception {
        ExportSink sink = new ExportSink(context);
        String relativePath = relativeDirectory(relativeDir);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri target = sink.resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target != null) {
                OutputStream out = sink.resolver.openOutputStream(target);
                if (out != null) {
                    sink.uri = target;
                    sink.stream = out;
                    sink.displayPath = join(relativePath, name);
                    return sink;
                }
                sink.resolver.delete(target, null, null);
            }
            Log.w(TAG, "MediaStore insert failed, falling back to direct file access");
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), relativeDir == null ? "" : relativeDir);
        dir.mkdirs();
        File target = new File(dir, name);
        sink.file = target;
        sink.stream = new FileOutputStream(target);
        sink.displayPath = target.getPath();
        return sink;
    }

    /**
     * Opens a destination that is meant to be rewritten rather than added to.
     *
     * <p>MediaStore renames a new row when the name it is handed is taken, which
     * is how the settings mirror ended up beside the pictures as
     * {@code note_watermark (1).txt}, {@code (2)} and so on. The row that is
     * already there is rewritten instead — and when the copies cannot be
     * matched to a row at all (an older build wrote the mirror as a plain file,
     * so no row exists for it), the copies are cleared away and one file with
     * the intended name is written, so the folder converges on a single mirror
     * instead of growing one per save.
     */
    public static ExportSink replace(Context context, String relativeDir, String name,
            String mimeType) throws Exception {
        Uri exact = find(context, relativeDir, name);
        if (exact != null) {
            ExportSink sink = rewrite(context, relativeDir, name, exact);
            if (sink != null) {
                return sink;
            }
        }
        // Anything that looks like the same file under a deduplicated name.
        List<Uri> copies = findCopies(context, relativeDir, name);
        for (Uri copy : copies) {
            ExportSink sink = rewrite(context, relativeDir, name, copy);
            if (sink != null) {
                for (Uri other : copies) {
                    if (!other.equals(copy)) {
                        deleteQuietly(context, other);
                    }
                }
                Log.i(TAG, "rewrote " + copy + " as " + name);
                return sink;
            }
        }
        return open(context, relativeDir, name, mimeType);
    }

    /** Opens that row for writing, or null when it cannot be written. */
    private static ExportSink rewrite(Context context, String relativeDir, String name, Uri row) {
        try {
            ExportSink sink = new ExportSink(context);
            OutputStream out = sink.resolver.openOutputStream(row, "wt");
            if (out == null) {
                return null;
            }
            sink.uri = row;
            sink.stream = out;
            sink.displayPath = join(relativeDirectory(relativeDir), name);
            return sink;
        } catch (Throwable t) {
            Log.w(TAG, "could not rewrite " + row + ": " + t);
            return null;
        }
    }

    /** Rows whose name is that one plus MediaStore's " (n)" suffix. */
    private static List<Uri> findCopies(Context context, String relativeDir, String name) {
        List<Uri> found = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return found;
            }
            String base = name;
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String extension = dot > 0 ? name.substring(dot) : "";
            String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME};
            String where = MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND "
                    + MediaStore.Downloads.RELATIVE_PATH + "=?";
            Cursor cursor = context.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, where,
                    new String[] {stem + " (%" + extension, relativeDirectory(relativeDir) + "/"},
                    null);
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        String display = cursor.getString(1);
                        if (display == null || display.equals(base)) {
                            continue;
                        }
                        found.add(android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)));
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not look for copies of " + name + ": " + t);
        }
        return found;
    }

    private static void deleteQuietly(Context context, Uri row) {
        try {
            context.getContentResolver().delete(row, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "could not remove " + row + ": " + t);
        }
    }

    /** The relative path (below Downloads) a directory argument stands for. */
    private static String relativeDirectory(String relativeDir) {
        return relativeDir == null || relativeDir.length() == 0
                ? Environment.DIRECTORY_DOWNLOADS
                : Environment.DIRECTORY_DOWNLOADS + "/" + relativeDir;
    }

    /** The MediaStore row already holding that name, or null. */
    private static Uri find(Context context, String relativeDir, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String[] projection = {MediaStore.Downloads._ID};
                String where = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                        + MediaStore.Downloads.RELATIVE_PATH + "=?";
                Cursor cursor = context.getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, where,
                        new String[] {name, relativeDirectory(relativeDir) + "/"}, null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            return android.content.ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    cursor.getLong(0));
                        }
                    } finally {
                        cursor.close();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not ask MediaStore about " + name + ": " + t);
        }
        return null;
    }

    /**
     * Whether that destination already holds a file of this name.
     *
     * <p>Used to resume an export that was interrupted: asked before opening a
     * destination, so a note that is already exported is left alone instead of
     * being drawn and written again.
     */
    public static boolean exists(Context context, String relativeDir, String name) {
        if (find(context, relativeDir, name) != null) {
            return true;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), relativeDir == null ? "" : relativeDir);
        return new File(dir, name).exists();
    }

    public OutputStream stream() {
        return stream;
    }

    /** Publishes the file. */
    public void finish() throws Exception {
        if (stream != null) {
            stream.close();
            stream = null;
        }
        if (uri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        }
    }

    /** Discards the file, leaving nothing behind for the user to find. */
    public void abort() {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (Throwable ignored) {
            // the file is being thrown away anyway
        }
        stream = null;
        if (uri != null) {
            try {
                resolver.delete(uri, null, null);
            } catch (Throwable ignored) {
                // a leftover pending row is cleaned up by the system
            }
        } else if (file != null) {
            file.delete();
        }
    }
}
