package com.cleandcim;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class EventLog extends SQLiteOpenHelper {
    static final int PAGE_SIZE = 200;
    private static EventLog instance;
    private final SharedPreferences preferences;

    static synchronized EventLog get(Context context) {
        if (instance == null) {
            instance = new EventLog(context.getApplicationContext());
        }
        return instance;
    }

    private EventLog(Context context) {
        super(context, "events.db", null, 1);
        preferences = Prefs.get(context);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "timestamp INTEGER NOT NULL, message TEXT NOT NULL)");
        db.execSQL("CREATE INDEX event_age ON events(timestamp)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported log database upgrade");
    }

    synchronized void add(String message) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            prune(db, now);
            ContentValues values = new ContentValues();
            values.put("timestamp", now);
            values.put("message", message);
            db.insertOrThrow("events", null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
    }

    synchronized void prune() {
        if (prune(getWritableDatabase(), System.currentTimeMillis()) > 0) {
            changed();
        }
    }

    private int prune(SQLiteDatabase db, long now) {
        return db.delete("events", "timestamp <= ?",
                new String[]{Long.toString(LogEntry.cutoff(now))});
    }

    synchronized List<String> read(int offset) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        prune(db, now);
        List<String> entries = new ArrayList<>();
        try (Cursor cursor = db.query("events", new String[]{"timestamp", "message"},
                "timestamp > ?", new String[]{Long.toString(LogEntry.cutoff(now))},
                null, null, "timestamp DESC, id DESC", offset + "," + PAGE_SIZE)) {
            while (cursor.moveToNext()) {
                entries.add(LogEntry.render(cursor.getLong(0), cursor.getString(1)));
            }
        }
        return entries;
    }

    synchronized void clear() {
        getWritableDatabase().delete("events", null, null);
        changed();
    }

    private void changed() {
        preferences.edit().putLong("logRevision", System.nanoTime()).apply();
    }
}
