package com.cleandcim;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class LogEntry {
    static final long RETENTION_MILLIS = 3L * 24 * 60 * 60 * 1000;

    private LogEntry() {}

    static long cutoff(long now) {
        return now - RETENTION_MILLIS;
    }

    static String render(long timestamp, String message) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
                + ": " + message;
    }
}
