package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-time stub only; see {@link IXposedHookLoadPackage}.
 *
 * <p>Only {@link #hookMethod} is declared, and deliberately so. Measured on this
 * device (LSPosed with an obfuscated API, ColorOS 16 / Android 16), none of the
 * {@code XposedHelpers.findAndHookMethod} overloads could be resolved at runtime
 * — including the plain string-based varargs one — while the module was injected
 * and running. {@code hookMethod} is the primitive every other helper is built
 * on, so the module resolves the {@link Member} itself with plain reflection and
 * hooks it here. See {@code com.dsh.noteexport.Hooks}.
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod,
            XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }
}
