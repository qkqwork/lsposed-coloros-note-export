package com.dsh.noteexport;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * The tiny request file the settings screen writes and the injected Notes code
 * reads.
 *
 * <p>This exists because the two sides live in different processes with
 * different UIDs. Querying the module's own provider from inside the Notes
 * process would need a Context the Notes process cannot obtain, and a
 * world-readable preference file has been unreliable for years. A plain file in
 * the shared Downloads folder is readable by both without any permission, and
 * it doubles as the record of "an export was requested".
 */
public final class ExportRequest {

    private static final String TAG = Main.TAG;

    /** Same folder the export output goes to, so everything stays together. */
    static final String DIR = "便签导出";
    private static final String FILE_NAME = ".request";

    public static final String KEY_REQUEST = "request";
    public static final String KEY_FORMAT = "format";
    public static final String KEY_LAYOUT = "layout";
    public static final String KEY_RECYCLED = "recycled";
    public static final String KEY_STAMPED = "stamped";
    /** Optional cap on how many notes to export; used for trying a format out. */
    public static final String KEY_LIMIT = "limit";
    /** What a long picture is drawn on: {@code auto}, {@code white} or {@code dark}. */
    public static final String KEY_BACKGROUND = "bg";

    /** Result of reading the file: the options plus the request id. */
    public static final class Request {
        public final long id;
        public final ExportOptions options;

        Request(long id, ExportOptions options) {
            this.id = id;
            this.options = options;
        }
    }

    private ExportRequest() {
    }

    public static File file() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DIR);
        return new File(dir, FILE_NAME);
    }

    /** Writes the request; returns the new request id, or 0 on failure. */
    public static long write(ExportOptions options) {
        long id = System.currentTimeMillis();
        File target = file();
        try {
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Properties properties = new Properties();
            properties.setProperty(KEY_REQUEST, Long.toString(id));
            properties.setProperty(KEY_FORMAT,
                    options.format == ExportOptions.Format.IMAGE
                            ? ConfigContract.FORMAT_IMAGE : ConfigContract.FORMAT_WORD);
            properties.setProperty(KEY_LAYOUT,
                    options.wordLayout == ExportOptions.WordLayout.PER_NOTE
                            ? ConfigContract.LAYOUT_PER_NOTE : ConfigContract.LAYOUT_SINGLE);
            properties.setProperty(KEY_RECYCLED, options.includeRecycled ? "1" : "0");
            properties.setProperty(KEY_STAMPED, options.timestampedFolder ? "1" : "0");
            FileOutputStream out = new FileOutputStream(target);
            try {
                properties.store(out, "ColorOS note export request");
            } finally {
                out.close();
            }
            Log.i(TAG, "export requested (" + id + ") -> " + target);
            return id;
        } catch (Throwable t) {
            Log.e(TAG, "could not write the request file", t);
            return 0L;
        }
    }

    /**
     * Packs the options into the query argument that carries them into the Notes
     * process.
     *
     * <p>The query is answered by the hook before the Notes app's own provider
     * ever sees it, so this string is free-form: no SQL is involved. It replaced
     * a request file in Downloads, which this module cannot write — it holds no
     * storage permission, so on Android 10+ the write was refused and every
     * export quietly ran with the default options.
     */
    public static String toSelection(ExportOptions options) {
        StringBuilder sb = new StringBuilder();
        sb.append(KEY_FORMAT).append('=').append(formatName(options.format));
        sb.append(';').append(KEY_LAYOUT).append('=')
                .append(options.wordLayout == ExportOptions.WordLayout.PER_NOTE
                        ? ConfigContract.LAYOUT_PER_NOTE : ConfigContract.LAYOUT_SINGLE);
        sb.append(';').append(KEY_RECYCLED).append('=')
                .append(options.includeRecycled ? '1' : '0');
        sb.append(';').append(KEY_STAMPED).append('=')
                .append(options.timestampedFolder ? '1' : '0');
        if (options.limit > 0) {
            sb.append(';').append(KEY_LIMIT).append('=').append(options.limit);
        }
        sb.append(';').append(KEY_BACKGROUND).append('=')
                .append(options.background == ExportOptions.Background.WHITE
                        ? "white"
                        : options.background == ExportOptions.Background.AUTO ? "auto" : "dark");
        return sb.toString();
    }

    private static String formatName(ExportOptions.Format format) {
        if (format == ExportOptions.Format.IMAGE) {
            return ConfigContract.FORMAT_IMAGE;
        }
        if (format == ExportOptions.Format.NATIVE) {
            return ConfigContract.FORMAT_NATIVE;
        }
        if (format == ExportOptions.Format.NATIVE_BATCH) {
            return ConfigContract.FORMAT_NATIVE_BATCH;
        }
        return ConfigContract.FORMAT_WORD;
    }

    /**
     * Reads options out of that query argument. Anything unrecognised is left at
     * its default, so an older or newer caller still gets a usable export.
     */
    public static ExportOptions parseSelection(String selection) {
        ExportOptions options = new ExportOptions();
        if (selection == null || selection.length() == 0) {
            return options;
        }
        for (String token : selection.split("[;,]")) {
            int equals = token.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = token.substring(0, equals).trim().toLowerCase(Locale.US);
            String value = token.substring(equals + 1).trim();
            if (KEY_FORMAT.equals(key)) {
                if (ConfigContract.FORMAT_IMAGE.equalsIgnoreCase(value)) {
                    options.format = ExportOptions.Format.IMAGE;
                } else if (ConfigContract.FORMAT_NATIVE.equalsIgnoreCase(value)) {
                    options.format = ExportOptions.Format.NATIVE;
                } else if (ConfigContract.FORMAT_NATIVE_BATCH.equalsIgnoreCase(value)) {
                    options.format = ExportOptions.Format.NATIVE_BATCH;
                } else {
                    options.format = ExportOptions.Format.WORD;
                }
            } else if (KEY_LAYOUT.equals(key)) {
                options.wordLayout = ConfigContract.LAYOUT_PER_NOTE.equalsIgnoreCase(value)
                        ? ExportOptions.WordLayout.PER_NOTE
                        : ExportOptions.WordLayout.SINGLE;
            } else if (KEY_RECYCLED.equals(key)) {
                options.includeRecycled = !"0".equals(value);
            } else if (KEY_STAMPED.equals(key)) {
                options.timestampedFolder = !"0".equals(value);
            } else if (KEY_LIMIT.equals(key)) {
                try {
                    options.limit = Math.max(0, Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    // a malformed limit simply means "no limit"
                }
            } else if (KEY_BACKGROUND.equals(key)) {
                if ("white".equalsIgnoreCase(value)) {
                    options.background = ExportOptions.Background.WHITE;
                } else if ("dark".equalsIgnoreCase(value) || "black".equalsIgnoreCase(value)) {
                    options.background = ExportOptions.Background.DARK;
                } else {
                    options.background = ExportOptions.Background.AUTO;
                }
            }
        }
        return options;
    }

    /** Reads the request. A missing or unreadable file yields defaults with id 0. */
    public static Request read() {
        ExportOptions options = new ExportOptions();
        long id = 0L;
        File source = file();
        if (!source.isFile() || !source.canRead()) {
            return new Request(0L, options);
        }
        InputStream in = null;
        try {
            Properties properties = new Properties();
            in = new FileInputStream(source);
            properties.load(in);
            id = parseLong(properties.getProperty(KEY_REQUEST));
            String format = properties.getProperty(KEY_FORMAT,
                    ConfigContract.FORMAT_WORD);
            options.format = ConfigContract.FORMAT_IMAGE.equals(format)
                    ? ExportOptions.Format.IMAGE : ExportOptions.Format.WORD;
            String layout = properties.getProperty(KEY_LAYOUT,
                    ConfigContract.LAYOUT_SINGLE);
            options.wordLayout = ConfigContract.LAYOUT_PER_NOTE.equals(layout)
                    ? ExportOptions.WordLayout.PER_NOTE
                    : ExportOptions.WordLayout.SINGLE;
            options.includeRecycled = !"0".equals(properties.getProperty(KEY_RECYCLED, "1"));
            options.timestampedFolder = !"0".equals(properties.getProperty(KEY_STAMPED, "1"));
        } catch (Throwable t) {
            Log.w(TAG, "could not read the request file: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing useful to do
                }
            }
        }
        return new Request(id, options);
    }

    /** Reads the options with the defaults filled in; never fails. */
    public static ExportOptions readOptions() {
        return read().options;
    }

    /**
     * A Context able to reach the module's own preferences is not available
     * inside the Notes process, so the request id is what both sides compare.
     */
    public static void markHandled(Context context, long requestId) {
        context.getSharedPreferences(ConfigContract.NOTES_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(ConfigContract.KEY_HANDLED, requestId)
                .apply();
    }

    public static long handled(Context context) {
        return context.getSharedPreferences(ConfigContract.NOTES_PREFS,
                Context.MODE_PRIVATE).getLong(ConfigContract.KEY_HANDLED, 0L);
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
