package com.cleandcim;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.ContentResolver;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class WatchService extends Service {
    static final String STOP = "com.cleandcim.STOP";
    private static final int NOTIFICATION_ID = 1;
    private static final int MASK = FileObserver.CREATE | FileObserver.MOVED_TO
            | FileObserver.MODIFY | FileObserver.CLOSE_WRITE
            | FileObserver.DELETE | FileObserver.MOVED_FROM
            | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF;
    private final Map<Path, FileObserver> observers = new HashMap<>();
    private FileReadiness readiness;
    private final Map<Path, String> waitingReasons = new HashMap<>();
    private HandlerThread thread;
    private Handler worker;
    private MoveEngine engine;
    private ContentObserver mediaObserver;
    private volatile boolean stopping;
    private boolean started;
    private boolean failed;
    private long nextScan = Long.MAX_VALUE;
    private int movedCount;
    private final Runnable scan = this::scan;

    @Override public void onCreate() {
        super.onCreate();
        Notifications.createChannels(this);
        thread = new HandlerThread("DCIM watcher");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            Prefs.get(this).edit().putBoolean("enabled", false).apply();
            stopping = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Prefs.get(this).getBoolean("enabled", false) || !MainActivity.permitted(this)) {
            fail(new IOException("Required permissions are missing, or watch is disabled."));
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(getString(R.string.watching_detail)),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification(getString(R.string.watching_detail)));
            }
        } catch (RuntimeException error) {
            fail(error);
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            getSystemService(NotificationManager.class).cancel(2);
            worker.post(() -> {
                try {
                    readiness = new FileReadiness(new File(Prefs.source(this)).getCanonicalFile().toPath());
                    engine = new MoveEngine(Environment.getExternalStorageDirectory(),
                            new File(Prefs.source(this)), new File(Prefs.destination(this)),
                            new MoveEngine.Events() {
                                @Override public void created(Path source) {
                                    readiness.discovered(source, SystemClock.elapsedRealtime());
                                    EventLog.get(WatchService.this).add("Created " + source);
                                }
                                @Override public void moving(Path destination) {
                                    EventLog.get(WatchService.this).add("Moving " + destination);
                                }
                                @Override public void moved(Path destination) {
                                    EventLog.get(WatchService.this).add("Moved " + destination);
                                }
                                @Override public void failed(Path destination, Exception error) {
                                    EventLog.get(WatchService.this).add("Error Moving to "
                                            + destination + " Reason: " + error);
                                }
                                @Override public void vanished(Path source) {
                                    EventLog.get(WatchService.this).add("Skipped " + source
                                            + " Reason: source was deleted or renamed before moving");
                                }
                            });
                    mediaObserver = new ContentObserver(worker) {
                        @Override public void onChange(boolean selfChange) {
                            schedule(MoveEngine.QUIET_MILLIS);
                        }
                    };
                    getContentResolver().registerContentObserver(
                            MediaStore.Files.getContentUri("external"), true, mediaObserver);
                    Prefs.status(this, getString(R.string.active));
                    schedule(0);
                } catch (IOException | RuntimeException error) {
                    fail(error);
                }
            });
        }
        return START_STICKY;
    }

    private Notification notification(String detail) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, WatchService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, Notifications.WATCH)
                .setSmallIcon(R.drawable.ic_watch)
                .setContentTitle(getString(R.string.watching))
                .setContentText(detail).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build())
                .build();
    }

    private void schedule(long delay) {
        if (stopping) {
            return;
        }
        long due = SystemClock.uptimeMillis() + delay;
        if (due < nextScan) {
            worker.removeCallbacks(scan);
            nextScan = due;
            worker.postAtTime(scan, due);
        }
    }

    private void syncObservers() throws IOException {
        Set<Path> directories = new HashSet<>(engine.directories());
        var iterator = observers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!directories.contains(entry.getKey())) {
                entry.getValue().stopWatching();
                iterator.remove();
            }
        }
        for (Path directory : directories) {
            if (observers.containsKey(directory)) {
                continue;
            }
            FileObserver observer = new FileObserver(directory.toFile(), MASK) {
                @Override public void onEvent(int event, String relative) {
                    if (!FileReadiness.relevant(event)) {
                        return;
                    }
                    if (relative != null && (event & 0x40000000) == 0) {
                        Path file = directory.resolve(relative);
                        if (engine.excluded(file)) {
                            return;
                        }
                        readiness.observe(file, event, SystemClock.elapsedRealtime());
                    }
                    worker.post(() -> handleEvent(directory, event, relative, this));
                }
            };
            observers.put(directory, observer);
            observer.startWatching();
        }
    }

    private void handleEvent(Path directory, int event, String relative, FileObserver origin) {
        if (stopping || engine == null || observers.get(directory) != origin) {
            return;
        }
        if ((event & (FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0) {
            FileObserver removed = observers.remove(directory);
            if (removed != null) {
                removed.stopWatching();
            }
            readiness.forgetDirectory(directory);
            waitingReasons.keySet().removeIf(path -> path.startsWith(directory));
        }
        if (relative != null) {
            Path file = directory.resolve(relative);
            if (engine.excluded(file)) {
                return;
            }
            if ((event & 0x40000000) == 0) {
                try {
                    engine.changed(file, SystemClock.elapsedRealtime());
                } catch (IOException | RuntimeException error) {
                    fail(error);
                    return;
                }
            }
        }
        schedule(MoveEngine.QUIET_MILLIS);
    }

    private boolean isBusy(Path file) throws IOException {
        if (stopping || !Prefs.get(this).getBoolean("enabled", false)) {
            return true;
        }
        String reason = readiness.waitingReason(file, SystemClock.elapsedRealtime());
        if (reason != null) {
            return waiting(file, reason);
        }
        Uri files = MediaStore.Files.getContentUri("external");
        Bundle query = new Bundle();
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.DATA + " = ?");
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{file.toString()});
        query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = getContentResolver().query(files,
                new String[]{MediaStore.MediaColumns.IS_PENDING}, query, null)) {
            if (cursor == null) {
                throw new IOException("Cannot check whether a media file is still being written.");
            }
            while (cursor.moveToNext()) {
                if (cursor.getInt(0) != 0) {
                    return waiting(file, "Android MediaStore marks this file as pending (still saving)");
                }
            }
        }
        waitingReasons.remove(file);
        return false;
    }

    private boolean waiting(Path file, String reason) {
        if (!reason.equals(waitingReasons.put(file, reason))) {
            EventLog.get(this).add("Waiting " + file + " Reason: " + reason);
        }
        return true;
    }

    private void scan() {
        nextScan = Long.MAX_VALUE;
        if (stopping) {
            return;
        }
        PowerManager.WakeLock lock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanDCIM:scan");
        try {
            lock.acquire(60_000);
            EventLog.get(this).prune();
            if (!MainActivity.permitted(this)) {
                throw new IOException("Required permissions were revoked.");
            }
            syncObservers();
            movedCount += engine.sweep(SystemClock.elapsedRealtime(), this::isBusy, (oldPath, newPath) -> {
                    readiness.forget(oldPath);
                    waitingReasons.remove(oldPath);
                    MediaScannerConnection.scanFile(this,
                            new String[]{oldPath.toString(), newPath.toString()}, null, null);
            });
            waitingReasons.keySet().removeIf(path -> !Files.exists(path));
            engine.cleanupEmptyDirectories(() -> !stopping
                    && Prefs.get(this).getBoolean("enabled", false));
            syncObservers();
            if (!stopping) {
                String detail = getResources().getQuantityString(
                        R.plurals.moved_count, movedCount, movedCount);
                Prefs.status(this, getString(R.string.active) + "\n" + detail + "\n"
                        + getString(R.string.last_check,
                        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                        waitingReasons.size()));
                if (Notifications.allowed(this, Notifications.WATCH)) {
                    getSystemService(NotificationManager.class).notify(
                            NOTIFICATION_ID, notification(detail));
                }
                // Reconciliation covers lost filesystem events and files created during observer setup.
                schedule(engine.hasCandidates() ? MoveEngine.QUIET_MILLIS : 30_000);
            }
        } catch (IOException | RuntimeException error) {
            fail(error);
        } finally {
            if (lock.isHeld()) {
                lock.release();
            }
        }
    }

    private void fail(Exception error) {
        Log.e("CleanDCIM", "Watcher stopped", error);
        failed = true;
        stopping = true;
        Prefs.get(this).edit().putBoolean("enabled", false).apply();
        String message = getString(R.string.error_status, error.getMessage());
        try {
            EventLog.get(this).add(message);
        } catch (SQLiteException logError) {
            Log.e("CleanDCIM", "Cannot persist watcher error", logError);
            message += "\n" + getString(R.string.log_error, logError.getMessage());
        }
        Prefs.status(this, message);
        if (Notifications.allowed(this, Notifications.ERRORS)) {
            getSystemService(NotificationManager.class).notify(2,
                    new Notification.Builder(this, Notifications.ERRORS)
                            .setSmallIcon(R.drawable.ic_watch).setContentTitle(getString(R.string.app_name))
                            .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                            .setContentIntent(PendingIntent.getActivity(this, 2,
                                    new Intent(this, MainActivity.class),
                                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                            .setAutoCancel(true).build());
        }
        stopSelf();
    }

    @Override public void onDestroy() {
        stopping = true;
        worker.removeCallbacksAndMessages(null);
        thread.interrupt();
        worker.post(() -> {
            for (FileObserver observer : observers.values()) {
                observer.stopWatching();
            }
            observers.clear();
            if (mediaObserver != null) {
                getContentResolver().unregisterContentObserver(mediaObserver);
            }
            thread.quitSafely();
        });
        if (!Prefs.get(this).getBoolean("enabled", false) && !failed) {
            Prefs.status(this, getString(R.string.idle));
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
