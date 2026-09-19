package io.github.qkqwork.noteexport;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 *
 * <p>What is ticked is held here rather than read back off the views: filtering
 * the list throws the rows away and builds new ones, and a tick has to survive
 * that. Ticking, searching and changing category all end in the same redraw.
 */
public class PickerActivity extends Activity {

    private static final String TAG = Main.TAG;

    /** Every note as the provider described it, in the order it arrived. */
    private final List<Row> all = new ArrayList<>();
    /** The ticked note ids. */
    private final Set<String> ticked = new LinkedHashSet<>();
    /** The rows currently on screen, so 全选 can act on what is being looked at. */
    private final List<Row> shown = new ArrayList<>();

    private LinearLayout listView;
    private TextView statusView;
    private EditText search;
    private Spinner categories;
    /** Empty means every category; the spinner's first entry. */
    private String categoryFilter = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);
        listView = findViewById(R.id.picker_list);
        statusView = findViewById(R.id.picker_status);
        search = findViewById(R.id.picker_search);
        categories = findViewById(R.id.picker_category);
        ticked.addAll(NoteSelection.read(this));

        View all = findViewById(R.id.picker_all);
        if (all != null) {
            all.setOnClickListener(view -> tickShown(true));
        }
        View none = findViewById(R.id.picker_none);
        if (none != null) {
            none.setOnClickListener(view -> tickShown(false));
        }
        View done = findViewById(R.id.picker_done);
        if (done != null) {
            done.setOnClickListener(view -> finishWithSelection());
        }
        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                    // nothing to do before the text changes
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                    // and nothing to do while it is changing
                }

                @Override
                public void afterTextChanged(Editable text) {
                    render();
                }
            });
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
        all.clear();
        all.addAll(notes);
        // A tick for a note that is no longer there is dropped, so the count the
        // screen shows is the count an export would use.
        Set<String> present = new LinkedHashSet<>();
        for (Row note : notes) {
            if (ticked.contains(note.id)) {
                present.add(note.id);
            }
        }
        ticked.clear();
        ticked.addAll(present);
        buildCategories();
        render();
    }

    /** The spinner's entries: every category the notes actually use. */
    private void buildCategories() {
        if (categories == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.picker_category_all));
        for (Row note : all) {
            String name = TextUtils.isEmpty(note.category)
                    ? getString(R.string.picker_no_category) : note.category;
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categories.setAdapter(adapter);
        categories.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                categoryFilter = position == 0 ? "" : names.get(position);
                render();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                categoryFilter = "";
                render();
            }
        });
    }

    /** Draws the notes that match the search box and the category, and no others. */
    private void render() {
        if (listView == null) {
            return;
        }
        String needle = search == null ? "" : search.getText().toString().trim().toLowerCase();
        listView.removeAllViews();
        shown.clear();
        for (Row note : all) {
            if (!categoryFilter.isEmpty() && !categoryFilter.equals(categoryName(note))) {
                continue;
            }
            if (!needle.isEmpty() && !note.matches(needle)) {
                continue;
            }
            CheckBox box = (CheckBox) getLayoutInflater()
                    .inflate(R.layout.picker_row, listView, false);
            box.setText(note.label(this));
            // An encrypted note cannot be read by the export at all, so it is
            // shown — its absence would look like a bug — but cannot be ticked.
            box.setEnabled(!note.encrypted);
            box.setChecked(!note.encrypted && ticked.contains(note.id));
            box.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    ticked.add(note.id);
                } else {
                    ticked.remove(note.id);
                }
                refreshSummary();
            });
            listView.addView(box);
            shown.add(note);
        }
        refreshSummary();
    }

    private String categoryName(Row note) {
        return TextUtils.isEmpty(note.category)
                ? getString(R.string.picker_no_category) : note.category;
    }

    /** Ticks or unticks what is on screen, which is what a filter leaves visible. */
    private void tickShown(boolean checked) {
        for (Row note : shown) {
            if (note.encrypted) {
                continue;
            }
            if (checked) {
                ticked.add(note.id);
            } else {
                ticked.remove(note.id);
            }
        }
        render();
    }

    private void refreshSummary() {
        setStatus(shown.size() == all.size()
                ? getString(R.string.picker_summary, all.size(), ticked.size())
                : getString(R.string.picker_summary_filtered, all.size(), ticked.size(),
                        shown.size()), false);
    }

    private void finishWithSelection() {
        NoteSelection.save(this, new ArrayList<>(ticked));
        Log.i(TAG, "the picker kept " + ticked.size() + " of " + all.size() + " note(s)");
        setResult(RESULT_OK, new Intent());
        finish();
    }

    private void setStatus(String message, boolean error) {
        statusView.setTextColor(getColor(error ? R.color.error_text : R.color.ok_text));
        statusView.setText(message);
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

        /** Whether the search box's text appears in the title or the category. */
        boolean matches(String needle) {
            String haystack = (title == null ? "" : title) + " " + (category == null ? "" : category);
            return haystack.toLowerCase().contains(needle);
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
