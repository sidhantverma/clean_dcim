package com.cleandcim;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

final class Prefs {
    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("watch", Context.MODE_PRIVATE);
    }

    static String source(Context context) {
        return get(context).getString("source",
                Environment.getExternalStorageDirectory() + "/DCIM");
    }

    static String destination(Context context) {
        return get(context).getString("destination",
                Environment.getExternalStorageDirectory() + "/DCIM-Sorted");
    }

    static void status(Context context, String status) {
        get(context).edit().putString("status", status).apply();
    }
}
