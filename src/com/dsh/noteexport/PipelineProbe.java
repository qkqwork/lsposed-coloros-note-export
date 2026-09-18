package com.dsh.noteexport;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Traces the Notes app's own "save this note as a picture" pipeline.
 *
 * <p>The share screen hangs on "正在生成" when it is started from outside with
 * nothing but a note id, while the same screen completes instantly when the user
 * opens it from the app. That difference is the whole problem, and the way to see
 * it is to watch what the app itself calls, in order, on the classes that do the
 * work:
 *
 * <pre>
 *   com.nearme.note.util.HtmlElementCaptureUtils     captureElements(…)
 *   com.nearme.note.util.CaptureScreenUtils          list capture with a callback
 *   com.nearme.note.util.ScreenShotUtils
 *   com.nearme.note.activity.richedit.webview.WVCaptureScreenHelper   WebView capture
 * </pre>
 *
 * <p>Every method of those classes is hooked and logged, and the log is capped
 * per method so a busy class cannot drown the interesting lines.
 */
final class PipelineProbe {

    private static final String TAG = Main.TAG;

    private static final String[] TARGETS = {
            "com.nearme.note.util.HtmlElementCaptureUtils",
            "com.nearme.note.util.CaptureScreenUtils",
            "com.nearme.note.util.ScreenShotUtils",
            "com.nearme.note.activity.richedit.webview.WVCaptureScreenHelper",
    };

    /** How many calls of one method are logged before it goes quiet. */
    private static final int CALLS_PER_METHOD = 4;
    /** Longest argument or result text that is logged. */
    private static final int VALUE_LIMIT = 160;

    private PipelineProbe() {
    }

    static void install(ClassLoader loader) {
        for (String className : TARGETS) {
            int hooked = Hooks.hookEveryMethod(className, loader, new Tracer(className));
            Log.i(TAG, "probe: traced " + hooked + " method(s) of " + className);
        }
    }

    /** Logs entry, exit, arguments and anything thrown. */
    private static final class Tracer extends XC_MethodHook {
        private final String owner;
        private final Map<String, Integer> counts = new HashMap<>();

        Tracer(String owner) {
            this.owner = owner;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String method = name(param);
            if (!budget(method, 1)) {
                return;
            }
            Log.i(TAG, "probe: -> " + method + "(" + describe(param.args) + ")");
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            String method = name(param);
            if (!budget(method, 0)) {
                return;
            }
            Throwable thrown = param.getThrowable();
            if (thrown != null) {
                Log.w(TAG, "probe: !! " + method + " threw " + thrown);
                return;
            }
            Log.i(TAG, "probe: <- " + method + " = " + describe(new Object[] {param.getResult()}));
        }

        private String name(MethodHookParam param) {
            try {
                java.lang.reflect.Member member = param.getMethod();
                if (member instanceof java.lang.reflect.Method) {
                    java.lang.reflect.Method method = (java.lang.reflect.Method) member;
                    return method.getDeclaringClass().getSimpleName() + "." + method.getName();
                }
                return String.valueOf(member);
            } catch (Throwable t) {
                return owner;
            }
        }

        /** True while this method still has log lines left. */
        private boolean budget(String method, int slot) {
            synchronized (counts) {
                Integer used = counts.get(method);
                int value = used == null ? 0 : used;
                if (value >= CALLS_PER_METHOD * 2) {
                    return false;
                }
                counts.put(method, value + 1);
                return true;
            }
        }
    }

    private static String describe(Object[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object value : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            if (value == null) {
                sb.append("null");
                continue;
            }
            String text;
            if (value instanceof Class) {
                text = ((Class<?>) value).getName();
            } else {
                try {
                    text = String.valueOf(value);
                } catch (Throwable t) {
                    text = value.getClass().getName();
                }
            }
            if (text.length() > VALUE_LIMIT) {
                text = text.substring(0, VALUE_LIMIT) + "…";
            }
            sb.append(value.getClass().getSimpleName()).append(':').append(text);
        }
        return sb.toString();
    }
}
