package com.dsh.noteexport;

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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int attemptsLeft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        restoreOptions();
        refreshLayoutVisibility();
    }

    // ------------------------------------------------------------------ the UI

    private View buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(title("便签批量导出"));
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
        options.timestampedFolder = true;
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
                .apply();
        // The watermark travels to the Notes process through the provider and a
        // mirrored file, so both are written here, where the user just decided.
        WatermarkSettings.save(this, watermarkModeOf(watermarkGroup),
                watermarkText == null ? "" : watermarkText.getText().toString().trim());
    }
}
