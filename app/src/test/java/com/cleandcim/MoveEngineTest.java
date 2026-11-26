package com.cleandcim;

import android.os.FileObserver;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class MoveEngineTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private Path source;
    private Path destination;
    private MoveEngine engine;
    private final List<Path> moved = new ArrayList<>();

    @Before public void setup() throws IOException {
        root = temporary.getRoot().toPath();
        source = Files.createDirectory(root.resolve("DCIM"));
        destination = root.resolve("Sorted");
        engine = new MoveEngine(root.toFile(), source.toFile(), destination.toFile());
    }

    private Path file(String name, String content) throws IOException {
        Path path = source.resolve(name);
        Files.createDirectories(path.getParent());
        return write(path, content);
    }

    private static Path write(Path path, String content) throws IOException {
        return Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private int sweep(long time) throws IOException {
        return engine.sweep(time, path -> false, (oldPath, newPath) -> moved.add(newPath));
    }

    @Test public void cameraIsUntouchedAndLayoutIsPreserved() throws IOException {
        Path photo = file("Camera/photo.jpg", "photo");
        Path video = file("Camera/nested/video.mp4", "video");
        Path screenshot = file("Screenshots/shot.png", "screenshot");
        Path loose = file("loose.txt", "loose");
        assertEquals(0, sweep(0));
        assertEquals(0, sweep(2999));
        assertEquals(2, sweep(3000));
        assertTrue(Files.exists(photo));
        assertTrue(Files.exists(video));
        assertFalse(Files.exists(screenshot));
        assertFalse(Files.exists(loose));
        assertEquals("screenshot", read(destination.resolve("Screenshots/shot.png")));
        assertEquals("loose", read(destination.resolve("loose.txt")));
        assertFalse(engine.directories().contains(source.resolve("Camera")));
    }

    @Test public void cameraMatchIsCaseInsensitive() throws IOException {
        Path photo = file("cAmErA/photo.jpg", "photo");
        sweep(0);
        assertEquals(0, sweep(3000));
        assertTrue(Files.exists(photo));
    }

    @Test public void changingFileWaitsAnotherThreeSeconds() throws IOException {
        Path recording = file("Recordings/video.mp4", "first");
        sweep(0);
        write(recording, "longer recording");
        assertEquals(0, sweep(3000));
        assertEquals(0, sweep(5999));
        assertEquals(1, sweep(6000));
        assertEquals("longer recording", read(destination.resolve("Recordings/video.mp4")));
    }

    @Test public void pendingFileIsNotMovedAndKeepsFastRetryActive() throws IOException {
        Path recording = file("Recordings/video.mp4", "recording");
        sweep(0);
        assertEquals(0, engine.sweep(3000, path -> true, (oldPath, newPath) -> fail()));
        assertTrue(engine.hasCandidates());
        assertTrue(Files.exists(recording));
        assertEquals(0, sweep(6000));
        assertEquals(1, sweep(9000));
        assertFalse(engine.hasCandidates());
    }

    @Test public void finalGatePreventsMoveAfterDisable() throws IOException {
        Path original = file("shot.png", "source");
        sweep(0);
        int[] calls = {0};
        assertEquals(0, engine.sweep(3000, path -> ++calls[0] > 1,
                (oldPath, newPath) -> fail()));
        assertTrue(Files.exists(original));
    }

    @Test public void finalMetadataCheckProtectsAFileChangedDuringSweep() throws IOException {
        Path recording = file("recording.mp4", "first");
        sweep(0);
        int[] calls = {0};
        assertEquals(0, engine.sweep(3000, path -> {
            if (++calls[0] == 2) {
                write(path, "a longer active recording");
            }
            return false;
        }, (oldPath, newPath) -> fail()));
        assertTrue(Files.exists(recording));
        assertEquals(0, sweep(6000));
        assertEquals(1, sweep(9000));
    }

    @Test public void collisionsNeverOverwrite() throws IOException {
        file("Screenshots/shot.png", "new");
        Files.createDirectories(destination.resolve("Screenshots"));
        write(destination.resolve("Screenshots/shot.png"), "original");
        write(destination.resolve("Screenshots/shot (1).png"), "second");
        sweep(0);
        assertEquals(1, sweep(3000));
        assertEquals("original", read(destination.resolve("Screenshots/shot.png")));
        assertEquals("second", read(destination.resolve("Screenshots/shot (1).png")));
        assertEquals("new", read(destination.resolve("Screenshots/shot (2).png")));
    }

    @Test public void extensionlessAndHiddenFilesAreMoved() throws IOException {
        file(".nomedia", "");
        file("notes", "notes");
        sweep(0);
        assertEquals(2, sweep(3000));
        assertTrue(Files.exists(destination.resolve(".nomedia")));
        assertTrue(Files.exists(destination.resolve("notes")));
    }

    @Test public void eventResetsQuietPeriod() throws IOException {
        Path original = file("shot.png", "source");
        sweep(0);
        engine.changed(original, 2000);
        assertEquals(0, sweep(3000));
        assertEquals(0, sweep(4999));
        assertEquals(1, sweep(5000));
    }

    @Test public void newFileMovesThreeSecondsAfterItsEvent() throws IOException {
        Path original = file("shot.png", "source");
        engine.changed(original, 1000);
        assertEquals(0, sweep(3999));
        assertEquals(1, sweep(4000));
    }

    @Test public void disappearingFileEventIsHarmless() throws IOException {
        Path original = file("shot.png", "source");
        sweep(0);
        Files.delete(original);
        engine.changed(original, 1000);
        assertEquals(0, sweep(4000));
    }

    @Test public void continuousNewFilesDoNotStarveOlderFiles() throws IOException {
        file("first.png", "first");
        sweep(0);
        file("second.png", "second");
        assertEquals(0, sweep(2000));
        assertEquals(1, sweep(3000));
        assertEquals(1, sweep(5000));
    }

    @Test public void destinationFailureKeepsOriginal() throws IOException {
        Path original = file("Screenshots/shot.png", "source");
        Files.createDirectories(destination);
        write(destination.resolve("Screenshots"), "blocking file");
        sweep(0);
        assertThrows(IOException.class, () -> sweep(3000));
        assertEquals("source", read(original));
    }

    @Test public void pathsMustNotOverlapOrEscapeSharedStorage() {
        for (Path invalid : List.of(source, source.resolve("Sorted"), root,
                root.resolve("Android/data/sorted"), root.getParent().resolve("outside"))) {
            assertThrows(IOException.class, () ->
                    new MoveEngine(root.toFile(), source.toFile(), invalid.toFile()));
        }
    }

    @Test public void sourceMustBeAnExistingDcimFolder() {
        assertThrows(IOException.class, () -> new MoveEngine(root.toFile(),
                root.resolve("Missing").toFile(), destination.toFile()));
        assertThrows(IOException.class, () -> new MoveEngine(root.toFile(),
                root.toFile(), destination.toFile()));
    }

    @Test public void cleanupRemovesPreexistingEmptyFoldersBottomUp() throws IOException {
        Files.createDirectories(source.resolve("Old/nested/empty"));
        Files.createDirectories(source.resolve("Screenshots"));
        assertEquals(4, engine.cleanupEmptyDirectories(() -> true));
        assertTrue(Files.isDirectory(source));
        assertFalse(Files.exists(source.resolve("Old")));
        assertFalse(Files.exists(source.resolve("Screenshots")));
        assertEquals(0, engine.cleanupEmptyDirectories(() -> true));
    }

    @Test public void cleanupPreservesEmptyCameraAndAllItsDescendants() throws IOException {
        Path nested = Files.createDirectories(source.resolve("cAmErA/nested/empty"));
        assertEquals(0, engine.cleanupEmptyDirectories(() -> true));
        assertTrue(Files.isDirectory(nested));
    }

    @Test public void cleanupPreservesNonemptyAndBusyFolders() throws IOException {
        Path recording = file("Recordings/video.mp4", "recording");
        engine.sweep(0, path -> true, (oldPath, newPath) -> fail());
        assertEquals(0, engine.cleanupEmptyDirectories(() -> true));
        assertTrue(Files.exists(recording));
        assertTrue(Files.isDirectory(recording.getParent()));
    }

    @Test public void cleanupRemovesFoldersAfterSuccessfulMoves() throws IOException {
        file("Screenshots/nested/shot.png", "shot");
        sweep(0);
        assertEquals(1, sweep(3000));
        assertEquals(2, engine.cleanupEmptyDirectories(() -> true));
        assertFalse(Files.exists(source.resolve("Screenshots")));
        assertEquals("shot", read(destination.resolve("Screenshots/nested/shot.png")));
    }

    @Test public void cleanupDoesNothingWhenWatchIsDisabled() throws IOException {
        Path empty = Files.createDirectories(source.resolve("Screenshots/nested"));
        assertEquals(0, engine.cleanupEmptyDirectories(() -> false));
        assertTrue(Files.isDirectory(empty));
    }

    @Test public void cleanupStopsWhenWatchIsDisabledDuringCleanup() throws IOException {
        Files.createDirectories(source.resolve("Screenshots/nested"));
        int[] checks = {0};
        assertEquals(1, engine.cleanupEmptyDirectories(() -> ++checks[0] == 1));
        assertTrue(Files.isDirectory(source.resolve("Screenshots")));
    }

    @Test public void cleanupNeverDeletesAFileCreatedAfterDirectoryListing() throws IOException {
        Path empty = Files.createDirectories(source.resolve("Screenshots"));
        Path screenshot = empty.resolve("new.png");
        assertEquals(0, engine.cleanupEmptyDirectories(() -> {
            try {
                write(screenshot, "new screenshot");
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
            return true;
        }));
        assertEquals("new screenshot", read(screenshot));
    }

    @Test public void cleanupPreservesFoldersContainingHiddenFiles() throws IOException {
        Path hidden = file("Screenshots/.nomedia", "");
        assertEquals(0, engine.cleanupEmptyDirectories(() -> true));
        assertTrue(Files.exists(hidden));
    }

    private MoveEngine loggedEngine(List<String> events) throws IOException {
        return new MoveEngine(root.toFile(), source.toFile(), destination.toFile(),
                new MoveEngine.Events() {
                    @Override public void created(Path path) { events.add("Created " + path); }
                    @Override public void moving(Path path) { events.add("Moving " + path); }
                    @Override public void moved(Path path) { events.add("Moved " + path); }
                    @Override public void failed(Path path, Exception error) {
                        events.add("Error Moving to " + path + " Reason: " + error);
                    }
                });
    }

    @Test public void logReportsDiscoveryAttemptAndSuccessInOrder() throws IOException {
        Path screenshot = file("Screenshots/a.png", "shot");
        List<String> events = new ArrayList<>();
        engine = loggedEngine(events);
        engine.changed(screenshot, 0);
        engine.changed(screenshot, 1000);
        sweep(2000);
        assertEquals(List.of("Created " + screenshot), events);
        sweep(4000);
        Path target = destination.resolve("Screenshots/a.png");
        assertEquals(List.of("Created " + screenshot, "Moving " + target, "Moved " + target), events);
    }

    @Test public void logReportsCollisionRenamedDestination() throws IOException {
        file("shot.png", "new");
        Files.createDirectory(destination);
        write(destination.resolve("shot.png"), "original");
        List<String> events = new ArrayList<>();
        engine = loggedEngine(events);
        sweep(0);
        sweep(3000);
        assertEquals("Moved " + destination.resolve("shot (1).png"), events.get(events.size() - 1));
        assertTrue(events.contains("Moving " + destination.resolve("shot (1).png")));
    }

    @Test public void logReportsDestinationAndReasonOnPreparationFailure() throws IOException {
        file("Screenshots/a.png", "shot");
        Files.createDirectory(destination);
        write(destination.resolve("Screenshots"), "blocking file");
        List<String> events = new ArrayList<>();
        engine = loggedEngine(events);
        sweep(0);
        assertThrows(IOException.class, () -> sweep(3000));
        assertEquals(2, events.size());
        assertTrue(events.get(1).startsWith("Error Moving to "
                + destination.resolve("Screenshots/a.png") + " Reason: "));
        assertFalse(events.get(1).endsWith("Reason: "));
    }

    @Test public void logNeverReportsCameraOrDeferredFilesAsMoving() throws IOException {
        file("Camera/photo.jpg", "photo");
        Path recording = file("Recordings/a.mp4", "recording");
        List<String> events = new ArrayList<>();
        engine = loggedEngine(events);
        engine.sweep(0, path -> true, (oldPath, newPath) -> fail());
        engine.sweep(4000, path -> true, (oldPath, newPath) -> fail());
        assertEquals(List.of("Created " + recording), events);
    }

    @Test public void logReportsFailedMoveWithoutSuccess() throws IOException {
        Path screenshot = file("shot.png", "shot");
        List<String> events = new ArrayList<>();
        engine = new MoveEngine(root.toFile(), source.toFile(), destination.toFile(),
                new MoveEngine.Events() {
                    @Override public void moving(Path path) {
                        events.add("Moving " + path);
                        try {
                            Files.delete(destination);
                        } catch (IOException error) {
                            throw new AssertionError(error);
                        }
                    }
                    @Override public void moved(Path path) { fail("Move did not succeed"); }
                    @Override public void failed(Path path, Exception error) {
                        events.add("Error Moving to " + path + " Reason: " + error);
                    }
                });
        sweep(0);
        assertThrows(IOException.class, () -> sweep(3000));
        assertEquals(2, events.size());
        assertEquals("Moving " + destination.resolve("shot.png"), events.get(0));
        assertTrue(events.get(1).startsWith("Error Moving to "
                + destination.resolve("shot.png") + " Reason: "));
        assertTrue(Files.exists(screenshot));
    }

    @Test public void vanishedSourceIsLoggedWithoutStoppingOtherMoves() throws IOException {
        Path screenshot = file("shot.png", "shot");
        List<Path> skipped = new ArrayList<>();
        engine = new MoveEngine(root.toFile(), source.toFile(), destination.toFile(),
                new MoveEngine.Events() {
                    @Override public void vanished(Path path) { skipped.add(path); }
                    @Override public void failed(Path path, Exception error) {
                        fail("Expected a harmless source rename, not a fatal error");
                    }
                });
        sweep(0);
        assertEquals(0, engine.sweep(3000, path -> {
            Files.deleteIfExists(path);
            return false;
        }, (oldPath, newPath) -> fail()));
        assertEquals(List.of(screenshot), skipped);
        assertFalse(engine.hasCandidates());
        file("new-shot.png", "new");
        assertEquals(0, sweep(6000));
        assertEquals(1, sweep(9000));
    }

    @Test public void pendingSamsungFileIsDeferredWhileFinalScreenshotMoves() throws IOException {
        Path pending = file("Screenshots/.pending-1794011896-Screenshot.jpg", "pending");
        Path completed = file("Screenshots/Screenshot.jpg", "complete");
        FileReadiness readiness = new FileReadiness(source);
        readiness.discovered(completed, 0);
        MoveEngine.Gate gate = path -> readiness.waitingReason(path, 120000) != null;
        engine.sweep(0, gate, (oldPath, newPath) -> fail());
        assertTrue(engine.hasCandidates());
        assertEquals(1, engine.sweep(3000, gate, (oldPath, newPath) -> moved.add(newPath)));
        assertTrue(Files.exists(pending));
        assertFalse(Files.exists(completed));
        assertEquals("complete", read(destination.resolve("Screenshots/Screenshot.jpg")));
        assertTrue(engine.hasCandidates());
    }

    @Test public void removedBusyFileDoesNotKeepFastRetryActiveForever() throws IOException {
        Path recording = file("video.mp4", "recording");
        engine.sweep(0, path -> true, (oldPath, newPath) -> fail());
        assertTrue(engine.hasCandidates());
        Files.delete(recording);
        assertEquals(0, sweep(3000));
        assertFalse(engine.hasCandidates());
    }

    @Test public void screenshotMovesAfterSharingGraceDespiteRepeatedPreviewReads() throws IOException {
        Path screenshot = file("Screenshots/Screenshot.jpg", "screenshot");
        FileReadiness readiness = new FileReadiness(source);
        readiness.observe(screenshot, FileObserver.CLOSE_WRITE, 1000);
        engine.changed(screenshot, 1000);
        long[] now = {1000};
        MoveEngine.Gate gate = path -> readiness.waitingReason(path, now[0]) != null;
        for (now[0] = 1100; now[0] < 4000; now[0] += 100) {
            for (int event : new int[]{FileObserver.OPEN, FileObserver.ACCESS,
                    FileObserver.CLOSE_NOWRITE}) {
                readiness.observe(screenshot, event, now[0]);
                if (FileReadiness.relevant(event)) {
                    engine.changed(screenshot, now[0]);
                }
            }
        }
        now[0] = 120999;
        assertEquals(0, engine.sweep(now[0], gate, (oldPath, newPath) -> fail()));
        assertTrue(Files.exists(screenshot));
        assertEquals(0, engine.cleanupEmptyDirectories(() -> true));
        now[0] = 121000;
        assertEquals(0, engine.sweep(now[0], gate, (oldPath, newPath) -> fail()));
        now[0] = 124000;
        assertEquals(1, engine.sweep(now[0], gate, (oldPath, newPath) -> moved.add(newPath)));
        assertEquals(List.of(destination.resolve("Screenshots/Screenshot.jpg")), moved);
        assertFalse(Files.exists(screenshot));
    }
}
