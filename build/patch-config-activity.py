"""Switches ConfigActivity onto the XML layout, and adds the two new buttons.

    python build/patch-config-activity.py

The screen used to be built in Java, view by view. It now inflates
res/layout/activity_config.xml and looks its controls up by id. The option radio
buttons are given the small numeric ids the reading code already expects, because
those ids double as the persisted option values; replacing them with R.id
comparisons is a separate, later change.

buildUi() and its helpers are left in place but unused for now: deleting them
belongs with the change that also removes the numeric ids.
"""
import re
import sys
from pathlib import Path

path = Path('src/com/qkqwork/noteexport/ConfigActivity.java')
text = path.read_text(encoding='utf-8')

old_start = '        setContentView(buildUi());'
if old_start not in text:
    print('the screen is not built the old way any more: %s not found' % old_start)
    sys.exit(1)
text = text.replace(old_start,
                    '        setContentView(R.layout.activity_config);\n'
                    '        bindViews();')

new_methods = '''
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
        backgroundGroup = findViewById(R.id.background_group);
        watermarkGroup = findViewById(R.id.watermark_group);
        watermarkText = findViewById(R.id.watermark_text);
        exportButton = findViewById(R.id.action_export);
        statusView = findViewById(R.id.status);

        // The ids below are the values the screen reads back and stores, so they
        // are kept as they were rather than replaced with the layout's own ids.
        option(R.id.format_word, 1);
        option(R.id.format_native, 2);
        option(R.id.format_drawn, 3);
        option(R.id.layout_single, 1);
        option(R.id.layout_per_note, 2);
        option(R.id.background_auto, 1);
        option(R.id.background_white, 2);
        option(R.id.background_dark, 3);
        option(R.id.watermark_remove, 1);
        option(R.id.watermark_space, 2);
        option(R.id.watermark_custom, 3);
        option(R.id.watermark_keep, 4);

        if (formatGroup != null) {
            formatGroup.setOnCheckedChangeListener((group, checked) -> refreshLayoutVisibility());
        }
        if (watermarkGroup != null) {
            watermarkGroup.setOnCheckedChangeListener((group, checked) -> refreshWatermarkField());
        }
        if (exportButton != null) {
            exportButton.setOnClickListener(view -> startExport());
        }
        View openFolder = findViewById(R.id.action_open_folder);
        if (openFolder != null) {
            openFolder.setOnClickListener(view -> openExportFolder());
        }
        View reset = findViewById(R.id.action_reset);
        if (reset != null) {
            reset.setOnClickListener(view -> resetOptions());
        }
    }

    /** Re-ids a radio button to the value the reading code expects. */
    private void option(int viewId, int value) {
        View view = findViewById(viewId);
        if (view != null) {
            view.setId(value);
        }
    }

    /** The custom watermark's text field only matters in that one mode. */
    private void refreshWatermarkField() {
        if (watermarkText == null || watermarkGroup == null) {
            return;
        }
        watermarkText.setEnabled(watermarkGroup.getCheckedRadioButtonId() == 3);
    }

    /** Opens the folder the exports land in, the way a file manager would. */
    private void openExportFolder() {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3ADownload"));
            intent.setType("resource/folder");
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            // A device without a file manager still gets told where the files are.
            setStatus(getString(R.string.status_no_file_manager), false);
        }
    }

    /** Puts every option back to what this module recommends, and saves it. */
    private void resetOptions() {
        check(formatGroup, 2);            // 原版长图
        check(layoutGroup, 1);
        check(backgroundGroup, 3);        // 一律黑底白字
        check(watermarkGroup, 1);         // 去掉水印
        set(recycledBox, true);
        set(numberedBox, true);
        set(foldersBox, true);
        set(stampedBox, true);
        set(skipBox, false);
        set(debugBox, false);
        if (limitBox != null) {
            limitBox.setText("");
        }
        if (watermarkText != null) {
            watermarkText.setText("");
        }
        refreshLayoutVisibility();
        refreshWatermarkField();
        getSharedPreferences(ConfigContract.PREFS, MODE_PRIVATE).edit()
                .putString(ConfigContract.COLUMN_FORMAT, ConfigContract.FORMAT_NATIVE_BATCH)
                .putString(ConfigContract.COLUMN_WORD_LAYOUT, ConfigContract.LAYOUT_SINGLE)
                .putString(ConfigContract.COLUMN_BACKGROUND, ConfigContract.BACKGROUND_DARK)
                .putString(ConfigContract.COLUMN_WATERMARK_MODE, ConfigContract.WATERMARK_REMOVE)
                .putString(ConfigContract.COLUMN_WATERMARK_TEXT, "")
                .putBoolean(ConfigContract.COLUMN_INCLUDE_RECYCLED, true)
                .putBoolean(ConfigContract.COLUMN_NUMBERED, true)
                .putBoolean(ConfigContract.COLUMN_FOLDERS, true)
                .putBoolean(ConfigContract.COLUMN_STAMPED, true)
                .putBoolean(ConfigContract.COLUMN_SKIP, false)
                .putBoolean(ConfigContract.COLUMN_DEBUG, false)
                .putString(ConfigContract.COLUMN_LIMIT, "")
                .apply();
        setStatus("已恢复默认设置。", true);
    }

    private void check(RadioGroup group, int option) {
        if (group != null) {
            group.check(option);
        }
    }

    private void set(CheckBox box, boolean value) {
        if (box != null) {
            box.setChecked(value);
        }
    }
'''

marker = text.rstrip()
if not marker.endswith('}'):
    print('the file does not end with a closing brace')
    sys.exit(1)
text = marker[:-1].rstrip('\n') + '\n' + new_methods + '}\n'
path.write_text(text, encoding='utf-8', newline='')
print('ConfigActivity now inflates the layout and has the two new buttons')
