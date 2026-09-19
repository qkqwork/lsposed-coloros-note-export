package io.github.qkqwork.noteexport;

import android.util.Log;

/**
 * How far the export running inside the Notes process has got, and whether
 * somebody has asked it to stop.
 *
 * <p>The settings screen cannot see the export work: it is happening in another
 * process, on a thread of that process. So the export leaves its state here —
 * plain static fields, read and written from different binder threads — and the
 * screen asks for it through the module's provider while it waits. Everything is
 * volatile, and every read is a single field: a progress bar being one step
 * stale is not worth a lock.
 *
 * <p>Cancelling is a request, not an instruction. The export checks between
 * notes, because a note is drawn by the app's own editor and killing that half
 * way through is how a capture screen is left behind.
 */
final class Progress {

    private static final String TAG = Main.TAG;

    private static volatile boolean running;
    private static volatile boolean cancelRequested;
    private static volatile int done;
    private static volatile int total;
    private static volatile String title = "";

    private Progress() {
    }

    /** The export has started and will cover {@code total} notes. */
    static void start(int total) {
        done = 0;
        Progress.total = Math.max(0, total);
        title = "";
        cancelRequested = false;
        running = true;
        Log.i(TAG, "progress: watching " + Progress.total + " note(s)");
    }

    /** One more note has been handed over. */
    static void step(int done, String title) {
        Progress.done = done;
        Progress.title = title == null ? "" : title;
    }

    /** The export is over, however it ended. */
    static void finish() {
        running = false;
    }

    /** Asks the export to stop at the next note boundary. */
    static void cancel() {
        if (running) {
            cancelRequested = true;
            Log.i(TAG, "progress: cancellation asked for");
        }
    }

    static boolean running() {
        return running;
    }

    static boolean cancelled() {
        return cancelRequested;
    }

    /** Whether the run that just finished was stopped early. */
    static boolean wasCancelled() {
        return cancelRequested;
    }

    static int done() {
        return done;
    }

    static int total() {
        return total;
    }

    static String title() {
        return title;
    }
}
