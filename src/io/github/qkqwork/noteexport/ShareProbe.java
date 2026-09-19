package io.github.qkqwork.noteexport;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Learns how the Notes app itself renders a note as a picture.
 *
 * <p>Asking the app to draw the picture is the only way to get the note exactly
 * as the app shows it — fonts, spacing, skin, the lot. Our own renderer cannot
 * match that, and the user noticed.
 *
 * <p>Driving the app's renderer means answering two questions first, and neither
 * needs guessing: what does the share screen expect when it is opened, and does
 * it expose anything that takes a note and produces a picture? Both are answered
 * from inside the process — the Intent is dumped when the app opens the screen
 * itself, and the class's own methods are listed by reflection, which no amount
 * of obfuscation hides.
 *
 * <p>Everything logged here is deliberately verbose: it is a one-off look at the
 * app's own behaviour, and it is what a batch "export as the app would" has to
 * be built on.
 */
final class ShareProbe {

    private static final String TAG = Main.TAG;

    private static final String SHARE_CLASS =
            "com.nearme.note.activity.edit.SaveImageAndShare";

    /** Longest string an extra's value is printed as. */
    private static final int VALUE_LIMIT = 200;

    private static boolean methodsLogged;

    private ShareProbe() {
    }

    static void install(ClassLoader loader) {
        Hooks.findAndHook(SHARE_CLASS, loader, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object target = param.thisObject;
                        dumpIntent(target);
                        dumpMethods(target);
                    }
                });
        Hooks.findAndHook(SHARE_CLASS, loader, "createImageFile",
                int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object value = param.getResult();
                        Log.i(TAG, "probe: createImageFile(" + describe(param.args) + ") -> "
                                + (value == null ? "null" : value.getClass().getName()
                                        + " " + value));
                    }
                });
        // The picture is written after the file is created, so the moment the
        // screen goes away is the moment the file is complete.
        Hooks.findAndHook(SHARE_CLASS, loader, "onDestroy", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Log.i(TAG, "probe: the share screen closed");
            }
        });
    }

    /** Prints what the app passed in when it opened its own share screen. */
    private static void dumpIntent(Object target) {
        try {
            if (!(target instanceof Activity)) {
                return;
            }
            Activity activity = (Activity) target;
            Intent intent = activity.getIntent();
            if (intent == null) {
                Log.i(TAG, "probe: the share screen was opened without an intent");
                return;
            }
            Log.i(TAG, "probe: share screen intent action=" + intent.getAction()
                    + " data=" + intent.getData()
                    + " type=" + intent.getType()
                    + " extras=" + describeExtras(intent));
        } catch (Throwable t) {
            Log.w(TAG, "probe: could not read the intent: " + t);
        }
    }

    private static String describeExtras(Intent intent) {
        Bundle extras;
        try {
            extras = intent.getExtras();
        } catch (Throwable t) {
            return "(unreadable: " + t + ")";
        }
        if (extras == null || extras.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : extras.keySet()) {
            Object value;
            try {
                value = extras.get(key);
            } catch (Throwable t) {
                value = "(unreadable)";
            }
            String text = value == null ? "null" : value.toString();
            if (text != null && text.length() > VALUE_LIMIT) {
                text = text.substring(0, VALUE_LIMIT) + "…";
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(key).append('=')
                    .append(value == null ? "null"
                            : value.getClass().getSimpleName() + ":" + text);
        }
        return sb.toString();
    }

    /**
     * Lists the class's own methods. This is how a renderer entry point is found
     * without disassembling anything: obfuscation renames code, but the methods
     * a class declares are always visible at runtime.
     */
    private static void dumpMethods(Object target) {
        if (methodsLogged || target == null) {
            return;
        }
        methodsLogged = true;
        try {
            Class<?> type = target.getClass();
            Log.i(TAG, "probe: " + type.getName() + " declares:");
            Class<?> current = type;
            int shown = 0;
            while (current != null && shown < 60) {
                for (Method method : current.getDeclaredMethods()) {
                    if (shown++ >= 60) {
                        break;
                    }
                    Log.i(TAG, "probe:   " + (Modifier.isStatic(method.getModifiers())
                            ? "static " : "") + method.getReturnType().getSimpleName()
                            + " " + method.getName() + "(" + describe(method.getParameterTypes())
                            + ")");
                }
                current = current.getSuperclass();
            }
        } catch (Throwable t) {
            Log.w(TAG, "probe: could not list the methods: " + t);
        }
    }

    private static String describe(Object[] values) {
        if (values == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object value : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            if (value instanceof Class) {
                sb.append(((Class<?>) value).getSimpleName());
            } else {
                sb.append(value);
            }
        }
        return sb.toString();
    }
}
