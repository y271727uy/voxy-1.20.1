package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import com.y271727uy.voxy.client.core.rendering.hierachical.GpuTraversalShadowController;
import com.y271727uy.voxy.client.core.rendering.hierachical.GpuTraversalShadowValidationGate;
import com.y271727uy.voxy.client.core.rendering.hierachical.NodeManager;
import com.y271727uy.voxy.client.core.rendering.hierachical.NodeGpuTraversalSnapshot;
import com.y271727uy.voxy.client.core.rendering.hierachical.WorldHierarchyInput;
import com.y271727uy.voxy.common.world.WorldEngine;
import com.y271727uy.voxy.common.world.WorldSectionKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Render-thread owner of the compatibility-only GPU traversal shadow path.
 * GPU results are validation data only and can never become a draw list.
 */
public final class GpuTraversalShadowRuntime implements AutoCloseable {
    public static final int MAX_NODES = 4_096;

    public interface Driver extends AutoCloseable {
        void initialize(NodeGpuTraversalSnapshot snapshot);

        void uploadNodesAndRoots(AsyncNodeManager.SyncBatch batch);

        FrameResult runFrame(FrameInput frame);

        boolean isAvailable();

        String disableReason();
    }

    public record FrameInput(long frameId, int sourceDepthTexture, int viewportWidth,
                             int viewportHeight, float[] mvp, double cameraX, double cameraY,
                             double cameraZ, float renderDistanceBlocks) {
        public FrameInput {
            if (frameId < 0) throw new IllegalArgumentException("frameId must not be negative");
            if (sourceDepthTexture <= 0) throw new IllegalArgumentException("invalid source depth texture");
            if (viewportWidth <= 0 || viewportHeight <= 0) throw new IllegalArgumentException("invalid viewport");
            if (mvp == null || mvp.length != 16) throw new IllegalArgumentException("mvp must contain 16 floats");
            mvp = mvp.clone();
        }

        @Override public float[] mvp() { return this.mvp.clone(); }
    }

    public record FrameResult(boolean dispatched, Optional<GpuTraversalShadowController.GpuReadback> readback) {
        public FrameResult {
            readback = Objects.requireNonNull(readback, "readback");
        }

        public static FrameResult unavailable() { return new FrameResult(false, Optional.empty()); }
    }

    public record PhaseCounts(int rootInput, int lodDescendAfter, int frustumAfter,
                              int hiZAfter, int cpuRenderQueueFinal) {
        public PhaseCounts {
            if (rootInput < 0 || lodDescendAfter < 0 || frustumAfter < 0
                    || cpuRenderQueueFinal < 0) {
                throw new IllegalArgumentException("phase counts must not be negative");
            }
        }

        static PhaseCounts inferred(Collection<Long> selectedRoots, Collection<Long> cpuVisibleKeys) {
            int selected = selectedRoots.size();
            int visible = cpuVisibleKeys.size();
            return new PhaseCounts(selected, selected, visible, -1, visible);
        }
    }

    public record Metrics(boolean available, long dispatches, long readbacks, long matches,
                          long mismatches, String disableReason,
                          int shadowRootInput, int shadowLodDescendAfter,
                          int shadowFrustumAfter, int shadowHiZAfter,
                          int shadowCpuRenderQueue, int shadowGpuRenderQueue,
                          long lastCpuOnly, long lastGpuOnly,
                          String lastCpuOnlyByLod, String lastGpuOnlyByLod,
                          String lastDifferenceSample) {
        public Metrics {
            disableReason = disableReason == null ? "" : disableReason;
            lastCpuOnlyByLod = lastCpuOnlyByLod == null ? "" : lastCpuOnlyByLod;
            lastGpuOnlyByLod = lastGpuOnlyByLod == null ? "" : lastGpuOnlyByLod;
            lastDifferenceSample = lastDifferenceSample == null ? "" : lastDifferenceSample;
        }

        public static Metrics unavailable(String reason) {
            return new Metrics(false, 0, 0, 0, 0, reason,
                    0, 0, 0, -1, 0, 0, 0, 0, "", "", "");
        }
    }

    private final Thread renderThread = Thread.currentThread();
    private final Driver driver;
    private final SectionGeometryRegistry<GeometryToken> geometry = new SectionGeometryRegistry<>();
    private final Map<Long, Integer> geometryIds = new LinkedHashMap<>();
    private final Map<Integer, Long> geometryKeys = new LinkedHashMap<>();
    private final Map<Long, TopologyNode> topology = new LinkedHashMap<>();
    private final Map<Long, DiagnosticFrame> pendingDiagnostics = new LinkedHashMap<>();
    private final Map<Integer, Runnable> releaseRequests = new java.util.concurrent.ConcurrentHashMap<>();
    private final AsyncNodeManager nodes;
    private final WorldHierarchyInput hierarchyInput;
    private final GpuTraversalShadowController controller = new GpuTraversalShadowController(
            new GpuTraversalShadowController.Configuration(
                    new GpuTraversalShadowValidationGate.Configuration(120, 600), 8, 32));

    private long dispatches;
    private long readbacks;
    private boolean disabled;
    private boolean closed;
    private String disableReason = "";
    private DiagnosticSummary lastDiagnostic = DiagnosticSummary.EMPTY;

    public GpuTraversalShadowRuntime(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        // SyncBatch owns release ordering; the immediate worker callback must not touch GL lifetime.
        NodeManager manager = new NodeManager(MAX_NODES, geometryId -> { });
        this.nodes = new AsyncNodeManager(manager);
        this.hierarchyInput = WorldHierarchyInput.forAsync(this.nodes, WorldEngine.MAX_LOD_LEVEL, MAX_NODES);
        try {
            this.driver.initialize(manager.captureGpuTraversalSnapshot());
            if (!this.driver.isAvailable()) {
                disable(this.driver.disableReason(), null);
                return;
            }
            this.nodes.start();
        } catch (RuntimeException | LinkageError failure) {
            disable("initialization failed", failure);
        }
    }

    /** Runs one shadow frame. The authoritative CPU keys are copied before any async work. */
    public Metrics frame(FrameInput input, Collection<Long> selectedRoots,
                         Collection<Long> cpuVisibleKeys) {
        return frame(input, selectedRoots, cpuVisibleKeys,
                PhaseCounts.inferred(selectedRoots, cpuVisibleKeys));
    }

    /** Runs one shadow frame with caller-provided CPU phase counters for diagnostics. */
    public Metrics frame(FrameInput input, Collection<Long> selectedRoots,
                         Collection<Long> cpuVisibleKeys, PhaseCounts phaseCounts) {
        Map<Long, LodCoverageSelector.Node> flatTopology = new LinkedHashMap<>();
        for (long key : selectedRoots) flatTopology.put(key, new LodCoverageSelector.Node(key, 0, true));
        return frame(input, flatTopology, cpuVisibleKeys, phaseCounts);
    }

    /** Runs one shadow frame from an immutable READY hierarchy snapshot. */
    public Metrics frame(FrameInput input, Map<Long, LodCoverageSelector.Node> readyTopology,
                         Collection<Long> cpuVisibleKeys, PhaseCounts phaseCounts) {
        requireRenderThread();
        requireOpen();
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(readyTopology, "readyTopology");
        Objects.requireNonNull(cpuVisibleKeys, "cpuVisibleKeys");
        Objects.requireNonNull(phaseCounts, "phaseCounts");
        if (this.disabled) return metrics();
        try {
            synchronizeTopology(readyTopology, input.frameId());
            applySyncBatch(input.frameId());

            List<Integer> cpuOutput = new ArrayList<>();
            for (Long key : cpuVisibleKeys) {
                Integer geometryId = this.geometryIds.get(key);
                if (geometryId != null) cpuOutput.add(geometryId);
            }
            rememberDiagnosticFrame(input.frameId(), cpuOutput, cpuVisibleKeys, phaseCounts);
            this.controller.submitCpuFrame(input.frameId(), cpuOutput);
            FrameResult result = this.driver.runFrame(input);
            if (!result.dispatched()) {
                if (!this.driver.isAvailable()) disable(this.driver.disableReason(), null);
                return metrics();
            }
            this.dispatches++;
            result.readback().ifPresent(readback -> {
                this.readbacks++;
                if (readback.state() == GpuTraversalShadowController.ReadbackState.READY) {
                    updateDiagnostics(readback.frameId(), readback.output());
                }
                this.controller.acceptGpuReadback(readback);
            });
        } catch (RuntimeException | LinkageError failure) {
            disable("shadow frame failed", failure);
        }
        return metrics();
    }

    private void synchronizeTopology(Map<Long, LodCoverageSelector.Node> readyTopology, long frameId) {
        if (readyTopology.size() > MAX_NODES) {
            throw new IllegalStateException("GPU shadow node capacity exceeded");
        }
        for (Map.Entry<Long, LodCoverageSelector.Node> entry : readyTopology.entrySet()) {
            LodCoverageSelector.Node node = entry.getValue();
            if (node == null) throw new IllegalArgumentException("readyTopology contains null node");
            if (entry.getKey() != node.key()) {
                throw new IllegalArgumentException("readyTopology key/node mismatch");
            }
        }

        for (Long key : new ArrayList<>(this.topology.keySet())) {
            if (readyTopology.containsKey(key)) continue;
            Integer geometryId = this.geometryIds.remove(key);
            if (geometryId != null) this.geometryKeys.remove(geometryId);
            this.hierarchyInput.removeSection(key);
            this.topology.remove(key);
        }

        for (Map.Entry<Long, LodCoverageSelector.Node> entry : readyTopology.entrySet()) {
            long key = entry.getKey();
            LodCoverageSelector.Node node = entry.getValue();
            int geometryId = geometryIdFor(node, frameId);
            TopologyNode next = new TopologyNode(node.childMask(), geometryId);
            TopologyNode previous = this.topology.put(key, next);
            if (previous == null || previous.childMask != next.childMask
                    || previous.geometryId != next.geometryId) {
                this.hierarchyInput.updateSection(key, (byte) next.childMask, next.geometryId);
            }
        }
    }

    /** Sync ordering is part of the resource lifetime contract. */
    private void applySyncBatch(long frameId) {
        AsyncNodeManager.SyncBatch batch = this.nodes.pollSyncBatch();
        if (batch != null) {
            this.driver.uploadNodesAndRoots(batch);
            for (int geometryId : batch.releasedGeometryIds()) {
                Runnable release = this.releaseRequests.remove(geometryId);
                if (release != null) release.run();
                this.geometryKeys.remove(geometryId);
            }
        }
        this.geometry.drain(frameId);
        Throwable workerFailure = this.nodes.failure();
        if (workerFailure != null) throw new IllegalStateException("async node manager failed", workerFailure);
    }

    public Metrics metrics() {
        GpuTraversalShadowValidationGate.Snapshot validation = this.controller.snapshot().validation();
        DiagnosticSummary diagnostic = this.lastDiagnostic;
        return new Metrics(!this.disabled && this.driver.isAvailable(), this.dispatches, this.readbacks,
                validation.matchingFrames(), validation.mismatchingFrames(), this.disableReason,
                diagnostic.phase.rootInput(), diagnostic.phase.lodDescendAfter(),
                diagnostic.phase.frustumAfter(), diagnostic.phase.hiZAfter(),
                diagnostic.phase.cpuRenderQueueFinal(), diagnostic.gpuRenderQueueFinal,
                diagnostic.cpuOnly, diagnostic.gpuOnly, diagnostic.cpuOnlyByLod,
                diagnostic.gpuOnlyByLod, diagnostic.sample);
    }

    private void disable(String reason, Throwable failure) {
        this.disabled = true;
        String detail = reason == null || reason.isBlank() ? "unavailable" : reason;
        if (failure != null) detail += ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        this.disableReason = detail;
    }

    @Override
    public void close() {
        requireRenderThread();
        if (this.closed) return;
        this.closed = true;
        Throwable failure = null;
        try { this.nodes.close(); } catch (Throwable closeFailure) { failure = closeFailure; }
        try { this.driver.close(); } catch (Throwable closeFailure) { failure = append(failure, closeFailure); }
        try { this.geometry.close(); } catch (Throwable closeFailure) { failure = append(failure, closeFailure); }
        this.geometryIds.clear();
        this.geometryKeys.clear();
        this.topology.clear();
        this.pendingDiagnostics.clear();
        this.releaseRequests.clear();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    private void requireRenderThread() {
        if (Thread.currentThread() != this.renderThread) {
            throw new IllegalStateException("GPU shadow lifecycle must run on its render thread");
        }
    }

    private void requireOpen() {
        if (this.closed) throw new IllegalStateException("GPU shadow runtime is closed");
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private void rememberDiagnosticFrame(long frameId, Collection<Integer> cpuOutput,
                                         Collection<Long> cpuVisibleKeys,
                                         PhaseCounts phaseCounts) {
        this.pendingDiagnostics.put(frameId, new DiagnosticFrame(
                new HashSet<>(cpuOutput), new LinkedHashSet<>(cpuVisibleKeys), phaseCounts));
        while (this.pendingDiagnostics.size() > 64) {
            Long oldest = this.pendingDiagnostics.keySet().iterator().next();
            this.pendingDiagnostics.remove(oldest);
        }
    }

    private void updateDiagnostics(long frameId, Collection<Integer> gpuOutput) {
        DiagnosticFrame frame = this.pendingDiagnostics.remove(frameId);
        if (frame == null) return;
        Set<Integer> gpu = new HashSet<>(gpuOutput);
        List<Long> cpuOnlyKeys = new ArrayList<>();
        for (int geometryId : frame.cpuOutput) {
            if (!gpu.contains(geometryId)) cpuOnlyKeys.add(this.geometryKeys.get(geometryId));
        }
        List<Long> gpuOnlyKeys = new ArrayList<>();
        for (int geometryId : gpu) {
            if (!frame.cpuOutput.contains(geometryId)) gpuOnlyKeys.add(this.geometryKeys.get(geometryId));
        }
        this.lastDiagnostic = new DiagnosticSummary(frame.phase, gpu.size(),
                cpuOnlyKeys.size(), gpuOnlyKeys.size(),
                lodSummary(cpuOnlyKeys), lodSummary(gpuOnlyKeys),
                sample(cpuOnlyKeys, gpuOnlyKeys));
    }

    private static String lodSummary(Collection<Long> keys) {
        int[] counts = new int[com.y271727uy.voxy.common.world.WorldSectionKey.MAX_LOD_LEVEL + 1];
        int unknown = 0;
        for (Long key : keys) {
            if (key == null) {
                unknown++;
            } else {
                counts[com.y271727uy.voxy.common.world.WorldSectionKey.level(key)]++;
            }
        }
        if (unknown == 0) return Arrays.toString(counts);
        return Arrays.toString(counts) + "+unknown=" + unknown;
    }

    private static String sample(List<Long> cpuOnly, List<Long> gpuOnly) {
        int limit = 6;
        return "cpuOnly=" + describe(cpuOnly, limit) + ";gpuOnly=" + describe(gpuOnly, limit);
    }

    private static String describe(List<Long> keys, int limit) {
        List<String> values = new ArrayList<>();
        int count = Math.min(limit, keys.size());
        for (int index = 0; index < count; index++) {
            Long key = keys.get(index);
            values.add(key == null ? "unknown" : com.y271727uy.voxy.common.world.WorldSectionKey.describe(key));
        }
        if (keys.size() > limit) values.add("...+" + (keys.size() - limit));
        return values.toString();
    }

    private int geometryIdFor(LodCoverageSelector.Node node, long frameId) {
        if (!node.drawable()) {
            Integer previous = this.geometryIds.remove(node.key());
            if (previous != null) this.geometryKeys.remove(previous);
            return NodeManager.EMPTY_GEOMETRY_ID;
        }
        Integer existing = this.geometryIds.get(node.key());
        if (existing != null) return existing;
        int geometryId = this.geometry.register(node.key(), new GeometryToken(), frameId);
        this.geometryIds.put(node.key(), geometryId);
        this.geometryKeys.put(geometryId, node.key());
        this.releaseRequests.put(geometryId, this.geometry.releaseCallback(node.key(), geometryId));
        return geometryId;
    }

    private record DiagnosticFrame(Set<Integer> cpuOutput, Set<Long> cpuVisibleKeys,
                                   PhaseCounts phase) {
    }

    private record DiagnosticSummary(PhaseCounts phase, int gpuRenderQueueFinal,
                                     long cpuOnly, long gpuOnly,
                                     String cpuOnlyByLod, String gpuOnlyByLod,
                                     String sample) {
        private static final DiagnosticSummary EMPTY = new DiagnosticSummary(
                new PhaseCounts(0, 0, 0, -1, 0), 0, 0, 0, "", "", "");
    }

    private record TopologyNode(int childMask, int geometryId) {
        private TopologyNode {
            childMask &= 0xFF;
        }
    }

    private static final class GeometryToken implements AutoCloseable {
        @Override public void close() { }
    }
}
