package com.y271727uy.voxy.client;

import com.mojang.logging.LogUtils;
import com.y271727uy.voxy.common.importer.chunky.RegionImportJobTask;
import com.y271727uy.voxy.common.importer.chunky.VanillaRegionImporter;
import com.y271727uy.voxy.common.importer.chunky.WorldEngineChunkImportSink;
import com.y271727uy.voxy.common.importing.job.FileImportCheckpointStore;
import com.y271727uy.voxy.common.importing.job.ImportJobExecutor;
import com.y271727uy.voxy.common.importing.job.ImportJobHandle;
import com.y271727uy.voxy.common.importing.job.ImportProgressSnapshot;
import com.y271727uy.voxy.common.world.WorldEngine;
import com.y271727uy.voxy.client.importing.progress.ClientImportProgressDisplay;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns the one bounded importer queue associated with the active client world. */
public final class ClientImportController implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FAILURE_DETAIL_LIMIT = 8;

    private final ClientLevel level;
    private final WorldEngine engine;
    private final ImportJobExecutor jobs;
    private final ClientImportProgressDisplay progressDisplay;
    private ImportJobHandle active;

    public ClientImportController(ClientLevel level, WorldEngine engine, Path checkpointDirectory) {
        this.level = level;
        this.engine = engine;
        this.jobs = new ImportJobExecutor(new FileImportCheckpointStore(checkpointDirectory), 1,
                "Voxy region importer");
        this.progressDisplay = new ClientImportProgressDisplay();
    }

    public synchronized ImportJobHandle startRegionImport(Path source) {
        if (this.active != null && !this.active.snapshot().state().isTerminal()) {
            throw new IllegalStateException("A Voxy import is already active");
        }
        Path normalized = source.toAbsolutePath().normalize();
        AtomicInteger failures = new AtomicInteger();
        RegionImportJobTask task = new RegionImportJobTask(normalized,
                new WorldEngineChunkImportSink(this.engine, this.level),
                (path, chunk, failure) -> {
                    int count = failures.incrementAndGet();
                    if (count <= FAILURE_DETAIL_LIMIT) {
                        LOGGER.warn("Voxy region import rejected {}{}", path,
                                chunk == null ? "" : " " + chunk.describe(), failure);
                    }
                });
        String jobId = "region|" + this.level.dimension().location() + "|" + normalized;
        ImportJobHandle handle = this.jobs.submit(jobId, task);
        this.active = handle;
        this.progressDisplay.begin(handle);
        handle.completion().whenComplete((snapshot, failure) -> {
            VanillaRegionImporter.ImportReport report = task.report();
            if (failure != null) {
                LOGGER.error("Voxy region import failed for {} after {} isolated input failures",
                        normalized, failures.get(), failure);
            } else {
                LOGGER.info("Voxy region import {} for {}: progress={}/{}, report={}, isolatedFailures={}",
                        snapshot.state(), normalized, snapshot.completed(), snapshot.total(), report, failures.get());
            }
        });
        return handle;
    }

    public synchronized Optional<ImportProgressSnapshot> snapshot() {
        return this.active == null ? Optional.empty() : Optional.of(this.active.snapshot());
    }

    public synchronized boolean cancel() {
        return this.active != null && this.active.cancel();
    }

    @Override
    public void close() {
        try {
            this.progressDisplay.close();
        } finally {
            this.jobs.close();
        }
    }
}
