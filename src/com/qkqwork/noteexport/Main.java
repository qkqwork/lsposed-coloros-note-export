package com.qkqwork.noteexport;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ContentProvider;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry point.
 *
 * <p>Everything the module does happens inside the Notes process, because that
 * is the only place where the app's private database and attachment files can be
 * read. Getting code to run there is easy; getting it to run <em>on demand</em>
 * from the module's own settings screen is the interesting part, and it is done
 * through the Notes app's own exported provider:
 *
 * <pre>
 *   settings screen --query--&gt; content://&lt;notes authority&gt;/note_dsh_export
 *                                    |
 *                                    v
 *                    this hook answers that query, after running the export
 * </pre>
 *
 * <p>That single trick gives three things at once: a trustworthy {@link Context}
 * for the Notes app, execution in the process that can read its data, and — as
 * a side effect of querying a provider — a cold start of the Notes app when it
 * is not running.
 *
 * <p>The export format travels in a small request file (see
 * {@link ExportRequest}) rather than over the provider, because the Notes
 * process has no usable Context for the module's own package.
 *
 * <p>A second path exists for frameworks or ROMs that block the provider query:
 * the settings screen records the request, opens the Notes app, and
 * {@link #hookApplicationStart} picks it up on the next process start.
 */
public class Main implements IXposedHookLoadPackage {

    public static final String TAG = "[NoteExport] ";

    private static final String TARGET_PKG = ConfigContract.NOTES_PKG;

    /**
     * The Notes app's own backup provider. It must stay queryable by third
     * party apps for the app's backup feature to work, which is exactly what
     * makes it usable as a trigger. Several candidate names are tried so a
     * rename in one ColorOS release does not disable the module.
     */
    private static final String[] PROVIDER_CLASSES = {
            "com.oplus.migrate.backuprestore.NoteBackupRestoreProvider",
            "com.coloros.migrate.backuprestore.NoteBackupRestoreProvider",
            "com.oplus.migrate.backuprestore.BackupRestoreProvider",
            "com.oplus.backuprestore.NoteBackupRestoreProvider",
    };

    /** Guards against two exports running at once. */
    private static boolean exportRunning;

    /**
     * The provider class the trigger was installed on, or null when none of the
     * candidates existed. The diagnostics report quotes it, so which authority
     * works on this ROM is a recorded fact instead of a guess.
     */
    static volatile String hookedProviderClass;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        // Logged before the package check on purpose. When a module is enabled
        // but apparently does nothing, the first question is whether it reached
        // the process at all — and this line answers exactly that.
        Log.i(TAG, "loaded into " + param.processName + " (" + param.packageName + ")");
        if (!TARGET_PKG.equals(param.packageName)) {
            return;
        }
        Log.i(TAG, "injecting into " + param.packageName);
        Hooks.reportApi();
        hookExportTrigger(param);
        hookApplicationStart(param.classLoader);
        WatermarkHook.install(param.classLoader);
        // The diagnostic probes are not installed here any more. They hook whole
        // families of methods and print inventories of the app's classes, which
        // is noise in every day's log and a cost at every app start; they are
        // installed on demand instead, when an export asks for debug logging.
        injectedLoader = param.classLoader;
        NativeImageExport.install(param.classLoader);
        NativeBatchExport.install(param.classLoader);
    }

    /** The hooked app's loader, so the probes can be installed later on demand. */
    private static volatile ClassLoader injectedLoader;
    private static volatile boolean probesInstalled;

    /**
     * Installs the diagnostic probes, once, for a debug export.
     *
     * <p>Hooks can be added while the app is running, which is what makes this
     * possible: a user who turns debug logging on gets the detail immediately
     * rather than after the next launch of the Notes app.
     */
    private static void enableProbes() {
        ClassLoader loader = injectedLoader;
        if (loader == null || probesInstalled) {
            return;
        }
        synchronized (Main.class) {
            if (probesInstalled) {
                return;
            }
            probesInstalled = true;
        }
        Log.i(TAG, "debug logging is on: installing the diagnostic probes");
        try {
            ShareProbe.install(loader);
            CaptureProbe.install(loader);
        } catch (Throwable t) {
            Log.w(TAG, "the probes could not be installed: " + t);
        }
    }

    // ------------------------------------------------------ the export trigger

    /**
     * The notes as the settings screen needs them: what to show in a list of
     * things to pick from, and nothing more.
     *
     * <p>It runs inside the Notes app, which is the only process that can read the
     * database; the settings screen asks for it through the same provider the
     * export trigger uses. Every note's own text stays here.
     */
    private static Cursor listNotes(Context context) {
        MatrixCursor cursor = new MatrixCursor(ConfigContract.LIST_COLUMNS);
        if (context == null) {
            return cursor;
        }
        try {
            NoteStore.Snapshot snapshot = NoteStore.read(context, true);
            for (java.util.Map.Entry<String, java.util.List<Note>> group
                    : NoteExporter.groupedByCategory(snapshot).entrySet()) {
                for (Note note : group.getValue()) {
                    cursor.addRow(new Object[] {
                            note.id,
                            NoteExporter.titleOf(note),
                            group.getKey(),
                            note.text == null ? 0 : note.text.length(),
                            note.encrypted ? 1 : 0,
                            note.recycled ? 1 : 0});
                }
            }
            Log.i(TAG, "listed " + cursor.getCount() + " note(s) for the settings screen");
        } catch (Throwable t) {
            Log.w(TAG, "listing the notes failed: " + t);
        }
        return cursor;
    }

    private void hookExportTrigger(XC_LoadPackage.LoadPackageParam param) {
        boolean hooked = false;
        for (String className : PROVIDER_CLASSES) {
            boolean installed = Hooks.findAndHook(className, param.classLoader, "query",
                    Uri.class, String[].class, String.class, String[].class,
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam method)
                                throws Throwable {
                            Uri uri = (Uri) method.args[0];
                            if (uri == null) {
                                return;
                            }
                            String segment = uri.getLastPathSegment();
                            if (ConfigContract.PROBE_SEGMENT.equals(segment)) {
                                method.setResult(probeCursor());
                                return;
                            }
                            if (ConfigContract.LIST_SEGMENT.equals(segment)) {
                                Context context = ((ContentProvider) method.thisObject)
                                        .getContext();
                                method.setResult(listNotes(context));
                                return;
                            }
                            if (ConfigContract.DIAG_SEGMENT.equals(segment)) {
                                Context context = ((ContentProvider) method.thisObject)
                                        .getContext();
                                method.setResult(runDiagnostics(context));
                                return;
                            }
                            if (!ConfigContract.EXPORT_SEGMENT.equals(segment)) {
                                return;
                            }
                            Context context =
                                    ((ContentProvider) method.thisObject).getContext();
                            // The options arrive in the query's selection argument;
                            // the request file is only the older, less reliable path.
                            String selection = method.args.length > 2
                                    ? (String) method.args[2] : null;
                            method.setResult(runExport(context, selection));
                        }
                    });
            if (installed) {
                Log.i(TAG, "hooked " + className + ".query()");
                hookedProviderClass = className;
                hooked = true;
                break;
            }
        }
        if (!hooked) {
            Log.e(TAG, "no export provider hook installed; the settings screen will "
                    + "fall back to opening the Notes app instead");
        }
    }

    private static MatrixCursor probeCursor() {
        MatrixCursor cursor = new MatrixCursor(ConfigContract.EXPORT_COLUMNS);
        cursor.addRow(new Object[] {1, "模块已注入便签进程", "", null});
        return cursor;
    }

    /**
     * Writes an environment report into Downloads and answers with its path.
     *
     * <p>Same caller check as an export: the report quotes note text, and the
     * provider is world-queryable.
     */
    private static Cursor runDiagnostics(Context context) {
        if (context == null) {
            return answer(false, "诊断失败：没有可用的便签上下文", "");
        }
        if (!callerAllowed(context)) {
            return answer(false, "诊断被拒绝：调用方不是本模块", "");
        }
        Diagnostics.Result result = Diagnostics.run(context);
        return answer(result.ok, result.message, result.path);
    }

    /**
     * Runs the export and answers the caller with a one row cursor.
     *
     * <p>The caller check matters: the provider is world-queryable, so without
     * it any app on the device could pull the user's notes into Downloads just
     * by asking for the right path.
     */
    private static Cursor runExport(Context context, String selection) {
        if (context == null) {
            return answer(false, "导出失败：没有可用的便签上下文", "");
        }
        if (!callerAllowed(context)) {
            int uid = Binder.getCallingUid();
            Log.w(TAG, "refusing an export request from uid " + uid);
            return answer(false, "导出被拒绝：调用方不是本模块", "");
        }

        ExportRequest.Request request = ExportRequest.read();
        ExportOptions options = selection == null || selection.length() == 0
                ? request.options : ExportRequest.parseSelection(selection);
        if (options.debug) {
            enableProbes();
        }
        // Which thread this runs on decides whether the WebView-based image
        // export can work at all: WebView callbacks are delivered on the main
        // thread, so an export occupying that thread can never see them.
        Log.i(TAG, "export runs on " + (Looper.myLooper() == Looper.getMainLooper()
                ? "the MAIN thread" : "a background thread"));
        // Logged because the options have to cross a process boundary: when an
        // export comes out in the wrong format, this says what was understood.
        Log.i(TAG, "export options from "
                + (selection == null || selection.length() == 0
                        ? "the request file" : "the query")
                + ": format=" + options.format + " layout=" + options.wordLayout
                + " recycled=" + options.includeRecycled);
        synchronized (Main.class) {
            if (exportRunning) {
                return answer(false, "已有一个导出任务在进行中", "");
            }
            exportRunning = true;
            try {
                NoteExporter.Result result = NoteExporter.export(context, options);
                if (request.id > 0) {
                    // Recorded so the fallback path does not repeat this export
                    // the next time the Notes app starts.
                    ExportRequest.markHandled(context, request.id);
                }
                return answer(result.ok, result.message, result.path, result.thumbnail);
            } catch (Throwable t) {
                Log.e(TAG, "export threw", t);
                return answer(false, "导出失败：" + t, "");
            } finally {
                exportRunning = false;
            }
        }
    }

    private static MatrixCursor answer(boolean ok, String message, String path) {
        return answer(ok, message, path, null);
    }

    private static MatrixCursor answer(boolean ok, String message, String path,
            byte[] thumbnail) {
        MatrixCursor cursor = new MatrixCursor(ConfigContract.EXPORT_COLUMNS);
        cursor.addRow(new Object[] {ok ? 1 : 0, message, path, thumbnail});
        Log.i(TAG, "export answer: ok=" + ok + " " + message
                + (path == null || path.isEmpty() ? "" : " -> " + path)
                + (thumbnail == null ? "" : " (+" + thumbnail.length + " byte preview)"));
        return cursor;
    }

    /** Only this module, root, or the shell may ask for an export. */
    private static boolean callerAllowed(Context context) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == 0 || uid == 2000) {
            // The shell (adb) is allowed because that is what makes the feature
            // testable with `adb shell content query`.
            return true;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String name : packages) {
                    if (ConfigContract.MODULE_PKG.equals(name)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not resolve the caller: " + t);
        }
        return false;
    }

    // -------------------------------------------------------- the fallback path

    /**
     * Runs an export that was requested while the Notes app was not running.
     *
     * <p>Hooked on the app's own start and run off the main thread, because
     * nothing here may slow down launching the Notes app.
     */
    private void hookApplicationStart(ClassLoader loader) {
        // Hooked by class name rather than by Class: see Hooks for why the
        // Class-taking overload is not usable on this framework.
        boolean installed = Hooks.findAndHook(Instrumentation.class.getName(), loader,
                "callApplicationOnCreate", Application.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam method)
                            throws Throwable {
                        Application app = (Application) method.args[0];
                        if (app != null && TARGET_PKG.equals(app.getPackageName())) {
                            runPendingExport(app);
                        }
                    }
                });
        if (installed) {
            Log.i(TAG, "hooked Instrumentation.callApplicationOnCreate()");
        }
    }

    private static void runPendingExport(final Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ExportRequest.Request request = ExportRequest.read();
                    if (request.id <= 0) {
                        return;
                    }
                    if (ExportRequest.handled(context) >= request.id) {
                        return;
                    }
                    // Claimed before running: a crash mid-export must not make
                    // the Notes app export again on every single launch.
                    ExportRequest.markHandled(context, request.id);

                    synchronized (Main.class) {
                        if (exportRunning) {
                            return;
                        }
                        exportRunning = true;
                    }
                    Log.i(TAG, "running pending export " + request.id);
                    NoteExporter.Result result =
                            NoteExporter.export(context, request.options);
                    toast(context, result.message);
                } catch (Throwable t) {
                    Log.e(TAG, "pending export failed", t);
                } finally {
                    synchronized (Main.class) {
                        exportRunning = false;
                    }
                }
            }
        }, "note-export-pending").start();
    }

    // ----------------------------------------------------------------- helpers

    private static void toast(final Context context, final String message) {
        if (message == null || message.length() == 0) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Log.w(TAG, "could not show a toast: " + t);
                }
            }
        });
    }
}
