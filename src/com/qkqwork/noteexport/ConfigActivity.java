package com.qkqwork.noteexport;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The module's only screen: pick a format, press export, read the result.
 *
 * <p>There is deliberately no note list here — the module cannot read the Notes
 * database from its own process, so it does not pretend to know what will be
 * exported. It hands the request to the Notes process and reports what came
 * back.
 *
 * <p>The form itself lives in {@code res/layout/activity_config.xml}; this class
 * only looks the controls up and wires them to the export. Every option is saved
 * the moment it changes rather than when the screen goes away: a settings screen
 * that forgets what was chosen because the task was swiped from the recents list
 * is worse than one that writes a few bytes on every tap.
 */
public class ConfigActivity extends Activity {

    private static final String TAG = Main.TAG;

    /** The folder the exports land in, as a documents-provider uri. */
    private static final String DOWNLOAD_DOCUMENT =
            "content://com.android.externalstorage.documents/document/primary%3ADownload";
    private static final String DOWNLOAD_ROOT =
            "content://com.android.externalstorage.documents/root/primary";
    private static final String DIRECTORY_MIME = "vnd.android.document/directory";
    private static final String ROOT_MIME = "vnd.android.document/root";
    /** How often the screen asks the Notes process how far along it is. */
    private static final long PROGRESS_POLL_MS = 700;

    private RadioGroup formatGroup;
    private RadioGroup layoutGroup;
    private LinearLayout layoutOptions;
    private CheckBox recycledBox;
    private EditText limitBox;
    private CheckBox numberedBox;
    private CheckBox foldersBox;
    private CheckBox stampedBox;
    private CheckBox skipBox;
    private CheckBox debugBox;
    /** Whether the long picture is drawn as the app's share card. */
    private CheckBox cardBox;
    /** Whether an image export also copies each note's attachments. */
    private CheckBox attachmentsBox;
    /**
     * States which background the long pictures will get.
     *
     * <p>It is not a choice: the Notes app draws its pictures in the colours of
     * the phone's theme, so the module follows that same theme instead of
     * repainting glyphs to suit a colour the user picked. Changing the phone's
     * dark mode is the only thing that moves it.
     */
    private TextView backgroundValue;
    private RadioGroup watermarkGroup;
    private EditText watermarkText;
    private Button exportButton;
    private TextView statusView;
    /** The top of the first picture of the last export, once there is one. */
    private ImageView previewView;
    /** How many notes the picker has ticked; none means all of them. */
    private TextView selectedNotesView;
    /** Shown only while an export is in flight. */
    private ProgressBar progressBar;
    private Button cancelButton;
    /** Polls the Notes process for progress while an export runs. */
    private final Runnable progressPoll = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) {
                return;
            }
            refreshProgress();
            handler.postDelayed(this, PROGRESS_POLL_MS);
        }
    };
    /** What the progress line last showed, so an unchanged poll draws nothing. */
    private String lastProgressKey = "";
    /** When the first counted note was reported, for the estimate below. */
    private long progressStartedAt;
    private int progressStartedDone;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int attemptsLeft;
    /**
     * True while the screen is filling itself in from the stored options.
     *
     * <p>Setting a radio button or a check box fires its listener, and those
     * listeners save: without this the screen would write half-restored values
     * back over the ones it is still reading — the count field, for instance,
     * is restored after the format group and would be saved as empty first.
     */
    private boolean restoring;
    /**
     * The count field's text while a "try one note" run is in flight.
     *
     * <p>The field is forced to 1 for that run and put back afterwards, so a
     * trial never quietly rewrites the count someone typed for a real export.
     */
    private String limitBeforeTrial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);
        bindViews();
        restoreOptions();
        refreshLayoutVisibility();
        refreshSelectedNotes();
        restorePreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the picker is the one moment the count can have moved.
        refreshSelectedNotes();
    }

    // ------------------------------------------------------------------ the UI

    /**
     * Looks the screen's controls up and wires them together.
     *
     * <p>Everything is found first and only then are the listeners attached: a
     * radio button's listener fires the moment its checked state is set, and a
     * listener that touched a view that did not exist yet is exactly how this
     * screen used to crash on start-up.
     */
    private void bindViews() {
        formatGroup = findViewById(R.id.format_group);
        layoutGroup = findViewById(R.id.layout_group);
        layoutOptions = findViewById(R.id.word_options);
        recycledBox = findViewById(R.id.option_recycled);
        limitBox = findViewById(R.id.option_limit);
        numberedBox = findViewById(R.id.option_numbered);
        foldersBox = findViewById(R.id.option_folders);
        stampedBox = findViewById(R.id.option_stamped);
        skipBox = findViewById(R.id.option_skip);
        debugBox = findViewById(R.id.option_debug);
        cardBox = findViewById(R.id.option_card);
        attachmentsBox = findViewById(R.id.option_attachments);
        backgroundValue = findViewById(R.id.background_value);
        watermarkGroup = findViewById(R.id.watermark_group);
        watermarkText = findViewById(R.id.watermark_text);
        exportButton = findViewById(R.id.action_export);
        statusView = findViewById(R.id.status);
        previewView = findViewById(R.id.preview);
        selectedNotesView = findViewById(R.id.selected_notes);
        progressBar = findViewById(R.id.progress);
        cancelButton = findViewById(R.id.action_cancel);

        if (formatGroup != null) {
            formatGroup.setOnCheckedChangeListener((group, checked) -> {
                refreshLayoutVisibility();
                saveOptions();
            });
        }
        if (layoutGroup != null) {
            layoutGroup.setOnCheckedChangeListener((group, checked) -> saveOptions());
        }
        if (watermarkGroup != null) {
            watermarkGroup.setOnCheckedChangeListener((group, checked) -> {
                refreshWatermarkField();
                saveOptions();
            });
        }
        CompoundButton.OnCheckedChangeListener saver = (button, checked) -> saveOptions();
        for (CheckBox box : new CheckBox[]{recycledBox, numberedBox, foldersBox,
                stampedBox, skipBox, attachmentsBox, cardBox, debugBox}) {
            if (box != null) {
                box.setOnCheckedChangeListener(saver);
            }
        }
        if (limitBox != null) {
            // Saved when the field is left rather than on every keystroke: the
            // write is a synchronous one (see saveOptions) and a half typed
            // number is not worth committing at all.
            limitBox.setOnFocusChangeListener((view, focused) -> {
                if (!focused) {
                    saveOptions();
                }
            });
        }
        if (watermarkText != null) {
            watermarkText.setOnFocusChangeListener((view, focused) -> {
                if (!focused) {
                    saveOptions();
                }
            });
        }
        if (exportButton != null) {
            exportButton.setOnClickListener(view -> startExport());
        }
        View tryOne = findViewById(R.id.action_try_one);
        if (tryOne != null) {
            tryOne.setOnClickListener(view -> exportOne());
        }
        View openFolder = findViewById(R.id.action_open_folder);
        if (openFolder != null) {
            openFolder.setOnClickListener(view -> openExportFolder());
        }
        View reset = findViewById(R.id.action_reset);
        if (reset != null) {
            reset.setOnClickListener(view -> resetOptions());
        }
        View pickNotes = findViewById(R.id.action_pick_notes);
        if (pickNotes != null) {
            pickNotes.setOnClickListener(view -> pickNotes());
        }
        if (cancelButton != null) {
            cancelButton.setOnClickListener(view -> cancelExport());
        }
    }

    // -------------------------------------------------------------- the progress

    /**
     * Asks the Notes process how far along it is and draws the bar.
     *
     * <p>The work happens in another process, so there is nothing to observe
     * locally: the export publishes its own state and this asks for it. The call
     * is a binder query, so it goes out on its own thread and the answer comes
     * back to the main thread.
     */
    private void refreshProgress() {
        new Thread(() -> {
            int done = -1;
            int total = 0;
            String title = "";
            boolean cancelAsked = false;
            for (String authority : ConfigContract.NOTES_AUTHORITIES) {
                Cursor cursor = null;
                try {
                    cursor = getContentResolver().query(
                            ConfigContract.progressUri(authority), null, null, null, null);
                    if (cursor == null || !cursor.moveToFirst()) {
                        continue;
                    }
                    done = intAt(cursor, "done", -1);
                    total = intAt(cursor, "total", 0);
                    title = stringAt(cursor, "title", "");
                    cancelAsked = intAt(cursor, "cancel", 0) != 0;
                    break;
                } catch (Throwable t) {
                    Log.w(TAG, "progress query to " + authority + " failed: " + t);
                } finally {
                    closeQuietly(cursor);
                }
            }
            final int doneNow = done;
            final int totalNow = total;
            final String titleNow = title;
            final boolean askedNow = cancelAsked;
            handler.post(() -> showProgress(doneNow, totalNow, titleNow, askedNow));
        }, "note-export-progress").start();
    }

    private void showProgress(int done, int total, String title, boolean cancelAsked) {
        if (progressBar == null) {
            return;
        }
        // Only redraw when something actually changed. The export is asked about
        // twice a second, and a note takes longer than that to draw: without this
        // the screen would repaint and re-announce itself several times per note
        // while showing the very same numbers.
        String key = done + "/" + total + "/" + title + "/" + cancelAsked;
        if (key.equals(lastProgressKey)) {
            return;
        }
        lastProgressKey = key;
        if (total > 0 && done >= 0) {
            progressBar.setMax(total);
            progressBar.setProgress(Math.min(done, total));
            String tail = TextUtils.isEmpty(title) ? "" : "：" + title;
            setStatus(getString(R.string.status_progress, done, total, tail,
                    remaining(done, total)), false);
        }
        // Before the first report the bar simply sits at zero: a spinner would
        // say no more than the status line already does, and an animation that
        // never stops is also what keeps a screen reader, or any tool asking the
        // window whether it has settled, waiting for ever.
        if (cancelAsked) {
            setStatus(getString(R.string.action_cancel_asked), false);
            if (cancelButton != null) {
                cancelButton.setEnabled(false);
            }
        }
    }

    /**
     * How long the rest of the export looks likely to take, as a short suffix.
     *
     * <p>Measured from the first report that had a number in it, since the wait
     * before the app's first note says nothing about how long a note takes. Left
     * out until there is enough to say — one note's worth of data would be a
     * guess dressed up as a promise.
     */
    private String remaining(int done, int total) {
        long now = System.currentTimeMillis();
        if (progressStartedAt == 0 || done <= progressStartedDone || total <= done) {
            if (progressStartedAt == 0 && done > 0) {
                progressStartedAt = now;
                progressStartedDone = done;
            }
            return "";
        }
        long perNote = (now - progressStartedAt) / Math.max(1, done - progressStartedDone);
        long left = perNote * (total - done);
        if (left < 5000) {
            return "";
        }
        long seconds = left / 1000;
        return getString(R.string.status_progress_eta, seconds >= 60
                ? getString(R.string.duration_minutes, seconds / 60, seconds % 60)
                : getString(R.string.duration_seconds, seconds));
    }

    /** Asks the export running in the Notes process to stop. */
    private void cancelExport() {
        if (!running.get()) {
            return;
        }
        if (cancelButton != null) {
            cancelButton.setEnabled(false);
        }
        setStatus(getString(R.string.action_cancel_asked), false);
        new Thread(() -> {
            for (String authority : ConfigContract.NOTES_AUTHORITIES) {
                Cursor cursor = null;
                try {
                    cursor = getContentResolver().query(
                            ConfigContract.cancelUri(authority), null, null, null, null);
                    if (cursor != null) {
                        Log.i(TAG, "cancellation asked for through " + authority);
                        break;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "cancel query to " + authority + " failed: " + t);
                } finally {
                    closeQuietly(cursor);
                }
            }
        }, "note-export-cancel").start();
    }

    private void setExporting(boolean exporting) {
        if (progressBar != null) {
            progressBar.setVisibility(exporting ? View.VISIBLE : View.GONE);
            if (exporting) {
                progressBar.setProgress(0);
            }
        }
        if (cancelButton != null) {
            cancelButton.setVisibility(exporting ? View.VISIBLE : View.GONE);
            cancelButton.setEnabled(exporting);
        }
        if (exportButton != null) {
            exportButton.setEnabled(!exporting);
        }
    }

    private static int intAt(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getInt(index);
    }

    private static String stringAt(Cursor cursor, String column, String fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0) {
            return fallback;
        }
        String value = cursor.getString(index);
        return value == null ? fallback : value;
    }

    private static void closeQuietly(Cursor cursor) {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
        }
    }

    /**
     * Opens the tick list of notes.
     *
     * <p>The selection lives in the preferences rather than coming back in a
     * result: the picker is also what the export reads from, so a visit that ends
     * with the task being killed still counts.
     */
    private void pickNotes() {
        try {
            startActivity(new Intent(this, PickerActivity.class));
        } catch (Throwable t) {
            Log.w(TAG, "could not open the note picker: " + t);
        }
    }

    /** Says how many notes the export will cover. */
    private void refreshSelectedNotes() {
        if (selectedNotesView == null) {
            return;
        }
        int chosen = NoteSelection.read(this).size();
        selectedNotesView.setText(chosen == 0
                ? getString(R.string.notes_all)
                : getString(R.string.notes_selected, chosen));
    }

    private void setStatus(String message, boolean error) {
        statusView.setTextColor(getColor(error ? R.color.error_text : R.color.ok_text));
        statusView.setText(message);
    }

    // -------------------------------------------------------------- the preview

    /**
     * Shows the small picture an export came back with.
     *
     * <p>The picture travels in the answer's {@code thumb} column, because this
     * process can neither read the export folder nor draw a long note itself.
     * It is also kept in the cache, so it survives the screen being rebuilt —
     * which happens on every theme change, the very thing the preview exists to
     * help judge.
     */
    private void showPreview(byte[] png) {
        if (previewView == null || png == null || png.length == 0) {
            return;
        }
        try {
            Bitmap picture = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (picture == null) {
                return;
            }
            Drawable previous = previewView.getDrawable();
            previewView.setImageBitmap(picture);
            previewView.setVisibility(View.VISIBLE);
            if (previous instanceof BitmapDrawable) {
                Bitmap older = ((BitmapDrawable) previous).getBitmap();
                if (older != null && !older.isRecycled()) {
                    older.recycle();
                }
            }
            FileOutputStream out = new FileOutputStream(previewFile());
            try {
                out.write(png);
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not show the preview: " + t);
        }
    }

    /** Puts the last export's preview back when the screen is rebuilt. */
    private void restorePreview() {
        if (previewView == null) {
            return;
        }
        File file = previewFile();
        if (!file.isFile() || file.length() == 0) {
            return;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] png = new byte[(int) file.length()];
            int read = 0;
            while (read < png.length) {
                int step = in.read(png, read, png.length - read);
                if (step < 0) {
                    break;
                }
                read += step;
            }
            Bitmap picture = BitmapFactory.decodeByteArray(png, 0, read);
            if (picture != null) {
                previewView.setImageBitmap(picture);
                previewView.setVisibility(View.VISIBLE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not restore the preview: " + t);
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

    private File previewFile() {
        return new File(getCacheDir(), "last-export.png");
    }

    /** The custom watermark's text field only matters in that one mode. */
    private void refreshWatermarkField() {
        if (watermarkText == null || watermarkGroup == null) {
            return;
        }
        watermarkText.setEnabled(
                watermarkGroup.getCheckedRadioButtonId() == R.id.watermark_custom);
    }

    private void refreshLayoutVisibility() {
        if (formatGroup == null || layoutOptions == null) {
            // Called before the rest of the layout exists — the format group's
            // listener fires while it is still being built.
            return;
        }
        layoutOptions.setVisibility(
                formatOf() == ExportOptions.Format.WORD ? View.VISIBLE : View.GONE);
        refreshWatermarkField();
    }

    /**
     * Opens the folder the exports land in, the way a file manager would.
     *
     * <p>The pictures are not in Downloads itself but in {@code 便签导出} below it,
     * which is a level a file manager would not open on its own — so the folder
     * itself is handed over as the document to view, with the Downloads root as
     * the fallback for a phone whose export has not created it yet.
     */
    private void openExportFolder() {
        String folder = "Download/" + ExportRequest.DIR;
        Uri target = Uri.parse(DOWNLOAD_DOCUMENT + "%2F" + Uri.encode(ExportRequest.DIR));
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(target, DIRECTORY_MIME);
        // Kept as well for the file managers that only read the picker's extra.
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, target);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            // Left alone the system builds a "open with" chooser here, because
            // both DocumentsUI and the phone's own file manager claim the uri.
            // Picking one of them outright is what the button promised.
            String viewer = fileManagerFor(intent);
            if (viewer != null) {
                intent.setPackage(viewer);
            }
            startActivity(intent);
            setStatus(getString(R.string.status_folder_opened, folder), false);
        } catch (Throwable t) {
            Log.w(TAG, "no app would open " + folder + ": " + t);
            openDownloadRoot(folder);
        }
    }

    /** The whole Downloads folder, for when the export folder cannot be opened. */
    private void openDownloadRoot(String folder) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(DOWNLOAD_ROOT), ROOT_MIME);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            String viewer = fileManagerFor(intent);
            if (viewer != null) {
                intent.setPackage(viewer);
            }
            startActivity(intent);
            setStatus(getString(R.string.status_folder_opened, "Download"), false);
        } catch (Throwable t) {
            // A device without a file manager still gets told where the files are.
            Log.w(TAG, "no app would open Download either: " + t);
            setStatus(getString(R.string.status_no_file_manager, folder), true);
        }
    }

    /** The first app that can really show a folder, skipping the chooser itself. */
    private String fileManagerFor(Intent intent) {
        String fallback = null;
        try {
            for (ResolveInfo info : getPackageManager().queryIntentActivities(intent, 0)) {
                if (info.activityInfo == null) {
                    continue;
                }
                String packageName = info.activityInfo.packageName;
                if ("android".equals(packageName)) {
                    continue;
                }
                if ("com.android.documentsui".equals(packageName)) {
                    return packageName;
                }
                if (fallback == null) {
                    fallback = packageName;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not list folder viewers: " + t);
        }
        return fallback;
    }

    // -------------------------------------------------------------- the options

    /** The format the radio group currently selects. */
    private ExportOptions.Format formatOf() {
        int id = formatGroup == null ? View.NO_ID : formatGroup.getCheckedRadioButtonId();
        if (id == R.id.format_native) {
            // The app draws these itself, one note at a time, driven from inside
            // its own process; the older share-screen route is kept for reference
            // only and is not offered here any more.
            return ExportOptions.Format.NATIVE_BATCH;
        }
        if (id == R.id.format_drawn) {
            return ExportOptions.Format.IMAGE;
        }
        return ExportOptions.Format.WORD;
    }

    private ExportOptions.WordLayout layoutOf() {
        int id = layoutGroup == null ? View.NO_ID : layoutGroup.getCheckedRadioButtonId();
        return id == R.id.layout_per_note
                ? ExportOptions.WordLayout.PER_NOTE : ExportOptions.WordLayout.SINGLE;
    }

    /**
     * What a long picture is drawn on.
     *
     * <p>Always {@link ExportOptions.Background#AUTO}: the pictures are drawn by
     * the Notes app in the colours of the phone's theme, and the module matches
     * them rather than repainting. The Notes process works the same thing out
     * from the page itself, so a theme switched between reading the screen and
     * drawing a note still comes out right.
     */
    private ExportOptions.Background backgroundOf() {
        return ExportOptions.Background.AUTO;
    }

    /** Says which background the phone's theme leads to. */
    private void refreshBackgroundValue() {
        if (backgroundValue == null) {
            return;
        }
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        backgroundValue.setText(getString(night
                ? R.string.background_follow_dark : R.string.background_follow_light));
    }

    /** The mode the watermark radio group currently selects. */
    private String watermarkModeOf() {
        int id = watermarkGroup == null ? View.NO_ID : watermarkGroup.getCheckedRadioButtonId();
        if (id == R.id.watermark_space) {
            return ConfigContract.WATERMARK_KEEP_SPACE;
        }
        if (id == R.id.watermark_custom) {
            return ConfigContract.WATERMARK_CUSTOM;
        }
        if (id == R.id.watermark_keep) {
            return ConfigContract.WATERMARK_OFF;
        }
        return ConfigContract.WATERMARK_REMOVE;
    }

    /** The radio button id matching a stored mode. */
    private static int watermarkRadioId(String mode) {
        if (ConfigContract.WATERMARK_KEEP_SPACE.equals(mode)) {
            return R.id.watermark_space;
        }
        if (ConfigContract.WATERMARK_CUSTOM.equals(mode)) {
            return R.id.watermark_custom;
        }
        if (ConfigContract.WATERMARK_OFF.equals(mode)) {
            return R.id.watermark_keep;
        }
        return R.id.watermark_remove;
    }

    private static String storedFormat(ExportOptions.Format format) {
        switch (format) {
            case IMAGE:
                return ConfigContract.FORMAT_IMAGE;
            case NATIVE:
                return ConfigContract.FORMAT_NATIVE;
            case NATIVE_BATCH:
                return ConfigContract.FORMAT_NATIVE_BATCH;
            default:
                return ConfigContract.FORMAT_WORD;
        }
    }

    /** Everything the form says, in the shape the export reads it in. */
    private ExportOptions readOptions() {
        ExportOptions options = new ExportOptions();
        options.format = formatOf();
        options.wordLayout = layoutOf();
        options.background = backgroundOf();
        options.includeRecycled = recycledBox != null && recycledBox.isChecked();
        options.timestampedFolder = stampedBox == null || stampedBox.isChecked();
        options.numberedNames = numberedBox == null || numberedBox.isChecked();
        options.categoryFolders = foldersBox == null || foldersBox.isChecked();
        options.skipExisting = skipBox != null && skipBox.isChecked();
        options.debug = debugBox != null && debugBox.isChecked();
        options.cardStyle = cardBox != null && cardBox.isChecked();
        options.exportAttachments = attachmentsBox != null && attachmentsBox.isChecked();
        // The picker's tick list, which an empty list turns back into "everything".
        options.guids.addAll(NoteSelection.read(this));
        options.limit = 0;
        if (limitBox != null) {
            try {
                options.limit = Math.max(0,
                        Integer.parseInt(limitBox.getText().toString().trim()));
            } catch (NumberFormatException ignored) {
                // an empty or malformed count simply means "all of them"
            }
        }
        return options;
    }

    // -------------------------------------------------------------- persistence

    private void restoreOptions() {
        SharedPreferences prefs = getSharedPreferences(ConfigContract.PREFS, MODE_PRIVATE);
        restoring = true;
        try {
            String format = prefs.getString(ConfigContract.COLUMN_FORMAT,
                    ConfigContract.FORMAT_NATIVE_BATCH);
            String layout = prefs.getString(ConfigContract.COLUMN_WORD_LAYOUT,
                    ConfigContract.LAYOUT_SINGLE);

            check(formatGroup, formatRadioId(format));
            check(layoutGroup, ConfigContract.LAYOUT_PER_NOTE.equals(layout)
                    ? R.id.layout_per_note : R.id.layout_single);
            check(recycledBox, prefs.getBoolean(ConfigContract.COLUMN_INCLUDE_RECYCLED, true));
            check(numberedBox, prefs.getBoolean(ConfigContract.COLUMN_NUMBERED, true));
            check(foldersBox, prefs.getBoolean(ConfigContract.COLUMN_FOLDERS, true));
            check(stampedBox, prefs.getBoolean(ConfigContract.COLUMN_STAMPED, true));
            check(skipBox, prefs.getBoolean(ConfigContract.COLUMN_SKIP, false));
            check(debugBox, prefs.getBoolean(ConfigContract.COLUMN_DEBUG, false));
            check(cardBox, prefs.getBoolean(ConfigContract.COLUMN_CARD_STYLE, false));
            check(attachmentsBox,
                    prefs.getBoolean(ConfigContract.COLUMN_EXPORT_ATTACHMENTS, false));
            if (limitBox != null) {
                limitBox.setText(prefs.getString(ConfigContract.COLUMN_LIMIT, ""));
            }
            check(watermarkGroup, watermarkRadioId(WatermarkSettings.read(this).mode));
            if (watermarkText != null) {
                watermarkText.setText(WatermarkSettings.read(this).text);
            }
        } finally {
            restoring = false;
        }
        refreshWatermarkField();
        refreshBackgroundValue();
    }

    private static int formatRadioId(String format) {
        if (ConfigContract.FORMAT_NATIVE.equals(format)
                || ConfigContract.FORMAT_NATIVE_BATCH.equals(format)) {
            return R.id.format_native;
        }
        if (ConfigContract.FORMAT_IMAGE.equals(format)) {
            return R.id.format_drawn;
        }
        return R.id.format_word;
    }

    /**
     * Writes the form's current state to the stored options.
     *
     * <p>Called from every control's listener, so a choice survives whatever
     * happens to the task afterwards, and again from {@link #onPause()}.
     */
    private void saveOptions() {
        if (restoring || formatGroup == null) {
            return;
        }
        ExportOptions options = readOptions();
        getSharedPreferences(ConfigContract.PREFS, MODE_PRIVATE)
                .edit()
                .putString(ConfigContract.COLUMN_FORMAT, storedFormat(options.format))
                .putString(ConfigContract.COLUMN_WORD_LAYOUT,
                        options.wordLayout == ExportOptions.WordLayout.PER_NOTE
                                ? ConfigContract.LAYOUT_PER_NOTE : ConfigContract.LAYOUT_SINGLE)
                .putBoolean(ConfigContract.COLUMN_INCLUDE_RECYCLED, options.includeRecycled)
                .putBoolean(ConfigContract.COLUMN_NUMBERED, options.numberedNames)
                .putBoolean(ConfigContract.COLUMN_FOLDERS, options.categoryFolders)
                .putBoolean(ConfigContract.COLUMN_STAMPED, options.timestampedFolder)
                .putBoolean(ConfigContract.COLUMN_SKIP, options.skipExisting)
                .putBoolean(ConfigContract.COLUMN_DEBUG, options.debug)
                .putBoolean(ConfigContract.COLUMN_CARD_STYLE, options.cardStyle)
                .putBoolean(ConfigContract.COLUMN_EXPORT_ATTACHMENTS, options.exportAttachments)
                .putString(ConfigContract.COLUMN_LIMIT,
                        limitBox == null ? "" : limitBox.getText().toString().trim())
                // Committed rather than applied: an option is written the moment
                // it is chosen, and a queued write dies with a process that is
                // killed before it flushes — which is exactly how a choice made
                // on this screen was once lost.
                .commit();
        // The watermark travels to the Notes process through the provider and a
        // mirrored file, so both are written here, where the user just decided.
        WatermarkSettings.save(this, watermarkModeOf(),
                watermarkText == null ? "" : watermarkText.getText().toString().trim());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveOptions();
    }

    /** Puts every option back to what this module recommends, and saves it. */
    private void resetOptions() {
        restoring = true;
        try {
            check(formatGroup, R.id.format_native);      // 原版长图
            check(layoutGroup, R.id.layout_single);
            check(watermarkGroup, R.id.watermark_remove);  // 去掉水印
            check(recycledBox, true);
            check(numberedBox, true);
            check(foldersBox, true);
            check(stampedBox, true);
            check(skipBox, false);
            check(debugBox, false);
            check(cardBox, false);
            check(attachmentsBox, false);
            if (limitBox != null) {
                limitBox.setText("");
            }
            if (watermarkText != null) {
                watermarkText.setText("");
            }
            limitBeforeTrial = null;
        } finally {
            restoring = false;
        }
        refreshLayoutVisibility();
        refreshWatermarkField();
        saveOptions();
        setStatus(getString(R.string.status_reset), false);
    }

    private void check(RadioGroup group, int option) {
        if (group != null) {
            group.check(option);
        }
    }

    private void check(CheckBox box, boolean value) {
        if (box != null) {
            box.setChecked(value);
        }
    }

    // -------------------------------------------------------------- the export

    /**
     * Exports a single note so the settings can be judged before a full run.
     *
     * <p>Only the count differs from a normal export, and only for as long as the
     * run lasts: {@link #startExport()} reads the screen into an
     * {@link ExportOptions} before it returns, and the retry path re-reads it
     * later, so the forced count has to stay in the field until the run is over.
     */
    private void exportOne() {
        if (running.get() || limitBox == null) {
            return;
        }
        if (limitBeforeTrial == null) {
            limitBeforeTrial = limitBox.getText().toString();
        }
        limitBox.setText("1");
        setStatus(getString(R.string.status_try_one), false);
        startExport();
    }

    /** Puts the count field back once a trial run has finished. */
    private void endTrial() {
        if (limitBeforeTrial == null) {
            return;
        }
        if (limitBox != null) {
            limitBox.setText(limitBeforeTrial);
        }
        limitBeforeTrial = null;
    }

    private void startExport() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // Everything the form says is written before it travels: a watermark
        // text typed and then exported straight away would otherwise leave with
        // the value from the last time something else was tapped.
        saveOptions();
        ExportOptions options = readOptions();
        // The options travel in the query itself. Writing them to a file in
        // Downloads used to look simpler, but this module holds no storage
        // permission, so on Android 10+ that write is refused and the export
        // silently fell back to the defaults.
        ExportRequest.write(options);

        setStatus(getString(R.string.status_asking), false);
        progressStartedAt = 0;
        progressStartedDone = 0;
        setExporting(true);
        attemptsLeft = 2;

        if (options.format == ExportOptions.Format.NATIVE
                || options.format == ExportOptions.Format.NATIVE_BATCH) {
            // The app draws these pictures itself, and Android only lets a
            // foreground app open its own screens — so the Notes app is brought
            // up first and asked a moment later.
            setStatus(getString(R.string.status_opening_notes), false);
            openNotesApp();
            handler.removeCallbacks(progressPoll);
            handler.post(progressPoll);
            handler.postDelayed(() -> queryNotes(options), 3000);
            return;
        }
        handler.removeCallbacks(progressPoll);
        handler.post(progressPoll);
        queryNotes(options);
    }

    /**
     * Asks the Notes process to export.
     *
     * <p>Querying the provider cold-starts the Notes app, so the first attempt
     * on a cold app can take a while; a second attempt covers the case where
     * the injection was not ready during the very first start.
     */
    private void queryNotes(final ExportOptions options) {
        final String[] authorities = ConfigContract.NOTES_AUTHORITIES;
        new Thread(() -> {
            String message = null;
            boolean ok = false;
            byte[] thumbnail = null;
            for (String authority : authorities) {
                Uri uri = ConfigContract.exportUri(authority);
                Cursor cursor = null;
                try {
                    Log.i(TAG, "querying " + uri + " " + ExportRequest.toSelection(options));
                    cursor = getContentResolver().query(uri, null,
                            ExportRequest.toSelection(options), null, null);
                    if (cursor == null) {
                        continue;
                    }
                    if (cursor.moveToFirst()) {
                        int okColumn = cursor.getColumnIndex("ok");
                        int messageColumn = cursor.getColumnIndex("message");
                        int pathColumn = cursor.getColumnIndex("path");
                        int thumbColumn = cursor.getColumnIndex("thumb");
                        ok = okColumn >= 0 && cursor.getInt(okColumn) != 0;
                        message = messageColumn >= 0 ? cursor.getString(messageColumn) : null;
                        String path = pathColumn >= 0 ? cursor.getString(pathColumn) : null;
                        // A blob column answers with null rather than throwing, and
                        // a provider that does not have one simply has no column.
                        thumbnail = thumbColumn >= 0 && !cursor.isNull(thumbColumn)
                                ? cursor.getBlob(thumbColumn) : null;
                        if (ok && !TextUtils.isEmpty(path)) {
                            message = (message == null ? "导出完成" : message)
                                    + "\n位置：" + path;
                        }
                        break;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "query to " + authority + " failed: " + t);
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
            publish(message, ok, thumbnail);
        }, "note-export-trigger").start();
    }

    private void publish(final String message, final boolean ok, final byte[] thumbnail) {
        handler.post(() -> {
            showPreview(thumbnail);
            if (message != null) {
                boolean trial = limitBeforeTrial != null;
                endTrial();
                setStatus(trial ? message + "\n" + getString(R.string.status_try_one_done)
                        : message, !ok);
                finishExport();
                return;
            }
            attemptsLeft--;
            if (attemptsLeft > 0) {
                // Most likely the Notes app was cold and the module had not
                // injected yet; opening it and asking again usually works.
                setStatus(getString(R.string.status_retrying), false);
                openNotesApp();
                handler.postDelayed(() -> queryNotes(readOptions()), 3000);
                return;
            }
            endTrial();
            setStatus(getString(R.string.status_no_response, TAG.trim()), true);
            finishExport();
        });
    }

    /** Takes the bar away and lets the screen be used again. */
    private void finishExport() {
        handler.removeCallbacks(progressPoll);
        running.set(false);
        setExporting(false);
    }

    private void openNotesApp() {
        try {
            Intent intent = getPackageManager()
                    .getLaunchIntentForPackage(ConfigContract.NOTES_PKG);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not open the Notes app: " + t);
        }
    }
}
