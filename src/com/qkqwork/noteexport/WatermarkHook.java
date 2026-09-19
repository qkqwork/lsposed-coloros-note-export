package com.qkqwork.noteexport;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Removes (or replaces) the ColorOS watermark that the Notes app stamps on the
 * bottom of a note when it is shared as a picture.
 *
 * <p>This is the half a post-processing export cannot do: the watermark is part
 * of the layout the app draws, so it has to be dealt with while the app builds
 * that picture. The views live in the app's own {@code color_os_logo.xml}:
 *
 * <pre>
 *   logo_ll        (mLogoLinearLayout)   the whole row
 *     line         (mLine)               the divider above it
 *     water_mark_ll
 *       water_mark (mWaterMark)          "ColorOS"
 *       share_logo (mShareLogo)          the app name
 *   share_logo_original (mShareLogoOriginal)   used on non-ColorOS devices
 * </pre>
 *
 * <p>Hiding {@code logo_ll} keeps the watermark out of the shared picture too,
 * because the bitmap is drawn from the container that holds it. Every name here
 * was confirmed against the installed Notes 16.6.22 dex with
 * {@code build/find-watermark-targets.py} instead of being assumed.
 *
 * <p>Nothing is expected to succeed unconditionally: each class, method and
 * field is looked up defensively and reported to the log, so an app update shows
 * up as a log line rather than a silent no-op.
 */
final class WatermarkHook {

    private static final String TAG = Main.TAG;

    /** The share-as-picture screen, and the paint-notes equivalent. */
    private static final String[] SHARE_CLASSES = {
            "com.nearme.note.activity.edit.SaveImageAndShare",
            "com.nearme.note.paint.ShareFragment",
    };

    private WatermarkHook() {
    }

    static void install(ClassLoader loader) {
        for (String className : SHARE_CLASSES) {
            if (Hooks.findClass(className, loader) == null) {
                Log.i(TAG, "watermark: " + className + " is not in this build");
                continue;
            }
            // setLogo() runs after the app has filled the views in;
            // createImageFile() runs just before the bitmap is drawn, which is
            // the last moment at which hiding the row still affects the picture.
            hook(className, loader, "setLogo", null, Hook.AFTER);
            hook(className, loader, "createImageFile",
                    new Object[] {int.class, int.class, int.class}, Hook.BEFORE);
        }
    }

    private enum Hook { BEFORE, AFTER }

    private static void hook(String className, ClassLoader loader, String method,
            Object[] parameterTypes, Hook when) {
        Object[] args = new Object[(parameterTypes == null ? 0 : parameterTypes.length) + 1];
        if (parameterTypes != null) {
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
        }
        args[args.length - 1] = when == Hook.AFTER
                ? new ApplyAfter(className, method) : new ApplyBefore(className, method);
        if (Hooks.findAndHook(className, loader, method, args)) {
            Log.i(TAG, "watermark: hooked " + className + "." + method + "()");
        }
    }

    /** Runs once the app has filled its logo views in. */
    private static final class ApplyAfter extends XC_MethodHook {
        private final String where;

        ApplyAfter(String className, String method) {
            this.where = className + "." + method;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            applyQuietly(param.thisObject, where);
        }
    }

    /** Runs immediately before the picture is drawn. */
    private static final class ApplyBefore extends XC_MethodHook {
        private final String where;

        ApplyBefore(String className, String method) {
            this.where = className + "." + method;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            applyQuietly(param.thisObject, where);
        }
    }

    private static void applyQuietly(Object target, String from) {
        try {
            apply(target, from);
        } catch (Throwable t) {
            Log.w(TAG, "watermark: apply() failed in " + from + ": " + t);
        }
    }

    private static void apply(Object target, String from) {
        WatermarkSettings settings = WatermarkSettings.read(asContext(target));
        if (!settings.active()) {
            Log.i(TAG, "watermark: left alone by request");
            return;
        }

        View row = view(target, "mLogoLinearLayout");
        View line = view(target, "mLine");
        View waterMark = view(target, "mWaterMark");
        View shareLogo = view(target, "mShareLogo");
        View shareLogoOriginal = view(target, "mShareLogoOriginal");
        if (row == null && waterMark == null && shareLogo == null
                && shareLogoOriginal == null) {
            Log.w(TAG, "watermark: none of the logo views exist on "
                    + target.getClass().getName());
            return;
        }

        if (settings.custom() && settings.text.length() > 0) {
            // Keep the row, drop the "ColorOS" half, and put the user's text
            // where the app name was.
            setVisibility(row, View.VISIBLE);
            setVisibility(line, View.VISIBLE);
            setVisibility(waterMark, View.GONE);
            setText(shareLogo, settings.text);
            setText(shareLogoOriginal, settings.text);
            setVisibility(shareLogo, View.VISIBLE);
            setVisibility(shareLogoOriginal, View.VISIBLE);
            Log.i(TAG, "watermark: replaced with \"" + settings.text + "\" in " + from);
            return;
        }

        if (ConfigContract.WATERMARK_KEEP_SPACE.equals(settings.mode)) {
            // The row keeps its measured height, so the picture keeps the same
            // bottom padding it had — only blank.
            setVisibility(row, View.VISIBLE);
            setVisibility(line, View.INVISIBLE);
            setVisibility(waterMark, View.INVISIBLE);
            setVisibility(shareLogo, View.INVISIBLE);
            setVisibility(shareLogoOriginal, View.INVISIBLE);
            Log.i(TAG, "watermark: blanked, spacing kept, in " + from);
            return;
        }

        // Default: the whole row goes away, divider included, so no empty gap is
        // left at the bottom of the picture.
        setVisibility(row, View.GONE);
        setVisibility(line, View.GONE);
        setVisibility(waterMark, View.GONE);
        setVisibility(shareLogo, View.GONE);
        setVisibility(shareLogoOriginal, View.GONE);
        Log.i(TAG, "watermark: removed in " + from);
    }

    /** The activity behind the hooked object, whichever kind of object it is. */
    private static Context asContext(Object target) {
        if (target instanceof Context) {
            return (Context) target;
        }
        for (String name : new String[] {"getActivity", "getContext", "requireActivity",
                "requireContext"}) {
            try {
                Method method = target.getClass().getMethod(name);
                Object value = method.invoke(target);
                if (value instanceof Context) {
                    return (Context) value;
                }
            } catch (Throwable ignored) {
                // that class simply does not have this accessor
            }
        }
        return null;
    }

    private static View view(Object target, String field) {
        Object value = Hooks.field(target, field);
        if (value == null) {
            Log.i(TAG, "watermark: no field " + field + " on "
                    + target.getClass().getSimpleName());
            return null;
        }
        return value instanceof View ? (View) value : null;
    }

    private static void setVisibility(View view, int visibility) {
        if (view == null) {
            return;
        }
        try {
            view.setVisibility(visibility);
        } catch (Throwable t) {
            Log.w(TAG, "watermark: setVisibility failed: " + t);
        }
    }

    private static void setText(View view, String text) {
        if (view == null || text == null || text.length() == 0) {
            return;
        }
        try {
            ((TextView) view).setText(text);
        } catch (Throwable t) {
            Log.w(TAG, "watermark: setText failed: " + t);
        }
    }
}
