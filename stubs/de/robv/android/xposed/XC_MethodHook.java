package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-time stub only; see {@link IXposedHookLoadPackage}. */
public abstract class XC_MethodHook {

    /** Hook priority constants, kept for source compatibility. */
    public static final int PRIORITY_HIGHEST = 10000;
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;

    public XC_MethodHook() {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {

        /** The instance the hooked method was called on, or null for statics. */
        public Object thisObject;

        /** The arguments of the call; writable. */
        public Object[] args;

        private Object result;
        private Throwable throwable;
        private Member method;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }

        public void setResultAndThrowable(Object result, Throwable throwable) {
            this.result = result;
            this.throwable = throwable;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Member getMethod() {
            return method;
        }
    }

    /** What {@code XposedBridge.hookMethod} hands back; unhooking is unused here. */
    public static class Unhook {

        public void unhook() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
