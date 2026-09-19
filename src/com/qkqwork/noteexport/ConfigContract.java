package com.qkqwork.noteexport;

import android.net.Uri;

/**
 * Constants shared between the module's own process (settings screen) and the
 * code injected into the ColorOS Notes process.
 *
 * <p>Both sides are the same APK, so they must agree on the trigger URI and on
 * the sentinel path segment that is used to smuggle an export request into the
 * Notes process.
 */
public final class ConfigContract {

    private ConfigContract() {
    }

    /** The Notes app. */
    public static final String NOTES_PKG = "com.coloros.note";

    /** This module. */
    public static final String MODULE_PKG = "com.qkqwork.noteexport";

    /**
     * One of the two exported providers of the Notes app that can be queried
     * without any permission. Querying it also cold-starts the Notes process,
     * which is what lets the settings screen trigger an export while the app is
     * not running.
     *
     * <p>Both authorities below are tried in turn; {@link #EXPORT_SEGMENT} is a
     * path segment this module invented, so the Notes app itself never uses it.
     */
    public static final String[] NOTES_AUTHORITIES = {
            "com.oneplus.provider.Note",
            "com.oplus.provider.Note",
            "com.coloros.provider.Note",
    };

    /** Sentinel last path segment that turns a query into an export request. */
    public static final String EXPORT_SEGMENT = "note_dsh_export";

    /** Sentinel last path segment that returns whether this module is injected. */
    public static final String PROBE_SEGMENT = "note_dsh_probe";

    /**
     * Sentinel last path segment that makes the injected code write an
     * environment report into Downloads. See {@code Diagnostics} for why the
     * module needs one: several of the facts the export relies on can only be
     * observed on a real device.
     */
    public static final String DIAG_SEGMENT = "note_dsh_diag";

    /** Columns of the {@link android.database.MatrixCursor} returned by a query. */
    public static final String[] EXPORT_COLUMNS = {"ok", "message", "path"};

    public static Uri exportUri(String authority) {
        return Uri.parse("content://" + authority + "/" + EXPORT_SEGMENT);
    }

    public static Uri probeUri(String authority) {
        return Uri.parse("content://" + authority + "/" + PROBE_SEGMENT);
    }

    public static Uri diagUri(String authority) {
        return Uri.parse("content://" + authority + "/" + DIAG_SEGMENT);
    }

    // ------------------------------------------------------- module settings

    /** Read-only provider of this module, queried by the injected Notes code. */
    public static final String SETTINGS_AUTHORITY = "com.qkqwork.noteexport.settings";

    public static final Uri SETTINGS_URI =
            Uri.parse("content://" + SETTINGS_AUTHORITY + "/config");

    public static final String COLUMN_EXPORT_REQUEST = "export_request";
    public static final String COLUMN_FORMAT = "format";
    public static final String COLUMN_WORD_LAYOUT = "word_layout";
    public static final String COLUMN_INCLUDE_RECYCLED = "include_recycled";
    public static final String COLUMN_BACKGROUND = "image_background";
    public static final String COLUMN_LIMIT = "export_limit";
    public static final String COLUMN_NUMBERED = "numbered_names";
    public static final String COLUMN_FOLDERS = "category_folders";
    public static final String COLUMN_STAMPED = "timestamped_folder";
    public static final String COLUMN_SKIP = "skip_existing";
    public static final String COLUMN_DEBUG = "debug_logging";
    public static final String COLUMN_WATERMARK_MODE = "watermark_mode";
    public static final String COLUMN_WATERMARK_TEXT = "watermark_text";

    // --------------------------------------------------- the long picture's paper

    /**
     * What a long picture is drawn on.
     *
     * <p>The app hands its pages over with no background at all, so something has
     * to go behind them. {@code auto} takes the colour from the text the pages
     * carry; the other two are what the user picked instead, and then the text is
     * repainted to stay readable.
     */
    public static final String BACKGROUND_AUTO = "auto";
    public static final String BACKGROUND_WHITE = "white";
    public static final String BACKGROUND_DARK = "dark";

    // ------------------------------------------------------- the watermark

    /**
     * What to do with the ColorOS watermark the Notes app stamps on the bottom
     * of a long picture it shares.
     *
     * <p>That watermark is drawn from the app's own layout, so this is a display
     * decision taken inside the Notes process while the picture is being made —
     * it is not something an export can crop away afterwards.
     */
    public static final String WATERMARK_REMOVE = "remove";
    public static final String WATERMARK_KEEP_SPACE = "keep_space";
    public static final String WATERMARK_CUSTOM = "custom";
    public static final String WATERMARK_OFF = "off";

    /**
     * Mirror of the watermark settings in the shared Downloads folder. The
     * injected code reads it when the settings provider cannot be reached. The
     * name is visible rather than dotted because MediaStore is what writes it,
     * and it does not accept hidden names.
     */
    public static final String WATERMARK_FILE = "note_watermark.txt";

    /** Preferences holding the export request counter, keyed per package. */
    public static final String PREFS = "export_state";

    /** Set inside the Notes process once it has handled a request id. */
    public static final String KEY_HANDLED = "handled_request";

    /** SharedPreferences name used inside the Notes process for the same thing. */
    public static final String NOTES_PREFS = "dsh_note_export";

    public static final String FORMAT_IMAGE = "image";
    /** Long pictures drawn by the Notes app itself. */
    public static final String FORMAT_NATIVE = "native";
    /** Experimental: the app's editor driven per note, for a whole notebook. */
    public static final String FORMAT_NATIVE_BATCH = "native_batch";
    public static final String FORMAT_WORD = "word";

    public static final String LAYOUT_SINGLE = "single";
    public static final String LAYOUT_PER_NOTE = "per_note";
}
