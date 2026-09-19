package com.qkqwork.noteexport;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Exports every note as the Notes app itself would draw it.
 *
 * <p>Anything this module draws by hand is an approximation: the app has its own
 * fonts, spacing, skins, checkbox and heading styles, and the difference is
 * visible. So instead of drawing, this drives the app's own share-as-picture
 * screen once per note and keeps the picture it produces.
 *
 * <p>How the app is asked is not guessed. The module's share probe recorded the
 * Intent the app uses when the user shares a note manually:
 *
 * <pre>
 *   guid=c08cc945-…        the note's local_id — the one thing that changes per note
 *   skinID=color_skin_white, key_background_width=1264, key_title_height=147.0,
 *   key_line_height=112.0, key_first_content_padding_top=14.0, isTextDark, twopane, …
 * </pre>
 *
 * <p>The picture itself is taken where the app finishes with it: the screen
 * compresses its finished bitmap, and that bitmap is the export. Nothing is
 * re-rendered, resized or re-encoded by this module beyond writing it out as a
 * PNG.
 */
final class NativeImageExport {

    private static final String TAG = Main.TAG;

    private static final String NOTES_PKG = ConfigContract.NOTES_PKG;
    private static final String SHARE_CLASS =
            "com.nearme.note.activity.edit.SaveImageAndShare";

    /** How long one note may take before it is written off. */
    private static final long NOTE_TIMEOUT_SECONDS = 25;
    /** Time between notes, so the app can settle after one screen closes. */
    private static final long SETTLE_MILLIS = 400;

    /** The note currently being rendered, or null when nothing is in flight. */
    private static volatile Note wanted;
    /** Set while the app is drawing a note for us. */
    private static volatile CountDownLatch waiting;
    /** What the app compressed while {@link #waiting} was set. */
    private static volatile Bitmap captured;
    /** The share screen the app opened for us, so it can be closed again. */
    private static volatile Activity shareScreen;
    /** The note the app is sharing right now, from the screen's own intent. */
    private static volatile String sharingNote;

    /** Where pictures the user shares by hand are collected. */
    private static final String COLLECT_DIR = "收集";

    private NativeImageExport() {
    }

    static void install(ClassLoader loader) {
        // The picture is taken here rather than from any of the app's own
        // methods: the screen compresses exactly the bitmap it is about to
        // share, so this is the app's final rendering, untouched.
        Hooks.findAndHook("android.graphics.Bitmap", loader, "compress",
                Bitmap.CompressFormat.class, int.class, OutputStream.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object target = param.thisObject;
                        if (!(target instanceof Bitmap)) {
                            return;
                        }
                        Bitmap bitmap = (Bitmap) target;
                        CountDownLatch latch = waiting;
                        if (latch != null) {
                            if (captured == null || area(bitmap) > area(captured)) {
                                captured = bitmap;
                            }
                            // A share screen compresses a thumbnail first and
                            // the full picture after it; the wait ends on the
                            // larger one.
                            latch.countDown();
                            return;
                        }
                        // Not part of a batch run: this is the user sharing a
                        // note by hand, which is the one path that produces the
                        // app's own rendering. Those pictures are collected so
                        // they end up in the export folder too, instead of
                        // having to be hunted down one by one.
                        collect(bitmap);
                    }
                });
        Hooks.findAndHook(SHARE_CLASS, loader, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity) {
                            Activity activity = (Activity) param.thisObject;
                            shareScreen = activity;
                            try {
                                Intent intent = activity.getIntent();
                                sharingNote = intent == null ? null
                                        : intent.getStringExtra("guid");
                            } catch (Throwable ignored) {
                                sharingNote = null;
                            }
                        }
                    }
                });
        Hooks.findAndHook(SHARE_CLASS, loader, "onDestroy", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                shareScreen = null;
                sharingNote = null;
            }
        });
    }

    /**
     * Keeps a copy of a picture the user produced by sharing a note themselves.
     *
     * <p>This is not a fallback bolted on afterwards — it is the honest answer to
     * "give me the app's own rendering": the app renders a note only when its own
     * share screen runs the whole note-list pipeline behind it, and that screen
     * hangs on "正在生成" when it is started cold from outside. Sharing by hand is
     * the path that works, so the module makes it worth doing: every picture gets
     * collected into the export folder, with the watermark already removed.
     */
    private static void collect(Bitmap bitmap) {
        try {
            if (bitmap.getWidth() < 200 || bitmap.getHeight() < 200) {
                // Thumbnails and avatars are not the long picture.
                return;
            }
            Context context = shareScreen;
            if (context == null) {
                // Not a share screen we know about; leave it alone.
                return;
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String name = ExportSink.fileName(
                    (sharingNote == null ? "便签" : sharingNote) + "_" + stamp, "便签") + ".png";
            ExportSink sink = ExportSink.open(context, COLLECT_DIR, name, "image/png");
            try {
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, sink.stream())) {
                    sink.finish();
                    Log.i(TAG, "collect: saved " + sink.path() + " ("
                            + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                } else {
                    sink.abort();
                }
            } catch (Throwable t) {
                sink.abort();
                Log.w(TAG, "collect: could not save the shared picture: " + t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "collect failed: " + t);
        }
    }

    private static long area(Bitmap bitmap) {
        return (long) bitmap.getWidth() * bitmap.getHeight();
    }

    /** The app's own picture of every note, one at a time. */
    static NoteExporter.Result export(Context context, NoteStore.Snapshot snapshot,
            ExportOptions options, String root, NoteExporter.Stats stats) {
        List<Note> notes = new ArrayList<>();
        for (List<Note> group : NoteExporter.groupedByCategory(snapshot).values()) {
            notes.addAll(group);
        }
        if (options.limit > 0 && notes.size() > options.limit) {
            // Used while testing: a couple of notes instead of everything.
            notes = notes.subList(0, notes.size() > options.limit ? options.limit : notes.size());
        }

        if (!foreground(context)) {
            // Android refuses to let a background app open its own screen, so
            // every note would time out one after another. Saying so beats
            // twenty-five silent seconds per note.
            Log.w(TAG, "native: the Notes app is not in the foreground; "
                    + "open it first, then export");
            return new NoteExporter.Result(false,
                    "请先把便签应用切到前台再导出（原版长图需要便签应用自己来画）", root);
        }

        int index = 0;
        for (Note note : notes) {
            index++;
            String category = NoteExporter.categoryOf(note);
            String dir = ExportSink.join(root, ExportSink.sanitize(category));
            String base = String.format(java.util.Locale.US, "%03d_", index)
                    + ExportSink.fileName(NoteExporter.titleOf(note), "无标题");
            Log.i(TAG, "native: rendering " + index + "/" + notes.size() + " " + note.id);

            Bitmap picture = renderOne(context, note);
            if (picture == null) {
                Log.w(TAG, "native: the app never produced a picture for " + note.id);
                stats.failed++;
                continue;
            }
            ExportSink sink = null;
            try {
                String name = NoteExporter.truncate(base, 60) + ".png";
                sink = ExportSink.open(context, dir, name, "image/png");
                if (picture.compress(Bitmap.CompressFormat.PNG, 100, sink.stream())) {
                    sink.finish();
                    stats.files++;
                    stats.notes++;
                    Log.i(TAG, "native: saved " + sink.path() + " ("
                            + picture.getWidth() + "x" + picture.getHeight() + ")");
                } else {
                    sink.abort();
                    stats.failed++;
                }
            } catch (Throwable t) {
                Log.w(TAG, "native: could not save the picture: " + t);
                if (sink != null) {
                    sink.abort();
                }
                stats.failed++;
            } finally {
                picture.recycle();
            }
            try {
                Thread.sleep(SETTLE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new NoteExporter.Result(false, "导出被中断", root);
            }
        }

        String message = "已按原版效果导出 " + stats.notes + " 条便签到 " + root;
        if (stats.failed > 0) {
            message += "（" + stats.failed + " 条失败）";
        }
        return new NoteExporter.Result(stats.notes > 0, message, root);
    }

    /**
     * Opens the app's share screen for one note and waits for the picture.
     *
     * <p>The screen is opened from inside the Notes process, where this code
     * already runs, so no cross-app activity rules are involved.
     */
    private static Bitmap renderOne(final Context context, final Note note) {
        final CountDownLatch latch = new CountDownLatch(1);
        captured = null;
        wanted = note;
        waiting = latch;

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent();
                    intent.setClassName(NOTES_PKG, SHARE_CLASS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // The complete set of extras the app itself sends when a note
                    // is shared, captured by the share probe. Only "guid" differs
                    // per note; leaving any of the others out made the screen open
                    // and close again without drawing anything.
                    intent.putExtra("guid", note.id);
                    intent.putExtra("skinID", "color_skin_white");
                    intent.putExtra("key_background_width", 1264);
                    intent.putExtra("key_title_height", 147.0f);
                    intent.putExtra("key_line_height", 112.0f);
                    intent.putExtra("key_first_content_padding_top", 14.0f);
                    intent.putExtra("key_end_is_picture", false);
                    intent.putExtra("key_is_empty_title", false);
                    intent.putExtra("keyLogoColor", -1);
                    intent.putExtra("topPadding", 0);
                    intent.putExtra("isTextDark", false);
                    intent.putExtra("infoDarkMode", true);
                    intent.putExtra("twopane", false);
                    intent.putExtra("itemCount", 1);
                    context.startActivity(intent);
                } catch (Throwable t) {
                    Log.w(TAG, "native: could not open the share screen: " + t);
                    latch.countDown();
                }
            }
        });

        long deadline = System.currentTimeMillis() + NOTE_TIMEOUT_SECONDS * 1000;
        try {
            // Waits for the first compression, then keeps waiting briefly in
            // case a bigger one follows.
            if (!latch.await(NOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "native: timed out waiting for " + note.id);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                Thread.sleep(Math.min(1500, remaining));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        waiting = null;
        wanted = null;

        Bitmap result = captured;
        captured = null;
        closeShareScreen();
        return result;
    }

    /** Closes the screen the app opened, so the next note can be rendered. */
    private static void closeShareScreen() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Activity activity = shareScreen;
                if (activity == null) {
                    return;
                }
                try {
                    activity.finish();
                } catch (Throwable t) {
                    Log.w(TAG, "native: could not close the share screen: " + t);
                }
            }
        });
    }

    /**
     * Whether this app counts as being in the foreground.
     *
     * <p>Android only lets a foreground app open its own screens, and the whole
     * native export depends on that, so the state is checked up front instead of
     * discovering it one timed-out note at a time.
     */
    private static boolean foreground(Context context) {
        try {
            android.app.ActivityManager manager =
                    (android.app.ActivityManager) context.getSystemService(
                            Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return true;
            }
            List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                    manager.getRunningAppProcesses();
            if (processes == null) {
                return true;
            }
            for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.processName != null
                        && process.processName.startsWith(NOTES_PKG)) {
                    boolean visible =
                            process.importance
                                    <= android.app.ActivityManager.RunningAppProcessInfo
                                            .IMPORTANCE_VISIBLE;
                    Log.i(TAG, "native: " + process.processName + " importance="
                            + process.importance + " -> "
                            + (visible ? "usable" : "background"));
                    return visible;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "native: could not read the process state: " + t);
        }
        return true;
    }
}
