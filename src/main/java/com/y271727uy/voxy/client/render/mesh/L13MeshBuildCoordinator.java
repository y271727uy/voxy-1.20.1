package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.client.core.rendering.building.result.MeshBuildResult;
import com.y271727uy.voxy.client.core.model.snapshot.ModelSnapshot;
import com.y271727uy.voxy.client.core.rendering.integration.LodMeshIntegrationBridge;
import com.y271727uy.voxy.client.core.rendering.integration.StableSectionSnapshot;
import com.y271727uy.voxy.common.world.WorldSection;
import com.y271727uy.voxy.common.world.WorldSectionKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Owns section references and translates L13 build states into the existing rebuild lifecycle. */
public final class L13MeshBuildCoordinator {
    static final long EXHAUSTED_RETRY_TTL_FRAMES = 600;
    private final int maxRetriesPerGeneration;
    private final ConcurrentHashMap<Long, RetryState> retries = new ConcurrentHashMap<>();
    private final AtomicLong readyBuilds = new AtomicLong();
    private final AtomicLong fallbackBuilds = new AtomicLong();
    private final AtomicLong retryBuilds = new AtomicLong();
    private final AtomicLong buildFailureOccurrences = new AtomicLong();

    public L13MeshBuildCoordinator(int maxRetriesPerGeneration) {
        if (maxRetriesPerGeneration < 0) throw new IllegalArgumentException("max retries must be non-negative");
        this.maxRetriesPerGeneration = maxRetriesPerGeneration;
    }

    public Outcome rebuild(long key, SectionSource sections, PrimaryBuilder primary,
                           FallbackBuilder fallback, RebuildCallbacks callbacks) {
        return rebuild(key, 0, 0, sections, primary, fallback, callbacks);
    }

    public Outcome rebuild(long key, long currentFrame, long catalogGeneration,
                           SectionSource sections, PrimaryBuilder primary,
                           FallbackBuilder fallback, RebuildCallbacks callbacks) {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(fallback, "fallback");
        Objects.requireNonNull(callbacks, "callbacks");
        Outcome gated = gateBuildFailure(key, currentFrame);
        if (gated != null) return gated;
        WorldSection center = sections.acquire(key);
        if (center == null) {
            this.retries.remove(key);
            callbacks.missing(key);
            return new Outcome(State.MISSING, 0, -1);
        }
        Map<Offset, WorldSection> neighbors = new HashMap<>();
        try {
            acquireNeighbors(sections, center, neighbors);
            NeighborAccess access = (x, y, z) -> neighbors.get(new Offset(x, y, z));
            try {
                MeshBuildResult<LodSectionMesh> result = primary.build(center, access);
                return switch (result.status()) {
                    case READY -> publishReady(key, center, result.payload(), callbacks,
                            result.modelGeneration());
                    case FALLBACK -> publishFallback(key, center, access, fallback, callbacks,
                            result.modelGeneration(), result.unsupportedModelIds().size());
                    case RETRY -> retry(key, result, currentFrame, catalogGeneration);
                };
            } catch (StaleFallbackSnapshot stale) {
                return retry(key, stale.generation, currentFrame, catalogGeneration,
                        RetryReason.SECTION_UNSTABLE);
            } catch (RuntimeException | LinkageError failure) {
                return buildFailure(key, currentFrame, catalogGeneration, callbacks, failure);
            }
        } finally {
            neighbors.values().forEach(WorldSection::release);
            center.release();
        }
    }

    public Outcome rebuildL13(long key, long currentFrame, long catalogGeneration,
                              int stableCaptureAttempts, SectionSource sections,
                              FallbackBuilder fallback, RebuildCallbacks callbacks) {
        StableSectionSnapshot[] stableSnapshot = {null};
        ModelSnapshot[] modelSnapshot = {null};
        return rebuild(key, currentFrame, catalogGeneration, sections, (center, neighbors) -> {
            StableSectionSnapshot.CaptureResult captured = LodMeshIntegrationBridge.captureSections(
                    center, neighbors::get, stableCaptureAttempts);
            if (captured.status() == StableSectionSnapshot.CaptureResult.Status.RETRY) {
                return new MeshBuildResult<>(MeshBuildResult.Status.RETRY, 0, catalogGeneration,
                        null, Set.of(), Set.of(), true);
            }
            StableSectionSnapshot stable = captured.snapshot();
            ModelSnapshot models = LodMeshIntegrationBridge.captureSnapshot(stable);
            stableSnapshot[0] = stable;
            modelSnapshot[0] = models;
            return LodMeshIntegrationBridge.build(stable, models);
        }, (center, neighbors) -> {
            StableSectionSnapshot stable = stableSnapshot[0];
            ModelSnapshot models = modelSnapshot[0];
            if (stable == null || models == null || !stable.isCurrent() || !models.isCurrent()) {
                throw new StaleFallbackSnapshot(models == null ? catalogGeneration : models.generation());
            }
            LodSectionMesh mesh = fallback.build(center, neighbors);
            if (!stable.isCurrent() || !models.isCurrent()) {
                throw new StaleFallbackSnapshot(models.generation());
            }
            return mesh;
        }, callbacks);
    }

    private Outcome publishReady(long key, WorldSection center, LodSectionMesh mesh,
                                 RebuildCallbacks callbacks, long generation) {
        callbacks.replace(center, mesh);
        this.retries.remove(key);
        this.readyBuilds.incrementAndGet();
        return new Outcome(State.READY, 0, generation);
    }

    private Outcome publishFallback(long key, WorldSection center, NeighborAccess neighbors,
                                    FallbackBuilder fallback, RebuildCallbacks callbacks,
                                    long generation, int unsupportedCount) {
        Map<WorldSection, Long> revisions = captureRevisions(center, neighbors);
        LodSectionMesh mesh = fallback.build(center, neighbors);
        if (revisions.entrySet().stream().anyMatch(entry ->
                entry.getKey().revision() != entry.getValue())) {
            throw new StaleFallbackSnapshot(generation);
        }
        callbacks.replace(center, mesh);
        this.retries.remove(key);
        try {
            callbacks.fallback(key, generation, unsupportedCount);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics cannot invalidate an already committed replacement.
        }
        this.fallbackBuilds.incrementAndGet();
        return new Outcome(State.FALLBACK, 0, generation);
    }

    private static Map<WorldSection, Long> captureRevisions(WorldSection center,
                                                             NeighborAccess neighbors) {
        Map<WorldSection, Long> revisions = new HashMap<>();
        revisions.put(center, center.revision());
        for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            if (x == 0 && y == 0 && z == 0) continue;
            WorldSection neighbor = neighbors.get(x, y, z);
            if (neighbor != null) revisions.put(neighbor, neighbor.revision());
        }
        return revisions;
    }

    private Outcome retry(long key, MeshBuildResult<LodSectionMesh> result,
                          long currentFrame, long catalogGeneration) {
        long generation = result.modelGeneration();
        RetryReason reason = !result.missingModelIds().isEmpty()
                ? RetryReason.MODEL_PENDING : result.staleSnapshot()
                ? RetryReason.SECTION_UNSTABLE : RetryReason.OTHER;
        return retry(key, generation, currentFrame, catalogGeneration, reason);
    }

    private Outcome retry(long key, long generation, long currentFrame, long catalogGeneration,
                          RetryReason reason) {
        RetryState next = this.retries.compute(key, (ignored, previous) -> {
            int attempts = previous == null || previous.generation() != generation
                    ? 1 : previous.attempts() + 1;
            long delay = reason == RetryReason.BUILD_FAILURE
                    && attempts > this.maxRetriesPerGeneration
                    ? EXHAUSTED_RETRY_TTL_FRAMES
                    : 1L << Math.min(3, Math.max(0, attempts - 1));
            return new RetryState(generation, attempts, currentFrame + delay,
                    catalogGeneration, reason, false, currentFrame);
        });
        this.retryBuilds.incrementAndGet();
        if (next.attempts() <= this.maxRetriesPerGeneration) {
            return new Outcome(State.RETRY_DEFERRED, next.attempts(), generation);
        }
        return new Outcome(State.RETRY_EXHAUSTED, next.attempts(), generation);
    }

    private Outcome buildFailure(long key, long currentFrame, long catalogGeneration,
                                 RebuildCallbacks callbacks, Throwable failure) {
        this.buildFailureOccurrences.incrementAndGet();
        Outcome outcome = retry(key, catalogGeneration, currentFrame, catalogGeneration,
                RetryReason.BUILD_FAILURE);
        try {
            callbacks.buildFailure(key, catalogGeneration, failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Failure reporting must not escape the render event either.
        }
        return outcome;
    }

    private Outcome gateBuildFailure(long key, long currentFrame) {
        RetryState state = this.retries.get(key);
        if (state == null || state.reason() != RetryReason.BUILD_FAILURE
                || currentFrame >= state.nextEligibleFrame()) {
            return null;
        }
        State outcome = state.attempts() <= this.maxRetriesPerGeneration
                ? State.RETRY_DEFERRED : State.RETRY_EXHAUSTED;
        return new Outcome(outcome, state.attempts(), state.generation());
    }

    public int enqueueEligible(long currentFrame, long catalogGeneration, RebuildCallbacks callbacks) {
        int[] queued = {0};
        this.retries.entrySet().removeIf(entry -> {
            RetryState state = entry.getValue();
            return state.reason() != RetryReason.BUILD_FAILURE
                    && state.attempts() > this.maxRetriesPerGeneration
                    && currentFrame - state.lastTouchedFrame() >= EXHAUSTED_RETRY_TTL_FRAMES;
        });
        this.retries.replaceAll((key, state) -> {
            boolean generationWake = state.reason() == RetryReason.MODEL_PENDING
                    && state.catalogGeneration() != catalogGeneration;
            boolean exhaustedBuildProbe = state.reason() == RetryReason.BUILD_FAILURE
                    && state.attempts() > this.maxRetriesPerGeneration
                    && currentFrame >= state.nextEligibleFrame();
            boolean withinBudget = state.attempts() <= this.maxRetriesPerGeneration
                    || generationWake || exhaustedBuildProbe;
            if (!state.scheduled() && withinBudget
                    && (generationWake || currentFrame >= state.nextEligibleFrame())) {
                if (callbacks.requeue(key)) {
                    queued[0]++;
                    return new RetryState(state.generation(), state.attempts(), state.nextEligibleFrame(),
                            catalogGeneration, state.reason(), true, currentFrame);
                }
            }
            return state;
        });
        return queued[0];
    }

    public void discard(long key) {
        this.retries.remove(key);
    }

    public int wakeBuildFailures() {
        int before = this.retries.size();
        this.retries.entrySet().removeIf(entry -> entry.getValue().reason() == RetryReason.BUILD_FAILURE);
        return before - this.retries.size();
    }

    public void clear() {
        this.retries.clear();
        this.readyBuilds.set(0);
        this.fallbackBuilds.set(0);
        this.retryBuilds.set(0);
        this.buildFailureOccurrences.set(0);
    }

    private static void acquireNeighbors(SectionSource sections, WorldSection center,
                                         Map<Offset, WorldSection> output) {
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    int sectionX = center.x() + x;
                    int sectionY = center.y() + y;
                    int sectionZ = center.z() + z;
                    if (!WorldSectionKey.canPack(center.level(), sectionX, sectionY, sectionZ)) continue;
                    WorldSection neighbor = sections.acquire(WorldSectionKey.pack(
                            center.level(), sectionX, sectionY, sectionZ));
                    if (neighbor != null) output.put(new Offset(x, y, z), neighbor);
                }
            }
        }
    }

    public Metrics metrics() {
        return new Metrics(this.readyBuilds.get(), this.fallbackBuilds.get(),
                this.retryBuilds.get(), this.buildFailureOccurrences.get(), this.retries.size());
    }

    public enum State { READY, RETRY_DEFERRED, RETRY_EXHAUSTED, FALLBACK, MISSING }

    public enum RetryReason { MODEL_PENDING, SECTION_UNSTABLE, BUILD_FAILURE, OTHER }

    public record Outcome(State state, int attempts, long modelGeneration) {
    }

    public record Metrics(long ready, long fallback, long retry, long buildFailureOccurrences,
                          int trackedRetries) {
    }

    private record Offset(int x, int y, int z) {
    }

    private record RetryState(long generation, int attempts, long nextEligibleFrame,
                              long catalogGeneration, RetryReason reason, boolean scheduled,
                              long lastTouchedFrame) {
    }

    private static final class StaleFallbackSnapshot extends RuntimeException {
        private final long generation;

        private StaleFallbackSnapshot(long generation) {
            super(null, null, false, false);
            this.generation = generation;
        }
    }

    @FunctionalInterface
    public interface SectionSource {
        WorldSection acquire(long key);
    }

    @FunctionalInterface
    public interface NeighborAccess {
        WorldSection get(int offsetX, int offsetY, int offsetZ);
    }

    @FunctionalInterface
    public interface PrimaryBuilder {
        MeshBuildResult<LodSectionMesh> build(WorldSection center, NeighborAccess neighbors);
    }

    @FunctionalInterface
    public interface FallbackBuilder {
        LodSectionMesh build(WorldSection center, NeighborAccess neighbors);
    }

    public interface RebuildCallbacks {
        void replace(WorldSection section, LodSectionMesh mesh);
        boolean requeue(long key);
        void missing(long key);
        void fallback(long key, long modelGeneration, int unsupportedModels);
        default void buildFailure(long key, long modelGeneration, Throwable failure) { }
    }
}
