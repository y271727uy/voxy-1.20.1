package com.y271727uy.voxy.common.importer.chunky;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Imports Minecraft/Chunky Anvil data with one deterministic progress unit per allocated chunk slot. */
public final class VanillaRegionImporter {
    private final AnvilRegionReader reader;

    public VanillaRegionImporter() {
        this(new AnvilRegionReader());
    }

    VanillaRegionImporter(AnvilRegionReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public ImportReport importFrom(Path source, ChunkImportSink sink, ProgressListener progress,
                                   FailureListener failures) throws IOException {
        Objects.requireNonNull(sink, "sink");
        progress = progress == null ? ProgressListener.NONE : progress;
        failures = failures == null ? FailureListener.NONE : failures;

        List<AnvilRegionReader.ChunkLocation> chunks = new ArrayList<>();
        int failedRegions = 0;
        for (RegionSource.RegionFile region : RegionSource.discover(source)) {
            try {
                chunks.addAll(this.reader.scan(region));
            } catch (Exception exception) {
                failedRegions++;
                failures.onFailure(region.path(), null, exception);
            }
        }

        int total = chunks.size();
        int imported = 0;
        int skipped = 0;
        int failedChunks = 0;
        progress.onProgress(0, total);
        for (int index = 0; index < total; index++) {
            AnvilRegionReader.ChunkLocation chunk = chunks.get(index);
            try {
                if (sink.importChunk(chunk.chunkX(), chunk.chunkZ(), this.reader.read(chunk))) {
                    imported++;
                } else {
                    skipped++;
                }
            } catch (Exception exception) {
                failedChunks++;
                failures.onFailure(chunk.region().path(), chunk, exception);
            } finally {
                progress.onProgress(index + 1, total);
            }
        }
        return new ImportReport(total, imported, skipped, failedChunks, failedRegions);
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (completed, total) -> { };
        void onProgress(int completed, int total);
    }

    @FunctionalInterface
    public interface FailureListener {
        FailureListener NONE = (path, chunk, failure) -> { };
        void onFailure(Path path, AnvilRegionReader.ChunkLocation chunk, Exception failure);
    }

    public record ImportReport(int totalChunks, int importedChunks, int skippedChunks,
                               int failedChunks, int failedRegions) {
    }
}
