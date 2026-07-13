package com.y271727uy.voxy.client;

import com.mojang.logging.LogUtils;
import com.y271727uy.voxy.common.storage.StorageRuntimeFactory;
import com.y271727uy.voxy.config.VoxyClientConfig;
import com.y271727uy.voxy.client.model.VoxyModelCatalog;
import com.y271727uy.voxy.client.render.EmbeddiumRenderBridge;
import com.y271727uy.voxy.client.render.OculusShaderBridge;
import com.y271727uy.voxy.client.render.mesh.GpuSectionRenderer;
import com.y271727uy.voxy.common.world.WorldEngine;
import com.y271727uy.voxy.common.world.service.VoxelIngestService;
import com.y271727uy.voxy.common.importing.job.ImportJobHandle;
import com.y271727uy.voxy.common.importing.job.ImportProgressSnapshot;
import com.y271727uy.voxy.common.release.CloseSequence;
import com.y271727uy.voxy.common.release.OneShotFailureDisable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

public final class VoxyClientLifecycle {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ClientLevel level;
    private static WorldEngine engine;
    private static VoxelIngestService ingestService;
    private static ClientImportController importController;
    private static StorageRuntimeFactory.RuntimeStorage storageRuntime;
    private static OneShotFailureDisable tickFailureDisable = new OneShotFailureDisable();
    private static ClientLevel disabledLevel;

    private VoxyClientLifecycle() {
    }

    public static synchronized void onLevelStarted(ClientLevel clientLevel) {
        if (disabledLevel == clientLevel) {
            return;
        }
        if (disabledLevel != null) {
            disabledLevel = null;
        }
        if (level == clientLevel) {
            return;
        }
        closeCurrentLevel();
        tickFailureDisable = new OneShotFailureDisable();
        level = clientLevel;
        Path storagePath = storagePath(clientLevel);
        try {
            storageRuntime = StorageRuntimeFactory.open(storagePath,
                    VoxyClientConfig.STORAGE_BACKEND.get(), VoxyClientConfig.STORAGE_QUEUE_CAPACITY.get());
            engine = new WorldEngine(storageRuntime.storage());
            importController = new ClientImportController(clientLevel, engine,
                    storagePath.resolve("import-checkpoints"));
            VoxyModelCatalog.INSTANCE.attach(engine.mapping());
            EmbeddiumRenderBridge.attach(clientLevel);
            OculusShaderBridge.attach(clientLevel);
            GpuSectionRenderer.attach(engine);
            ingestService = new VoxelIngestService(engine, VoxyClientConfig.INGEST_QUEUE_CAPACITY.get());
            LOGGER.info("Voxy world engine started for {} at {} with backend={} asyncCapacity={} ingestCapacity={}",
                    clientLevel.dimension().location(), storagePath,
                    storageRuntime.provider().descriptor().id(), storageRuntime.storage().metrics().capacity(),
                    ingestService.metrics().queueCapacity());
        } catch (RuntimeException failure) {
            disabledLevel = clientLevel;
            try {
                closeCurrentLevel();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            LOGGER.error("Voxy failed to start; disabling Voxy for this world", failure);
        } catch (Error failure) {
            try {
                closeCurrentLevel();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public static synchronized void onChunkLoaded(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ClientLevel clientLevel)) {
            return;
        }
        onLevelStarted(clientLevel);
        if (ingestService == null) return;
        int submitted = ingestService.enqueue(chunk);
        LOGGER.trace("Queued {} Voxy sections from chunk [{}, {}]", submitted, chunk.getPos().x, chunk.getPos().z);
    }

    public static synchronized void onBlockChanged(ClientLevel clientLevel, BlockPos position) {
        if (level != clientLevel || ingestService == null) {
            return;
        }
        LevelChunk chunk = clientLevel.getChunkAt(position);
        int sectionIndex = clientLevel.getSectionIndex(position.getY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return;
        }
        ingestService.enqueueSection(chunk.getSection(sectionIndex), SectionPos.of(position), chunk);
    }

    public static synchronized void onLevelClosed() {
        disabledLevel = null;
        closeCurrentLevel();
    }

    public static synchronized void onClientTick() {
        if (engine != null && engine.isLive()) {
            try {
                engine.saveDirtySections(8);
            } catch (RuntimeException failure) {
                disabledLevel = level;
                tickFailureDisable.disable(failure,
                        cause -> LOGGER.error("Voxy background persistence failed; disabling Voxy for this world",
                                cause),
                        VoxyClientLifecycle::closeCurrentLevel);
            }
        }
    }

    public static synchronized ImportJobHandle startRegionImport(Path source) {
        if (importController == null || engine == null || !engine.isLive()) {
            throw new IllegalStateException("No active Voxy world engine");
        }
        return importController.startRegionImport(source);
    }

    public static synchronized Optional<ImportProgressSnapshot> importSnapshot() {
        return importController == null ? Optional.empty() : importController.snapshot();
    }

    public static synchronized boolean cancelImport() {
        return importController != null && importController.cancel();
    }

    public static synchronized StorageDiagnostics storageDiagnostics() {
        if (storageRuntime == null) {
            return new StorageDiagnostics(false, VoxyClientConfig.STORAGE_BACKEND.get(),
                    0, VoxyClientConfig.STORAGE_QUEUE_CAPACITY.get(), 0, 0, false);
        }
        var metrics = storageRuntime.storage().metrics();
        return new StorageDiagnostics(true, storageRuntime.provider().descriptor().id(),
                metrics.queuedCommands(), metrics.capacity(), metrics.highWaterMark(),
                metrics.backpressureEvents(), metrics.failed());
    }

    public record StorageDiagnostics(boolean active, String backend, int queued, int capacity,
                                     int highWaterMark, long backpressureEvents, boolean failed) {
    }

    private static void closeCurrentLevel() {
        ClientImportController closingImports = importController;
        VoxelIngestService closingIngest = ingestService;
        WorldEngine closingEngine = engine;
        StorageRuntimeFactory.RuntimeStorage closingStorage = storageRuntime;
        importController = null;
        ingestService = null;
        engine = null;
        storageRuntime = null;
        level = null;

        CloseSequence.run(
                CloseSequence.Step.of("import jobs", () -> {
                    if (closingImports != null) closingImports.close();
                }),
                CloseSequence.Step.of("voxel ingest", () -> {
                    if (closingIngest == null) return;
                    closingIngest.close();
                    var metrics = closingIngest.metrics();
                    LOGGER.info("Voxy ingest summary: {} submitted, {} completed, {} failed, highWater={}, "
                                    + "backpressure={}",
                            closingIngest.submittedTaskCount(), closingIngest.completedTaskCount(),
                            closingIngest.failedTaskCount(), metrics.queueHighWaterMark(),
                            metrics.backpressureEvents());
                }),
                CloseSequence.Step.of("Embeddium bridge", EmbeddiumRenderBridge::detach),
                CloseSequence.Step.of("Oculus bridge", OculusShaderBridge::detach),
                CloseSequence.Step.of("GPU renderer", GpuSectionRenderer::detach),
                CloseSequence.Step.of("model catalog", VoxyModelCatalog.INSTANCE::detach),
                CloseSequence.Step.of("world engine", () -> {
                    if (closingEngine == null) return;
                    int loaded = closingEngine.loadedSectionCount();
                    LOGGER.info("Voxy storage summary: {} restored, {} new, {} corrupt",
                            closingEngine.restoredSectionCount(), closingEngine.newSectionCount(),
                            closingEngine.corruptSectionCount());
                    closingEngine.close();
                    LOGGER.info("Voxy world engine stopped after persisting {} loaded sections", loaded);
                }),
                CloseSequence.Step.of("storage runtime", () -> {
                    if (closingStorage == null) return;
                    var metrics = closingStorage.storage().metrics();
                    LOGGER.info("Voxy async storage: backend={}, submitted={}, completed={}, highWater={}, "
                                    + "backpressure={}, failed={}",
                            closingStorage.provider().descriptor().id(), metrics.submittedCommands(),
                            metrics.completedCommands(), metrics.highWaterMark(), metrics.backpressureEvents(),
                            metrics.failed());
                    if (!metrics.closingOrClosed()) closingStorage.storage().close();
                }));
    }

    private static Path storagePath(ClientLevel clientLevel) {
        Minecraft minecraft = Minecraft.getInstance();
        String dimension = sanitize(clientLevel.dimension().location().toString());
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            return minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("voxy").resolve(dimension);
        }
        ServerData server = minecraft.getCurrentServer();
        String identity = server == null ? "unknown" : Base64.getUrlEncoder().withoutPadding()
                .encodeToString(server.ip.getBytes(StandardCharsets.UTF_8));
        return FMLPaths.GAMEDIR.get().resolve("voxy").resolve("servers").resolve(identity).resolve(dimension);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
