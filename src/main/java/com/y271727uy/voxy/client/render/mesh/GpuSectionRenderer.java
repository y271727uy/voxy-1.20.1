package com.y271727uy.voxy.client.render.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.y271727uy.voxy.client.model.BakedBlockModel;
import com.y271727uy.voxy.client.model.VoxyModelCatalog;
import com.y271727uy.voxy.client.render.OculusShaderBridge;
import com.y271727uy.voxy.client.render.OculusTraversalPipelineAdapter;
import com.y271727uy.voxy.client.render.gl.GpuCapabilities;
import com.y271727uy.voxy.client.render.gl.RenderBackendPolicy;
import com.y271727uy.voxy.common.world.WorldEngine;
import com.y271727uy.voxy.common.world.WorldSection;
import com.y271727uy.voxy.common.world.WorldSectionKey;
import com.y271727uy.voxy.config.VoxyClientConfig;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.irisshaders.iris.pipeline.SodiumTerrainPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.Direction;
import org.slf4j.Logger;

public final class GpuSectionRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long METRIC_INTERVAL_NANOS = 10_000_000_000L;
    private static final long CLEANUP_FAILURE_LOG_INTERVAL_NANOS = 10_000_000_000L;
    private static final long L13_FAILURE_LOG_INTERVAL_NANOS = 10_000_000_000L;
    private static final long DISCOVERY_INTERVAL_FRAMES = 20;
    private static final double COVERAGE_CACHE_CELL_SIZE = 16.0;
    private static final int MAX_CACHED_SECTIONS = 2_048;
    private static final int REBUILD_PRIORITY_WINDOW = 64;
    private static final int METADATA_REBUILD_BUDGET = 64;
    private static final int MISSING_LOAD_BUDGET = 8;
    private static final ConcurrentLinkedQueue<Long> REBUILD_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<Long> QUEUED = ConcurrentHashMap.newKeySet();
    private static final Set<Long> DISCOVERED = ConcurrentHashMap.newKeySet();
    private static final Map<Long, GpuSection> SECTIONS = new ConcurrentHashMap<>();
    private static final Map<Long, LodCoverageSelector.Node> READY_SECTIONS = new ConcurrentHashMap<>();
    private static final LodCoverageSelector COVERAGE_SELECTOR =
            new LodCoverageSelector(WorldEngine.MAX_LOD_LEVEL);
    private static final HierarchicalOcclusionTraverser OCCLUSION_TRAVERSER =
            new HierarchicalOcclusionTraverser(WorldEngine.MAX_LOD_LEVEL);
    private static final L13MeshBuildCoordinator L13_COORDINATOR = new L13MeshBuildCoordinator(4);
    private static WorldEngine engine;
    private static long modelGeneration = -1;
    private static long gpuBytes;
    private static long frame;
    private static long uploads;
    private static long evictions;
    private static boolean nativeBuffersDisabled;
    private static boolean translucentBuffersDisabled;
    private static boolean terrainProgramDisabled;
    private static VoxyTerrainProgram terrainProgram;
    private static VoxySsaoPipeline ssaoPipeline;
    private static boolean ssaoDisabled;
    private static boolean regionalBuffersDisabled;
    private static EmbeddiumRegionalBuffer regionalBuffers;
    private static SodiumTerrainPipeline oculusPipeline;
    private static OculusTerrainProgram oculusTerrainProgram;
    private static OculusTerrainProgram oculusTranslucentProgram;
    private static OculusTerrainProgram oculusShadowProgram;
    private static GpuCapabilities gpuCapabilities;
    private static RenderBackendPolicy.Selection backendSelection;
    private static GpuTraversalShadowRuntime gpuTraversalShadow;
    private static boolean gpuTraversalShadowAttempted;
    private static GpuTraversalShadowRuntime.Metrics gpuTraversalShadowMetrics =
            GpuTraversalShadowRuntime.Metrics.unavailable("not initialized");
    private static final EnumMap<OculusTerrainProgram.Pass, OculusTraversalPipelineAdapter.Capability>
            OCULUS_TRAVERSAL_CAPABILITIES = new EnumMap<>(OculusTerrainProgram.Pass.class);
    private static final EnumSet<OculusTerrainProgram.Pass> OCULUS_DISABLED_PASSES =
            EnumSet.noneOf(OculusTerrainProgram.Pass.class);
    private static long selectionTransitions;
    private static long lastMetricTime;
    private static int frameSelected;
    private static int frameVisible;
    private static int frameDrawn;
    private static int frameTerrainDrawn;
    private static int frameRegionalBatches;
    private static int frameSsaoDraws;
    private static long ssaoFrames;
    private static int frameOculusTerrainDrawn;
    private static int frameOculusShadowDrawn;
    private static int frameTranslucentVisible;
    private static int frameTranslucentDrawn;
    private static int frameHierarchyVisited;
    private static int frameHierarchyPruned;
    private static long translucentSortUploads;
    private static long l13UnsupportedOccurrences;
    private static long lastL13FailureLogTime;
    private static long suppressedL13FailureLogs;
    private static long lastCleanupFailureLogTime;
    private static long suppressedCleanupFailureLogs;
    private static double frameCameraX;
    private static double frameCameraZ;
    private static long lastDiscoveryFrame = Long.MIN_VALUE;
    private static long readyRevision;
    private static Map<Long, LodCoverageSelector.Node> frameReadySnapshot = Map.of();
    private static long coverageReadyRevision = Long.MIN_VALUE;
    private static long coverageCameraCellX = Long.MIN_VALUE;
    private static long coverageCameraCellZ = Long.MIN_VALUE;
    private static int coverageVanillaDistance = Integer.MIN_VALUE;
    private static double coverageConfiguredDistance = Double.NaN;
    private static LodCoverageSelector.Selection frameCoverage =
            new LodCoverageSelector.Selection(Set.of(), Set.of(), Set.of(), 0, 0, 0, 0, 0);
    private static Set<Long> frameVisibleKeys = Set.of();
    private static volatile RenderMetrics lastMetrics = RenderMetrics.EMPTY;
    private static long frameDiscoverNs;
    private static long frameSelectionNs;
    private static long frameRebuildNs;
    private static long frameTraversalNs;
    private static long frameShadowNs;
    private static long frameDrawNs;
    private static long timingFrames;
    private static long timingFrameNs;
    private static long timingDiscoverNs;
    private static long timingSelectionNs;
    private static long timingRebuildNs;
    private static long timingTraversalNs;
    private static long timingShadowNs;
    private static long timingDrawNs;
    private static long coverageCacheHits;
    private static long coverageCacheMisses;

    private GpuSectionRenderer() {
    }

    public static void attach(WorldEngine worldEngine) {
        closeGpuTraversalShadow();
        gpuTraversalShadowAttempted = false;
        gpuTraversalShadowMetrics = GpuTraversalShadowRuntime.Metrics.unavailable("attached; awaiting render thread");
        engine = worldEngine;
        modelGeneration = VoxyModelCatalog.INSTANCE.generation();
        worldEngine.setDirtyListener(section -> queue(section.key()));
        loadStoredRoots(worldEngine);
        discoverLoadedSections(worldEngine, true);
    }

    public static void detach() {
        WorldEngine current = engine;
        if (current != null) {
            current.setDirtyListener(null);
        }
        engine = null;
        REBUILD_QUEUE.clear();
        QUEUED.clear();
        L13_COORDINATOR.clear();
        DISCOVERED.clear();
        SECTIONS.forEach((key, section) -> closeReplaced(section, key));
        SECTIONS.clear();
        if (terrainProgram != null) {
            closeResource(terrainProgram, "terrain program");
            terrainProgram = null;
        }
        if (ssaoPipeline != null) {
            closeResource(ssaoPipeline, "SSAO pipeline");
            ssaoPipeline = null;
        }
        if (regionalBuffers != null) {
            closeResource(regionalBuffers, "regional buffers");
            regionalBuffers = null;
        }
        closeOculusPrograms();
        closeGpuTraversalShadow();
        gpuCapabilities = null;
        backendSelection = null;
        OCULUS_TRAVERSAL_CAPABILITIES.clear();
        clearReadySections();
        COVERAGE_SELECTOR.reset();
        gpuBytes = 0;
        frame = 0;
        uploads = 0;
        evictions = 0;
        nativeBuffersDisabled = false;
        translucentBuffersDisabled = false;
        terrainProgramDisabled = false;
        ssaoDisabled = false;
        regionalBuffersDisabled = false;
        OCULUS_DISABLED_PASSES.clear();
        selectionTransitions = 0;
        lastMetricTime = 0;
        frameSelected = 0;
        frameVisible = 0;
        frameDrawn = 0;
        frameTerrainDrawn = 0;
        frameRegionalBatches = 0;
        frameSsaoDraws = 0;
        ssaoFrames = 0;
        frameOculusTerrainDrawn = 0;
        frameOculusShadowDrawn = 0;
        frameTranslucentVisible = 0;
        frameTranslucentDrawn = 0;
        frameHierarchyVisited = 0;
        frameHierarchyPruned = 0;
        frameVisibleKeys = Set.of();
        frameReadySnapshot = Map.of();
        translucentSortUploads = 0;
        l13UnsupportedOccurrences = 0;
        lastL13FailureLogTime = 0;
        suppressedL13FailureLogs = 0;
        lastCleanupFailureLogTime = 0;
        suppressedCleanupFailureLogs = 0;
        frameCameraX = 0;
        frameCameraZ = 0;
        lastDiscoveryFrame = Long.MIN_VALUE;
        invalidateCoverageCache();
        resetFrameTiming();
        resetTimingTotals();
        coverageCacheHits = 0;
        coverageCacheMisses = 0;
        lastMetrics = RenderMetrics.EMPTY;
        gpuTraversalShadowAttempted = false;
        gpuTraversalShadowMetrics = GpuTraversalShadowRuntime.Metrics.unavailable("detached");
    }

    public static int cachedSectionCount() {
        return SECTIONS.size();
    }

    public static void render(RenderLevelStageEvent event, SodiumWorldRenderer embeddium) {
        WorldEngine current = engine;
        if (current == null || !VoxyClientConfig.RENDERING_ENABLED.get()) {
            return;
        }
        long frameStart = System.nanoTime();
        resetFrameTiming();
        ensureBackendSelection();
        OculusShaderBridge.ShaderState shaderState = OculusShaderBridge.lastState();
        if (shaderState != null && shaderState.shaderPackInUse()) {
            closeGpuTraversalShadow();
            gpuTraversalShadowAttempted = false;
            gpuTraversalShadowMetrics = GpuTraversalShadowRuntime.Metrics.unavailable("shader pack active");
            return;
        }
        long currentGeneration = VoxyModelCatalog.INSTANCE.generation();
        if (modelGeneration != currentGeneration) {
            modelGeneration = currentGeneration;
            DISCOVERED.clear();
            current.loadedSectionKeys().forEach(GpuSectionRenderer::queue);
            lastDiscoveryFrame = Long.MIN_VALUE;
        }
        frame++;
        timeDiscover(() -> discoverLoadedSections(current, false));
        L13_COORDINATOR.enqueueEligible(frame, currentGeneration, new L13QueueCallbacks(null));
        Vec3 camera = event.getCamera().getPosition();
        int vanillaDistance = Minecraft.getInstance().options.renderDistance().get() * 16;
        rebuildOne(current, camera, vanillaDistance);
        Matrix4f projection = extendedProjection(event.getProjectionMatrix());
        drawVisible(current, event, camera, vanillaDistance, projection);
        evictIfNeeded(camera);
        recordFrameTiming(System.nanoTime() - frameStart);
    }

    public static void renderTranslucent(RenderLevelStageEvent event, SodiumWorldRenderer embeddium) {
        WorldEngine current = engine;
        if (current == null || !VoxyClientConfig.RENDERING_ENABLED.get()) return;
        OculusShaderBridge.ShaderState shaderState = OculusShaderBridge.lastState();
        if (shaderState != null && shaderState.shaderPackInUse()) return;
        Vec3 camera = event.getCamera().getPosition();
        VoxyTerrainProgram activeTerrainProgram = terrainProgram();
        ShaderInstance fallbackShader = GameRenderer.getPositionColorTexLightmapShader();
        if (activeTerrainProgram == null || fallbackShader == null) {
            reportMetrics(current);
            return;
        }

        List<Map.Entry<Long, GpuSection>> visible = new ArrayList<>();
        for (long key : frameVisibleKeys) {
            GpuSection section = SECTIONS.get(key);
            if (section == null || section.translucentBuffer() == null) continue;
            visible.add(Map.entry(key, section));
            if (section.sortDistanceSquared(camera) > 16.0 * 16.0) queue(key);
        }
        visible.sort(Comparator.comparingDouble(
                (Map.Entry<Long, GpuSection> entry) -> distanceSquared(entry.getValue(), camera)).reversed());
        frameTranslucentVisible = visible.size();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        PoseStack poses = event.getPoseStack();
        Matrix4f projection = extendedProjection(event.getProjectionMatrix());
        try {
            for (Map.Entry<Long, GpuSection> entry : visible) {
                GpuSection section = entry.getValue();
                poses.pushPose();
                try {
                    poses.translate(section.x() - camera.x, section.y() - camera.y, section.z() - camera.z);
                    section.translucentBuffer().draw(poses.last().pose(), projection, activeTerrainProgram, fallbackShader);
                    frameTranslucentDrawn++;
                } finally {
                    poses.popPose();
                }
            }
        } finally {
            VertexBuffer.unbind();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
        }
        reportMetrics(current);
    }

    public static void renderOculus(RenderLevelStageEvent event, SodiumWorldRenderer embeddium,
                                    WorldRenderingPipeline worldPipeline,
                                    SodiumTerrainPipeline terrainPipeline, boolean shadowPass) {
        WorldEngine current = engine;
        if (current == null || !VoxyClientConfig.RENDERING_ENABLED.get()) return;
        long frameStart = System.nanoTime();
        resetFrameTiming();
        ensureBackendSelection();
        long currentGeneration = VoxyModelCatalog.INSTANCE.generation();
        if (modelGeneration != currentGeneration) {
            modelGeneration = currentGeneration;
            DISCOVERED.clear();
            current.loadedSectionKeys().forEach(GpuSectionRenderer::queue);
            lastDiscoveryFrame = Long.MIN_VALUE;
        }
        frame++;
        timeDiscover(() -> discoverLoadedSections(current, false));
        if (shouldScheduleL13Retries(shadowPass)) {
            L13_COORDINATOR.enqueueEligible(frame, currentGeneration, new L13QueueCallbacks(null));
        }
        Vec3 camera = event.getCamera().getPosition();
        int vanillaDistance = Minecraft.getInstance().options.renderDistance().get() * 16;
        if (!shadowPass) {
            frameVisibleKeys = Set.of();
            rebuildOne(current, camera, vanillaDistance);
        }
        OculusTerrainProgram program = oculusProgram(terrainPipeline,
                shadowPass ? OculusTerrainProgram.Pass.SHADOW : OculusTerrainProgram.Pass.TERRAIN);
        if (program == null) return;
        Matrix4f projection = extendedProjection(event.getProjectionMatrix());
        try {
            drawOculusVisible(current, camera, vanillaDistance, program, shadowPass,
                    event.getPoseStack().last().pose(), projection,
                    frameReadySnapshot,
                    extendedFrustumVisibility(event.getPoseStack().last().pose(), projection, camera));
        } catch (RuntimeException | LinkageError failure) {
            disableOculusPass(shadowPass ? OculusTerrainProgram.Pass.SHADOW
                    : OculusTerrainProgram.Pass.TERRAIN, failure);
            return;
        }
        if (!shadowPass) evictIfNeeded(camera);
        recordFrameTiming(System.nanoTime() - frameStart);
        reportMetrics(current);
    }

    public static void renderOculusShadow(Camera shadowCamera, SodiumTerrainPipeline terrainPipeline) {
        WorldEngine current = engine;
        if (current == null || !VoxyClientConfig.RENDERING_ENABLED.get()
                || ShadowRenderer.MODELVIEW == null || ShadowRenderer.PROJECTION == null) return;
        ensureBackendSelection();
        OculusTerrainProgram program = oculusProgram(terrainPipeline, OculusTerrainProgram.Pass.SHADOW);
        if (program == null) return;
        Vec3 camera = shadowCamera.getPosition();
        try {
            drawOculusVisible(current, camera, 0, program, true,
                    ShadowRenderer.MODELVIEW, ShadowRenderer.PROJECTION,
                    frameReadySnapshot,
                    bounds -> withinShadowDistance((int) bounds.minX(), (int) bounds.minZ(),
                            (int) (bounds.maxX() - bounds.minX()),
                            camera.x, camera.z, ShadowRenderer.renderDistance));
        } catch (RuntimeException | LinkageError failure) {
            disableOculusPass(OculusTerrainProgram.Pass.SHADOW, failure);
            return;
        }
        reportMetrics(current);
    }

    static boolean shouldScheduleL13Retries(boolean shadowPass) {
        return !shadowPass;
    }

    public static void renderOculusTranslucent(RenderLevelStageEvent event, SodiumWorldRenderer embeddium,
                                               SodiumTerrainPipeline terrainPipeline) {
        WorldEngine current = engine;
        if (current == null || !VoxyClientConfig.RENDERING_ENABLED.get()) return;
        OculusTerrainProgram program = oculusProgram(terrainPipeline, OculusTerrainProgram.Pass.TRANSLUCENT);
        if (program == null) return;
        Vec3 camera = event.getCamera().getPosition();
        List<GpuSection> visible = new ArrayList<>();
        for (long key : frameVisibleKeys) {
            GpuSection section = SECTIONS.get(key);
            if (section == null || section.translucentBuffer() == null) continue;
            visible.add(section);
        }
        visible.sort(Comparator.comparingDouble((GpuSection section) -> distanceSquared(section, camera)).reversed());
        frameTranslucentVisible = visible.size();
        frameTranslucentDrawn = 0;
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        Matrix4f projection = extendedProjection(event.getProjectionMatrix());
        try {
            Matrix4f baseModelView = event.getPoseStack().last().pose();
            for (GpuSection section : visible) {
                program.setRegionOffset((float) (section.x() - camera.x),
                        (float) (section.y() - camera.y), (float) (section.z() - camera.z));
                ((NativeSectionBuffer) section.translucentBuffer()).buffer().draw(
                        baseModelView, projection, program);
                frameTranslucentDrawn++;
            }
        } catch (RuntimeException | LinkageError failure) {
            disableOculusPass(OculusTerrainProgram.Pass.TRANSLUCENT, failure);
        } finally {
            VertexBuffer.unbind();
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
        }
        reportMetrics(current);
    }

    private static void drawOculusVisible(WorldEngine current, Vec3 camera, int vanillaDistance,
                                            OculusTerrainProgram program, boolean shadowPass,
                                            Matrix4f baseModelView, Matrix4f projection,
                                            Map<Long, LodCoverageSelector.Node> readySnapshot,
                                            HierarchicalOcclusionTraverser.Visibility visibility) {
        if (!shadowPass) {
            frameCoverage = selectCoverage(camera, vanillaDistance);
            frameCameraX = camera.x;
            frameCameraZ = camera.z;
            selectionTransitions += frameCoverage.transitions();
            loadMissingSections(current, frameCoverage.missingKeys());
            frameSelected = frameCoverage.selectedKeys().size();
            frameVisible = 0;
            frameDrawn = 0;
            frameTerrainDrawn = 0;
            frameRegionalBatches = 0;
            frameSsaoDraws = 0;
            frameTranslucentVisible = 0;
            frameTranslucentDrawn = 0;
            frameOculusTerrainDrawn = 0;
        } else {
            frameOculusShadowDrawn = 0;
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        List<GpuSection> nativeDraws = new ArrayList<>();
        Map<EmbeddiumRegionalBuffer.Region, List<GpuSection>> regionalDraws = new java.util.LinkedHashMap<>();
        long traversalStart = System.nanoTime();
        HierarchicalOcclusionTraverser.Traversal traversal = OCCLUSION_TRAVERSER.traverse(
                frameCoverage.selectedKeys(), visibility);
        frameTraversalNs += System.nanoTime() - traversalStart;
        if (!shadowPass) {
            frameVisibleKeys = traversal.visibleKeys();
            frameHierarchyVisited = traversal.metrics().visited();
            frameHierarchyPruned = traversal.metrics().prunedBranches();
        }
        for (long key : traversal.visibleKeys()) {
            GpuSection section = SECTIONS.get(key);
            if (section == null || section.buffer() == null || section.buffer() instanceof VanillaSectionBuffer) continue;
            if (!shadowPass) {
                frameVisible++;
                section.markVisible(frame);
            }
            if (section.buffer() instanceof SharedSectionBuffer shared) {
                regionalDraws.computeIfAbsent(shared.allocation().region(), ignored -> new ArrayList<>()).add(section);
            } else if (section.buffer() instanceof NativeSectionBuffer) {
                nativeDraws.add(section);
            }
        }
        long drawStart = System.nanoTime();
        try {
            for (GpuSection section : nativeDraws) {
                program.setRegionOffset((float) (section.x() - camera.x),
                        (float) (section.y() - camera.y), (float) (section.z() - camera.z));
                ((NativeSectionBuffer) section.buffer()).buffer().draw(baseModelView, projection, program);
                if (shadowPass) frameOculusShadowDrawn++; else frameOculusTerrainDrawn++;
            }
            if (regionalBuffers != null) {
                for (Map.Entry<EmbeddiumRegionalBuffer.Region, List<GpuSection>> entry : regionalDraws.entrySet()) {
                    List<GpuSection> sections = entry.getValue();
                    EmbeddiumRegionalBuffer.RegionKey regionKey =
                            ((SharedSectionBuffer) sections.get(0).buffer()).allocation().regionKey();
                    program.setRegionOffset((float) (regionKey.originX() - camera.x),
                            (float) (regionKey.originY() - camera.y),
                            (float) (regionKey.originZ() - camera.z));
                    List<EmbeddiumRegionalBuffer.Allocation> allocations = sections.stream()
                            .map(section -> ((SharedSectionBuffer) section.buffer()).allocation()).toList();
                    regionalBuffers.draw(entry.getKey(), allocations, baseModelView, projection, program);
                    if (shadowPass) frameOculusShadowDrawn += sections.size();
                    else frameOculusTerrainDrawn += sections.size();
                }
            }
            if (!shadowPass) {
                frameDrawn = frameOculusTerrainDrawn;
                frameTerrainDrawn = frameOculusTerrainDrawn;
                frameRegionalBatches = regionalDraws.size();
            }
        } finally {
            frameDrawNs += System.nanoTime() - drawStart;
            VertexBuffer.unbind();
        }
    }

    private static OculusTerrainProgram oculusProgram(SodiumTerrainPipeline pipeline,
                                                      OculusTerrainProgram.Pass pass) {
        if (oculusPipeline != pipeline) {
            closeOculusPrograms();
            OCULUS_DISABLED_PASSES.clear();
            oculusPipeline = pipeline;
        }
        if (OCULUS_DISABLED_PASSES.contains(pass)) return null;
        try {
            if (pass == OculusTerrainProgram.Pass.SHADOW) {
                if (!pipeline.hasShadowPass()) return null;
                if (oculusShadowProgram == null) {
                    oculusShadowProgram = OculusTerrainProgram.create(pipeline, OculusTerrainProgram.Pass.SHADOW);
                    LOGGER.info("Voxy Oculus shadow program active: {}", oculusShadowProgram.diagnostics());
                    reportOculusTraversalCapability(pipeline, pass);
                }
                return oculusShadowProgram;
            }
            if (pass == OculusTerrainProgram.Pass.TRANSLUCENT) {
                if (oculusTranslucentProgram == null) {
                    oculusTranslucentProgram = OculusTerrainProgram.create(
                            pipeline, OculusTerrainProgram.Pass.TRANSLUCENT);
                    LOGGER.info("Voxy Oculus translucent program active: {}",
                            oculusTranslucentProgram.diagnostics());
                    reportOculusTraversalCapability(pipeline, pass);
                }
                return oculusTranslucentProgram;
            }
            if (oculusTerrainProgram == null) {
                oculusTerrainProgram = OculusTerrainProgram.create(pipeline, OculusTerrainProgram.Pass.TERRAIN);
                LOGGER.info("Voxy Oculus terrain program active: {}", oculusTerrainProgram.diagnostics());
                reportOculusTraversalCapability(pipeline, pass);
            }
            return oculusTerrainProgram;
        } catch (RuntimeException | LinkageError failure) {
            disableOculusPass(pass, failure);
            return null;
        }
    }

    private static void disableOculusPass(OculusTerrainProgram.Pass pass, Throwable failure) {
        OCULUS_DISABLED_PASSES.add(pass);
        closeOculusProgram(pass);
        LOGGER.error("Voxy Oculus {} program failed; disabling only that pass until the pipeline reloads",
                pass.name().toLowerCase(java.util.Locale.ROOT), failure);
    }

    private static void closeOculusProgram(OculusTerrainProgram.Pass pass) {
        if (pass == OculusTerrainProgram.Pass.SHADOW && oculusShadowProgram != null) {
            closeResource(oculusShadowProgram, "Oculus shadow program");
            oculusShadowProgram = null;
        } else if (pass == OculusTerrainProgram.Pass.TRANSLUCENT && oculusTranslucentProgram != null) {
            closeResource(oculusTranslucentProgram, "Oculus translucent program");
            oculusTranslucentProgram = null;
        } else if (pass == OculusTerrainProgram.Pass.TERRAIN && oculusTerrainProgram != null) {
            closeResource(oculusTerrainProgram, "Oculus terrain program");
            oculusTerrainProgram = null;
        }
    }

    private static void closeOculusPrograms() {
        if (oculusTranslucentProgram != null) {
            closeResource(oculusTranslucentProgram, "Oculus translucent program");
            oculusTranslucentProgram = null;
        }
        if (oculusShadowProgram != null) {
            closeResource(oculusShadowProgram, "Oculus shadow program");
            oculusShadowProgram = null;
        }
        if (oculusTerrainProgram != null) {
            closeResource(oculusTerrainProgram, "Oculus terrain program");
            oculusTerrainProgram = null;
        }
        oculusPipeline = null;
        OCULUS_TRAVERSAL_CAPABILITIES.clear();
    }

    private static void ensureBackendSelection() {
        if (backendSelection != null) return;
        gpuCapabilities = GpuCapabilities.detect();
        backendSelection = RenderBackendPolicy.select(gpuCapabilities);
        LOGGER.info("Voxy GPU backend: backend={}, hardwareEligible={}, fallback={}, vendor={}, version={}, "
                        + "compute={}, ssbo={}, mdi={}, indirectCount={}, bufferStorage={}, dsa={}",
                backendSelection.backend(), backendSelection.hardwareEligible(),
                backendSelection.primaryReason(), gpuCapabilities.vendor(), gpuCapabilities.version(),
                gpuCapabilities.computeShaders(), gpuCapabilities.shaderStorageBuffers(),
                gpuCapabilities.multiDrawIndirect(), gpuCapabilities.indirectDrawCount(),
                gpuCapabilities.bufferStorage(), gpuCapabilities.directStateAccess());
    }

    private static void reportOculusTraversalCapability(SodiumTerrainPipeline pipeline,
                                                         OculusTerrainProgram.Pass pass) {
        OculusShaderBridge.ShaderState state = OculusShaderBridge.lastState();
        if (state == null) return;
        OculusTraversalPipelineAdapter.Pass adapterPass = switch (pass) {
            case TERRAIN -> OculusTraversalPipelineAdapter.Pass.OPAQUE;
            case TRANSLUCENT -> OculusTraversalPipelineAdapter.Pass.TRANSLUCENT;
            case SHADOW -> OculusTraversalPipelineAdapter.Pass.SHADOW;
        };
        OculusTraversalPipelineAdapter.Capability capability =
                OculusTraversalPipelineAdapter.probe(state, pipeline, adapterPass);
        OCULUS_TRAVERSAL_CAPABILITIES.put(pass, capability);
        LOGGER.info("Voxy Oculus traversal ABI {}: geometry={}, framebuffer={}, depth={}, nodeSsbo={}, "
                        + "computeTraversal={}, hiZ={}, limitation={}",
                adapterPass, capability.geometryPathAvailable(), capability.framebufferId(),
                capability.depthAttachmentAvailable(), capability.nodeSsboAbiAvailable(),
                capability.computeTraversalAvailable(), capability.hiZAvailable(), capability.limitation());
    }

    private static boolean queue(long key) {
        if (QUEUED.add(key)) {
            REBUILD_QUEUE.add(key);
            return true;
        }
        return false;
    }

    private static void putReadySection(LodCoverageSelector.Node node) {
        LodCoverageSelector.Node previous = READY_SECTIONS.put(node.key(), node);
        if (!node.equals(previous)) {
            readyRevision++;
        }
    }

    private static void removeReadySection(long key) {
        if (READY_SECTIONS.remove(key) != null) {
            readyRevision++;
        }
    }

    private static void clearReadySections() {
        if (!READY_SECTIONS.isEmpty()) {
            READY_SECTIONS.clear();
            readyRevision++;
        } else {
            READY_SECTIONS.clear();
        }
        invalidateCoverageCache();
    }

    private static void invalidateCoverageCache() {
        coverageReadyRevision = Long.MIN_VALUE;
        coverageCameraCellX = Long.MIN_VALUE;
        coverageCameraCellZ = Long.MIN_VALUE;
        coverageVanillaDistance = Integer.MIN_VALUE;
        coverageConfiguredDistance = Double.NaN;
        frameReadySnapshot = Map.of();
    }

    private static LodCoverageSelector.Selection selectCoverage(Vec3 camera, int vanillaDistance) {
        long cameraCellX = (long) Math.floor(camera.x / COVERAGE_CACHE_CELL_SIZE);
        long cameraCellZ = (long) Math.floor(camera.z / COVERAGE_CACHE_CELL_SIZE);
        double configuredDistance = configuredRenderDistanceBlocks();
        if (coverageReadyRevision == readyRevision
                && coverageCameraCellX == cameraCellX
                && coverageCameraCellZ == cameraCellZ
                && coverageVanillaDistance == vanillaDistance
                && Double.compare(coverageConfiguredDistance, configuredDistance) == 0) {
            coverageCacheHits++;
            return frameCoverage;
        }

        coverageCacheMisses++;
        long selectionStart = System.nanoTime();
        Map<Long, LodCoverageSelector.Node> snapshot = Map.copyOf(READY_SECTIONS);
        LodCoverageSelector.Selection selection = COVERAGE_SELECTOR.select(snapshot, camera.x, camera.z,
                vanillaDistance, configuredDistance);
        frameSelectionNs += System.nanoTime() - selectionStart;

        frameReadySnapshot = snapshot;
        coverageReadyRevision = readyRevision;
        coverageCameraCellX = cameraCellX;
        coverageCameraCellZ = cameraCellZ;
        coverageVanillaDistance = vanillaDistance;
        coverageConfiguredDistance = configuredDistance;
        return selection;
    }

    private static void discoverLoadedSections(WorldEngine current, boolean force) {
        if (!force && lastDiscoveryFrame != Long.MIN_VALUE
                && frame - lastDiscoveryFrame < DISCOVERY_INTERVAL_FRAMES) {
            return;
        }
        lastDiscoveryFrame = frame;
        for (long key : current.loadedSectionKeys()) {
            if (DISCOVERED.add(key)) {
                queue(key);
            }
        }
    }

    private static void loadStoredRoots(WorldEngine current) {
        for (long key : current.storedSectionKeys(WorldEngine.MAX_LOD_LEVEL)) {
            WorldSection section = current.acquireIfExists(key);
            if (section != null) {
                try {
                    queue(key);
                } finally {
                    section.release();
                }
            }
        }
    }

    private static void rebuildOne(WorldEngine current, Vec3 camera, int vanillaDistance) {
        long rebuildStart = System.nanoTime();
        try {
            int maxRenderableLevel = maxRenderableLevel(vanillaDistance);
            for (int metadataBuilds = 0; metadataBuilds < METADATA_REBUILD_BUDGET; metadataBuilds++) {
                Long key = pollPrioritized(camera, vanillaDistance);
                if (key == null) {
                    return;
                }
                if (WorldSectionKey.level(key) > maxRenderableLevel
                        || !LodCoverageSelector.outsideVanillaDistance(
                                key, camera.x, camera.z, vanillaDistance)) {
                    publishMetadataSection(current, key);
                    continue;
                }
                L13_COORDINATOR.rebuildL13(key, frame, VoxyModelCatalog.INSTANCE.generation(), 3,
                        current::acquireLoaded,
                        (section, neighbors) -> LodSectionMeshBuilder.build(section,
                                VoxyModelCatalog.INSTANCE, direction -> neighbors.get(
                                        direction.getStepX(), direction.getStepY(), direction.getStepZ())),
                        new L13QueueCallbacks(camera));
                return;
            }
        } finally {
            frameRebuildNs += System.nanoTime() - rebuildStart;
        }
    }

    private static void publishMetadataSection(WorldEngine current, long key) {
        L13_COORDINATOR.discard(key);
        WorldSection section = current.acquireLoaded(key);
        if (section == null) {
            remove(key);
            return;
        }
        try {
            removeBuffer(key);
            putReadySection(new LodCoverageSelector.Node(key, section.nonEmptyChildren(), false));
        } finally {
            section.release();
        }
    }

    private static final class L13QueueCallbacks implements L13MeshBuildCoordinator.RebuildCallbacks {
        private final Vec3 camera;

        private L13QueueCallbacks(Vec3 camera) {
            this.camera = camera;
        }

        @Override
        public void replace(WorldSection section, LodSectionMesh mesh) {
            if (this.camera == null) throw new IllegalStateException("L13 upload requires a camera");
            upload(section, mesh, this.camera);
        }

        @Override public boolean requeue(long key) { return queue(key); }
        @Override public void missing(long key) { remove(key); }

        @Override
        public void fallback(long key, long modelGeneration, int unsupportedModels) {
            l13UnsupportedOccurrences += unsupportedModels;
            LOGGER.warn("Voxy L13 compatibility fallback for section {} at model generation {}: unsupportedModels={}",
                    WorldSectionKey.describe(key), modelGeneration, unsupportedModels);
        }

        @Override
        public void buildFailure(long key, long modelGeneration, Throwable failure) {
            reportL13BuildFailure(key, modelGeneration, failure);
        }
    }

    private static void reportL13BuildFailure(long key, long modelGeneration, Throwable failure) {
        long now = System.nanoTime();
        if (lastL13FailureLogTime != 0
                && now - lastL13FailureLogTime < L13_FAILURE_LOG_INTERVAL_NANOS) {
            suppressedL13FailureLogs++;
            return;
        }
        long suppressed = suppressedL13FailureLogs;
        suppressedL13FailureLogs = 0;
        lastL13FailureLogTime = now;
        LOGGER.error("Voxy L13 mesh build/upload failed for section {} at model generation {}"
                        + " (suppressed {} similar failures); retaining the previous GPU section",
                WorldSectionKey.describe(key), modelGeneration, suppressed, failure);
    }

    private static Long pollPrioritized(Vec3 camera, int vanillaDistance) {
        List<Long> candidates = new ArrayList<>(REBUILD_PRIORITY_WINDOW);
        for (int index = 0; index < REBUILD_PRIORITY_WINDOW; index++) {
            Long key = REBUILD_QUEUE.poll();
            if (key == null) {
                break;
            }
            candidates.add(key);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Long selected = candidates.stream().min(Comparator.comparingDouble(key ->
                rebuildPriority(key, camera, vanillaDistance))).orElseThrow();
        for (Long candidate : candidates) {
            if (!candidate.equals(selected)) {
                REBUILD_QUEUE.add(candidate);
            }
        }
        QUEUED.remove(selected);
        return selected;
    }

    private static double rebuildPriority(long key, Vec3 camera, int vanillaDistance) {
        int level = WorldSectionKey.level(key);
        double span = WorldSection.SIDE_LENGTH * (1 << level);
        double centerX = WorldSectionKey.x(key) * span + span * 0.5;
        double centerZ = WorldSectionKey.z(key) * span + span * 0.5;
        double distance = Math.max(Math.abs(centerX - camera.x), Math.abs(centerZ - camera.z));
        int desiredLevel = desiredLevel(distance, vanillaDistance);
        double hierarchyPriority = (WorldEngine.MAX_LOD_LEVEL - level) * 1.0e13;
        return hierarchyPriority + distance
                + (level == desiredLevel ? 0.0 : 1.0e12 + Math.abs(level - desiredLevel) * 1.0e9);
    }

    private static EnumMap<Direction, WorldSection> acquireNeighbors(WorldEngine current, WorldSection section) {
        EnumMap<Direction, WorldSection> neighbors = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) {
            int x = section.x() + direction.getStepX();
            int y = section.y() + direction.getStepY();
            int z = section.z() + direction.getStepZ();
            if (!WorldSectionKey.canPack(section.level(), x, y, z)) {
                continue;
            }
            long key = WorldSectionKey.pack(section.level(), x, y, z);
            WorldSection neighbor = current.acquireLoaded(key);
            if (neighbor != null) {
                neighbors.put(direction, neighbor);
            }
        }
        return neighbors;
    }

    private static void upload(WorldSection section, LodSectionMesh mesh, Vec3 camera) {
        if (mesh.quads().isEmpty()) {
            replaceWithEmpty(section);
            return;
        }
        int scale = 1 << section.level();
        int span = WorldSection.SIDE_LENGTH * scale;
        int originX = section.x() * span;
        int originY = section.y() * span;
        int originZ = section.z() * span;
        UploadTransaction<GpuSection> transaction = new UploadTransaction<>(SECTIONS, section.key(), gpuBytes,
                GpuSection::byteSize, previous -> closeReplaced(previous, section.key()));
        try (transaction) {
            SectionBuffer opaqueBuffer = transaction.own(uploadRegional(mesh, originX, originY, originZ));
            if (opaqueBuffer == null && !nativeBuffersDisabled) {
                try (EmbeddiumIndexedBuffer.EncodedMesh encoded = EmbeddiumIndexedBuffer.encode(
                        mesh, originX, originY, originZ)) {
                    if (encoded.indexCount() != 0) {
                        opaqueBuffer = transaction.own(
                                new NativeSectionBuffer(EmbeddiumIndexedBuffer.upload(encoded)));
                    }
                } catch (RuntimeException | LinkageError failure) {
                    nativeBuffersDisabled = true;
                    LOGGER.error("Embeddium indexed Voxy buffers failed; using the vanilla fallback for this session",
                            failure);
                }
            }
            if (opaqueBuffer == null) {
                opaqueBuffer = transaction.own(uploadVanilla(mesh, originX, originY, originZ));
            }
            SectionBuffer translucentBuffer = transaction.own(
                    uploadTranslucent(mesh, originX, originY, originZ, camera));
            if (opaqueBuffer == null && translucentBuffer == null) {
                transaction.commit(null);
                gpuBytes = transaction.bytes();
                putReadySection(new LodCoverageSelector.Node(section.key(), section.nonEmptyChildren(), false));
                return;
            }
            long byteSize = (opaqueBuffer == null ? 0 : opaqueBuffer.byteSize())
                    + (translucentBuffer == null ? 0 : translucentBuffer.byteSize());
            GpuSection replacement = new GpuSection(opaqueBuffer, translucentBuffer, byteSize,
                    section.level(), originX, originY, originZ, span, camera.x, camera.y, camera.z);
            transaction.commit(replacement);
            uploads++;
            gpuBytes = transaction.bytes();
            putReadySection(new LodCoverageSelector.Node(section.key(), section.nonEmptyChildren(), true));
        }
    }

    private static void closeReplaced(GpuSection previous, long sectionKey) {
        Throwable closeFailure = previous.closeBuffers();
        if (closeFailure != null) reportCleanupFailure(
                "section " + WorldSectionKey.describe(sectionKey), closeFailure);
    }

    private static void closeResource(AutoCloseable resource, String description) {
        Throwable closeFailure = closeAllResources(resource);
        if (closeFailure != null) reportCleanupFailure(description, closeFailure);
    }

    static Throwable closeAllResources(AutoCloseable... resources) {
        Throwable failure = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) continue;
            try {
                resource.close();
            } catch (Exception | LinkageError closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        return failure;
    }

    private static void reportCleanupFailure(String description, Throwable failure) {
        long now = System.nanoTime();
        if (lastCleanupFailureLogTime == 0
                || now - lastCleanupFailureLogTime >= CLEANUP_FAILURE_LOG_INTERVAL_NANOS) {
            long suppressed = suppressedCleanupFailureLogs;
            suppressedCleanupFailureLogs = 0;
            lastCleanupFailureLogTime = now;
            LOGGER.warn("Failed to release Voxy {}{}", description,
                    suppressed == 0 ? "" : " (plus " + suppressed + " suppressed cleanup failures)", failure);
        } else {
            suppressedCleanupFailureLogs++;
        }
    }

    private static SectionBuffer uploadTranslucent(LodSectionMesh mesh, int originX, int originY, int originZ,
                                                   Vec3 camera) {
        if (translucentBuffersDisabled) return null;
        try (EmbeddiumIndexedBuffer.EncodedMesh encoded = EmbeddiumIndexedBuffer.encode(mesh,
                originX, originY, originZ, BakedBlockModel.MaterialPass.TRANSLUCENT, camera)) {
            if (encoded.indexCount() == 0) return null;
            translucentSortUploads++;
            return new NativeSectionBuffer(EmbeddiumIndexedBuffer.upload(encoded));
        } catch (RuntimeException | LinkageError failure) {
            translucentBuffersDisabled = true;
            LOGGER.error("Voxy translucent buffers failed; disabling the translucent stream for this session",
                    failure);
            throw failure;
        }
    }

    private static SectionBuffer uploadRegional(LodSectionMesh mesh, int originX, int originY, int originZ) {
        if (regionalBuffersDisabled) return null;
        EmbeddiumRegionalBuffer.RegionKey regionKey =
                EmbeddiumRegionalBuffer.regionKey(originX, originY, originZ);
        try (EmbeddiumIndexedBuffer.EncodedMesh encoded = EmbeddiumIndexedBuffer.encode(mesh,
                regionKey.originX(), regionKey.originY(), regionKey.originZ())) {
            if (encoded.indexCount() == 0) return null;
            if (regionalBuffers == null) regionalBuffers = new EmbeddiumRegionalBuffer();
            return new SharedSectionBuffer(regionalBuffers.upload(encoded, originX, originY, originZ));
        } catch (RuntimeException | LinkageError failure) {
            regionalBuffersDisabled = true;
            LOGGER.error("Voxy regional arenas failed; retaining per-section L3 buffers for this session", failure);
            return null;
        }
    }

    private static SectionBuffer uploadVanilla(LodSectionMesh mesh, int originX, int originY, int originZ) {
        BufferBuilder builder = new BufferBuilder(Math.max(256, mesh.quads().size() * 64));
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
        int emittedQuads = 0;
        for (LodSectionMesh.QuadInstance instance : mesh.quads()) {
            if (instance.quad().materialPass() == BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT) {
                emitQuad(builder, instance, originX, originY, originZ);
                emittedQuads++;
            }
        }
        BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
        if (rendered == null) {
            return null;
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        boolean uploaded = false;
        try {
            buffer.bind();
            buffer.upload(rendered);
            uploaded = true;
            long byteSize = (long) emittedQuads * 4L
                    * DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP.getVertexSize();
            return new VanillaSectionBuffer(buffer, byteSize);
        } finally {
            VertexBuffer.unbind();
            if (!uploaded) {
                buffer.close();
                try {
                    rendered.release();
                } catch (IllegalStateException alreadyReleased) {
                    // VertexBuffer.upload releases the rendered data when it reaches its normal completion path.
                }
            }
        }
    }

    private static void replaceWithEmpty(WorldSection section) {
        UploadTransaction<GpuSection> transaction = new UploadTransaction<>(SECTIONS, section.key(), gpuBytes,
                GpuSection::byteSize, previous -> closeReplaced(previous, section.key()));
        try (transaction) {
            transaction.commit(null);
            gpuBytes = transaction.bytes();
        }
        putReadySection(new LodCoverageSelector.Node(section.key(), section.nonEmptyChildren(), false));
    }

    private static void emitQuad(BufferBuilder builder, LodSectionMesh.QuadInstance instance,
                                 int sectionOriginX, int sectionOriginY, int sectionOriginZ) {
        BakedBlockModel.Quad quad = instance.quad();
        int[] vertices = quad.vertices();
        int stride = vertices.length / 4;
        if (stride < 6) {
            return;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            float x = Float.intBitsToFloat(vertices[base]) * instance.scale()
                    + instance.worldX() - sectionOriginX;
            float y = Float.intBitsToFloat(vertices[base + 1]) * instance.scale()
                    + instance.worldY() - sectionOriginY;
            float z = Float.intBitsToFloat(vertices[base + 2]) * instance.scale()
                    + instance.worldZ() - sectionOriginZ;
            int abgr = vertices[base + 3];
            int red = abgr & 0xFF;
            int green = (abgr >>> 8) & 0xFF;
            int blue = (abgr >>> 16) & 0xFF;
            int tint = instance.tintColor();
            int alpha = VertexColorUtil.multiplyAlpha(abgr, tint);
            red = red * ((tint >>> 16) & 0xFF) / 255;
            green = green * ((tint >>> 8) & 0xFF) / 255;
            blue = blue * (tint & 0xFF) / 255;
            float shade = faceShade(quad) * instance.ambientOcclusion(vertex) / 255.0F;
            red = Math.round(red * shade);
            green = Math.round(green * shade);
            blue = Math.round(blue * shade);
            float u = Float.intBitsToFloat(vertices[base + 4]);
            float v = Float.intBitsToFloat(vertices[base + 5]);
            int light = instance.packedLight();
            int blockLight = (light >>> 4) & 0xF;
            int skyLight = light & 0xF;
            builder.vertex(x, y, z).color(red, green, blue, alpha).uv(u, v)
                    .uv2(LightTexture.pack(blockLight, skyLight)).endVertex();
        }
    }

    private static float faceShade(BakedBlockModel.Quad quad) {
        if (!quad.shade() || quad.cullFace() == null) return 1.0F;
        return switch (quad.cullFace()) {
            case DOWN -> 0.5F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
            default -> 1.0F;
        };
    }

    private static void drawVisible(WorldEngine current, RenderLevelStageEvent event, Vec3 camera,
                                    int vanillaDistance, Matrix4f projection) {
        frameCoverage = selectCoverage(camera, vanillaDistance);
        frameCameraX = camera.x;
        frameCameraZ = camera.z;
        selectionTransitions += frameCoverage.transitions();
        loadMissingSections(current, frameCoverage.missingKeys());
        frameSelected = frameCoverage.selectedKeys().size();
        frameVisible = 0;
        frameDrawn = 0;
        frameTerrainDrawn = 0;
        frameRegionalBatches = 0;
        frameSsaoDraws = 0;
        frameTranslucentVisible = 0;
        frameTranslucentDrawn = 0;
        long traversalStart = System.nanoTime();
        HierarchicalOcclusionTraverser.Visibility visibility =
                extendedFrustumVisibility(event.getPoseStack().last().pose(), projection, camera);
        HierarchicalOcclusionTraverser.Traversal traversal = OCCLUSION_TRAVERSER.traverse(
                frameCoverage.selectedKeys(), visibility);
        frameTraversalNs += System.nanoTime() - traversalStart;
        frameVisibleKeys = traversal.visibleKeys();
        frameHierarchyVisited = traversal.metrics().visited();
        frameHierarchyPruned = traversal.metrics().prunedBranches();
        long shadowStart = System.nanoTime();
        runGpuTraversalShadow(event, camera, frameReadySnapshot, projection);
        frameShadowNs += System.nanoTime() - shadowStart;
        VoxyTerrainProgram activeTerrainProgram = terrainProgram();
        ShaderInstance shader = GameRenderer.getPositionColorTexLightmapShader();
        if (shader == null) {
            return;
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorTexLightmapShader);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        PoseStack poses = event.getPoseStack();
        Map<EmbeddiumRegionalBuffer.Region, List<GpuSection>> regionalDraws = new java.util.LinkedHashMap<>();
        List<GpuSection> nativeDraws = new ArrayList<>();
        long drawStart = System.nanoTime();
        try {
            for (long key : frameVisibleKeys) {
                GpuSection section = SECTIONS.get(key);
                if (section != null && section.buffer() != null) {
                    frameVisible++;
                    section.markVisible(frame);
                    if (activeTerrainProgram != null && section.buffer() instanceof SharedSectionBuffer shared) {
                        regionalDraws.computeIfAbsent(shared.allocation().region(), ignored -> new ArrayList<>())
                                .add(section);
                        continue;
                    }
                    if (activeTerrainProgram != null && section.buffer() instanceof NativeSectionBuffer) {
                        nativeDraws.add(section);
                    }
                    poses.pushPose();
                    try {
                        poses.translate(section.x() - camera.x, section.y() - camera.y, section.z() - camera.z);
                        section.buffer().draw(poses.last().pose(), projection, activeTerrainProgram, shader);
                        if (activeTerrainProgram != null && section.buffer() instanceof NativeSectionBuffer) {
                            frameTerrainDrawn++;
                        }
                        frameDrawn++;
                    } finally {
                        poses.popPose();
                    }
                }
            }
            if (activeTerrainProgram != null && regionalBuffers != null) {
                for (Map.Entry<EmbeddiumRegionalBuffer.Region, List<GpuSection>> entry : regionalDraws.entrySet()) {
                    EmbeddiumRegionalBuffer.Region region = entry.getKey();
                    List<GpuSection> sections = entry.getValue();
                    EmbeddiumRegionalBuffer.RegionKey key =
                            ((SharedSectionBuffer) sections.get(0).buffer()).allocation().regionKey();
                    poses.pushPose();
                    try {
                        poses.translate(key.originX() - camera.x, key.originY() - camera.y,
                                key.originZ() - camera.z);
                        List<EmbeddiumRegionalBuffer.Allocation> allocations = sections.stream()
                                .map(section -> ((SharedSectionBuffer) section.buffer()).allocation()).toList();
                        regionalBuffers.draw(region, allocations, poses.last().pose(), projection, activeTerrainProgram);
                        frameDrawn += sections.size();
                        frameTerrainDrawn += sections.size();
                        frameRegionalBatches++;
                    } finally {
                        poses.popPose();
                    }
                }
            }
            if (activeTerrainProgram != null && VoxyClientConfig.SSAO_ENABLED.get()
                    && (!nativeDraws.isEmpty() || !regionalDraws.isEmpty())) {
                renderSsao(event, camera, nativeDraws, regionalDraws, projection);
            }
        } finally {
            frameDrawNs += System.nanoTime() - drawStart;
            VertexBuffer.unbind();
        }
    }

    private static void renderSsao(RenderLevelStageEvent event, Vec3 camera,
                                   List<GpuSection> nativeDraws,
                                   Map<EmbeddiumRegionalBuffer.Region, List<GpuSection>> regionalDraws,
                                   Matrix4f projection) {
        VoxySsaoPipeline pipeline = ssaoPipeline();
        if (pipeline == null) return;
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        try {
            VoxyGeometryProgram normalProgram = pipeline.begin(mainTarget);
            PoseStack poses = event.getPoseStack();
            for (GpuSection section : nativeDraws) {
                poses.pushPose();
                try {
                    poses.translate(section.x() - camera.x, section.y() - camera.y, section.z() - camera.z);
                    ((NativeSectionBuffer) section.buffer()).buffer().draw(
                            poses.last().pose(), projection, normalProgram);
                    frameSsaoDraws++;
                } finally {
                    poses.popPose();
                }
            }
            if (regionalBuffers != null) {
                for (Map.Entry<EmbeddiumRegionalBuffer.Region, List<GpuSection>> entry : regionalDraws.entrySet()) {
                    List<GpuSection> sections = entry.getValue();
                    EmbeddiumRegionalBuffer.RegionKey key =
                            ((SharedSectionBuffer) sections.get(0).buffer()).allocation().regionKey();
                    poses.pushPose();
                    try {
                        poses.translate(key.originX() - camera.x, key.originY() - camera.y,
                                key.originZ() - camera.z);
                        List<EmbeddiumRegionalBuffer.Allocation> allocations = sections.stream()
                                .map(section -> ((SharedSectionBuffer) section.buffer()).allocation()).toList();
                        regionalBuffers.draw(entry.getKey(), allocations, poses.last().pose(), projection, normalProgram);
                        frameSsaoDraws += sections.size();
                    } finally {
                        poses.popPose();
                    }
                }
            }
            pipeline.composite(mainTarget, projection);
            ssaoFrames++;
        } catch (RuntimeException | LinkageError failure) {
            mainTarget.bindWrite(true);
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            disableSsao(failure);
        }
    }

    private static VoxySsaoPipeline ssaoPipeline() {
        if (ssaoPipeline == null && !ssaoDisabled) {
            try {
                ssaoPipeline = VoxySsaoPipeline.create();
                LOGGER.info("Voxy SSAO pipeline active with copied scene depth and view-normal target");
            } catch (RuntimeException | LinkageError failure) {
                disableSsao(failure);
            }
        }
        return ssaoPipeline;
    }

    private static void disableSsao(Throwable failure) {
        ssaoDisabled = true;
        if (ssaoPipeline != null) {
            ssaoPipeline.close();
            ssaoPipeline = null;
        }
        LOGGER.error("Voxy SSAO pipeline failed; retaining the L8 forward terrain path for this session", failure);
    }

    private static VoxyTerrainProgram terrainProgram() {
        if (terrainProgram == null && !terrainProgramDisabled) {
            try {
                terrainProgram = VoxyTerrainProgram.create();
                LOGGER.info("Voxy dedicated terrain program active with atlas, lightmap, and fog bindings");
            } catch (RuntimeException | LinkageError failure) {
                terrainProgramDisabled = true;
                LOGGER.error("Voxy dedicated terrain program failed; retaining the L3 shader path for this session",
                        failure);
            }
        }
        return terrainProgram;
    }

    private static void loadMissingSections(WorldEngine current, Set<Long> missingKeys) {
        int remaining = MISSING_LOAD_BUDGET;
        for (long key : missingKeys) {
            if (remaining-- == 0) {
                break;
            }
            WorldSection section = current.acquireIfExists(key);
            if (section != null) {
                try {
                    queue(key);
                } finally {
                    section.release();
                }
            }
        }
    }

    private static int desiredLevel(double distance, int vanillaDistance) {
        int desiredLevel = 0;
        double threshold = vanillaDistance * 2.0;
        while (desiredLevel < WorldEngine.MAX_LOD_LEVEL && distance > threshold) {
            desiredLevel++;
            threshold *= 2.0;
        }
        return desiredLevel;
    }

    private static int maxRenderableLevel(int vanillaDistance) {
        return desiredLevel(configuredRenderDistanceBlocks(), vanillaDistance);
    }

    private static void evictIfNeeded(Vec3 camera) {
        long maxGpuBytes = maxGpuBytes();
        if (allocatedGpuBytes() <= maxGpuBytes && SECTIONS.size() <= MAX_CACHED_SECTIONS) {
            return;
        }
        List<Map.Entry<Long, GpuSection>> candidates = new ArrayList<>(SECTIONS.entrySet());
        candidates.sort(Comparator.<Map.Entry<Long, GpuSection>>comparingLong(entry -> entry.getValue().lastVisibleFrame())
                .thenComparingInt(entry -> entry.getValue().level())
                .thenComparing((left, right) -> Double.compare(
                        distanceSquared(right.getValue(), camera), distanceSquared(left.getValue(), camera))));
        for (Map.Entry<Long, GpuSection> candidate : candidates) {
            if (allocatedGpuBytes() <= maxGpuBytes && SECTIONS.size() <= MAX_CACHED_SECTIONS) {
                break;
            }
            if (frameCoverage.protectedKeys().contains(candidate.getKey())) {
                continue;
            }
            remove(candidate.getKey());
            evictions++;
        }
    }

    private static long maxGpuBytes() {
        long configured = VoxyClientConfig.MAX_GPU_MIB.get();
        return Math.max(16L, Long.getLong("voxy.maxGpuMiB", configured)) * 1024L * 1024L;
    }

    private static double configuredRenderDistanceBlocks() {
        return VoxyClientConfig.SECTION_RENDER_DISTANCE.get() * 32.0 * 16.0;
    }

    private static Matrix4f extendedProjection(Matrix4f projection) {
        Matrix4f extended = new Matrix4f(projection);
        float currentM22 = projection.m22();
        float currentM32 = projection.m32();
        float near = currentM32 / (currentM22 - 1.0F);
        if (!Float.isFinite(near) || near <= 0.0F) {
            near = 0.05F;
        }
        float far = (float) Math.max(configuredRenderDistanceBlocks(), 1024.0);
        if (far <= near + 1.0F) {
            return extended;
        }
        extended.m22(-(far + near) / (far - near));
        extended.m32(-(2.0F * far * near) / (far - near));
        return extended;
    }

    private static HierarchicalOcclusionTraverser.Visibility extendedFrustumVisibility(
            Matrix4f modelView, Matrix4f projection, Vec3 camera) {
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(projection).mul(modelView));
        return bounds -> frustum.testAab(
                (float) (bounds.minX() - camera.x), (float) (bounds.minY() - camera.y),
                (float) (bounds.minZ() - camera.z), (float) (bounds.maxX() - camera.x),
                (float) (bounds.maxY() - camera.y), (float) (bounds.maxZ() - camera.z));
    }

    private static void timeDiscover(Runnable action) {
        long start = System.nanoTime();
        try {
            action.run();
        } finally {
            frameDiscoverNs += System.nanoTime() - start;
        }
    }

    private static void resetFrameTiming() {
        frameDiscoverNs = 0;
        frameSelectionNs = 0;
        frameRebuildNs = 0;
        frameTraversalNs = 0;
        frameShadowNs = 0;
        frameDrawNs = 0;
    }

    private static void recordFrameTiming(long frameNs) {
        timingFrames++;
        timingFrameNs += frameNs;
        timingDiscoverNs += frameDiscoverNs;
        timingSelectionNs += frameSelectionNs;
        timingRebuildNs += frameRebuildNs;
        timingTraversalNs += frameTraversalNs;
        timingShadowNs += frameShadowNs;
        timingDrawNs += frameDrawNs;
    }

    private static void resetTimingTotals() {
        timingFrames = 0;
        timingFrameNs = 0;
        timingDiscoverNs = 0;
        timingSelectionNs = 0;
        timingRebuildNs = 0;
        timingTraversalNs = 0;
        timingShadowNs = 0;
        timingDrawNs = 0;
    }

    private static String averageMillis(long nanos, long frames) {
        if (frames <= 0) return "0.000";
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / (frames * 1_000_000.0));
    }

    private static long allocatedGpuBytes() {
        long bytes = regionalBuffers == null ? 0 : regionalBuffers.allocatedBytes();
        for (GpuSection section : SECTIONS.values()) {
            if (section.buffer() != null && !(section.buffer() instanceof SharedSectionBuffer)) {
                bytes += section.buffer().byteSize();
            }
            if (section.translucentBuffer() != null) bytes += section.translucentBuffer().byteSize();
        }
        return bytes;
    }

    private static double distanceSquared(GpuSection section, Vec3 camera) {
        double centerX = section.x() + section.span() * 0.5;
        double centerY = section.y() + section.span() * 0.5;
        double centerZ = section.z() + section.span() * 0.5;
        double dx = centerX - camera.x;
        double dy = centerY - camera.y;
        double dz = centerZ - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    static boolean withinShadowDistance(int x, int z, int span, double cameraX, double cameraZ,
                                        int renderDistanceChunks) {
        double dx = cameraX < x ? x - cameraX : cameraX > x + span ? cameraX - x - span : 0.0;
        double dz = cameraZ < z ? z - cameraZ : cameraZ > z + span ? cameraZ - z - span : 0.0;
        double distance = Math.max(16.0, renderDistanceChunks * 16.0);
        return dx * dx + dz * dz <= distance * distance;
    }

    private static void remove(long key) {
        L13_COORDINATOR.discard(key);
        removeBuffer(key);
        removeReadySection(key);
    }

    private static void removeBuffer(long key) {
        GpuSection previous = SECTIONS.remove(key);
        if (previous != null) {
            gpuBytes -= previous.byteSize();
            closeReplaced(previous, key);
        }
    }

    public static RenderMetrics lastMetrics() {
        return lastMetrics;
    }

    public static GpuTraversalShadowRuntime.Metrics gpuTraversalShadowMetrics() {
        return gpuTraversalShadowMetrics;
    }

    private static void runGpuTraversalShadow(RenderLevelStageEvent event, Vec3 camera,
                                             Map<Long, LodCoverageSelector.Node> readySnapshot,
                                             Matrix4f projection) {
        if (!VoxyClientConfig.GPU_SHADOW_VALIDATION.get()) {
            closeGpuTraversalShadow();
            gpuTraversalShadowAttempted = false;
            gpuTraversalShadowMetrics = GpuTraversalShadowRuntime.Metrics.unavailable("shadow validation disabled");
            return;
        }
        ensureBackendSelection();
        if (gpuTraversalShadow == null && !gpuTraversalShadowAttempted) {
            gpuTraversalShadowAttempted = true;
            if (backendSelection == null || !backendSelection.hardwareEligible()) {
                String reason = backendSelection == null ? "GPU capability policy unavailable"
                        : "GPU capability policy: " + backendSelection.primaryReason();
                gpuTraversalShadowMetrics = GpuTraversalShadowRuntime.Metrics.unavailable(reason);
                return;
            }
            gpuTraversalShadow = new GpuTraversalShadowRuntime(new LwjglGpuTraversalShadowDriver());
            gpuTraversalShadowMetrics = gpuTraversalShadow.metrics();
            if (!gpuTraversalShadowMetrics.available()) {
                LOGGER.warn("Voxy GPU traversal shadow unavailable: {}",
                        gpuTraversalShadowMetrics.disableReason());
            }
        }
        if (gpuTraversalShadow == null) return;
        var target = Minecraft.getInstance().getMainRenderTarget();
        Matrix4f mvp = new Matrix4f(projection).mul(event.getPoseStack().last().pose());
        float[] matrix = new float[16];
        mvp.get(matrix);
        GpuTraversalShadowRuntime.FrameInput input = new GpuTraversalShadowRuntime.FrameInput(
                frame, target.getDepthTextureId(), target.width, target.height, matrix,
                camera.x, camera.y, camera.z, (float) configuredRenderDistanceBlocks());
        GpuTraversalShadowRuntime.PhaseCounts phaseCounts = new GpuTraversalShadowRuntime.PhaseCounts(
                frameCoverage.roots(), frameCoverage.selectedKeys().size(),
                frameVisibleKeys.size(), -1, frameVisibleKeys.size());
        gpuTraversalShadowMetrics = gpuTraversalShadow.frame(input, readySnapshot, frameVisibleKeys, phaseCounts);
    }

    private static void closeGpuTraversalShadow() {
        if (gpuTraversalShadow == null) return;
        closeResource(gpuTraversalShadow, "GPU traversal shadow runtime");
        gpuTraversalShadow = null;
    }

    private static void reportMetrics(WorldEngine current) {
        long now = System.nanoTime();
        int[] cachedByLod = new int[WorldEngine.MAX_LOD_LEVEL + 1];
        int[] drawnByLod = new int[WorldEngine.MAX_LOD_LEVEL + 1];
        int nativeCached = 0;
        int vanillaCached = 0;
        int regionalCached = 0;
        int translucentCached = 0;
        for (GpuSection section : SECTIONS.values()) {
            cachedByLod[section.level()]++;
            if (section.buffer() instanceof NativeSectionBuffer) {
                nativeCached++;
            } else if (section.buffer() instanceof SharedSectionBuffer) {
                nativeCached++;
                regionalCached++;
            } else if (section.buffer() instanceof VanillaSectionBuffer) {
                vanillaCached++;
            }
            if (section.translucentBuffer() != null) translucentCached++;
            if (section.lastVisibleFrame() == frame) {
                drawnByLod[section.level()]++;
            }
        }
        lastMetrics = new RenderMetrics(current.loadedSectionCount(), REBUILD_QUEUE.size(), uploads,
                SECTIONS.size(), nativeCached, vanillaCached, regionalCached,
                regionalBuffers == null ? 0 : regionalBuffers.regionCount(), translucentCached,
                READY_SECTIONS.size(),
                frameSelected, frameVisible, frameDrawn, frameTerrainDrawn, frameRegionalBatches,
                frameSsaoDraws, ssaoFrames,
                frameOculusTerrainDrawn, frameOculusShadowDrawn,
                frameTranslucentVisible, frameTranslucentDrawn, translucentSortUploads,
                frameHierarchyVisited, frameHierarchyPruned,
                frameCoverage.fallbackParents(), frameCoverage.incompleteChildren(), frameCoverage.uncovered(),
                selectionTransitions, gpuBytes, allocatedGpuBytes(),
                regionalBuffers == null ? 0 : regionalBuffers.reclaimedRegions(),
                evictions, cachedByLod, drawnByLod);
        if (lastMetricTime == 0 || now - lastMetricTime >= METRIC_INTERVAL_NANOS) {
            lastMetricTime = now;
            L13MeshBuildCoordinator.Metrics l13 = L13_COORDINATOR.metrics();
            long timingWindowFrames = timingFrames;
            LOGGER.info("Voxy render metrics: camera=({}, {}), renderDistanceChunks={}, loaded={}, restored={}, new={}, corrupt={}, queued={}, uploads={}, cached={}, native={}, vanilla={}, regional={}, regions={}, translucentCached={}, ready={}, tintLut={}, selected={}, visible={}, drawn={}, terrainDrawn={}, regionBatches={}, ssaoDraws={}, ssaoFrames={}, oculusTerrainDrawn={}, oculusShadowDrawn={}, translucentVisible={}, translucentDrawn={}, translucentSorts={}, hierarchyVisited={}, hierarchyPruned={}, fallback={}, missingChildren={}, uncovered={}, transitions={}, coverageCacheHits={}, coverageCacheMisses={}, gpuMiB={}, allocatedMiB={}, arenaMiB={}, reclaimedRegions={}, evicted={}, cachedLod={}, drawnLod={}, l13Ready={}, l13Retry={}, l13BuildFailureOccurrences={}, l13Fallback={}, l13RetryTracked={}, l13UnsupportedOccurrences={}, frameMsAvg={}, discoverMsAvg={}, selectMsAvg={}, rebuildMsAvg={}, traverseMsAvg={}, shadowMsAvg={}, drawMsAvg={}, gpuShadowAvailable={}, gpuShadowDispatch={}, gpuShadowReadback={}, gpuShadowMatch={}, gpuShadowMismatch={}, gpuShadowRootInput={}, gpuShadowLodAfter={}, gpuShadowFrustumAfter={}, gpuShadowHiZAfter={}, gpuShadowCpuQueue={}, gpuShadowGpuQueue={}, gpuShadowCpuOnly={}, gpuShadowGpuOnly={}, gpuShadowCpuOnlyLod={}, gpuShadowGpuOnlyLod={}, gpuShadowSample={}, gpuShadowDisableReason={}",
                    String.format(java.util.Locale.ROOT, "%.1f", frameCameraX),
                    String.format(java.util.Locale.ROOT, "%.1f", frameCameraZ),
                    VoxyClientConfig.SECTION_RENDER_DISTANCE.get() * 32,
                    lastMetrics.loaded(), current.restoredSectionCount(), current.newSectionCount(),
                    current.corruptSectionCount(), lastMetrics.queued(), lastMetrics.uploaded(), lastMetrics.cached(),
                    lastMetrics.nativeCached(), lastMetrics.vanillaCached(), lastMetrics.regionalCached(),
                    lastMetrics.regions(), lastMetrics.translucentCached(), lastMetrics.ready(),
                    VoxyModelCatalog.INSTANCE.tintCacheSize(),
                    lastMetrics.selected(), lastMetrics.visible(), lastMetrics.drawn(), lastMetrics.terrainDrawn(),
                    lastMetrics.regionalBatches(),
                    lastMetrics.ssaoDraws(), lastMetrics.ssaoFrames(),
                    lastMetrics.oculusTerrainDrawn(), lastMetrics.oculusShadowDrawn(),
                    lastMetrics.translucentVisible(), lastMetrics.translucentDrawn(),
                    lastMetrics.translucentSortUploads(),
                    lastMetrics.hierarchyVisited(), lastMetrics.hierarchyPruned(),
                    lastMetrics.fallbackParents(), lastMetrics.incompleteChildren(), lastMetrics.uncovered(),
                    lastMetrics.transitions(), coverageCacheHits, coverageCacheMisses,
                    String.format(java.util.Locale.ROOT, "%.2f", gpuBytes / 1048576.0),
                    String.format(java.util.Locale.ROOT, "%.2f", lastMetrics.allocatedGpuBytes() / 1048576.0),
                    String.format(java.util.Locale.ROOT, "%.2f",
                            (regionalBuffers == null ? 0 : regionalBuffers.allocatedBytes()) / 1048576.0),
                    lastMetrics.reclaimedRegions(), lastMetrics.evicted(),
                    java.util.Arrays.toString(cachedByLod), java.util.Arrays.toString(drawnByLod),
                    l13.ready(), l13.retry(), l13.buildFailureOccurrences(), l13.fallback(),
                    l13.trackedRetries(), l13UnsupportedOccurrences,
                    averageMillis(timingFrameNs, timingWindowFrames),
                    averageMillis(timingDiscoverNs, timingWindowFrames),
                    averageMillis(timingSelectionNs, timingWindowFrames),
                    averageMillis(timingRebuildNs, timingWindowFrames),
                    averageMillis(timingTraversalNs, timingWindowFrames),
                    averageMillis(timingShadowNs, timingWindowFrames),
                    averageMillis(timingDrawNs, timingWindowFrames),
                    gpuTraversalShadowMetrics.available(), gpuTraversalShadowMetrics.dispatches(),
                    gpuTraversalShadowMetrics.readbacks(), gpuTraversalShadowMetrics.matches(),
                    gpuTraversalShadowMetrics.mismatches(),
                    gpuTraversalShadowMetrics.shadowRootInput(),
                    gpuTraversalShadowMetrics.shadowLodDescendAfter(),
                    gpuTraversalShadowMetrics.shadowFrustumAfter(),
                    gpuTraversalShadowMetrics.shadowHiZAfter(),
                    gpuTraversalShadowMetrics.shadowCpuRenderQueue(),
                    gpuTraversalShadowMetrics.shadowGpuRenderQueue(),
                    gpuTraversalShadowMetrics.lastCpuOnly(),
                    gpuTraversalShadowMetrics.lastGpuOnly(),
                    gpuTraversalShadowMetrics.lastCpuOnlyByLod(),
                    gpuTraversalShadowMetrics.lastGpuOnlyByLod(),
                    gpuTraversalShadowMetrics.lastDifferenceSample(),
                    gpuTraversalShadowMetrics.disableReason());
            resetTimingTotals();
        }
    }

    public record RenderMetrics(int loaded, int queued, long uploaded, int cached,
                                int nativeCached, int vanillaCached, int regionalCached, int regions,
                                int translucentCached, int ready,
                                int selected, int visible, int drawn, int terrainDrawn, int regionalBatches,
                                int ssaoDraws, long ssaoFrames,
                                 int oculusTerrainDrawn, int oculusShadowDrawn,
                                 int translucentVisible, int translucentDrawn, long translucentSortUploads,
                                 int hierarchyVisited, int hierarchyPruned,
                                 int fallbackParents,
                                int incompleteChildren, int uncovered, long transitions,
                                long gpuBytes, long allocatedGpuBytes, long reclaimedRegions,
                                long evicted, int[] cachedByLod, int[] drawnByLod) {
        private static final RenderMetrics EMPTY = new RenderMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0,
                new int[WorldEngine.MAX_LOD_LEVEL + 1], new int[WorldEngine.MAX_LOD_LEVEL + 1]);
        public RenderMetrics {
            cachedByLod = cachedByLod.clone();
            drawnByLod = drawnByLod.clone();
        }
        @Override public int[] cachedByLod() { return cachedByLod.clone(); }
        @Override public int[] drawnByLod() { return drawnByLod.clone(); }
    }

    private static final class GpuSection {
        private final SectionBuffer buffer;
        private final SectionBuffer translucentBuffer;
        private final long byteSize;
        private final int level;
        private final int x;
        private final int y;
        private final int z;
        private final int span;
        private final double sortCameraX;
        private final double sortCameraY;
        private final double sortCameraZ;
        private long lastVisibleFrame;

        private GpuSection(SectionBuffer buffer, SectionBuffer translucentBuffer, long byteSize,
                           int level, int x, int y, int z, int span,
                           double sortCameraX, double sortCameraY, double sortCameraZ) {
            this.buffer = buffer;
            this.translucentBuffer = translucentBuffer;
            this.byteSize = byteSize;
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.span = span;
            this.sortCameraX = sortCameraX;
            this.sortCameraY = sortCameraY;
            this.sortCameraZ = sortCameraZ;
        }

        SectionBuffer buffer() { return this.buffer; }
        SectionBuffer translucentBuffer() { return this.translucentBuffer; }
        long byteSize() { return this.byteSize; }
        int level() { return this.level; }
        int x() { return this.x; }
        int y() { return this.y; }
        int z() { return this.z; }
        int span() { return this.span; }
        long lastVisibleFrame() { return this.lastVisibleFrame; }
        void markVisible(long currentFrame) { this.lastVisibleFrame = currentFrame; }
        double sortDistanceSquared(Vec3 camera) {
            double dx = this.sortCameraX - camera.x;
            double dy = this.sortCameraY - camera.y;
            double dz = this.sortCameraZ - camera.z;
            return dx * dx + dy * dy + dz * dz;
        }
        Throwable closeBuffers() {
            return closeAllResources(this.translucentBuffer, this.buffer);
        }
    }

    static final class UploadTransaction<T> implements AutoCloseable {
        private final Map<Long, T> entries;
        private final long key;
        private final java.util.function.ToLongFunction<T> sizeOf;
        private final java.util.function.Consumer<T> closeReplaced;
        private final List<AutoCloseable> owned = new ArrayList<>(2);
        private long bytes;
        private boolean committed;

        UploadTransaction(Map<Long, T> entries, long key, long bytes,
                          java.util.function.ToLongFunction<T> sizeOf,
                          java.util.function.Consumer<T> closeReplaced) {
            this.entries = entries;
            this.key = key;
            this.bytes = bytes;
            this.sizeOf = sizeOf;
            this.closeReplaced = closeReplaced;
        }

        <R extends AutoCloseable> R own(R resource) {
            if (resource != null) this.owned.add(resource);
            return resource;
        }

        void commit(T replacement) {
            T previous = replacement == null
                    ? this.entries.remove(this.key) : this.entries.put(this.key, replacement);
            if (previous != null) this.bytes -= this.sizeOf.applyAsLong(previous);
            if (replacement != null) this.bytes += this.sizeOf.applyAsLong(replacement);
            this.committed = true;
            this.owned.clear();
            if (previous != null) this.closeReplaced.accept(previous);
        }

        long bytes() {
            return this.bytes;
        }

        @Override
        public void close() {
            if (this.committed) return;
            java.util.Collections.reverse(this.owned);
            Throwable closeFailure = closeAllResources(this.owned.toArray(AutoCloseable[]::new));
            if (closeFailure != null) reportCleanupFailure("uncommitted upload resources", closeFailure);
            this.owned.clear();
        }
    }

    interface SectionBuffer extends AutoCloseable {
        long byteSize();
        void draw(org.joml.Matrix4f modelView, org.joml.Matrix4f projection,
                  VoxyGeometryProgram terrainProgram, ShaderInstance fallbackShader);
        @Override void close();
    }

    private record NativeSectionBuffer(EmbeddiumIndexedBuffer buffer) implements SectionBuffer {
        @Override public long byteSize() { return this.buffer.byteSize(); }
        @Override public void draw(org.joml.Matrix4f modelView, org.joml.Matrix4f projection,
                                   VoxyGeometryProgram terrainProgram, ShaderInstance fallbackShader) {
            if (terrainProgram != null) {
                this.buffer.draw(modelView, projection, terrainProgram);
            } else {
                this.buffer.draw(modelView, projection, fallbackShader);
            }
        }
        @Override public void close() { this.buffer.close(); }
    }

    private record SharedSectionBuffer(EmbeddiumRegionalBuffer.Allocation allocation) implements SectionBuffer {
        @Override public long byteSize() { return this.allocation.byteSize(); }
        @Override public void draw(org.joml.Matrix4f modelView, org.joml.Matrix4f projection,
                                   VoxyGeometryProgram terrainProgram, ShaderInstance fallbackShader) {
            throw new UnsupportedOperationException("Regional sections are submitted as a batch");
        }
        @Override public void close() { this.allocation.close(); }
    }

    private record VanillaSectionBuffer(VertexBuffer buffer, long byteSize) implements SectionBuffer {
        @Override public void draw(org.joml.Matrix4f modelView, org.joml.Matrix4f projection,
                                   VoxyGeometryProgram terrainProgram, ShaderInstance shader) {
            this.buffer.bind();
            this.buffer.drawWithShader(modelView, projection, shader);
        }
        @Override public void close() { this.buffer.close(); }
    }
}
