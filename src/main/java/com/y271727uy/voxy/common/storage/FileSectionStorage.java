package com.y271727uy.voxy.common.storage;

import com.y271727uy.voxy.common.world.WorldSection;
import com.y271727uy.voxy.common.world.WorldSectionKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.LongConsumer;

public final class FileSectionStorage implements SectionStorage {
    private static final String SUFFIX = ".bin";
    static final int MOVE_ATTEMPTS = 4;
    static final long MOVE_RETRY_DELAY_MILLIS = 5;

    private final Path sectionDirectory;
    private final Path mappingDirectory;
    private boolean closed;

    public FileSectionStorage(Path root) {
        this.sectionDirectory = root.resolve("sections");
        this.mappingDirectory = root.resolve("mappings");
        try {
            Files.createDirectories(this.sectionDirectory);
            Files.createDirectories(this.mappingDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create Voxy storage at " + root, exception);
        }
    }

    @Override
    public synchronized int loadSection(WorldSection section) {
        requireOpen();
        Path path = sectionPath(section.key());
        if (!Files.exists(path)) {
            return LOAD_ABSENT;
        }
        try {
            WorldSectionCodec.deserialize(section, Files.readAllBytes(path));
            return LOAD_SUCCESS;
        } catch (RuntimeException | IOException exception) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException suppressed) {
                exception.addSuppressed(suppressed);
            }
            section.clear();
            return LOAD_CORRUPT;
        }
    }

    @Override
    public synchronized void saveSection(WorldSection section) {
        requireOpen();
        writeAtomically(sectionPath(section.key()), WorldSectionCodec.serialize(section));
    }

    @Override
    public synchronized void iteratePositions(int level, LongConsumer consumer) {
        requireOpen();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(this.sectionDirectory, "*" + SUFFIX)) {
            for (Path path : paths) {
                long key = parseUnsignedLong(path);
                if (level == -1 || WorldSectionKey.level(key) == level) {
                    consumer.accept(key);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to enumerate Voxy sections", exception);
        }
    }

    @Override
    public synchronized void putIdMapping(int key, ByteBuffer data) {
        requireOpen();
        ByteBuffer source = data.duplicate();
        byte[] copy = new byte[source.remaining()];
        source.get(copy);
        writeAtomically(mappingPath(key), copy);
    }

    @Override
    public synchronized Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        requireOpen();
        Int2ObjectOpenHashMap<byte[]> mappings = new Int2ObjectOpenHashMap<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(this.mappingDirectory, "*" + SUFFIX)) {
            for (Path path : paths) {
                int key = parseUnsignedInt(path);
                mappings.put(key, Files.readAllBytes(path));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Voxy mappings", exception);
        }
        return mappings;
    }

    @Override
    public synchronized void flush() {
        requireOpen();
    }

    @Override
    public synchronized void close() {
        this.closed = true;
    }

    private Path sectionPath(long key) {
        return this.sectionDirectory.resolve(Long.toUnsignedString(key, 16) + SUFFIX);
    }

    private Path mappingPath(int key) {
        return this.mappingDirectory.resolve(Integer.toUnsignedString(key, 16) + SUFFIX);
    }

    private static long parseUnsignedLong(Path path) {
        return Long.parseUnsignedLong(baseName(path), 16);
    }

    private static int parseUnsignedInt(Path path) {
        return Integer.parseUnsignedInt(baseName(path), 16);
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - SUFFIX.length());
    }

    private static void writeAtomically(Path destination, byte[] data) {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try {
            Files.write(temporary, data);
            replaceWithRetry(temporary, destination, FileSectionStorage::move, Thread::sleep,
                    MOVE_ATTEMPTS, MOVE_RETRY_DELAY_MILLIS);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException suppressed) {
                exception.addSuppressed(suppressed);
            }
            throw new IllegalStateException("Unable to persist Voxy record " + destination, exception);
        }
    }

    static void replaceWithRetry(Path temporary, Path destination, MoveOperation mover, Sleeper sleeper,
                                 int attempts, long delayMillis) throws IOException {
        if (attempts < 1 || delayMillis < 0) throw new IllegalArgumentException("Invalid move retry policy");
        IOException previous = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                mover.move(temporary, destination, true);
                return;
            } catch (AtomicMoveNotSupportedException exception) {
                try {
                    mover.move(temporary, destination, false);
                    return;
                } catch (IOException fallbackFailure) {
                    previous = retain(previous, fallbackFailure);
                }
            } catch (IOException exception) {
                previous = retain(previous, exception);
            }
            if (!isTransientMoveFailure(previous) || attempt == attempts) throw previous;
            try {
                sleeper.sleep(delayMillis * attempt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                previous.addSuppressed(interrupted);
                throw previous;
            }
        }
        throw new AssertionError("Move retry loop exhausted without a result");
    }

    private static void move(Path temporary, Path destination, boolean atomic) throws IOException {
        if (atomic) {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static IOException retain(IOException previous, IOException current) {
        if (previous != null && previous != current) current.addSuppressed(previous);
        return current;
    }

    static boolean isTransientMoveFailure(IOException exception) {
        if (exception instanceof AccessDeniedException) return true;
        if (!(exception instanceof FileSystemException fileSystem)) return false;
        String reason = fileSystem.getReason();
        if (reason == null) return false;
        String normalized = reason.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("sharing violation")
                || normalized.contains("used by another process")
                || normalized.contains("process cannot access");
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path temporary, Path destination, boolean atomic) throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Section storage is closed");
        }
    }
}
