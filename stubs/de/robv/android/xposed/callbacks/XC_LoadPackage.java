package de.robv.android.xposed.callbacks;

/** Compile-time stub only; see {@link de.robv.android.xposed.IXposedHookLoadPackage}. */
public abstract class XC_LoadPackage {

    public static class LoadPackageParam {

        /** Package name of the app being loaded. */
        public String packageName;

        /** Class loader of the app being loaded. */
        public ClassLoader classLoader;

        /** Process name of the app being loaded. */
        public String processName;

        public boolean isFirstApplication;
    }

    public abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
