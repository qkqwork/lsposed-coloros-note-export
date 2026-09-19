package com.qkqwork.noteexport;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The tick list of notes: what the export should cover.
 *
 * <p>The list is not read here — this process cannot open the Notes database —
 * but asked for through the module's own provider, which the injected code
 * answers from inside the Notes app. That query cold starts the Notes app on the
 * first use, so it runs on a background thread and the screen says what it is
 * waiting for.
 *
 * <p>A tick list rather than a filtered export because a note's id is the only
 * handle that survives the trip into the other process, and because "these
 * twelve, not the other hundred" is a decision someone makes once and then
 * exports again next week.
 */
public class PickerActivity extends Activity {

    private static final String TAG = Main.TAG;

    /** The rows, in the order the notes came back; the ids line up with them. */
    private final List<CheckBox> rows = new ArrayList<>();
    private final List<String> ids = new ArrayList<>();
    /** What was ticked when the screen opened, so an unfinished visit can go back. */
    private List<String> initial = new ArrayList<>();

    private LinearLayout listView;
    private TextView statusView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);
        listView = findViewById(R.id.picker_list);
        statusView = findViewById(R.id.picker_status);
        initial = NoteSelection.read(this);

        View all = findViewById(R.id.picker_all);
        if (all != null) {
            all.setOnClickListener(view -> setAll(true));
        }
        View none = findViewById(R.id.picker_none);
        if (none != null) {
            none.setOnClickListener(view -> setAll(false));
        }
        View done = findViewById(R.id.picker_done);
        if (done != null) {
            done.setOnClickListener(view -> finishWithSelection());
        }

        loadNotes();
    }

    // ------------------------------------------------------------------ the list

    /** Asks the Notes process for the notes; the provider starts it if needed. */
    private void loadNotes() {
        setStatus(getString(R.string.picker_loading), false);
        new Thread(() -> {
            List<Row> found = new ArrayList<>();
            String failure = null;
            // Whether any authority answered with the module's own columns. A
            // provider that does not know this path answers null, and that is not
            // "there are no notes" — it is "the module is not there".
            boolean answered = false;
            for (String authority : ConfigContract.NOTES_AUTHORITIES) {
                Uri uri = ConfigContract.listUri(authority);
                Cursor cursor = null;
                try {
                    Log.i(TAG, "asking " + uri + " for the note list");
                    cursor = getContentResolver().query(uri, null, null, null, null);
                    if (cursor == null) {
                        continue;
                    }
                    if (!hasModuleColumns(cursor)) {
                        // The real provider answered instead of the module's
                        // injected one: it hands back whole notes under its own
                        // column names, and one of the authorities answers this
                        // path with an empty cursor of its own shape. Listing
                        // either would be a screenful of rows with no titles, so
                        // both count as no answer.
                        failure = "not the module's list";
                        Log.w(TAG, authority + " answered without the module's columns; "
                                + "is the module enabled for the Notes app?");
                        continue;
                    }
                    answered = true;
                    while (cursor.moveToNext()) {
                        found.add(Row.of(cursor));
                    }
                    failure = null;
                    break;
                } catch (Throwable t) {
                    failure = String.valueOf(t);
                    Log.w(TAG, "the note list could not be read from " + authority + ": " + t);
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
            final List<Row> result = found;
            final String error = answered ? failure
                    : (failure == null ? "nothing answered" : failure);
            handler.post(() -> fill(result, error));
        }, "note-list").start();
    }

    private void fill(List<Row> notes, String error) {
        if (notes.isEmpty()) {
            setStatus(getString(error != null ? R.string.picker_failed : R.string.picker_empty),
                    error != null);
            return;
        }
        for (Row note : notes) {
            CheckBox box = (CheckBox) getLayoutInflater()
                    .inflate(R.layout.picker_row, listView, false);
            box.setText(note.label(this));
            // An encrypted note cannot be read by the export at all, so it is
            // shown — its absence would look like a bug — but cannot be ticked.
            box.setEnabled(!note.encrypted);
            box.setChecked(!note.encrypted && NoteSelection.contains(initial, note.id));
            box.setOnCheckedChangeListener((button, checked) -> refreshSummary());
            listView.addView(box);
            rows.add(box);
            ids.add(note.id);
        }
        refreshSummary();
    }

    /**
     * Whether that answer really came from the module.
     *
     * <p>The check is on the whole column set the module promises, not on one
     * name: the Notes app's own backup provider answers the same path with a
     * completely different cursor, and another authority answers it with an empty
     * one, so a single column is not enough to tell them apart.
     */
    private static boolean hasModuleColumns(Cursor cursor) {
        for (String column : ConfigContract.LIST_COLUMNS) {
            if (cursor.getColumnIndex(column) < 0) {
                return false;
            }
        }
        return true;
    }

    private void setAll(boolean checked) {
        for (CheckBox box : rows) {
            if (checked && !box.isEnabled()) {
                continue;
            }
            box.setChecked(checked);
        }
        refreshSummary();
    }

    private void refreshSummary() {
        int total = rows.size();
        int ticked = 0;
        for (CheckBox box : rows) {
            if (box.isChecked()) {
                ticked++;
            }
        }
        setStatus(getString(R.string.picker_summary, total, ticked), false);
    }

    private void finishWithSelection() {
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).isChecked()) {
                chosen.add(ids.get(i));
            }
        }
        NoteSelection.save(this, chosen);
        Log.i(TAG, "the picker kept " + chosen.size() + " of " + rows.size() + " note(s)");
        setResult(RESULT_OK, new Intent());
        finish();
    }

    private void setStatus(String message, boolean error) {
        statusView.setTextColor(getColor(error ? R.color.error_text : R.color.ok_text));
        statusView.setText(message);
    }

    /** One note as the provider describes it: no text, just what it is. */
    private static final class Row {

        final String id;
        final String title;
        final String category;
        final int words;
        final boolean encrypted;
        final boolean recycled;

        private Row(String id, String title, String category, int words, boolean encrypted,
                boolean recycled) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.words = words;
            this.encrypted = encrypted;
            this.recycled = recycled;
        }

        static Row of(Cursor cursor) {
            return new Row(
                    string(cursor, "guid", ""),
                    string(cursor, "title", ""),
                    string(cursor, "category", ""),
                    number(cursor, "words"),
                    flag(cursor, "encrypted"),
                    flag(cursor, "recycled"));
        }

        /** The one line the tick box shows. */
        String label(PickerActivity screen) {
            StringBuilder sb = new StringBuilder(
                    TextUtils.isEmpty(title) ? screen.getString(R.string.untitled) : title);
            if (!TextUtils.isEmpty(category)) {
                sb.append(" · ").append(category);
            }
            if (words > 0) {
                sb.append(" · ").append(words).append(' ').append(screen.getString(R.string.words));
            }
            if (encrypted) {
                sb.append(' ').append(screen.getString(R.string.picker_encrypted));
            }
            if (recycled) {
                sb.append(' ').append(screen.getString(R.string.picker_recycled));
            }
            return sb.toString();
        }

        private static String string(Cursor cursor, String column, String fallback) {
            int index = cursor.getColumnIndex(column);
            if (index < 0) {
                return fallback;
            }
            String value = cursor.getString(index);
            return value == null ? fallback : value;
        }

        private static int number(Cursor cursor, String column) {
            int index = cursor.getColumnIndex(column);
            return index < 0 || cursor.isNull(index) ? 0 : cursor.getInt(index);
        }

        private static boolean flag(Cursor cursor, String column) {
            int index = cursor.getColumnIndex(column);
            return index >= 0 && !cursor.isNull(index) && cursor.getInt(index) != 0;
        }
    }
}
