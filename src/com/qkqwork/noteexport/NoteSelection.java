package com.qkqwork.noteexport;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which notes an export covers.
 *
 * <p>Held as a list of note ids: the picker fills it in, the settings screen
 * remembers it between runs — exporting the same few notes twice is then one tap
 * instead of a walk through the picker — and it travels to the Notes process
 * inside the same query string as the rest of the options.
 *
 * <p>An empty list is not "nothing": it means every note. A picker with nothing
 * ticked therefore cannot make an export that produces no files, which is the
 * reading that surprises people least.
 */
final class NoteSelection {

    /** Ids are joined with this: never part of an id, and safe inside a query. */
    private static final String SEPARATOR = "|";

    private NoteSelection() {
    }

    static List<String> read(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        SharedPreferences prefs =
                context.getSharedPreferences(ConfigContract.PREFS, Context.MODE_PRIVATE);
        return split(prefs.getString(ConfigContract.COLUMN_SELECTED_NOTES, ""));
    }

    static void save(Context context, List<String> ids) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(ConfigContract.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ConfigContract.COLUMN_SELECTED_NOTES, join(ids))
                .apply();
    }

    /** The stored form of a selection: one string, empty when it means "all". */
    static String join(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (id == null || id.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(id);
        }
        return sb.toString();
    }

    static List<String> split(String value) {
        List<String> ids = new ArrayList<>();
        if (value == null || value.length() == 0) {
            return ids;
        }
        for (String part : value.split("\\" + SEPARATOR)) {
            String id = part.trim();
            if (id.length() > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Whether that id is in the list. */
    static boolean contains(List<String> ids, String id) {
        if (ids == null || id == null) {
            return false;
        }
        for (String candidate : ids) {
            if (id.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
