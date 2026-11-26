package com.cleandcim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!Prefs.get(context).getBoolean("enabled", false)) {
            return;
        }
        if (!MainActivity.permitted(context)) {
            Prefs.get(context).edit().putBoolean("enabled", false).apply();
            Prefs.status(context, context.getString(R.string.error_status, "Permissions are missing."));
            return;
        }
        try {
            context.startForegroundService(new Intent(context, WatchService.class));
        } catch (RuntimeException error) {
            Log.e("CleanDCIM", "Cannot restart watch after boot/update", error);
            Prefs.get(context).edit().putBoolean("enabled", false).apply();
            Prefs.status(context, context.getString(R.string.error_status, error.getMessage()));
        }
    }
}
