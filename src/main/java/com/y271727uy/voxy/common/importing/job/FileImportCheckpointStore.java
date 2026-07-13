package com.y271727uy.voxy.common.importing.job;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Versioned, atomically replaced checkpoint files suitable for crash recovery. */
public final class FileImportCheckpointStore implements ImportCheckpointStore {
    private static final int MAGIC = 0x564F5849; // VOXI
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    private final Path directory;

    public FileImportCheckpointStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public Optional<ImportCheckpoint> load(String jobId) throws IOException {
        ImportCheckpoint.requireText(jobId, "jobId");
        Path path = pathFor(jobId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid Voxy import checkpoint header");
            }
            int version = input.readInt();
            String storedJobId = readText(input);
            String cursor = readText(input);
            long completed = input.readLong();
            long total = input.readLong();
            if (!storedJobId.equals(jobId)) {
                throw new IOException("Import checkpoint job id mismatch");
            }
            try {
                return Optional.of(new ImportCheckpoint(version, storedJobId, cursor, completed, total));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid Voxy import checkpoint", exception);
            }
        }
    }

    @Override
    public void save(ImportCheckpoint checkpoint) throws IOException {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Files.createDirectories(this.directory);
        Path target = pathFor(checkpoint.jobId());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(checkpoint.schemaVersion());
                writeText(output, checkpoint.jobId());
                writeText(output, checkpoint.cursor());
                output.writeLong(checkpoint.completed());
                output.writeLong(checkpoint.total());
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectoryBestEffort();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void clear(String jobId) throws IOException {
        ImportCheckpoint.requireText(jobId, "jobId");
        if (Files.deleteIfExists(pathFor(jobId))) {
            forceDirectoryBestEffort();
        }
    }

    private Path pathFor(String jobId) {
        return this.directory.resolve(sha256(jobId) + ".checkpoint");
    }

    private void forceDirectoryBestEffort() {
        try (FileChannel channel = FileChannel.open(this.directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and some file systems do not permit opening directories as channels.
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IOException("Import checkpoint text is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_TEXT_BYTES) {
            throw new IOException("Invalid import checkpoint text length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Truncated import checkpoint text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
