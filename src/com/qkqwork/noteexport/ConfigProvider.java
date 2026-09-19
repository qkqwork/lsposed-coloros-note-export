package com.qkqwork.noteexport;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only settings provider of the module itself.
 *
 * <p>The export is executed inside the Notes process, which cannot read this
 * module's SharedPreferences. It can however query this provider, because it is
 * exported, so that is how the format the user picked travels across the
 * process boundary.
 */
public class ConfigProvider extends ContentProvider {

    public static final String NAME = "settings";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        MatrixCursor cursor = new MatrixCursor(new String[] {
                ConfigContract.COLUMN_EXPORT_REQUEST,
                ConfigContract.COLUMN_FORMAT,
                ConfigContract.COLUMN_WORD_LAYOUT,
                ConfigContract.COLUMN_INCLUDE_RECYCLED,
                ConfigContract.COLUMN_WATERMARK_MODE,
                ConfigContract.COLUMN_WATERMARK_TEXT,
        });
        if (context == null) {
            return cursor;
        }
        SharedPreferences prefs = context.getSharedPreferences(
                ConfigContract.PREFS, Context.MODE_PRIVATE);
        cursor.addRow(new Object[] {
                prefs.getLong(ConfigContract.COLUMN_EXPORT_REQUEST, 0L),
                prefs.getString(ConfigContract.COLUMN_FORMAT,
                        ConfigContract.FORMAT_WORD),
                prefs.getString(ConfigContract.COLUMN_WORD_LAYOUT,
                        ConfigContract.LAYOUT_SINGLE),
                prefs.getBoolean(ConfigContract.COLUMN_INCLUDE_RECYCLED, true) ? 1 : 0,
                prefs.getString(ConfigContract.COLUMN_WATERMARK_MODE,
                        ConfigContract.WATERMARK_REMOVE),
                prefs.getString(ConfigContract.COLUMN_WATERMARK_TEXT, ""),
        });
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }
}
