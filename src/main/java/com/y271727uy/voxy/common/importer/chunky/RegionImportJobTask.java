package com.y271727uy.voxy.common.importer.chunky;

import com.y271727uy.voxy.common.importing.job.ImportCheckpoint;
import com.y271727uy.voxy.common.importing.job.ImportJobContext;
import com.y271727uy.voxy.common.importing.job.ImportJobTask;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Resumable job adapter for deterministic Anvil region imports. */
public final class RegionImportJobTask implements ImportJobTask {
    private static final String CURSOR_PREFIX = "chunky-v1:";

    private final Path source;
    private final ChunkImportSink sink;
    private final VanillaRegionImporter.FailureListener failures;
    private final AnvilRegionReader reader;
    private volatile VanillaRegionImporter.ImportReport report;

    public RegionImportJobTask(Path source, ChunkImportSink sink,
                               VanillaRegionImporter.FailureListener failures) {
        this(source, sink, failures, new AnvilRegionReader());
    }

    RegionImportJobTask(Path source, ChunkImportSink sink,
                        VanillaRegionImporter.FailureListener failures, AnvilRegionReader reader) {
        this.source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        this.sink = Objects.requireNonNull(sink, "sink");
        this.failures = failures == null ? VanillaRegionImporter.FailureListener.NONE : failures;
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public VanillaRegionImporter.ImportReport report() {
        return this.report;
    }

    @Override
    public void execute(ImportJobContext context) throws Exception {
        List<AnvilRegionReader.ChunkLocation> chunks = new ArrayList<>();
        int failedRegions = 0;
        for (RegionSource.RegionFile region : RegionSource.discover(this.source)) {
            context.throwIfCancellationRequested();
            try {
                chunks.addAll(this.reader.scan(region));
            } catch (Exception exception) {
                failedRegions++;
                this.failures.onFailure(region.path(), null, exception);
            }
        }

        String fingerprint = fingerprint(chunks);
        int total = chunks.size();
        int start = resumeIndex(context, fingerprint, total);
        int imported = 0;
        int skipped = 0;
        int failedChunks = 0;
        context.reportProgress(start, total);
        for (int index = start; index < total; index++) {
            context.throwIfCancellationRequested();
            AnvilRegionReader.ChunkLocation chunk = chunks.get(index);
            try {
                if (this.sink.importChunk(chunk.chunkX(), chunk.chunkZ(), this.reader.read(chunk))) {
                    imported++;
                } else {
                    skipped++;
                }
            } catch (Exception exception) {
                failedChunks++;
                this.failures.onFailure(chunk.region().path(), chunk, exception);
            }
            int completed = index + 1;
            context.checkpoint(cursor(fingerprint, completed), completed, total);
        }
        this.report = new VanillaRegionImporter.ImportReport(total, imported, skipped, failedChunks, failedRegions);
    }

    private static int resumeIndex(ImportJobContext context, String fingerprint, int total) {
        if (context.resumeCheckpoint().isEmpty()) return 0;
        ImportCheckpoint checkpoint = context.resumeCheckpoint().orElseThrow();
        String expectedPrefix = CURSOR_PREFIX + fingerprint + ":";
        if (!checkpoint.cursor().startsWith(expectedPrefix) || checkpoint.total() != total) {
            throw new IllegalStateException("Region import source changed since the last checkpoint");
        }
        long completed = checkpoint.completed();
        if (completed > Integer.MAX_VALUE || !checkpoint.cursor().equals(cursor(fingerprint, (int) completed))) {
            throw new IllegalStateException("Invalid region import checkpoint cursor");
        }
        return (int) completed;
    }

    private static String cursor(String fingerprint, int completed) {
        return CURSOR_PREFIX + fingerprint + ":" + completed;
    }

    private static String fingerprint(List<AnvilRegionReader.ChunkLocation> chunks) throws Exception {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path lastPath = null;
            for (AnvilRegionReader.ChunkLocation chunk : chunks) {
                Path path = chunk.region().path();
                if (!path.equals(lastPath)) {
                    update(digest, path.toString());
                    update(digest, Long.toString(Files.size(path)));
                    update(digest, Long.toString(Files.getLastModifiedTime(path).toMillis()));
                    lastPath = path;
                }
                update(digest, Integer.toString(chunk.index()));
                update(digest, Integer.toString(chunk.sectorOffset()));
                update(digest, Integer.toString(chunk.sectorCount()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
