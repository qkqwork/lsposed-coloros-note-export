package com.qkqwork.noteexport;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The module's only screen: pick a format, press export, read the result.
 *
 * <p>There is deliberately no note list here — the module cannot read the Notes
 * database from its own process, so it does not pretend to know what will be
 * exported. It hands the request to the Notes process and reports what came
 * back.
 */
public class ConfigActivity extends Activity {

    private static final String TAG = Main.TAG;

    private RadioGroup formatGroup;
    private RadioGroup layoutGroup;
    private CheckBox recycledBox;
    private TextView statusView;
    private Button exportButton;
    private LinearLayout layoutOptions;
    private RadioGroup watermarkGroup;
    private EditText watermarkText;
    /** What a long picture is drawn on: automatic, always white or always dark. */
    private RadioGroup backgroundGroup;
    /** How many notes to export, and how the files are named. */
    private EditText limitBox;
    private CheckBox numberedBox;
    private CheckBox foldersBox;
    private CheckBox stampedBox;
    private CheckBox skipBox;
    private CheckBox debugBox;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int attemptsLeft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);
        bindViews();
        restoreOptions();
        refreshLayoutVisibility();
    }

    // ------------------------------------------------------------------ the UI

    private View buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // Kept in step with app_name in res/values/strings.xml by hand: that is
        // the name LSPosed and the launcher show, and this is the same words on
        // the screen it opens.
        root.addView(title("ColorOS Note Export"));
        root.addView(hint("在 LSPosed 中启用本模块，作用域勾选「便签」，"
                + "然后冷启动一次便签应用。"));

        root.addView(section("导出为"));
        formatGroup = new RadioGroup(this);
        RadioButton word = radio("Word 文档（.docx）", 1);
        RadioButton nativeImage = radio("原版长图（便签应用自己渲染，推荐）", 2);
        RadioButton drawnImage = radio("长图（模块绘制，版式与便签不同）", 3);        formatGroup.addView(word);
        formatGroup.addView(nativeImage);
        formatGroup.addView(drawnImage);
        root.addView(formatGroup);

        layoutOptions = new LinearLayout(this);
        layoutOptions.setOrientation(LinearLayout.VERTICAL);
        layoutOptions.addView(section("Word 组织方式"));
        layoutGroup = new RadioGroup(this);
        RadioButton single = radio("一个汇总文档（全部分类在一个 .docx 内）", 1);
        RadioButton perNote = radio("每条便签一个文档（按分类分文件夹）", 2);
        layoutGroup.addView(single);
        layoutGroup.addView(perNote);
        single.setChecked(true);
        layoutOptions.addView(layoutGroup);
        root.addView(layoutOptions);

        root.addView(section("选项"));
        recycledBox = new CheckBox(this);
        recycledBox.setText("包含回收站中的便签");
        recycledBox.setChecked(true);
        root.addView(recycledBox);

        limitBox = new EditText(this);
        limitBox.setHint("导出条数（0 或留空 = 全部）");
        limitBox.setSingleLine(true);
        limitBox.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(limitBox);
        root.addView(hint("先用 1–2 条试格式，确认满意再跑全部。"));

        numberedBox = new CheckBox(this);
        numberedBox.setText("文件名带序号（001_标题.png，顺序与便签一致）");
        numberedBox.setChecked(true);
        root.addView(numberedBox);

        foldersBox = new CheckBox(this);
        foldersBox.setText("按分类分文件夹");
        foldersBox.setChecked(true);
        root.addView(foldersBox);

        stampedBox = new CheckBox(this);
        stampedBox.setText("每次导出放到新的时间戳文件夹");
        stampedBox.setChecked(true);
        root.addView(stampedBox);

        skipBox = new CheckBox(this);
        skipBox.setText("跳过已存在的文件（配合上面取消勾选，可接着上次继续导出）");
        root.addView(skipBox);

        debugBox = new CheckBox(this);
        debugBox.setText("开启调试日志（详细诊断，导出很多次都不用开）");
        root.addView(debugBox);
        root.addView(hint("调试日志会挂上十几个探针并把便签内部的方法清单打进 logcat，"
                + "只在排查问题时勾选。"));

        root.addView(section("长图底色"));
        root.addView(hint("便签应用把长图的纸面交出来时是不带底色的，字画在透明底上。"
                + "「黑底白字」是应用自己导出时的样子（默认）；「白底」会把白字重画成黑字；"
                + "「自动」按便签的字色选，若你把系统切成浅色主题、字变黑了，自动就会给白底。"
                + "注意：白底或黑底与照片里的大片同色区域无法区分，那一块会融进底色。"));
        backgroundGroup = new RadioGroup(this);
        RadioButton darkBackground = radio("一律黑底白字（默认）", 3);
        RadioButton whiteBackground = radio("一律白底（白字自动转黑）", 2);
        RadioButton autoBackground = radio("自动（跟着便签的字色，浅色主题下自动白底）", 1);
        backgroundGroup.addView(darkBackground);
        backgroundGroup.addView(whiteBackground);
        backgroundGroup.addView(autoBackground);
        darkBackground.setChecked(true);
        root.addView(backgroundGroup);

        // ------------------------------------------------------- the watermark

        root.addView(section("分享长图的水印"));
        root.addView(hint("便签自己「分享为图片」时，长图底部会带 ColorOS 水印。"
                + "这里决定怎么处理它 —— 这一项在便签应用里生效，导出产物不涉及水印。"));

        watermarkGroup = new RadioGroup(this);
        RadioButton removeWatermark = watermarkRadio("去掉水印（推荐）", 1);
        RadioButton keepSpace = watermarkRadio("去掉文字但保留原高度（底部留白）", 2);
        RadioButton customWatermark = watermarkRadio("换成我自己的文字", 3);
        RadioButton keepWatermark = watermarkRadio("不动它", 4);
        watermarkGroup.addView(removeWatermark);
        watermarkGroup.addView(keepSpace);
        watermarkGroup.addView(customWatermark);
        watermarkGroup.addView(keepWatermark);
        root.addView(watermarkGroup);

        watermarkText = new EditText(this);
        watermarkText.setHint("自定义文字，例如自己的昵称");
        watermarkText.setSingleLine(true);
        root.addView(watermarkText);

        exportButton = new Button(this);
        exportButton.setText("开始导出");
        exportButton.setOnClickListener(view -> startExport());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(16);
        root.addView(exportButton, buttonParams);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setPadding(0, dp(16), 0, 0);
        statusView.setTextIsSelectable(true);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        // Listeners are attached only once the whole layout exists: setting a
        // radio button's checked state fires its listener immediately, and a
        // listener that touches a view built later would crash on start-up.
        word.setChecked(true);
        formatGroup.setOnCheckedChangeListener((group, checked) -> refreshLayoutVisibility());
        watermarkGroup.setOnCheckedChangeListener((group, checked) -> refreshLayoutVisibility());
        return scroll;
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setPadding(0, dp(16), 0, dp(4));
        return view;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(Color.GRAY);
        return view;
    }

    private RadioButton radio(String text, int id) {
        RadioButton button = new RadioButton(this);
        button.setText(text);
        button.setId(id);
        return button;
    }

    private RadioButton watermarkRadio(String text, int id) {
        return radio(text, id);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshLayoutVisibility() {
        if (formatGroup == null || layoutOptions == null) {
            // Called before the rest of the layout exists — the format group's
            // listener fires while it is still being built.
            return;
        }
        boolean word = formatGroup.getCheckedRadioButtonId() == 1;
        layoutOptions.setVisibility(word ? View.VISIBLE : View.GONE);
        if (watermarkText != null) {
            watermarkText.setEnabled(watermarkModeOf(watermarkGroup) != null
                    && ConfigContract.WATERMARK_CUSTOM.equals(watermarkModeOf(watermarkGroup)));
        }
    }

    /** The mode the watermark radio group currently selects. */
    private static String watermarkModeOf(RadioGroup group) {
        if (group == null) {
            return ConfigContract.WATERMARK_REMOVE;
        }
        switch (group.getCheckedRadioButtonId()) {
            case 2:
                return ConfigContract.WATERMARK_KEEP_SPACE;
            case 3:
                return ConfigContract.WATERMARK_CUSTOM;
            case 4:
                return ConfigContract.WATERMARK_OFF;
            default:
                return ConfigContract.WATERMARK_REMOVE;
        }
    }

    /** The radio button id matching a stored mode. */
    private static int watermarkRadioId(String mode) {
        if (ConfigContract.WATERMARK_KEEP_SPACE.equals(mode)) {
            return 2;
        }
        if (ConfigContract.WATERMARK_CUSTOM.equals(mode)) {
            return 3;
        }
        if (ConfigContract.WATERMARK_OFF.equals(mode)) {
            return 4;
        }
        return 1;
    }

    // -------------------------------------------------------------- the export

    private ExportOptions readOptions() {
        ExportOptions options = new ExportOptions();
        switch (formatGroup.getCheckedRadioButtonId()) {
            case 2:
                // The app draws these itself, one note at a time, driven from
                // inside its own process; the older share-screen route is kept
                // for reference only and is not offered here any more.
                options.format = ExportOptions.Format.NATIVE_BATCH;
                break;
            case 3:
                options.format = ExportOptions.Format.IMAGE;
                break;
            default:
                options.format = ExportOptions.Format.WORD;
                break;
        }
        options.wordLayout = layoutGroup.getCheckedRadioButtonId() == 2
                ? ExportOptions.WordLayout.PER_NOTE : ExportOptions.WordLayout.SINGLE;
        options.includeRecycled = recycledBox.isChecked();
        options.timestampedFolder = stampedBox == null || stampedBox.isChecked();
        options.numberedNames = numberedBox == null || numberedBox.isChecked();
        options.categoryFolders = foldersBox == null || foldersBox.isChecked();
        options.skipExisting = skipBox != null && skipBox.isChecked();
        options.debug = debugBox != null && debugBox.isChecked();
        options.limit = 0;
        if (limitBox != null) {
            try {
                options.limit = Math.max(0,
                        Integer.parseInt(limitBox.getText().toString().trim()));
            } catch (NumberFormatException ignored) {
                // an empty or malformed count simply means "all of them"
            }
        }
        switch (backgroundGroup.getCheckedRadioButtonId()) {
            case 2:
                options.background = ExportOptions.Background.WHITE;
                break;
            case 3:
                options.background = ExportOptions.Background.DARK;
                break;
            default:
                options.background = ExportOptions.Background.AUTO;
                break;
        }
        return options;
    }

    private void startExport() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ExportOptions options = readOptions();
        // The options travel in the query itself. Writing them to a file in
        // Downloads used to look simpler, but this module holds no storage
        // permission, so on Android 10+ that write is refused and the export
        // silently fell back to the defaults.
        ExportRequest.write(options);

        setStatus("正在通知便签导出…（若便签未运行，首次启动可能需要几秒）", false);
        exportButton.setEnabled(false);
        attemptsLeft = 2;

        if (options.format == ExportOptions.Format.NATIVE
                || options.format == ExportOptions.Format.NATIVE_BATCH) {
            // The app draws these pictures itself, and Android only lets a
            // foreground app open its own screens — so the Notes app is brought
            // up first and asked a moment later.
            setStatus("正在打开便签应用…（原版长图需要便签自己来画）", false);
            openNotesApp();
            handler.postDelayed(() -> queryNotes(options), 3000);
            return;
        }
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
                        ok = okColumn >= 0 && cursor.getInt(okColumn) != 0;
                        message = messageColumn >= 0 ? cursor.getString(messageColumn) : null;
                        String path = pathColumn >= 0 ? cursor.getString(pathColumn) : null;
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
            publish(message, ok);
        }, "note-export-trigger").start();
    }

    private void publish(final String message, final boolean ok) {
        handler.post(() -> {
            if (message != null) {
                setStatus(message, !ok);
                running.set(false);
                exportButton.setEnabled(true);
                return;
            }
            attemptsLeft--;
            if (attemptsLeft > 0) {
                // Most likely the Notes app was cold and the module had not
                // injected yet; opening it and asking again usually works.
                setStatus("便签未响应，正在启动便签应用后重试…", false);
                openNotesApp();
                handler.postDelayed(() -> queryNotes(readOptions()), 3000);
                return;
            }
            setStatus("便签未响应。请确认：\n"
                    + "1) 模块已在 LSPosed 中启用，作用域包含「便签」；\n"
                    + "2) 至少冷启动过一次便签应用；\n"
                    + "3) 在 LSPosed 日志中搜索 " + TAG.trim() + " 查看注入情况。", true);
            running.set(false);
            exportButton.setEnabled(true);
        });
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

    private void setStatus(String message, boolean error) {
        statusView.setTextColor(error ? 0xFFB00020 : 0xFF00695C);
        statusView.setText(message);
    }

    // -------------------------------------------------------------- persistence

    private void restoreOptions() {
        Context context = this;
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                ConfigContract.PREFS, MODE_PRIVATE);
        String format = prefs.getString(ConfigContract.COLUMN_FORMAT,
                ConfigContract.FORMAT_WORD);
        String layout = prefs.getString(ConfigContract.COLUMN_WORD_LAYOUT,
                ConfigContract.LAYOUT_SINGLE);
        int formatId = 1;
        if (ConfigContract.FORMAT_NATIVE.equals(format)
                || ConfigContract.FORMAT_NATIVE_BATCH.equals(format)) {
            formatId = 2;
        } else if (ConfigContract.FORMAT_IMAGE.equals(format)) {
            formatId = 3;
        }
        formatGroup.check(formatId);
        layoutGroup.check(ConfigContract.LAYOUT_PER_NOTE.equals(layout) ? 2 : 1);
        recycledBox.setChecked(prefs.getBoolean(ConfigContract.COLUMN_INCLUDE_RECYCLED, true));
        String background = prefs.getString(ConfigContract.COLUMN_BACKGROUND,
                ConfigContract.BACKGROUND_DARK);
        int backgroundId = 3;
        if (ConfigContract.BACKGROUND_WHITE.equals(background)) {
            backgroundId = 2;
        } else if (ConfigContract.BACKGROUND_AUTO.equals(background)) {
            backgroundId = 1;
        }
        backgroundGroup.check(backgroundId);

        String limit = prefs.getString(ConfigContract.COLUMN_LIMIT, "");
        if (limitBox != null) {
            limitBox.setText(limit);
        }
        if (numberedBox != null) {
            numberedBox.setChecked(prefs.getBoolean(ConfigContract.COLUMN_NUMBERED, true));
        }
        if (foldersBox != null) {
            foldersBox.setChecked(prefs.getBoolean(ConfigContract.COLUMN_FOLDERS, true));
        }
        if (stampedBox != null) {
            stampedBox.setChecked(prefs.getBoolean(ConfigContract.COLUMN_STAMPED, true));
        }
        if (skipBox != null) {
            skipBox.setChecked(prefs.getBoolean(ConfigContract.COLUMN_SKIP, false));
        }
        if (debugBox != null) {
            debugBox.setChecked(prefs.getBoolean(ConfigContract.COLUMN_DEBUG, false));
        }

        WatermarkSettings watermark = WatermarkSettings.read(this);
        watermarkGroup.check(watermarkRadioId(watermark.mode));
        watermarkText.setText(watermark.text);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ExportOptions options = readOptions();
        getSharedPreferences(ConfigContract.PREFS, MODE_PRIVATE)
                .edit()
                .putString(ConfigContract.COLUMN_FORMAT,
                        readOptions().format == ExportOptions.Format.IMAGE
                                ? ConfigContract.FORMAT_IMAGE
                                : readOptions().format == ExportOptions.Format.NATIVE
                                        ? ConfigContract.FORMAT_NATIVE
                                        : readOptions().format == ExportOptions.Format.NATIVE_BATCH
                                                ? ConfigContract.FORMAT_NATIVE_BATCH
                                                : ConfigContract.FORMAT_WORD)
                .putString(ConfigContract.COLUMN_WORD_LAYOUT,
                        options.wordLayout == ExportOptions.WordLayout.PER_NOTE
                                ? ConfigContract.LAYOUT_PER_NOTE : ConfigContract.LAYOUT_SINGLE)
                .putBoolean(ConfigContract.COLUMN_INCLUDE_RECYCLED, options.includeRecycled)
                .putString(ConfigContract.COLUMN_BACKGROUND,
                        options.background == ExportOptions.Background.WHITE
                                ? ConfigContract.BACKGROUND_WHITE
                                : options.background == ExportOptions.Background.DARK
                                        ? ConfigContract.BACKGROUND_DARK
                                        : ConfigContract.BACKGROUND_AUTO)
                .putString(ConfigContract.COLUMN_LIMIT,
                        limitBox == null ? "" : limitBox.getText().toString().trim())
                .putBoolean(ConfigContract.COLUMN_NUMBERED, options.numberedNames)
                .putBoolean(ConfigContract.COLUMN_FOLDERS, options.categoryFolders)
                .putBoolean(ConfigContract.COLUMN_STAMPED, options.timestampedFolder)
                .putBoolean(ConfigContract.COLUMN_SKIP, options.skipExisting)
                .putBoolean(ConfigContract.COLUMN_DEBUG, options.debug)
                .apply();
        // The watermark travels to the Notes process through the provider and a
        // mirrored file, so both are written here, where the user just decided.
        WatermarkSettings.save(this, watermarkModeOf(watermarkGroup),
                watermarkText == null ? "" : watermarkText.getText().toString().trim());
    }

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
}
