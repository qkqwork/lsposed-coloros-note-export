package com.dsh.noteexport;

import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * How the ColorOS watermark on a shared long picture should be treated.
 *
 * <p>The setting is written by the module's own screen and read inside the Notes
 * process at the moment the app draws that picture. The two processes share no
 * storage, so it travels the same way the export request does: primarily through
 * the module's exported settings provider (the hook runs inside an Activity and
 * therefore has a ContentResolver), with a plain file in Downloads as the
 * fallback for the case where that query is refused.
 */
final class WatermarkSettings {

    private static final String TAG = Main.TAG;

    final String mode;
    final String text;

    WatermarkSettings(String mode, String text) {
        this.mode = mode == null || mode.length() == 0
                ? ConfigContract.WATERMARK_REMOVE : mode;
        this.text = text == null ? "" : text;
    }

    /** Whether the watermark should be touched at all. */
    boolean active() {
        return !ConfigContract.WATERMARK_OFF.equals(mode);
    }

    boolean custom() {
        return ConfigContract.WATERMARK_CUSTOM.equals(mode);
    }

    @Override
    public String toString() {
        return "mode=" + mode + " text=" + text;
    }

    /** Where the fallback copy lives — beside the export request file. */
    static File file() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), ExportRequest.DIR);
        return new File(dir, ConfigContract.WATERMARK_FILE);
    }

    /**
     * Reads the settings from the first place that answers: the provider, then
     * the fallback file, then the defaults. Never fails.
     */
    static WatermarkSettings read(Context context) {
        if (context != null) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        ConfigContract.SETTINGS_URI, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int modeColumn = cursor.getColumnIndex(ConfigContract.COLUMN_WATERMARK_MODE);
                    int textColumn = cursor.getColumnIndex(ConfigContract.COLUMN_WATERMARK_TEXT);
                    if (modeColumn >= 0) {
                        String mode = cursor.getString(modeColumn);
                        String text = textColumn >= 0 ? cursor.getString(textColumn) : "";
                        Log.i(TAG, "watermark settings from the provider: " + mode);
                        return new WatermarkSettings(mode, text);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "watermark settings provider unusable (" + t + "), trying the file");
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Throwable ignored) {
                        // nothing useful to do
                    }
                }
            }
        }
        return fromFile();
    }

    private static WatermarkSettings fromFile() {
        File source = file();
        if (!source.isFile() || !source.canRead()) {
            Log.i(TAG, "no watermark settings anywhere, defaulting to removing it");
            return new WatermarkSettings(ConfigContract.WATERMARK_REMOVE, "");
        }
        InputStream in = null;
        try {
            Properties properties = new Properties();
            in = new FileInputStream(source);
            properties.load(in);
            return new WatermarkSettings(
                    properties.getProperty(ConfigContract.COLUMN_WATERMARK_MODE),
                    properties.getProperty(ConfigContract.COLUMN_WATERMARK_TEXT));
        } catch (Throwable t) {
            Log.w(TAG, "could not read " + source + ": " + t);
            return new WatermarkSettings(ConfigContract.WATERMARK_REMOVE, "");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    /**
     * Stores the settings for this module's own process, and mirrors them to the
     * shared folder for the Notes process.
     *
     * <p>The mirror is best effort on purpose. The provider is the path the hook
     * actually uses; the file only matters when that query is refused, and on
     * Android 10+ a direct write into Downloads is refused in turn — which is
     * why the mirror goes through MediaStore first.
     */
    static void save(Context context, String mode, String text) {
        String safeMode = mode == null || mode.length() == 0
                ? ConfigContract.WATERMARK_REMOVE : mode;
        String safeText = text == null ? "" : text;
        if (context != null) {
            context.getSharedPreferences(ConfigContract.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(ConfigContract.COLUMN_WATERMARK_MODE, safeMode)
                    .putString(ConfigContract.COLUMN_WATERMARK_TEXT, safeText)
                    .apply();
        }

        Properties properties = new Properties();
        properties.setProperty(ConfigContract.COLUMN_WATERMARK_MODE, safeMode);
        properties.setProperty(ConfigContract.COLUMN_WATERMARK_TEXT, safeText);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            properties.store(buffer, "ColorOS note watermark");
        } catch (Throwable t) {
            Log.w(TAG, "could not serialise the watermark settings: " + t);
            return;
        }
        byte[] body = buffer.toByteArray();

        if (context != null) {
            ExportSink sink = null;
            try {
                sink = ExportSink.open(context, ExportRequest.DIR,
                        ConfigContract.WATERMARK_FILE, "text/plain");
                sink.stream().write(body);
                sink.finish();
                return;
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore mirror failed (" + t + "), trying a plain file");
                if (sink != null) {
                    sink.abort();
                }
            }
        }

        File target = file();
        try {
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(target);
            try {
                out.write(body);
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            // Not fatal: the provider carries the settings, this is only a net.
            Log.w(TAG, "could not mirror the watermark settings to " + target + ": " + t);
        }
    }
}
