package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Compile-time stub only. The real class is provided by LSPosed at runtime, and
 * the build script strips this package out of the dex — the framework refuses
 * modules that bundle the Xposed API.
 */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
