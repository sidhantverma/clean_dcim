package com.cleandcim;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

final class Notifications {
    static final String WATCH = "watch";
    static final String ERRORS = "move_errors";

    private Notifications() {}

    static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(WATCH,
                context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(ERRORS,
                context.getString(R.string.error_channel_name), NotificationManager.IMPORTANCE_DEFAULT));
    }

    static boolean allowed(Context context, String channel) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel category = manager.getNotificationChannel(channel);
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && manager.areNotificationsEnabled()
                && category != null && category.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
}
