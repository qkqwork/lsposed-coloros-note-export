package com.qkqwork.noteexport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Tells the user how an export is getting on, on the phone itself.
 *
 * <p>A long export runs inside the Notes app and takes minutes, and the settings
 * screen cannot show that: it fires the export through a content provider and
 * the answer only comes back at the end. Until now the only progress was in
 * logcat, which is no use to somebody holding the phone, so the batch posts a
 * notification through the app it is running in — the app already has the
 * permission to post one, and the notification is updated in place rather than
 * piled up.
 *
 * <p>Every call is wrapped: a notification that cannot be posted must never be
 * the reason an export fails.
 */
final class ProgressNotifier {

    private static final String TAG = Main.TAG;

    private static final String CHANNEL = "note_export_progress";
    private static final int NOTIFICATION_ID = 0x0E40;

    private ProgressNotifier() {
    }

    static void start(Context context, int total) {
        post(context, "正在导出便签长图", total > 0 ? "共 " + total + " 条，请勿操作手机" : "准备中",
                false, -1, 0, total);
    }

    static void progress(Context context, int done, int total, String title) {
        String text = done + " / " + total + (title == null || title.isEmpty() ? "" : "：" + title);
        // The settings screen watches the same numbers through the provider.
        Progress.step(done, title);
        post(context, "正在导出便签长图", text, false, -1, done, total);
    }

    /** The export is over: the notification stops being permanent and says so. */
    static void finish(Context context, String message, boolean ok) {
        post(context, ok ? "便签长图导出完成" : "便签长图导出未完成",
                message + "\n可以继续使用手机了", true, ok ? 0 : 1, 0, 0);
    }

    /** Removes the progress notification, for a request that never started. */
    static void clear(Context context) {
        try {
            NotificationManager manager = manager(context);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
            // nothing to clear is not a problem
        }
    }

    private static void post(Context context, String title, String text, boolean finished,
            int tint, int done, int total) {
        try {
            NotificationManager manager = manager(context);
            if (manager == null) {
                return;
            }
            Notification.Builder builder = new Notification.Builder(context, CHANNEL);
            builder.setContentTitle(title);
            builder.setContentText(text);
            builder.setStyle(new Notification.BigTextStyle().bigText(text));
            builder.setSmallIcon(context.getApplicationInfo().icon);
            builder.setOngoing(!finished);
            builder.setAutoCancel(finished);
            builder.setOnlyAlertOnce(true);
            if (tint == 0) {
                builder.setColor(0xFF1565C0);
            } else if (tint > 0) {
                builder.setColor(0xFFC62828);
            }
            if (!finished && total > 0) {
                builder.setProgress(total, done, false);
            }
            builder.setShowWhen(false);
            PendingIntent open = settings(context);
            if (open != null) {
                builder.setContentIntent(open);
            }
            // A finished export is the moment someone wants the folder, and the
            // notification is where they will be looking: the action saves them
            // opening the app, which then opens the folder anyway.
            if (finished) {
                PendingIntent folder = folder(context);
                if (folder != null) {
                    builder.addAction(new Notification.Action.Builder(null, "打开导出目录", folder)
                            .build());
                }
            }
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            Log.w(TAG, "could not post the progress notification: " + t);
        }
    }

    /** Tapping the notification opens the module's own settings screen. */
    private static PendingIntent settings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context.getPackageName(), ConfigActivity.class.getName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The action that opens the export folder.
     *
     * <p>The file manager is asked for the folder as a document, the same way the
     * settings screen's own button does it; the intent is only built here, and
     * the system starts it when the action is tapped.
     */
    private static PendingIntent folder(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3ADownload"
                            + "%2F" + Uri.encode(ExportRequest.DIR)),
                    "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(context, 1, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            Log.w(TAG, "could not build the folder action: " + t);
            return null;
        }
    }

    private static NotificationManager manager(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return null;
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "便签导出进度",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("导出便签长图时的进度与结果");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        return manager;
    }
}
