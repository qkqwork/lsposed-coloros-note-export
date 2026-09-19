package de.robv.android.xposed;

/**
 * Compile-time stub only; see {@link IXposedHookLoadPackage}.
 *
 * <p><b>Only the string-based varargs form is declared, on purpose.</b> That is
 * the only shape this device's framework actually has. Measured on LSPosed
 * (obfuscated API, ColorOS 16 / Android 16) with the module injected into the
 * Notes process:
 *
 * <pre>
 *   NoSuchMethodError: No static method findAndHookMethod(Ljava/lang/String;
 *     Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;
 *     Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;LXC_MethodHook;)V
 *   NoSuchMethodError: No static method findAndHookMethod(Ljava/lang/Class;
 *     Ljava/lang/String;[Ljava/lang/Object;)V
 * </pre>
 *
 * <p>So declaring "convenience" overloads here is a trap rather than a help:
 * the module compiles against them and then loses every hook at runtime, with a
 * log line that is easy to overlook. Everything else this module needs — class
 * lookup, field access — is done with plain Java reflection instead; see
 * {@code com.qkqwork.noteexport.Hooks}.
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static void findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }
}
