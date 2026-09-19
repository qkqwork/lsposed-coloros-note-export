package io.github.qkqwork.noteexport;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The single place in the module that talks to the Xposed API.
 *
 * <p>It does as little of that as possible. On this device the framework ships
 * with its API obfuscated, and in that form the usual
 * {@code XposedHelpers.findAndHookMethod} entry points could not be resolved at
 * runtime at all — the module injected, and then lost every single hook to a
 * {@code NoSuchMethodError} while looking perfectly healthy. Measured inside the
 * injected Notes process:
 *
 * <pre>
 *   NoSuchMethodError: No static method findAndHookMethod(Ljava/lang/String;
 *     Ljava/lang/ClassLoader;Ljava/lang/String;[Ljava/lang/Object;)V
 * </pre>
 *
 * <p>So the module finds the method it wants with plain Java reflection, on the
 * app's own class loader, and hands the {@link java.lang.reflect.Member} to
 * {@code XposedBridge.hookMethod} — the primitive that the convenience helpers
 * are built on and the one thing the framework must provide. If that ever fails
 * too, {@link #reportApi()} says so in the log at start-up instead of leaving a
 * silent no-op behind.
 */
final class Hooks {

    private static final String TAG = Main.TAG;

    private Hooks() {
    }

    /**
     * Hooks a method by class name.
     *
     * @param parameterTypesAndCallback the parameter classes followed by the
     *                                  {@code XC_MethodHook}
     * @return whether the hook was installed
     */
    static boolean findAndHook(String className, ClassLoader loader, String method,
            Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0) {
            Log.w(TAG, "hook " + className + "." + method + ": no callback was given");
            return false;
        }
        Object callback = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        if (!(callback instanceof XC_MethodHook)) {
            Log.w(TAG, "hook " + className + "." + method + ": last argument is not a hook");
            return false;
        }
        Class<?>[] types = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < types.length; i++) {
            Object value = parameterTypesAndCallback[i];
            if (!(value instanceof Class)) {
                Log.w(TAG, "hook " + className + "." + method + ": argument " + i
                        + " is not a class");
                return false;
            }
            types[i] = (Class<?>) value;
        }

        Class<?> target = findClass(className, loader);
        if (target == null) {
            Log.w(TAG, "hook " + className + ": class not found");
            return false;
        }
        Method member = findMethod(target, method, types);
        if (member == null) {
            Log.w(TAG, "hook " + className + "." + method + ": no such method in this build");
            return false;
        }
        try {
            member.setAccessible(true);
        } catch (Throwable ignored) {
            // Hooking does not need it; the call still works.
        }
        try {
            XposedBridge.hookMethod(member, (XC_MethodHook) callback);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "hook " + className + "." + method + " failed: " + t);
            return false;
        }
    }

    /** The method as declared, searching up the hierarchy like the framework does. */
    private static Method findMethod(Class<?> type, String name, Class<?>[] parameters) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                if (!Modifier.isAbstract(method.getModifiers())) {
                    return method;
                }
            } catch (NoSuchMethodException missing) {
                // try the superclass, then the interfaces it implements
            }
            for (Class<?> iface : current.getInterfaces()) {
                Method method = findMethod(iface, name, parameters);
                if (method != null) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * Hooks every method a class declares.
     *
     * <p>For exploration: the classes that matter here are obfuscated enough that
     * their method signatures are unknown, and a tracer that only needs names
     * answers "what does the app actually run, and in what order" without any
     * disassembly at all.
     *
     * @return how many methods were hooked
     */
    static int hookEveryMethod(String className, ClassLoader loader, XC_MethodHook hook) {
        Class<?> type = findClass(className, loader);
        if (type == null) {
            return 0;
        }
        int hooked = 0;
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.getName().startsWith("lambda$")) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, hook);
                    hooked++;
                } catch (Throwable t) {
                    Log.w(TAG, "could not hook " + className + "." + method.getName()
                            + ": " + t);
                }
            }
            if (type == Object.class) {
                break;
            }
            type = type.getSuperclass();
        }
        return hooked;
    }

    /**
     * Hooks every method of a class with one of these names.
     *
     * <p>The narrow version of {@link #hookEveryMethod}: an obfuscated method's
     * signature is unknown, but its name is not, and hooking exactly one name
     * keeps the cost off everything else — which matters when the class is also
     * used while the app starts.
     *
     * @return how many methods were hooked
     */
    static int hookMethodsNamed(String className, ClassLoader loader, String name,
            XC_MethodHook hook) {
        return hookMethodsNamed(className, loader, name, hook, false);
    }

    /**
     * As above, optionally hooking only the methods the class declares itself.
     *
     * <p>That matters for a lifecycle method like {@code onCreate}: hooking the
     * inherited one as well makes the callback fire twice for a single call.
     */
    static int hookMethodsNamed(String className, ClassLoader loader, String name,
            XC_MethodHook hook, boolean declaredOnly) {
        Class<?> type = findClass(className, loader);
        if (type == null) {
            Log.i(TAG, "no " + className + " in this build");
            return 0;
        }
        int hooked = 0;
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (!name.equals(method.getName())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, hook);
                    hooked++;
                } catch (Throwable t) {
                    Log.w(TAG, "could not hook " + className + "." + name + ": " + t);
                }
            }
            if (declaredOnly) {
                break;
            }
            current = current.getSuperclass();
        }
        return hooked;
    }

    /**
     * Hooks every method of a class whose name starts with a prefix.
     *
     * <p>Kotlin appends a hash to the name of a suspend function that has a
     * value class in its signature — {@code saveBitmap} becomes
     * {@code saveBitmap-0E7RQCE} — and the hash changes when the app is
     * rebuilt, so the prefix is what can be relied on.
     */
    static int hookMethodsStartingWith(String className, ClassLoader loader, String prefix,
            XC_MethodHook hook) {
        Class<?> type = findClass(className, loader);
        if (type == null) {
            Log.i(TAG, "no " + className + " in this build");
            return 0;
        }
        int hooked = 0;
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().startsWith(prefix)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, hook);
                    hooked++;
                    Log.i(TAG, "hooked " + className + "." + method.getName());
                } catch (Throwable t) {
                    Log.w(TAG, "could not hook " + className + "." + method.getName() + ": " + t);
                }
            }
            current = current.getSuperclass();
        }
        return hooked;
    }

    /**
     * Writes a class's declared methods to the log.
     *
     * <p>Pure reflection with no hooks at all, which is the safe way to find out
     * what to hook: {@code initialize} is false, so nothing of the app's runs.
     */
    static void logDeclaredMethods(String className, ClassLoader loader) {
        Class<?> type = findClass(className, loader);
        if (type == null) {
            Log.i(TAG, "inventory: " + className + " is not in this build");
            return;
        }
        Log.i(TAG, "inventory: " + className + " extends " + type.getSuperclass());
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic() || method.getName().startsWith("lambda$")) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(parameter.getSimpleName());
                }
                Log.i(TAG, "inventory:   "
                        + (Modifier.isStatic(method.getModifiers()) ? "static " : "")
                        + method.getReturnType().getSimpleName() + " "
                        + method.getName() + "(" + sb + ")");
            }
            current = current.getSuperclass();
        }
    }

    /** The class as the hooked app sees it, or null when it is not there. */
    static Class<?> findClass(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads a field, walking up the class hierarchy the way the framework does. */
    static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                Log.w(TAG, "could not read field " + name + ": " + t);
                return null;
            }
        }
        return null;
    }

    /**
     * Logs the framework API this process actually has.
     *
     * <p>Kept in the module on purpose: when a hook silently does nothing, the
     * first question is whether the API being called exists here at all, and
     * three compact log lines answer it.
     */
    static void reportApi() {
        describe("XposedBridge", XposedBridge.class);
        describe("XposedHelpers", XposedHelpers.class);
        describe("IXposedHookLoadPackage", IXposedHookLoadPackage.class);
    }

    private static void describe(String label, Class<?> type) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(type.getName()).append(" -> ");
            Method[] methods = type.getDeclaredMethods();
            sb.append(methods.length).append(" methods: ");
            for (int i = 0; i < methods.length && i < 12; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(methods[i].getName()).append('/')
                        .append(methods[i].getParameterTypes().length);
            }
            if (methods.length > 12) {
                sb.append(", …");
            }
        } catch (Throwable t) {
            sb.append("could not be inspected: ").append(t);
        }
        Log.i(TAG, "api " + label + ": " + sb);
    }
}
