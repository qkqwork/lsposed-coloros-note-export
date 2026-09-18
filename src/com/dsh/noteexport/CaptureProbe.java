package com.dsh.noteexport;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;

/**
 * The careful half of the pipeline investigation.
 *
 * <p>An earlier version hooked every method of the capture classes and made the
 * Notes app unlaunchable — those classes are used while the app starts, and the
 * logging alone blew the launch budget. This one adds exactly one hook, on the
 * method the app uses to capture its own content, and nothing else:
 *
 * <ul>
 *   <li>the classes are listed by reflection only, which runs none of the app's
 *       code and cannot slow anything down;</li>
 *   <li>{@code captureElements} is hooked by name, so no signature has to be
 *       guessed and no other method pays for it;</li>
 *   <li>the hook logs at most a handful of lines, never touches the values it is
 *       given, and swallows its own failures so it can never break the app.</li>
 * </ul>
 */
final class CaptureProbe {

    private static final String TAG = Main.TAG;

    private static final String ELEMENT_CAPTURE =
            "com.nearme.note.util.HtmlElementCaptureUtils";
    private static final String SCREEN_CAPTURE =
            "com.nearme.note.util.CaptureScreenUtils";
    private static final String SCREEN_SHOT =
            "com.nearme.note.util.ScreenShotUtils";
    private static final String WEBVIEW_CAPTURE =
            "com.nearme.note.activity.richedit.webview.WVCaptureScreenHelper";
    /** The screen a note is opened in; it is what draws the long picture. */
    private static final String EDITOR_ACTIVITY =
            "com.nearme.note.activity.richedit.NoteViewRichEditActivity";
    /** The fragment inside it that runs the capture. */
    private static final String EDITOR_FRAGMENT =
            "com.nearme.note.activity.richedit.webview.WVNoteViewEditFragment";

    /** How many calls of the hooked method are logged. */
    private static final int CALLS = 25;

    /**
     * The capture and assembly steps, by name.
     *
     * <p>Listed from the classes' own method inventories: a rich note's picture
     * comes from capturing the editor's WebView ({@code captureWebView}), a long
     * one from grabbing the screen a page at a time and stitching the pieces
     * ({@code createListBitmap} + {@code mergeAndSaveImagesAsLongBitmap}). These
     * are the steps to compare between a share that works and one that hangs.
     */
    private static final String[][] STEPS = {
            {"com.nearme.note.activity.richedit.webview.WVCaptureScreenHelper",
                    "captureWebView"},
            {"com.nearme.note.activity.richedit.webview.WVCaptureScreenHelper",
                    "doCaptureWebView"},
            // The two inner paths. The app's own share produces text with one of
            // them; if a driven capture takes the other, that is the difference
            // between a picture with glyphs and one without.
            {"com.nearme.note.activity.richedit.webview.WVCaptureScreenHelper",
                    "captureByDraw"},
            {"com.nearme.note.activity.richedit.webview.WVCaptureScreenHelper",
                    "captureByPixelCopy"},
            {"com.nearme.note.activity.richedit.webview.WVCaptureScreenHelper",
                    "captureCurrentScreen"},
            {"com.nearme.note.util.ScreenShotUtils", "captureCurrentScreen"},
            {"com.nearme.note.util.ScreenShotUtils", "createListBitmap"},
            {"com.nearme.note.util.CaptureScreenUtils", "captureCurrentScreen"},
            {"com.nearme.note.util.CaptureScreenUtils", "createListBitmap"},
            {"com.nearme.note.util.CaptureScreenUtils", "mergeAndSaveImagesAsLongBitmap"},
            {"com.nearme.note.util.ScreenShotUtils", "getViewDrawingCache"},
            {"com.nearme.note.activity.edit.SaveImageAndShare", "createImageFile"},
            {"com.nearme.note.activity.edit.SaveImageAndShare", "getCaptureBitmap"},
            {"com.nearme.note.activity.edit.SaveImageAndShare", "fillItemCapture"},
    };

    private static int logged;

    private CaptureProbe() {
    }

    static void install(ClassLoader loader) {
        // Inventory first, hooks second: knowing the names costs nothing and is
        // what makes a single-hook follow-up possible.
        Hooks.logDeclaredMethods(ELEMENT_CAPTURE, loader);
        Hooks.logDeclaredMethods(SCREEN_CAPTURE, loader);
        Hooks.logDeclaredMethods(WEBVIEW_CAPTURE, loader);
        Hooks.logDeclaredMethods(SCREEN_SHOT, loader);
        // The editor is the piece that actually draws the long picture; the share
        // screen only reads it back. Its launch contract and its methods are what
        // a batch export would have to drive.
        Hooks.logDeclaredMethods(EDITOR_ACTIVITY, loader);
        Hooks.logDeclaredMethods(EDITOR_FRAGMENT, loader);
        Hooks.logDeclaredMethods(EDITOR_FRAGMENT + "$Companion", loader);

        int total = 0;
        for (String[] step : STEPS) {
            total += Hooks.hookMethodsNamed(step[0], loader, step[1], new StepTracer(step[1]));
        }
        int elements = Hooks.hookMethodsNamed(ELEMENT_CAPTURE, loader, "captureElements",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("captureElements called with " + param.args.length
                                + " argument(s) from " + caller());
                    }
                });
        // The editor's own share entry point, with its argument *values*: the
        // page's JavaScript calls it with a background colour and a callback,
        // while the native dialog calls it with neither, and the two produce
        // very different pictures. Tapping share once in the app therefore says
        // exactly what a driven call has to pass.
        int shares = Hooks.hookMethodsNamed(EDITOR_FRAGMENT, loader, "doPictureShare",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        StringBuilder values = new StringBuilder();
                        for (Object argument : param.args) {
                            if (values.length() > 0) {
                                values.append(", ");
                            }
                            if (argument == null) {
                                values.append("null");
                            } else if (argument instanceof Number || argument instanceof Boolean) {
                                values.append(argument.getClass().getSimpleName())
                                        .append('=').append(argument);
                            } else {
                                values.append(argument.getClass().getName());
                            }
                        }
                        log("doPictureShare(" + values + ") from " + caller());
                    }
                }, true);
        shares += Hooks.hookMethodsNamed(
                "com.nearme.note.activity.richedit.webview.WVNoteViewEditFragmentShareHelper",
                loader, "doPictureShare", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        StringBuilder values = new StringBuilder();
                        for (Object argument : param.args) {
                            if (values.length() > 0) {
                                values.append(", ");
                            }
                            if (argument == null) {
                                values.append("null");
                            } else if (argument instanceof Number || argument instanceof Boolean) {
                                values.append(argument.getClass().getSimpleName())
                                        .append('=').append(argument);
                            } else {
                                values.append(argument.getClass().getName());
                            }
                        }
                        log("shareHelper.doPictureShare(" + values + ")");
                    }
                });
        // The editor's own entry point: what it is opened with tells us how to
        // open it for any other note.
        Hooks.hookMethodsNamed(EDITOR_ACTIVITY, loader, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object target = param.thisObject;
                    if (!(target instanceof android.app.Activity)) {
                        return;
                    }
                    android.content.Intent intent =
                            ((android.app.Activity) target).getIntent();
                    if (intent != null) {
                        log("editor intent: action=" + intent.getAction()
                                + " data=" + intent.getData() + " extras="
                                + intent.getExtras());
                    }
                } catch (Throwable ignored) {
                    // a probe must never break the app it is watching
                }
            }
        });
        Log.i(TAG, "capture probe: hooked " + total + " capture step(s) + " + shares + " share entry + "
                + elements + " captureElements + the editor's onCreate — nothing else");
    }

    /** Logs one line when a step starts and one when it ends. */
    private static final class StepTracer extends XC_MethodHook {
        private final String step;
        private int calls;

        StepTracer(String step) {
            this.step = step;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (calls >= CALLS) {
                return;
            }
            calls++;
            // Argument types are logged, not values: they are what a later
            // reflective call would have to supply.
            StringBuilder types = new StringBuilder();
            for (Object argument : param.args) {
                if (types.length() > 0) {
                    types.append(", ");
                }
                types.append(argument == null ? "null"
                        : argument.getClass().getName());
            }
            log("step -> " + step + " #" + calls + "(" + types + ")"
                    + viewState(step, param) + " from " + caller());
        }

        /**
         * What the view being captured looks like right now.
         *
         * <p>The pixel-copy path can only work on a view that is attached to a
         * visible window, so whether the app's own share and a driven capture
         * differ here is exactly what needs to be known.
         */
        private String viewState(String step, MethodHookParam param) {
            if (!step.startsWith("captureBy")) {
                return "";
            }
            try {
                if (param.args.length == 0 || !(param.args[0] instanceof android.view.View)) {
                    return "";
                }
                android.view.View view = (android.view.View) param.args[0];
                String bitmap = "";
                if (param.args.length > 1 && param.args[1] instanceof android.graphics.Bitmap) {
                    android.graphics.Bitmap target = (android.graphics.Bitmap) param.args[1];
                    bitmap = ", bitmap=" + target.getWidth() + "x" + target.getHeight();
                }
                return " [view attached=" + view.isAttachedToWindow() + " shown=" + view.isShown()
                        + " " + view.getWidth() + "x" + view.getHeight() + bitmap + "]";
            } catch (Throwable ignored) {
                return "";
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (calls > CALLS) {
                return;
            }
            Throwable thrown = param.getThrowable();
            log("step <- " + step + (thrown == null ? " ok" : " threw " + thrown));
        }
    }

    /** Logs a bounded number of lines, and never lets a failure escape. */
    private static void log(String message) {
        try {
            if (logged >= CALLS) {
                return;
            }
            logged++;
            Log.i(TAG, "capture probe: " + message);
        } catch (Throwable ignored) {
            // a probe must never break the app it is watching
        }
    }

    /** The app frame that called the hooked method, for orientation only. */
    private static String caller() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 0; i < stack.length; i++) {
                String name = stack[i].getClassName();
                if (name.contains("HtmlElementCaptureUtils")) {
                    continue;
                }
                if (name.startsWith("com.nearme.note") || name.startsWith("com.oplus.note")) {
                    return name + "." + stack[i].getMethodName();
                }
            }
        } catch (Throwable ignored) {
            // no caller information is not worth failing over
        }
        return "(unknown)";
    }
}
