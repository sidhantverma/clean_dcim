package com.cleandcim;

import android.os.FileObserver;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.*;

public class FileReadinessTest {
    @Test public void readEventsNeverBlockOrExtendTheQuietPeriod() {
        var path = Paths.get("DCIM", "Downloads", "shot.jpg");
        var readiness = new FileReadiness(Paths.get("DCIM"));
        readiness.observe(path, FileObserver.CLOSE_WRITE, 1000);
        for (long time = 1100; time <= 4000; time += 100) {
            readiness.observe(path, FileObserver.OPEN, time);
            readiness.observe(path, FileObserver.ACCESS, time);
            readiness.observe(path, FileObserver.CLOSE_NOWRITE, time);
        }
        assertNull(readiness.waitingReason(path, 4000));
        assertFalse(FileReadiness.relevant(FileObserver.OPEN));
        assertFalse(FileReadiness.relevant(FileObserver.ACCESS));
        assertFalse(FileReadiness.relevant(FileObserver.CLOSE_NOWRITE));
    }

    @Test public void unmatchedOpenCannotLeaveAFilePermanentlyBusy() {
        var path = Paths.get("DCIM", "Downloads", "shot.jpg");
        var readiness = new FileReadiness(Paths.get("DCIM"));
        readiness.observe(path, FileObserver.OPEN, 1000);
        assertNull(readiness.waitingReason(path, 1000));
        assertNull(readiness.waitingReason(path, 60000));
    }

    @Test public void writesRequireThreeQuietSecondsFromTheLatestWrite() {
        var path = Paths.get("DCIM", "Recordings", "video.mp4");
        var readiness = new FileReadiness(Paths.get("DCIM"));
        for (int event : new int[]{FileObserver.CREATE, FileObserver.MOVED_TO,
                FileObserver.MODIFY, FileObserver.CLOSE_WRITE}) {
            assertTrue(FileReadiness.relevant(event));
            readiness.observe(path, event, 1000);
            assertNotNull(readiness.waitingReason(path, 3999));
            assertNull(readiness.waitingReason(path, 4000));
        }
        readiness.observe(path, FileObserver.MODIFY, 3999);
        assertNotNull(readiness.waitingReason(path, 6998));
        assertNull(readiness.waitingReason(path, 6999));
    }

    @Test public void temporarySamsungPendingFilenameNeverBecomesReadyByTimeout() {
        var pending = Paths.get("DCIM", "Screenshots", ".pending-1794011896-Screenshot.jpg");
        var completed = pending.resolveSibling("Screenshot.jpg");
        var readiness = new FileReadiness(Paths.get("DCIM"));
        readiness.observe(pending, FileObserver.CREATE, 1000);
        assertTrue(readiness.waitingReason(pending, 60000).contains("pending"));
        readiness.observe(pending, FileObserver.MOVED_FROM, 60000);
        readiness.observe(completed, FileObserver.MOVED_TO, 60000);
        assertNotNull(readiness.waitingReason(completed, 62999));
        assertNotNull(readiness.waitingReason(completed, 179999));
        assertNull(readiness.waitingReason(completed, 180000));
    }

    @Test public void removingDirectoryClearsOnlyItsWriteHistory() {
        var directory = Paths.get("DCIM", "Downloads");
        var path = directory.resolve("shot.jpg");
        var other = Paths.get("DCIM", "Recordings", "video.mp4");
        var readiness = new FileReadiness(Paths.get("DCIM"));
        readiness.observe(path, FileObserver.MODIFY, 1000);
        readiness.observe(other, FileObserver.MODIFY, 1000);
        readiness.forgetDirectory(directory);
        assertNull(readiness.waitingReason(path, 1000));
        assertNotNull(readiness.waitingReason(other, 1000));
    }

    @Test public void screenshotWaitsExactlyTwoMinutesFromFirstDetection() {
        var path = Paths.get("DCIM", "sCrEeNsHoTs", "nested", "shot.jpg");
        var readiness = new FileReadiness(Paths.get("DCIM"));
        readiness.discovered(path, 1000);
        assertTrue(readiness.waitingReason(path, 120999).contains("sharing"));
        assertNull(readiness.waitingReason(path, 121000));
    }

    @Test public void previewReadsAndRepeatedDiscoveryDoNotRestartSharingGrace() {
        var path = Paths.get("DCIM", "Screenshots", "shot.jpg");
        var readiness = new FileReadiness(Paths.get("DCIM"));
        readiness.discovered(path, 1000);
        readiness.discovered(path, 60000);
        readiness.observe(path, FileObserver.OPEN, 120999);
        readiness.observe(path, FileObserver.CLOSE_NOWRITE, 120999);
        assertNull(readiness.waitingReason(path, 121000));
    }

    @Test public void writeAtEndOfSharingGraceStillNeedsThreeQuietSeconds() {
        var path = Paths.get("DCIM", "Screenshots", "shot.jpg");
        var readiness = new FileReadiness(Paths.get("DCIM"));
        readiness.discovered(path, 1000);
        readiness.observe(path, FileObserver.MODIFY, 120000);
        assertNotNull(readiness.waitingReason(path, 121000));
        assertNotNull(readiness.waitingReason(path, 122999));
        assertNull(readiness.waitingReason(path, 123000));
    }

    @Test public void onlyTopLevelScreenshotsFolderGetsSharingGrace() {
        var readiness = new FileReadiness(Paths.get("DCIM"));
        assertNull(readiness.waitingReason(Paths.get("DCIM", "Other", "Screenshots", "shot.jpg"), 1000));
        assertNull(readiness.waitingReason(Paths.get("DCIM", "Screenshot.jpg"), 1000));
        assertNull(readiness.waitingReason(Paths.get("Other", "Screenshots", "shot.jpg"), 1000));
    }
}
