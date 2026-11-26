package com.cleandcim;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.junit.Assert.*;

public class LogEntryTest {
    @Test public void retentionIsExactlyThreeDays() {
        long now = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli();
        long cutoff = LogEntry.cutoff(now);
        assertEquals(Instant.parse("2026-10-27T12:00:00Z").toEpochMilli(), cutoff);
        assertEquals(cutoff + 1, LogEntry.cutoff(now + 1));
    }

    @Test public void renderingIncludesLocalDateTimeAndFullMessage() {
        long time = Instant.parse("2026-10-30T12:34:56.789Z").toEpochMilli();
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()).format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
        for (String message : new String[]{
                "Created /storage/emulated/0/DCIM/Screenshots/a.png",
                "Moving /storage/emulated/0/Sorted/Screenshots/a.png",
                "Moved /storage/emulated/0/Sorted/Screenshots/a (1).png",
                "Error Moving to /storage/emulated/0/Sorted/a.png Reason: Access denied"}) {
            assertEquals(timestamp + ": " + message, LogEntry.render(time, message));
        }
    }
}
