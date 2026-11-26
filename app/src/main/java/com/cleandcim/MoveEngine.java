package com.cleandcim;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class MoveEngine {
    static final long QUIET_MILLIS = 3000;
    final Path source;
    final Path destination;
    private final Map<Path, Stamp> stamps = new HashMap<>();
    private final Set<Path> knownFiles = new HashSet<>();
    private final Events events;

    interface Events {
        default void created(Path source) {}
        default void moving(Path destination) {}
        default void moved(Path destination) {}
        default void failed(Path destination, Exception error) {}
        default void vanished(Path source) {}
    }

    interface Gate {
        boolean isBusy(Path path) throws IOException;
    }

    interface Moved {
        void accept(Path oldPath, Path newPath);
    }

    record Stamp(long size, long modified, long since) {}

    MoveEngine(File storage, File sourceFile, File destinationFile) throws IOException {
        this(storage, sourceFile, destinationFile, new Events() {});
    }

    MoveEngine(File storage, File sourceFile, File destinationFile, Events events) throws IOException {
        this.events = events;
        Path root = storage.getCanonicalFile().toPath();
        source = sourceFile.getCanonicalFile().toPath();
        destination = destinationFile.getCanonicalFile().toPath();
        if (!sourceFile.isAbsolute() || !destinationFile.isAbsolute()) {
            throw new IOException("Use absolute paths.");
        }
        if (!source.startsWith(root) || source.equals(root)
                || !source.getFileName().toString().equalsIgnoreCase("DCIM")
                || source.startsWith(root.resolve("Android"))) {
            throw new IOException("Source must be a DCIM folder in internal shared storage.");
        }
        if (!Files.isDirectory(source) || !Files.isReadable(source)) {
            throw new IOException("DCIM folder does not exist or is not readable.");
        }
        if (!destination.startsWith(root) || destination.equals(root)
                || destination.startsWith(root.resolve("Android"))
                || destination.startsWith(source) || source.startsWith(destination)) {
            throw new IOException("Destination must be outside DCIM and Android, in internal shared storage.");
        }
        if (Files.exists(destination) && !Files.isDirectory(destination)) {
            throw new IOException("Destination is not a folder.");
        }
    }

    boolean excluded(Path path) {
        Path relative = source.relativize(path);
        return relative.getNameCount() > 0
                && relative.getName(0).toString().equalsIgnoreCase("Camera");
    }

    List<Path> directories() throws IOException {
        List<Path> result = new ArrayList<>();
        collect(source, result, null);
        return result;
    }

    private void collect(Path directory, List<Path> directories, List<Path> files) throws IOException {
        if (Files.isSymbolicLink(directory) || excluded(directory)) {
            return;
        }
        if (directories != null) {
            directories.add(directory);
        }
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path child : entries) {
                if (excluded(child) || Files.isSymbolicLink(child)) {
                    continue;
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    collect(child, directories, files);
                } else if (files != null && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(child);
                }
            }
        }
    }

    void changed(Path path, long now) throws IOException {
        stamps.remove(path);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile()) {
                discovered(path);
                stamps.put(path, new Stamp(attributes.size(),
                        attributes.lastModifiedTime().toMillis(), now));
            }
        } catch (NoSuchFileException removed) {
            knownFiles.remove(path);
            // Delete/move events can arrive after the entry has disappeared.
        }
    }

    private void discovered(Path path) {
        if (knownFiles.add(path)) {
            events.created(path);
        }
    }

    boolean hasCandidates() {
        return !knownFiles.isEmpty();
    }

    int cleanupEmptyDirectories(BooleanSupplier enabled) throws IOException {
        List<Path> directories = directories();
        int removed = 0;
        for (int i = directories.size() - 1; i >= 0; i--) {
            if (Thread.currentThread().isInterrupted() || !enabled.getAsBoolean()) {
                break;
            }
            Path directory = directories.get(i);
            if (directory.equals(source) || excluded(directory)) {
                continue;
            }
            try {
                Path real = directory.toRealPath();
                if (!real.equals(directory) || !real.startsWith(source) || excluded(real)
                        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Folder changed during cleanup: " + directory);
                }
                // A single-directory delete cannot remove files created concurrently.
                Files.delete(directory);
                removed++;
            } catch (DirectoryNotEmptyException | NoSuchFileException unchanged) {
                // Keep nonempty folders; another app may also have removed the folder.
            }
        }
        return removed;
    }

    int sweep(long now, Gate gate, Moved moved) throws IOException {
        List<Path> files = new ArrayList<>();
        collect(source, null, files);
        Set<Path> seen = new HashSet<>(files);
        stamps.keySet().retainAll(seen);
        knownFiles.retainAll(seen);
        int count = 0;
        for (Path file : files) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            discovered(file);
            Path target = moveFile(file, now, gate);
            if (target != null) {
                count++;
                moved.accept(file, target);
            }
        }
        return count;
    }

    private Path moveFile(Path file, long now, Gate gate) throws IOException {
        Path target = destination.resolve(source.relativize(file));
        try {
            if (gate.isBusy(file)) {
                stamps.remove(file);
                return null;
            }
            long size = Files.size(file);
            long modified = Files.getLastModifiedTime(file).toMillis();
            Stamp previous = stamps.get(file);
            if (previous == null || previous.size != size || previous.modified != modified) {
                stamps.put(file, new Stamp(size, modified, now));
                return null;
            }
            if (now - previous.since < QUIET_MILLIS) {
                return null;
            }
            Files.createDirectories(target.getParent());
            if (!destination.toRealPath().equals(destination)
                    || !target.getParent().toRealPath().startsWith(destination)) {
                throw new IOException("Destination subfolder escapes the destination: " + target);
            }
            Path realSource = file.toRealPath();
            if (!realSource.startsWith(source) || excluded(realSource)) {
                throw new IOException("Source path changed during scan: " + file);
            }
            if (Thread.currentThread().isInterrupted() || gate.isBusy(file)
                    || size != Files.size(file)
                    || modified != Files.getLastModifiedTime(file).toMillis()) {
                stamps.remove(file);
                return null;
            }
            String name = target.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String extension = dot > 0 ? name.substring(dot) : "";
            for (int suffix = 1; ; suffix++) {
                try {
                    events.moving(target);
                    Files.move(file, target);
                    break;
                } catch (FileAlreadyExistsException collision) {
                    target = target.resolveSibling(stem + " (" + suffix + ")" + extension);
                }
            }
            stamps.remove(file);
            knownFiles.remove(file);
            events.moved(target);
            return target;
        } catch (NoSuchFileException error) {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                stamps.remove(file);
                knownFiles.remove(file);
                events.vanished(file);
                return null;
            }
            events.failed(target, error);
            throw error;
        } catch (IOException | RuntimeException error) {
            events.failed(target, error);
            throw error;
        }
    }
}
