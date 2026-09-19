package com.qkqwork.noteexport;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.ListView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Tries to export every note exactly as the Notes app draws it.
 *
 * <p>What the probes established, and why this is shaped the way it is:
 *
 * <ul>
 *   <li>the long picture is produced by the <b>note editor</b>
 *       ({@code WVNoteViewEditFragment} → {@code WVCaptureScreenHelper.captureWebView}),
 *       which puts the finished image in a cache;</li>
 *   <li>the share screen is only a <em>consumer</em>: it reads that cache
 *       ({@code getCaptureBitmap}) and writes the file. Started on its own it has
 *       nothing to read, which is why it sat on "正在生成" forever;</li>
 *   <li>the editor is opened with a whole {@code NoteBinder} object plus two
 *       internal lambdas, so it cannot simply be handed a note id from outside.</li>
 * </ul>
 *
 * <p>So this drives the app's own route instead of rebuilding it: open the note
 * from the list (the list's own item-click handler does the work of building the
 * editor's arguments), let the editor load, ask its fragment to run the picture
 * capture, and copy the image the app saves. Every stage is logged and a failure
 * stops the run, so a wrong assumption shows up as one clear line rather than as
 * a pile of notes that each took twenty-five seconds to fail.
 *
 * <p>Nothing here is a re-render: the picture copied is the app's own output.
 */
final class NativeBatchExport {

    private static final String TAG = Main.TAG;

    private static final String LIST_ACTIVITY = "com.nearme.note.main.MainActivity";
    private static final String EDITOR_ACTIVITY =
            "com.nearme.note.activity.richedit.NoteViewRichEditActivity";
    private static final String EDITOR_FRAGMENT =
            "com.nearme.note.activity.richedit.webview.WVNoteViewEditFragment";
    private static final String SCREEN_SHOT_UTILS = "com.nearme.note.util.ScreenShotUtils";
    private static final String CAPTURE_HELPER =
            "com.nearme.note.activity.richedit.webview.WVCaptureScreenHelper";
    private static final String SHARE_SCREEN = "com.nearme.note.activity.edit.SaveImageAndShare";
    private static final String CAPTURE_UTILS = "com.nearme.note.util.CaptureScreenUtils";

    private static final long LIST_WAIT_SECONDS = 20;
    private static final long EDITOR_WAIT_SECONDS = 20;
    private static final long CAPTURE_WAIT_SECONDS = 90;
    private static final long SETTLE_MILLIS = 700;

    /**
     * What a dark note is read on. Measured from the picture the app itself drew
     * for one of this device's notes: its background is 13,13,13.
     */
    private static final int DARK_NOTE_BACKGROUND = 0xFF0D0D0D;

    /** The note being captured, or null when no batch is running. */
    private static volatile Note pending;
    /** The pages the app rendered for that note, in the order they arrived. */
    private static final List<Bitmap> pages = new ArrayList<>();
    /** The picture the app merged its pages into, when it merges them at all. */
    private static volatile Bitmap merged;
    /** Height of the page that was stacked last, which bounds the tail trimming. */
    private static volatile int lastPageHeight;
    /** The editor screen that opened, so it can be closed again. */
    private static volatile Activity editor;
    /** Bumped per note so a late save from the previous one is ignored. */
    private static volatile int round;
    /** The hooked app's class loader, used to reach its Kotlin types. */
    private static volatile ClassLoader appLoader;
    /** The screen the app resumed last, which is the one the batch works from. */
    private static volatile Activity resumed;

    private NativeBatchExport() {
    }

    static void install(ClassLoader loader) {
        appLoader = loader;
        // The app's own "write the picture out" step. Copying it here is the whole
        // point: the bytes are the app's, not a re-render.
        XC_MethodHook copier = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 1 && param.args[1] instanceof String) {
                    Log.i(TAG, "native batch: the app writes " + param.args[1]);
                }
                if (param.args.length > 0) {
                    collect(param.args[0]);
                }
            }
        };
        int copied = Hooks.hookMethodsStartingWith(SCREEN_SHOT_UTILS, loader, "saveBitmap",
                copier);
        copied += Hooks.hookMethodsStartingWith(CAPTURE_HELPER, loader, "saveBitmap", copier);
        // When the app merges the pages itself, its picture is the finished long
        // image and is used as it is. The merge lives in CaptureScreenUtils, not
        // in ScreenShotUtils, which is where the name suggests it should be.
        copied += Hooks.hookMethodsNamed(CAPTURE_UTILS, loader,
                "mergeAndSaveImagesAsLongBitmap", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (pending != null && result instanceof Bitmap) {
                            Bitmap picture = (Bitmap) result;
                            Log.i(TAG, "native batch: the app merged a " + picture.getWidth()
                                    + "x" + picture.getHeight() + " picture");
                            merged = picture;
                        }
                    }
                });

        Hooks.hookMethodsNamed(EDITOR_ACTIVITY, loader, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Activity) {
                    editor = (Activity) param.thisObject;
                    Log.i(TAG, "native batch: the editor opened");
                }
            }
        }, true);
        // The framework's own onResume, reached through the list activity's
        // hierarchy, is the reliable way to know which screen is in front:
        // reading ActivityThread's internal map sometimes comes back empty.
        int resumers = Hooks.hookMethodsNamed(LIST_ACTIVITY, loader, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity) {
                            resumed = (Activity) param.thisObject;
                        }
                    }
                });
        Log.i(TAG, "native batch: tracking " + resumers + " onResume method(s)");
        Hooks.hookMethodsNamed(EDITOR_ACTIVITY, loader, "onDestroy", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                editor = null;
            }
        }, true);
        Log.i(TAG, "native batch: hooked " + copied + " save method(s) and the editor");
    }

    /**
     * Keeps every page the app rendered for the note being captured.
     *
     * <p>The editor does not draw one tall bitmap: it captures the note in
     * slices and hands them on one by one, and the slices tile the note exactly
     * (their heights add up to the editor height the app measured). The same
     * bitmap can be reported twice, once by the producer and once by whoever
     * writes it out, so a page is kept only the first time it is seen.
     */
    private static void collect(Object candidate) {
        if (pending == null || !(candidate instanceof Bitmap)) {
            return;
        }
        Bitmap bitmap = (Bitmap) candidate;
        synchronized (pages) {
            for (Bitmap seen : pages) {
                if (seen == bitmap) {
                    return;
                }
            }
            pages.add(bitmap);
        }
        Log.i(TAG, "native batch: the app rendered a " + bitmap.getWidth() + "x"
                + bitmap.getHeight() + " page, " + describePixels(bitmap));
    }

    /**
     * What a page is made of, sampled rather than scanned in full.
     *
     * <p>A page whose text is drawn in the colour of its own background looks
     * empty, and a page whose background is see-through only shows its pictures
     * once it is composited on the right colour. Counting transparent, light and
     * dark pixels tells the two apart.
     */
    private static String describePixels(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int step = Math.max(1, width / 120);
            int[] row = new int[width];
            long transparent = 0;
            long light = 0;
            long dark = 0;
            long total = 0;
            for (int y = 0; y < height; y += Math.max(1, height / 120)) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x += step) {
                    int pixel = row[x];
                    total++;
                    int alpha = (pixel >>> 24) & 0xFF;
                    if (alpha < 16) {
                        transparent++;
                        continue;
                    }
                    int luminance = ((pixel >> 16 & 0xFF) * 299 + (pixel >> 8 & 0xFF) * 587
                            + (pixel & 0xFF) * 114) / 1000;
                    if (luminance >= 235) {
                        light++;
                    } else if (luminance <= 60) {
                        dark++;
                    }
                }
            }
            return String.format(java.util.Locale.US,
                    "alpha=%s transparent=%.1f%% light=%.1f%% dark=%.1f%% (of %d samples)",
                    bitmap.hasAlpha(), transparent * 100.0 / total, light * 100.0 / total,
                    dark * 100.0 / total, total);
        } catch (Throwable t) {
            return "pixels could not be read: " + t;
        }
    }

    /**
     * The note as one picture.
     *
     * <p>The editor hands out the note in slices that tile it exactly, and their
     * heights add up to the editor height the app itself measured, so stacking
     * them in the order they arrived reproduces the note. If the app ever merges
     * the slices itself, that finished picture is used instead.
     */
    private static Bitmap combined(int background) {
        List<Bitmap> captured;
        synchronized (pages) {
            captured = new ArrayList<>(pages);
        }
        Bitmap appPicture = merged;
        if (appPicture != null) {
            Log.i(TAG, "native batch: the app merged its pages into one picture");
            captured = new ArrayList<>();
            captured.add(appPicture);
        }
        if (captured.isEmpty()) {
            return null;
        }
        lastPageHeight = captured.get(captured.size() - 1).getHeight();
        int width = 0;
        int height = 0;
        for (Bitmap page : captured) {
            width = Math.max(width, page.getWidth());
            height += page.getHeight();
        }
        // The pages themselves are still in memory while the note is stacked, so
        // a very long note is stacked with half the bytes per pixel: a 12,000 px
        // note already needs about 60 MB at full colour.
        Bitmap.Config config = height > 12000 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        Bitmap stitched;
        try {
            stitched = Bitmap.createBitmap(width, height, config);
        } catch (Throwable t) {
            Log.w(TAG, "native batch: the note could not be stacked: " + t);
            Bitmap tallest = captured.get(0);
            for (Bitmap page : captured) {
                if (page.getHeight() > tallest.getHeight()) {
                    tallest = page;
                }
            }
            return composited(tallest, background);
        }
        Canvas canvas = new Canvas(stitched);
        // The pages are see-through: the editor renders white text on nothing and
        // the app shows them over the colour of the note's background. Stacking
        // them on white instead is what made a dark note's text disappear.
        canvas.drawColor(background);
        int top = 0;
        for (Bitmap page : captured) {
            canvas.drawBitmap(page, 0, top, null);
            top += page.getHeight();
        }
        Log.i(TAG, "native batch: stacked " + captured.size() + " pages into " + width + "x"
                + height + " on 0x" + Integer.toHexString(background));
        // Not trimmed here: text that is the same colour as the background is
        // invisible until it has been repainted, and trimming first would cut it
        // off as if the note ended there. The caller repaints, then trims.
        return stitched;
    }

    /** Puts a single see-through page on the note's background. */
    private static Bitmap composited(Bitmap page, int background) {
        if (!page.hasAlpha()) {
            return page;
        }
        Bitmap flat = Bitmap.createBitmap(page.getWidth(), page.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flat);
        canvas.drawColor(background);
        canvas.drawBitmap(page, 0, 0, null);
        return flat;
    }

    /**
     * The colour a note's see-through pages have to be stacked on.
     *
     * <p>The editor hands its pages over with the text drawn and no background at
     * all, so the pages themselves say which way round the note is meant to be
     * read: the text is the short strokes, and its colour decides the backdrop.
     * The caller can also insist on white or on the near-black, in which case the
     * glyphs are repainted to suit - see {@link #contrastText}.
     *
     * <p>{@code 0x0D0D0D} is the colour the app itself uses behind a dark note,
     * measured from the picture the app drew for one.
     */
    private static int backgroundFor(List<Bitmap> pages, ExportOptions.Background preference,
            boolean[] textIsLightOut) {
        long lightRuns = 0;
        long darkRuns = 0;
        for (Bitmap page : pages) {
            long[] runs = strokes(page);
            lightRuns += runs[0];
            darkRuns += runs[1];
        }
        boolean lightText = lightRuns >= darkRuns;
        textIsLightOut[0] = lightText;
        Log.i(TAG, "native batch: the text is " + (lightText ? "light" : "dark")
                + " (" + lightRuns + " light strokes vs " + darkRuns + " dark ones)");
        if (preference == ExportOptions.Background.WHITE) {
            return Color.WHITE;
        }
        if (preference == ExportOptions.Background.DARK) {
            return DARK_NOTE_BACKGROUND;
        }
        return lightText ? DARK_NOTE_BACKGROUND : Color.WHITE;
    }

    /**
     * Counts the glyph strokes of a page, by colour.
     *
     * <p>Text is short runs of one colour; a photograph is long runs of many, so
     * counting short runs of a flat, unsaturated colour finds the text and
     * ignores the pictures. It is what makes a note that is mostly a photo come
     * out the right way round.
     */
    private static long[] strokes(Bitmap page) {
        long light = 0;
        long dark = 0;
        try {
            int width = page.getWidth();
            int height = page.getHeight();
            int[] row = new int[width];
            int step = Math.max(1, height / 60);
            for (int y = 0; y < height; y += step) {
                page.getPixels(row, 0, width, 0, y, width, 1);
                int run = 0;
                int polarity = 0;
                for (int x = 0; x < width; x++) {
                    int here = polarityOf(row[x]);
                    if (here != polarity) {
                        if (polarity > 0) {
                            light++;
                        } else if (polarity < 0) {
                            dark++;
                        }
                        polarity = here;
                        run = 1;
                    } else {
                        run++;
                        if (run > 40) {
                            // too long to be a glyph: stop calling it one
                            polarity = 0;
                            run = 0;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // a page that cannot be sampled simply does not vote
        }
        return new long[] {light, dark};
    }

    /** 1 for a light flat pixel, -1 for a dark one, 0 for everything else. */
    private static int polarityOf(int pixel) {
        int alpha = (pixel >>> 24) & 0xFF;
        if (alpha < 200) {
            return 0;
        }
        int red = (pixel >> 16) & 0xFF;
        int green = (pixel >> 8) & 0xFF;
        int blue = pixel & 0xFF;
        int high = Math.max(red, Math.max(green, blue));
        int low = Math.min(red, Math.min(green, blue));
        if (high - low > 24) {
            // a coloured pixel: a picture or an emoji, not text
            return 0;
        }
        int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        if (luminance >= 200) {
            return 1;
        }
        if (luminance <= 60) {
            return -1;
        }
        return 0;
    }

    /**
     * Repaints the glyphs when the background was chosen to match them.
     *
     * <p>White text on a white picture is unreadable, so when the two agree the
     * strokes are drawn in the opposite colour. Only short runs of one flat
     * colour are touched, which is what keeps the pictures and the emoji as they
     * were.
     */
    private static Bitmap contrastText(Bitmap picture, boolean lightText, int background) {
        boolean backgroundIsLight = isLight(background);
        if (backgroundIsLight != lightText) {
            return picture;                       // already light-on-dark or dark-on-light
        }
        int glyph = lightText ? 0xFFFFFFFF : 0xFF000000;
        int replacement = lightText ? 0xFF000000 : 0xFFFFFFFF;
        int width = picture.getWidth();
        int height = picture.getHeight();
        int[] row = new int[width];
        long changed = 0;
        try {
            for (int y = 0; y < height; y++) {
                picture.getPixels(row, 0, width, 0, y, width, 1);
                int start = -1;
                for (int x = 0; x <= width; x++) {
                    boolean glyphHere = x < width && near(row[x], glyph);
                    if (glyphHere) {
                        if (start < 0) {
                            start = x;
                        }
                    } else if (start >= 0) {
                        if (x - start <= 40) {
                            for (int index = start; index < x; index++) {
                                row[index] = replacement;
                                changed++;
                            }
                        }
                        start = -1;
                    }
                }
                picture.setPixels(row, 0, width, 0, y, width, 1);
            }
        } catch (Throwable t) {
            Log.w(TAG, "native batch: the text could not be repainted: " + t);
            return picture;
        }
        Log.i(TAG, "native batch: repainted " + changed + " pixel(s) of "
                + (lightText ? "light" : "dark") + " text to suit the background");
        return picture;
    }

    /** Whether a pixel is the given flat colour, allowing for antialiasing. */
    private static boolean near(int pixel, int wanted) {
        int alpha = (pixel >>> 24) & 0xFF;
        if (alpha < 200) {
            return false;
        }
        int red = Math.abs(((pixel >> 16) & 0xFF) - ((wanted >> 16) & 0xFF));
        int green = Math.abs(((pixel >> 8) & 0xFF) - ((wanted >> 8) & 0xFF));
        int blue = Math.abs((pixel & 0xFF) - (wanted & 0xFF));
        return red <= 8 && green <= 8 && blue <= 8;
    }

    private static boolean isLight(int colour) {
        int luminance = (((colour >> 16) & 0xFF) * 299 + ((colour >> 8) & 0xFF) * 587
                + (colour & 0xFF) * 114) / 1000;
        return luminance >= 128;
    }

    /**
     * Drops the empty rows the app leaves under a short note.
     *
     * <p>The editor measures its own height, which is the whole screen, so a
     * note that ends half way down comes back with blank rows under it. Only rows
     * that are the background colour all the way across are dropped, only from
     * the bottom, and never further up than the last page: a note whose content
     * ends in something white on a white background would otherwise have real
     * content cut away as if it were blank.
     */
    private static Bitmap trimmed(Bitmap picture, int background, int stopAbove) {
        int width = picture.getWidth();
        int height = picture.getHeight();
        int floor = Math.max(1, stopAbove);
        int[] row = new int[width];
        int bottom = height;
        while (bottom > floor) {
            picture.getPixels(row, 0, width, 0, bottom - 1, width, 1);
            boolean empty = true;
            for (int pixel : row) {
                if (!matches(pixel, background)) {
                    empty = false;
                    break;
                }
            }
            if (!empty) {
                break;
            }
            bottom--;
        }
        if (bottom == height) {
            return picture;
        }
        Log.i(TAG, "native batch: trimmed " + (height - bottom) + " empty rows at the bottom");
        return Bitmap.createBitmap(picture, 0, 0, width, bottom);
    }

    /** Whether a pixel is the background colour, allowing for rounding. */
    private static boolean matches(int pixel, int background) {
        if (((pixel >>> 24) & 0xFF) < 16) {
            return true;
        }
        int red = Math.abs((pixel >> 16 & 0xFF) - (background >> 16 & 0xFF));
        int green = Math.abs((pixel >> 8 & 0xFF) - (background >> 8 & 0xFF));
        int blue = Math.abs((pixel & 0xFF) - (background & 0xFF));
        int tolerance = ((pixel >> 16 & 0xFF) > 200 && (background >> 16 & 0xFF) > 200) ? 16 : 8;
        return red <= tolerance && green <= tolerance && blue <= tolerance;
    }

    /** The app's own picture of every note, one note at a time. */
    static NoteExporter.Result export(Context context, NoteStore.Snapshot snapshot,
            ExportOptions options, String root, NoteExporter.Stats stats) {
        List<Note> notes = new ArrayList<>();
        for (List<Note> group : NoteExporter.groupedByCategory(snapshot).values()) {
            notes.addAll(group);
        }
        int limit = options.limit > 0 ? Math.min(options.limit, notes.size()) : notes.size();

        Activity list = openNoteList(context);
        if (list == null) {
            ProgressNotifier.clear(context);
            return new NoteExporter.Result(false,
                    "打不开便签列表界面：请先把便签应用切到前台再导出", root);
        }

        ProgressNotifier.start(context, limit);
        int failuresInARow = 0;
        byte[] preview = null;
        for (int i = 0; i < limit; i++) {
            Note note = notes.get(i);
            Log.i(TAG, "native batch: " + (i + 1) + "/" + limit + " " + note.id);
            ProgressNotifier.progress(context, i, limit, NoteExporter.titleOf(note));
            if (options.skipExisting) {
                // Asked before the note is drawn, not after: drawing one takes
                // several seconds, and resuming an interrupted export should only
                // pay that for the notes it still has to do.
                String[] target = targetOf(note, i + 1, root, options);
                if (ExportSink.exists(context, target[0], target[1])) {
                    Log.i(TAG, "native batch: " + target[1] + " is already there, left alone");
                    stats.skipped++;
                    continue;
                }
            }
            if (topActivity() == null || !topActivity().getClass().getName().equals(LIST_ACTIVITY)) {
                list = openNoteList(context);
                if (list == null) {
                    Log.w(TAG, "native batch: the note list is gone");
                    break;
                }
            }
            Bitmap picture = captureOne(context, list, note, options.background);
            if (picture == null) {
                // A capture that timed out once usually works when it is asked
                // again from a clean editor, so a note is retried before it is
                // written off.
                Log.i(TAG, "native batch: trying " + note.id + " once more");
                list = openNoteList(context);
                if (list != null) {
                    picture = captureOne(context, list, note, options.background);
                }
            }
            if (picture == null) {
                stats.failed++;
                failuresInARow++;
                Log.w(TAG, "native batch: " + note.id + " produced no picture");
                // One note the app will not open, for instance a locked one, is
                // no reason to give up on the rest; three in a row means the
                // screen is no longer where this expects it to be.
                if (failuresInARow >= 3) {
                    Log.w(TAG, "native batch: stopping after " + failuresInARow
                            + " notes in a row produced nothing");
                    break;
                }
                list = openNoteList(context);
                if (list == null) {
                    Log.w(TAG, "native batch: the note list is gone");
                    break;
                }
                continue;
            }
            failuresInARow = 0;
            if (save(context, note, i + 1, root, picture, stats, options)) {
                stats.notes++;
                if (preview == null) {
                    // Kept so the settings screen can show what came out without
                    // being able to read the export folder.
                    preview = Thumbnail.of(picture);
                }
            } else {
                stats.failed++;
            }
            sleep(SETTLE_MILLIS);
        }

        String message = "原版长图导出：" + stats.notes + " 条成功";
        if (stats.skipped > 0) {
            message += "，跳过 " + stats.skipped + " 条已存在的";
        }
        if (stats.failed > 0) {
            message += "，" + stats.failed + " 条失败";
        }
        // An export where every note was already there did what it was asked to
        // do, so it counts as a success with nothing to report but the skips.
        boolean ok = stats.notes > 0 || stats.skipped > 0;
        ProgressNotifier.finish(context, message + "\n位置：" + root, ok);
        return new NoteExporter.Result(ok, message, root, preview);
    }

    /** Where a note's picture goes: the folder and the file name. */
    private static String[] targetOf(Note note, int index, String root, ExportOptions options) {
        String dir = options.categoryFolders
                ? ExportSink.join(root, ExportSink.sanitize(NoteExporter.categoryOf(note)))
                : root;
        String name = (options.numberedNames
                        ? String.format(java.util.Locale.US, "%03d_", index) : "")
                + ExportSink.fileName(NoteExporter.titleOf(note), "无标题") + ".png";
        return new String[] {dir, name};
    }

    private static boolean save(Context context, Note note, int index, String root,
            Bitmap picture, NoteExporter.Stats stats, ExportOptions options) {
        String[] target = targetOf(note, index, root, options);
        String dir = target[0];
        String name = target[1];
        if (options.skipExisting && ExportSink.exists(context, dir, name)) {
            Log.i(TAG, "native batch: " + name + " is already there, left alone");
            stats.skipped++;
            return true;
        }
        ExportSink sink = null;
        try {
            sink = ExportSink.open(context, dir, name, "image/png");
            if (picture.compress(Bitmap.CompressFormat.PNG, 100, sink.stream())) {
                sink.finish();
                stats.files++;
                Log.i(TAG, "native batch: saved " + sink.path() + " ("
                        + picture.getWidth() + "x" + picture.getHeight() + ")");
                return true;
            }
            sink.abort();
        } catch (Throwable t) {
            Log.w(TAG, "native batch: could not save the picture: " + t);
            if (sink != null) {
                sink.abort();
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the stages

    /** Opens the note list, which is the only thing that can build an editor. */
    private static Activity openNoteList(Context context) {
        Activity current = topActivity();
        if (current != null && current.getClass().getName().equals(LIST_ACTIVITY)) {
            return current;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(ConfigContract.NOTES_PKG, LIST_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "native batch: could not open the note list: " + t);
            return null;
        }
        long deadline = System.currentTimeMillis() + LIST_WAIT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            sleep(500);
            Activity top = topActivity();
            if (top != null && top.getClass().getName().equals(LIST_ACTIVITY)) {
                sleep(1500);
                return top;
            }
        }
        Activity top = topActivity();
        Log.w(TAG, "native batch: the list did not come up; top is "
                + (top == null ? "unknown" : top.getClass().getName()));
        return null;
    }

    /**
     * Opens the editor straight at a note, for one the list is not showing.
     *
     * <p>The editor's own argument name is read from the app, because it is what
     * the app itself puts in the intent when something outside it asks for a
     * note to be opened; guessing it would be worse than asking.
     */
    private static boolean openEditorByGuid(Context context, Note note) {
        try {
            Class<?> fragment = Class.forName(EDITOR_FRAGMENT, false, appLoader);
            Field argument = fragment.getField("ARGUMENTS_EXTRA_NOTE_GUID");
            argument.setAccessible(true);
            Object name = argument.get(null);
            if (!(name instanceof String)) {
                return false;
            }
            Intent intent = new Intent();
            intent.setClassName(ConfigContract.NOTES_PKG, EDITOR_ACTIVITY);
            intent.putExtra((String) name, note.id);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Log.i(TAG, "native batch: opening " + note.id + " with " + name);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "native batch: the editor could not be opened by guid: " + t);
            return false;
        }
    }

    /** Clicks the note in the list, waits for the editor, and asks it to capture. */
    private static Bitmap captureOne(Context context, Activity list, Note note,
            ExportOptions.Background preference) {
        View item = findListItem(list, note);
        if (item == null) {
            // The list shows one folder at a time, so a note from another folder
            // is simply not on screen. The editor can be opened at a note by its
            // guid instead, which needs nothing from the list at all.
            Log.i(TAG, "native batch: " + note.id + " is not in the folder on screen");
            if (!openEditorByGuid(context, note)) {
                Log.w(TAG, "native batch: " + note.id + " is not in the list right now");
                return null;
            }
        }
        pending = note;
        synchronized (pages) {
            pages.clear();
        }
        merged = null;
        final int thisRound = ++round;

        if (item != null && !clickItem(list, item)) {
            Log.w(TAG, "native batch: the list item could not be clicked");
            pending = null;
            return null;
        }
        if (!waitForEditor()) {
            Activity top = topActivity();
            Log.w(TAG, "native batch: the editor did not open; the top screen is "
                    + (top == null ? "unknown" : top.getClass().getName()));
            pending = null;
            return null;
        }
        if (!triggerCapture(editor, preference)) {
            Log.w(TAG, "native batch: the editor's capture could not be started");
            pending = null;
            closeEditor();
            return null;
        }

        // The app renders the note page by page and merges the pages into the
        // long picture, then opens its own preview screen. Waiting for the first
        // bitmap would save one page of many, and tearing the editor down before
        // the merge cancels the capture coroutine half way through, so the wait
        // is for the preview screen and the settle after it lets the merge land.
        // A note with a lot of content can take a while, hence the long cap and
        // the progress line that says it is still working.
        long deadline = System.currentTimeMillis() + CAPTURE_WAIT_SECONDS * 1000;
        long lastReport = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline && !shareScreenShown()) {
            if (System.currentTimeMillis() - lastReport >= 15000) {
                lastReport = System.currentTimeMillis();
                Log.i(TAG, "native batch: still waiting for the picture ("
                        + pageCount() + " page(s) so far)");
            }
            sleep(200);
        }
        if (shareScreenShown()) {
            Log.i(TAG, "native batch: the app finished the picture");
            sleep(SETTLE_MILLIS * 2);
        } else {
            Log.w(TAG, "native batch: the app never showed its preview screen after "
                    + CAPTURE_WAIT_SECONDS + "s (" + pageCount() + " page(s) arrived)");
        }

        // The pages arrive with no background at all, so the text they carry
        // decides what they are stacked on; a background the caller insisted on
        // is used as it is, and the glyphs are repainted to suit it.
        List<Bitmap> captured;
        synchronized (pages) {
            captured = new ArrayList<>(pages);
        }
        if (round != thisRound) {
            pending = null;
            merged = null;
            synchronized (pages) {
                pages.clear();
            }
            leaveCaptureScreens();
            return null;
        }
        boolean[] lightText = new boolean[1];
        int background = backgroundFor(captured, preference, lightText);
        Bitmap painted = combined(background);
        if (painted != null) {
            painted = contrastText(painted, lightText[0], background);
            int stopAbove = painted.getHeight() - Math.max(0, lastPageHeight);
            painted = trimmed(painted, background, stopAbove);
        }
        pending = null;
        merged = null;
        synchronized (pages) {
            pages.clear();
        }
        leaveCaptureScreens();
        return painted;
    }

    /** How many pages the app has handed over for the note being captured. */
    private static int pageCount() {
        synchronized (pages) {
            return pages.size();
        }
    }

    /**
     * Closes whatever the capture left on screen and waits for the list.
     *
     * <p>A capture that worked ends on the app's own preview screen, and one
     * that failed can leave the editor open; either way the list has to be back
     * in front before the next note can be opened, and it is waited for rather
     * than assumed.
     */
    private static void leaveCaptureScreens() {
        for (int attempt = 0; attempt < 8; attempt++) {
            Activity top = topActivity();
            if (top == null || top.getClass().getName().equals(LIST_ACTIVITY)) {
                if (attempt > 0) {
                    Log.i(TAG, "native batch: the list is back");
                }
                return;
            }
            String name = top.getClass().getName();
            if (name.equals(SHARE_SCREEN) || name.equals(EDITOR_ACTIVITY)) {
                Log.i(TAG, "native batch: closing " + name);
                final Activity activity = top;
                new Handler(Looper.getMainLooper()).post(activity::finish);
            } else {
                Log.w(TAG, "native batch: " + name + " is in front; waiting for the list");
            }
            sleep(SETTLE_MILLIS);
        }
        Activity top = topActivity();
        Log.w(TAG, "native batch: the list did not come back; top is "
                + (top == null ? "unknown" : top.getClass().getName()));
    }

    /** Whether the app's own preview of the finished picture is in front. */
    private static boolean shareScreenShown() {
        Activity top = topActivity();
        return top != null && top.getClass().getName().equals(SHARE_SCREEN);
    }

    /**
     * Finds the list row that shows a given note.
     *
     * <p>The list turned out to be a {@code RecyclerView}, and its data lives in
     * a ViewModel rather than in a plain adapter, so the row is found two ways:
     * first by asking the adapter for each item and looking for the note id
     * inside it, and failing that by scrolling to each position and reading the
     * text the row currently shows.
     */
    private static View findListItem(Activity list, Note note) {
        View content = list.findViewById(android.R.id.content);
        try {
            List<View> candidates = findRecyclerViews(content);
            Log.i(TAG, "native batch: " + candidates.size() + " RecyclerView(s) on screen");
            // The tab pager is itself a RecyclerView, and the note rows live in the
            // list inside the page it currently shows, so the inner ones go first.
            List<View> ordered = new ArrayList<>();
            for (View candidate : candidates) {
                if (!isPager(candidate)) {
                    ordered.add(candidate);
                }
            }
            for (View candidate : candidates) {
                if (isPager(candidate)) {
                    ordered.add(candidate);
                }
            }
            for (View recycler : ordered) {
                Log.i(TAG, "native batch: trying " + recycler.getClass().getName()
                        + " with " + rowCount(recycler) + " row(s)");
                View row = findRecyclerRow(recycler, note);
                if (row != null) {
                    return row;
                }
            }
            ListView listView = findView(content, ListView.class);
            if (listView != null) {
                Adapter adapter = listView.getAdapter();
                for (int position = 0; adapter != null && position < adapter.getCount()
                        && position < 400; position++) {
                    if (mentions(adapter.getItem(position), note.id, 0)) {
                        Log.i(TAG, "native batch: " + note.id + " is at position " + position);
                        return listView.getChildAt(position - listView.getFirstVisiblePosition());
                    }
                }
                Log.w(TAG, "native batch: " + note.id + " was not found in the ListView");
                return null;
            }
            Log.w(TAG, "native batch: no list on the list screen; it holds "
                    + describeTree(content));
        } catch (Throwable t) {
            Log.w(TAG, "native batch: searching the list failed: " + t);
        }
        return null;
    }

    /**
     * Every list on screen, whatever the app calls it.
     *
     * <p>AndroidX is not on this module's classpath, so the type is loaded from
     * the app's loader and matched with {@code isInstance}: the app wraps its
     * lists in subclasses of its own, which a name comparison would miss.
     */
    private static List<View> findRecyclerViews(View content) throws Exception {
        Class<?> type = Class.forName("androidx.recyclerview.widget.RecyclerView",
                false, appLoader);
        List<View> found = new ArrayList<>();
        collectInstances(content, type.asSubclass(View.class), found, 0);
        return found;
    }

    private static void collectInstances(View view, Class<? extends View> type,
            List<View> out, int depth) {
        if (view == null || depth > 10 || out.size() >= 12) {
            return;
        }
        if (type.isInstance(view)) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectInstances(group.getChildAt(i), type, out, depth + 1);
            }
        }
    }

    /** True for the pager that holds the category pages, which is not the list. */
    private static boolean isPager(View view) {
        return view.getClass().getName().toLowerCase(java.util.Locale.US).contains("viewpager2");
    }

    /** How many rows a list holds, or -1 when it will not say. */
    private static int rowCount(View recycler) {
        try {
            Object adapter = recycler.getClass().getMethod("getAdapter").invoke(recycler);
            if (adapter == null) {
                return -1;
            }
            return (Integer) adapter.getClass().getMethod("getItemCount").invoke(adapter);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Every view class on screen, for when the expected list is not there. */
    private static String describeTree(View view) {
        List<String> names = new ArrayList<>();
        collectClasses(view, names, 0);
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(name);
        }
        return sb.toString();
    }

    private static void collectClasses(View view, List<String> out, int depth) {
        if (view == null || depth > 8 || out.size() >= 60) {
            return;
        }
        out.add(view.getClass().getName());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectClasses(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    private static View findViewByTypeName(View view, String className) {
        if (view == null) {
            return null;
        }
        if (view.getClass().getName().equals(className)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByTypeName(group.getChildAt(i), className);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Scrolls through the rows and matches the one showing this note. */
    private static View findRecyclerRow(Object recycler, Note note) throws Exception {
        Method getAdapter = recycler.getClass().getMethod("getAdapter");
        Object adapter = getAdapter.invoke(recycler);
        if (adapter == null) {
            Log.w(TAG, "native batch: the RecyclerView has no adapter");
            return null;
        }
        int count = (Integer) adapter.getClass().getMethod("getItemCount").invoke(adapter);
        Log.i(TAG, "native batch: the list holds " + count + " row(s)");

        // Some adapters hand their items out; when they do, the note id is what
        // identifies the row and no scrolling is needed.
        Method getItem = null;
        try {
            getItem = adapter.getClass().getMethod("getItem", int.class);
        } catch (Throwable ignored) {
            // not that kind of adapter
        }
        Method scrollTo = recycler.getClass().getMethod("scrollToPosition", int.class);
        Method holderFor = recycler.getClass()
                .getMethod("findViewHolderForAdapterPosition", int.class);
        String wanted = NoteExporter.titleOf(note);
        String wantedText = note.text == null ? "" : note.text.trim();

        for (int position = 0; position < Math.min(count, 400); position++) {
            if (getItem != null) {
                Object item = getItem.invoke(adapter, position);
                if (mentions(item, note.id, 0)) {
                    Log.i(TAG, "native batch: " + note.id + " is at position " + position);
                    return rowView(recycler, holderFor, position);
                }
            }
            View holder = rowView(recycler, holderFor, position);
            if (holder == null) {
                scrollTo.invoke(recycler, position);
                sleep(120);
                holder = rowView(recycler, holderFor, position);
            }
            if (holder != null && matchesText(holder, note.id, wanted, wantedText)) {
                Log.i(TAG, "native batch: " + note.id + " is at position " + position
                        + " (matched by its text)");
                return holder;
            }
            if (position % 20 == 19) {
                scrollTo.invoke(recycler, position);
                sleep(60);
            }
        }
        Log.w(TAG, "native batch: " + note.id + " was not found among the rows");
        return null;
    }

    /** The row view at a position, or null when it is not laid out. */
    private static View rowView(Object recycler, Method holderFor, int position) {
        try {
            Object holder = holderFor.invoke(recycler, position);
            if (holder == null) {
                return null;
            }
            return (View) holder.getClass().getField("itemView").get(holder);
        } catch (Throwable t) {
            try {
                Object holder = holderFor.invoke(recycler, position);
                return holder == null ? null
                        : findViewByTypeName((View) holder, "android.view.View");
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** Whether a row's own text is the note's title or the start of its body. */
    private static boolean matchesText(View row, String noteId, String title, String body) {
        List<String> texts = new ArrayList<>();
        collectText(row, texts, 0);
        for (String text : texts) {
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            if (trimmed.equals(title) || (!body.isEmpty() && body.startsWith(trimmed)
                    && trimmed.length() >= 4)) {
                return true;
            }
            if (mentions(trimmed, noteId, 0)) {
                return true;
            }
        }
        return false;
    }

    private static void collectText(View view, List<String> out, int depth) {
        if (view == null || depth > 6 || out.size() > 40) {
            return;
        }
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null) {
                out.add(text.toString());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectText(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** True when the object, or something it holds, carries the note id. */
    private static boolean mentions(Object value, String noteId, int depth) {
        if (value == null || depth > 2) {
            return false;
        }
        if (value instanceof String) {
            return noteId.equals(value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return false;
        }
        try {
            for (Field field : value.getClass().getDeclaredFields()) {
                if (field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object inner = field.get(value);
                if (inner instanceof String) {
                    if (noteId.equals(inner)) {
                        return true;
                    }
                } else if (inner != null && !(inner instanceof java.util.Collection)
                        && mentions(inner, noteId, depth + 1)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // an unreadable field simply does not match
        }
        return false;
    }

    /** The first clickable view inside a row, or null when the row itself is it. */
    private static View clickableChild(View row) {
        if (row == null || !(row instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child != null && child.isClickable()) {
                return child;
            }
        }
        return null;
    }

    /** Clicks the row the way the app's own list would. */
    private static boolean clickItem(Activity list, View item) {
        try {
            ListView listView = findView(list.findViewById(android.R.id.content), ListView.class);
            if (listView != null) {
                int position = listView.getPositionForView(item);
                if (position == AdapterView.INVALID_POSITION) {
                    return false;
                }
                new Handler(Looper.getMainLooper()).post(() ->
                        listView.performItemClick(item, position,
                                listView.getAdapter().getItemId(position)));
                return true;
            }
            // A RecyclerView row carries its own click listener, and on some item
            // layouts that listener sits on a clickable child rather than on the
            // row itself, so the row is clicked first and then the first
            // clickable view inside it.
            View target = clickableChild(item);
            new Handler(Looper.getMainLooper()).post(() -> {
                item.performClick();
                if (target != null) {
                    target.performClick();
                }
            });
            Log.i(TAG, "native batch: clicked the row" + (target == null ? ""
                    : " and its " + target.getClass().getName()));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "native batch: clicking failed: " + t);
            return false;
        }
    }

    private static boolean waitForEditor() {
        long deadline = System.currentTimeMillis() + EDITOR_WAIT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            Activity current = editor;
            if (current != null && current.getClass().getName().equals(EDITOR_ACTIVITY)) {
                // The note is loaded into a WebView; give the page a moment.
                sleep(2500);
                return true;
            }
            sleep(300);
        }
        return false;
    }

    /**
     * Asks the editor to share the note as a picture, which makes it render the
     * long picture itself.
     *
     * <p>{@code doPictureShare(int, Integer, CaptureCallback)} is the app's own
     * entry point: its share dialog calls it with {@code (0, null, null)} and a
     * default-argument mask that fills in exactly those values, and everything
     * after it - measuring the content, capturing the WebView, writing the
     * picture - happens inside the app. Calling {@code doPictureCapture}
     * directly is not an option: it is private and its first argument is built
     * by the code in between.
     */
    private static boolean triggerCapture(Activity activity, ExportOptions.Background preference) {
        try {
            Object fragment = findFragment(activity);
            if (fragment == null) {
                Log.w(TAG, "native batch: the editor fragment was not found");
                return false;
            }
            Log.i(TAG, "native batch: the editor fragment is " + fragment.getClass().getName());
            Method share = findPictureShare(fragment.getClass());
            if (share == null) {
                Log.w(TAG, "native batch: the editor fragment has no doPictureShare");
                return false;
            }
            // The second argument is the colour the share is drawn on, and the
            // page's own JavaScript passes one; the native dialog passes null.
            // Measured: handing it a colour changes nothing about the pages — the
            // same pixels come back either way, because the app paints that
            // colour behind them only in its own preview. It is passed anyway so
            // that the app is asked the same question its own code asks, and the
            // note's text stays whatever colour the note was rendered in.
            Integer asked = null;
            if (preference == ExportOptions.Background.WHITE) {
                asked = Integer.valueOf(Color.WHITE);
            } else if (preference == ExportOptions.Background.DARK) {
                asked = Integer.valueOf(DARK_NOTE_BACKGROUND);
            }
            Class<?>[] types = share.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < args.length; i++) {
                if (types[i] == int.class || types[i] == long.class || types[i] == short.class) {
                    // The capture type the share dialog itself passes.
                    args[i] = 0;
                } else if (types[i] == boolean.class) {
                    args[i] = Boolean.FALSE;
                } else if (types[i] == Integer.class) {
                    args[i] = asked;
                } else {
                    // The result callback is null in the app's own call too.
                    args[i] = null;
                }
            }
            share.setAccessible(true);
            Log.i(TAG, "native batch: calling doPictureShare with " + describe(types)
                    + " and background " + (asked == null ? "null"
                            : "0x" + Integer.toHexString(asked)));
            share.invoke(fragment, args);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "native batch: the capture could not be started: " + t);
            return false;
        }
    }

    /** {@code doPictureShare(int, Integer, CaptureCallback)} on the fragment. */
    private static Method findPictureShare(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals("doPictureShare")) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 3 && types[0] == int.class && types[1] == Integer.class) {
                return method;
            }
        }
        return null;
    }

    private static Object findFragment(Activity activity) {
        try {
            Method getSupportFragmentManager = activity.getClass()
                    .getMethod("getSupportFragmentManager");
            Object manager = getSupportFragmentManager.invoke(activity);
            Method getFragments = manager.getClass().getMethod("getFragments");
            List<?> fragments = (List<?>) getFragments.invoke(manager);
            for (Object fragment : fragments) {
                if (fragment != null && fragment.getClass().getName().equals(EDITOR_FRAGMENT)) {
                    return fragment;
                }
            }
            // The fragment class is nested; compare by simple name as a fallback.
            for (Object fragment : fragments) {
                if (fragment != null
                        && fragment.getClass().getName().contains("WVNoteViewEditFragment")) {
                    return fragment;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "native batch: fragments could not be listed: " + t);
        }
        return null;
    }

    private static void closeEditor() {
        new Handler(Looper.getMainLooper()).post(() -> {
            Activity activity = editor;
            if (activity != null) {
                try {
                    activity.finish();
                } catch (Throwable t) {
                    Log.w(TAG, "native batch: could not close the editor: " + t);
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The screen in front of the user.
     *
     * <p>The activity the app resumed last is the honest answer and always
     * available; the scan of ActivityThread's records is the fallback, and it
     * fails on its own when the map is being changed underneath it.
     */
    private static Activity topActivity() {
        Activity tracked = resumed;
        if (tracked != null && !tracked.isFinishing() && !tracked.isDestroyed()) {
            return tracked;
        }
        return scannedActivity();
    }

    private static Activity scannedActivity() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method current = thread.getMethod("currentActivityThread");
            Object value = current.invoke(null);
            Field activities = thread.getDeclaredField("mActivities");
            activities.setAccessible(true);
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) activities.get(value);
            Activity newest = null;
            if (map != null) {
                for (Object record : map.values().toArray()) {
                    try {
                        Field paused = record.getClass().getDeclaredField("paused");
                        paused.setAccessible(true);
                        if (Boolean.TRUE.equals(paused.get(record))) {
                            continue;
                        }
                        Field activity = record.getClass().getDeclaredField("activity");
                        activity.setAccessible(true);
                        Object candidate = activity.get(record);
                        if (candidate instanceof Activity) {
                            newest = (Activity) candidate;
                        }
                    } catch (Throwable ignored) {
                        // a record that does not look like the others is skipped
                    }
                }
            }
            return newest;
        } catch (Throwable t) {
            Log.i(TAG, "native batch: the activity list could not be read: " + t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findView(View view, Class<T> type) {
        if (view == null) {
            return null;
        }
        if (type.isInstance(view)) {
            return (T) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findView(group.getChildAt(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String describe(Class<?>[] types) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> type : types) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(type.getSimpleName());
        }
        return sb.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
