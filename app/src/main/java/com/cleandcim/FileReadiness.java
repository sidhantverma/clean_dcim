package com.cleandcim;

import android.os.FileObserver;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class FileReadiness {
    static final long SCREENSHOT_GRACE_MILLIS = 120_000;
    private static final int WRITING = FileObserver.CREATE | FileObserver.MOVED_TO
            | FileObserver.MODIFY | FileObserver.CLOSE_WRITE;
    private static final int REMOVED = FileObserver.DELETE | FileObserver.MOVED_FROM;
    private final Map<Path, Long> lastWrite = new ConcurrentHashMap<>();
    private final Map<Path, Long> screenshotSeen = new ConcurrentHashMap<>();
    private final Path source;

    FileReadiness(Path source) {
        this.source = source;
    }

    void discovered(Path path, long now) {
        if (path.startsWith(source)) {
            Path relative = source.relativize(path);
            if (relative.getNameCount() >= 2
                    && relative.getName(0).toString().equalsIgnoreCase("Screenshots")) {
                screenshotSeen.putIfAbsent(path, now);
            }
        }
    }

    static boolean relevant(int event) {
        return (event & (WRITING | REMOVED | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0;
    }

    void observe(Path path, int event, long now) {
        if ((event & REMOVED) != 0) {
            forget(path);
        } else if ((event & WRITING) != 0) {
            discovered(path, now);
            lastWrite.put(path, now);
        }
    }

    String waitingReason(Path path, long now) {
        if (path.getFileName().toString().startsWith(".pending-")) {
            return "Android temporary pending file; waiting for its final filename";
        }
        Long written = lastWrite.get(path);
        if (written != null && now - written < MoveEngine.QUIET_MILLIS) {
            return "File changed recently; waiting for 3 quiet seconds";
        }
        discovered(path, now);
        Long seen = screenshotSeen.get(path);
        if (seen != null && now - seen < SCREENSHOT_GRACE_MILLIS) {
            return "Screenshot sharing grace period; waiting 2 minutes after first detection";
        }
        return null;
    }

    void forget(Path path) {
        lastWrite.remove(path);
        screenshotSeen.remove(path);
    }

    void forgetDirectory(Path directory) {
        lastWrite.keySet().removeIf(path -> path.startsWith(directory));
        screenshotSeen.keySet().removeIf(path -> path.startsWith(directory));
    }
}
