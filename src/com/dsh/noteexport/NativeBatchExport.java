package com.dsh.noteexport;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    private static final long LIST_WAIT_SECONDS = 20;
    private static final long EDITOR_WAIT_SECONDS = 20;
    private static final long CAPTURE_WAIT_SECONDS = 40;
    private static final long SETTLE_MILLIS = 700;

    /** The note being captured, or null when no batch is running. */
    private static volatile Note pending;
    /** Set while waiting for the app to save the picture of {@link #pending}. */
    private static volatile CountDownLatch saved;
    /** What the app saved for that note. */
    private static volatile Bitmap savedBitmap;
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
                if (param.args.length > 0) {
                    collect(param.args[0]);
                }
            }
        };
        int copied = Hooks.hookMethodsStartingWith(SCREEN_SHOT_UTILS, loader, "saveBitmap",
                copier);
        copied += Hooks.hookMethodsStartingWith(CAPTURE_HELPER, loader, "saveBitmap", copier);
        // The long picture is assembled here as well, so its result is taken too:
        // that covers the versions where the bitmap never reaches a save method.
        copied += Hooks.hookMethodsNamed(SCREEN_SHOT_UTILS, loader,
                "mergeAndSaveImagesAsLongBitmap", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        collect(param.getResult());
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
     * Keeps the biggest picture the app produced for the note being captured.
     *
     * <p>The capture is built up out of several bitmaps and every one of them
     * passes through the hooked methods, so the largest is the finished long
     * picture and the small ones are the pages it was assembled from.
     */
    private static void collect(Object candidate) {
        if (pending == null || !(candidate instanceof Bitmap)) {
            return;
        }
        Bitmap bitmap = (Bitmap) candidate;
        Log.i(TAG, "native batch: the app produced a " + bitmap.getWidth() + "x"
                + bitmap.getHeight() + " picture");
        Bitmap best = savedBitmap;
        if (best == null || (long) bitmap.getWidth() * bitmap.getHeight()
                > (long) best.getWidth() * best.getHeight()) {
            savedBitmap = bitmap;
        }
        CountDownLatch latch = saved;
        if (latch != null) {
            latch.countDown();
        }
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
            return new NoteExporter.Result(false,
                    "打不开便签列表界面：请先把便签应用切到前台再导出", root);
        }

        for (int i = 0; i < limit; i++) {
            Note note = notes.get(i);
            Log.i(TAG, "native batch: " + (i + 1) + "/" + limit + " " + note.id);
            Bitmap picture = captureOne(context, list, note);
            if (picture == null) {
                stats.failed++;
                Log.w(TAG, "native batch: stopping — " + note.id + " produced no picture");
                break;
            }
            if (save(context, note, i + 1, root, picture, stats)) {
                stats.notes++;
            } else {
                stats.failed++;
            }
            sleep(SETTLE_MILLIS);
        }

        String message = "原版长图导出：" + stats.notes + " 条成功";
        if (stats.failed > 0) {
            message += "，" + stats.failed + " 条失败";
        }
        return new NoteExporter.Result(stats.notes > 0, message, root);
    }

    private static boolean save(Context context, Note note, int index, String root,
            Bitmap picture, NoteExporter.Stats stats) {
        String dir = ExportSink.join(root,
                ExportSink.sanitize(NoteExporter.categoryOf(note)));
        String name = String.format(java.util.Locale.US, "%03d_", index)
                + ExportSink.fileName(NoteExporter.titleOf(note), "无标题") + ".png";
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

    /** Clicks the note in the list, waits for the editor, and asks it to capture. */
    private static Bitmap captureOne(Context context, Activity list, Note note) {
        View item = findListItem(list, note);
        if (item == null) {
            Log.w(TAG, "native batch: " + note.id + " is not in the list right now");
            return null;
        }
        pending = note;
        savedBitmap = null;
        saved = new CountDownLatch(1);
        final int thisRound = ++round;

        if (!clickItem(list, item)) {
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
        if (!triggerCapture(editor)) {
            Log.w(TAG, "native batch: the editor's capture could not be started");
            pending = null;
            closeEditor();
            return null;
        }

        boolean got;
        try {
            got = saved.await(CAPTURE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            got = false;
        }
        Bitmap result = got && round == thisRound ? savedBitmap : null;
        pending = null;
        saved = null;
        savedBitmap = null;
        dismissShareScreen();
        closeEditor();
        sleep(SETTLE_MILLIS);
        return result;
    }

    /**
     * Closes the screen the app opens to show the finished picture.
     *
     * <p>Capturing a picture ends with the app pushing its own preview on top of
     * the editor, and that preview has to go before the next note can be opened
     * from the list.
     */
    private static void dismissShareScreen() {
        Activity top = topActivity();
        if (top == null || !top.getClass().getName().equals(SHARE_SCREEN)) {
            return;
        }
        Log.i(TAG, "native batch: closing the share screen");
        new Handler(Looper.getMainLooper()).post(top::finish);
        sleep(SETTLE_MILLIS * 2);
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
    private static boolean triggerCapture(Activity activity) {
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
            Class<?>[] types = share.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < args.length; i++) {
                if (types[i] == int.class || types[i] == long.class || types[i] == short.class) {
                    // The capture type the share dialog itself passes.
                    args[i] = 0;
                } else if (types[i] == boolean.class) {
                    args[i] = Boolean.FALSE;
                } else {
                    // The background colour and the result callback are both
                    // null in the app's own call.
                    args[i] = null;
                }
            }
            share.setAccessible(true);
            Log.i(TAG, "native batch: calling doPictureShare with " + describe(types));
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
