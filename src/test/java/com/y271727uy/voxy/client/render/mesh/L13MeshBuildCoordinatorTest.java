package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.client.core.rendering.building.result.MeshBuildResult;
import com.y271727uy.voxy.common.world.WorldSection;
import com.y271727uy.voxy.common.world.WorldSectionKey;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class L13MeshBuildCoordinatorTest {
    @Test
    public void readyBuildAcquiresAndReleasesAllTwentySixNeighbors() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(2);

        L13MeshBuildCoordinator.Outcome outcome = coordinator.rebuild(fixture.center.key(), fixture::acquire,
                (center, neighbors) -> ready(center, 7), (center, neighbors) -> mesh(center, 7), fixture);

        assertEquals(L13MeshBuildCoordinator.State.READY, outcome.state());
        assertEquals(1, fixture.replacements);
        assertEquals(27, fixture.acquired.size());
        fixture.sections.values().forEach(section -> assertEquals(0, section.refCount()));
    }

    @Test
    public void retryKeepsExistingMeshAndIsBoundedPerGeneration() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(1);

        L13MeshBuildCoordinator.Outcome first = coordinator.rebuild(fixture.center.key(), 0, 0, fixture::acquire,
                (center, neighbors) -> retry(3), (center, neighbors) -> mesh(center, 3), fixture);
        assertEquals(0, coordinator.enqueueEligible(0, 0, fixture));
        assertEquals(1, coordinator.enqueueEligible(1, 0, fixture));
        assertEquals(0, coordinator.enqueueEligible(1, 0, fixture));
        L13MeshBuildCoordinator.Outcome exhausted = coordinator.rebuild(fixture.center.key(), 1, 0, fixture::acquire,
                (center, neighbors) -> retry(3), (center, neighbors) -> mesh(center, 3), fixture);
        assertEquals(1, coordinator.enqueueEligible(1, 1, fixture));
        L13MeshBuildCoordinator.Outcome newGeneration = coordinator.rebuild(fixture.center.key(), 2, 1, fixture::acquire,
                (center, neighbors) -> retry(4), (center, neighbors) -> mesh(center, 4), fixture);

        assertEquals(L13MeshBuildCoordinator.State.RETRY_DEFERRED, first.state());
        assertEquals(L13MeshBuildCoordinator.State.RETRY_EXHAUSTED, exhausted.state());
        assertEquals(L13MeshBuildCoordinator.State.RETRY_DEFERRED, newGeneration.state());
        assertEquals(2, fixture.requeues);
        assertEquals(0, fixture.replacements);
    }

    @Test
    public void unstableRetryUsesOneTwoFourEightFrameBackoffWithoutQueueStorm() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(4);
        MeshBuildResult<LodSectionMesh> unstable = new MeshBuildResult<>(MeshBuildResult.Status.RETRY,
                1, 5, null, Set.of(), Set.of(), true);

        coordinator.rebuild(fixture.center.key(), 10, 2, fixture::acquire,
                (center, neighbors) -> unstable, (center, neighbors) -> mesh(center, 5), fixture);
        assertEquals(0, coordinator.enqueueEligible(10, 2, fixture));
        assertEquals(1, coordinator.enqueueEligible(11, 2, fixture));
        assertEquals(0, coordinator.enqueueEligible(11, 2, fixture));
        coordinator.rebuild(fixture.center.key(), 11, 2, fixture::acquire,
                (center, neighbors) -> unstable, (center, neighbors) -> mesh(center, 5), fixture);
        assertEquals(0, coordinator.enqueueEligible(12, 2, fixture));
        assertEquals(1, coordinator.enqueueEligible(13, 2, fixture));
    }

    @Test
    public void rejectedQueueInsertionDoesNotLosePendingRetry() {
        Fixture fixture = new Fixture();
        fixture.rejectRequeue = true;
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(0);
        coordinator.rebuild(fixture.center.key(), 0, 4, fixture::acquire,
                (center, neighbors) -> retry(8), (center, neighbors) -> mesh(center, 8), fixture);

        assertEquals(0, coordinator.enqueueEligible(1, 5, fixture));
        fixture.rejectRequeue = false;
        assertEquals(1, coordinator.enqueueEligible(1, 5, fixture));
        assertEquals(1, fixture.requeues);
    }

    @Test
    public void l13ConveniencePathCapturesStableSectionsOnceBeforeRelease() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(1);

        L13MeshBuildCoordinator.Outcome outcome = coordinator.rebuildL13(fixture.center.key(),
                0, 0, 3, fixture::acquire, (center, neighbors) -> mesh(center, 0), fixture);

        assertEquals(L13MeshBuildCoordinator.State.READY, outcome.state());
        fixture.sections.values().forEach(section -> assertEquals(0, section.refCount()));
    }

    @Test
    public void fallbackUsesCompatibilityBuilderAndRecordsDiagnostic() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(1);
        int[] fallbackCalls = {0};

        L13MeshBuildCoordinator.Outcome outcome = coordinator.rebuild(fixture.center.key(), fixture::acquire,
                (center, neighbors) -> new MeshBuildResult<>(MeshBuildResult.Status.FALLBACK,
                        1, 9, null, Set.of(), Set.of(12, 13), false),
                (center, neighbors) -> { fallbackCalls[0]++; return mesh(center, 9); }, fixture);

        assertEquals(L13MeshBuildCoordinator.State.FALLBACK, outcome.state());
        assertEquals(1, fallbackCalls[0]);
        assertEquals(1, fixture.replacements);
        assertEquals(1, fixture.fallbacks);
        assertEquals(2, fixture.lastUnsupported);
    }

    @Test
    public void primaryFailureIsRetriedAndStillReleasesEverySection() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(1);

        L13MeshBuildCoordinator.Outcome outcome = coordinator.rebuild(fixture.center.key(), 4, 12,
                fixture::acquire,
                (center, neighbors) -> { throw new IllegalStateException("failed"); },
                (center, neighbors) -> mesh(center, 1), fixture);

        assertEquals(L13MeshBuildCoordinator.State.RETRY_DEFERRED, outcome.state());
        assertEquals(1, fixture.failures);
        assertEquals(0, fixture.replacements);
        assertEquals(1, coordinator.enqueueEligible(5, 12, fixture));
        fixture.sections.values().forEach(section -> assertEquals(0, section.refCount()));
    }

    @Test
    public void fallbackAndReplaceFailuresDoNotEscapeAndUseBoundedBackoff() {
        Fixture fallbackFixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(1);
        MeshBuildResult<LodSectionMesh> fallbackResult = new MeshBuildResult<>(
                MeshBuildResult.Status.FALLBACK, 1, 9, null, Set.of(), Set.of(4), false);

        L13MeshBuildCoordinator.Outcome fallbackFailure = coordinator.rebuild(
                fallbackFixture.center.key(), 10, 9, fallbackFixture::acquire,
                (center, neighbors) -> fallbackResult,
                (center, neighbors) -> { throw new LinkageError("legacy failed"); }, fallbackFixture);
        assertEquals(L13MeshBuildCoordinator.State.RETRY_DEFERRED, fallbackFailure.state());
        assertEquals(1, fallbackFixture.failures);
        assertEquals(0, fallbackFixture.replacements);

        Fixture uploadFixture = new Fixture();
        uploadFixture.rejectReplace = true;
        L13MeshBuildCoordinator.Outcome uploadFailure = coordinator.rebuild(
                uploadFixture.center.key(), 11, 9, uploadFixture::acquire,
                (center, neighbors) -> ready(center, 9),
                (center, neighbors) -> mesh(center, 9), uploadFixture);
        assertEquals(L13MeshBuildCoordinator.State.RETRY_EXHAUSTED, uploadFailure.state());
        assertEquals(1, uploadFixture.failures);
        assertEquals(0, uploadFixture.replacements);
        assertEquals(0, coordinator.enqueueEligible(19, 9, uploadFixture));
    }

    @Test
    public void dirtyQueueCannotBypassBuildFailureBackoff() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(2);
        int[] builds = {0};
        L13MeshBuildCoordinator.PrimaryBuilder failing = (center, neighbors) -> {
            builds[0]++;
            throw new IllegalStateException("failed");
        };

        assertEquals(L13MeshBuildCoordinator.State.RETRY_DEFERRED,
                coordinator.rebuild(fixture.center.key(), 20, 4, fixture::acquire,
                        failing, (center, neighbors) -> mesh(center, 4), fixture).state());
        assertEquals(L13MeshBuildCoordinator.State.RETRY_DEFERRED,
                coordinator.rebuild(fixture.center.key(), 20, 4, fixture::acquire,
                        failing, (center, neighbors) -> mesh(center, 4), fixture).state());
        assertEquals(1, builds[0]);
        assertEquals(1, coordinator.metrics().buildFailureOccurrences());

        coordinator.rebuild(fixture.center.key(), 21, 4, fixture::acquire,
                failing, (center, neighbors) -> mesh(center, 4), fixture);
        assertEquals(2, builds[0]);
    }

    @Test
    public void exhaustedBuildFailureAllowsOnlyOneProbePerTtl() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(0);
        int[] builds = {0};
        L13MeshBuildCoordinator.PrimaryBuilder failing = (center, neighbors) -> {
            builds[0]++;
            throw new IllegalStateException("failed");
        };

        assertEquals(L13MeshBuildCoordinator.State.RETRY_EXHAUSTED,
                coordinator.rebuild(fixture.center.key(), 30, 6, fixture::acquire,
                        failing, (center, neighbors) -> mesh(center, 6), fixture).state());
        assertEquals(0, coordinator.enqueueEligible(
                30 + L13MeshBuildCoordinator.EXHAUSTED_RETRY_TTL_FRAMES - 1, 6, fixture));
        assertEquals(1, coordinator.enqueueEligible(
                30 + L13MeshBuildCoordinator.EXHAUSTED_RETRY_TTL_FRAMES, 6, fixture));
        assertEquals(0, coordinator.enqueueEligible(
                30 + L13MeshBuildCoordinator.EXHAUSTED_RETRY_TTL_FRAMES, 6, fixture));

        long probeFrame = 30 + L13MeshBuildCoordinator.EXHAUSTED_RETRY_TTL_FRAMES;
        coordinator.rebuild(fixture.center.key(), probeFrame, 6, fixture::acquire,
                failing, (center, neighbors) -> mesh(center, 6), fixture);
        coordinator.rebuild(fixture.center.key(), probeFrame, 6, fixture::acquire,
                failing, (center, neighbors) -> mesh(center, 6), fixture);
        assertEquals(2, builds[0]);
        assertEquals(1, coordinator.metrics().trackedRetries());
    }

    @Test
    public void explicitWakeAllowsBuildFailureToRetryWithoutGenerationChange() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(0);
        coordinator.rebuild(fixture.center.key(), 40, 8, fixture::acquire,
                (center, neighbors) -> { throw new IllegalStateException("failed"); },
                (center, neighbors) -> mesh(center, 8), fixture);

        assertEquals(1, coordinator.wakeBuildFailures());
        assertEquals(0, coordinator.metrics().trackedRetries());
        L13MeshBuildCoordinator.Outcome recovered = coordinator.rebuild(
                fixture.center.key(), 41, 8, fixture::acquire,
                (center, neighbors) -> ready(center, 8),
                (center, neighbors) -> mesh(center, 8), fixture);
        assertEquals(L13MeshBuildCoordinator.State.READY, recovered.state());
        assertEquals(1, fixture.replacements);
    }

    @Test
    public void discardAndExhaustedTtlRemoveTrackedRetries() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(0);
        coordinator.rebuild(fixture.center.key(), 20, 2, fixture::acquire,
                (center, neighbors) -> retry(2), (center, neighbors) -> mesh(center, 2), fixture);
        assertEquals(1, coordinator.metrics().trackedRetries());
        coordinator.discard(fixture.center.key());
        assertEquals(0, coordinator.metrics().trackedRetries());

        coordinator.rebuild(fixture.center.key(), 30, 2, fixture::acquire,
                (center, neighbors) -> retry(2), (center, neighbors) -> mesh(center, 2), fixture);
        coordinator.enqueueEligible(30 + L13MeshBuildCoordinator.EXHAUSTED_RETRY_TTL_FRAMES, 2, fixture);
        assertEquals(0, coordinator.metrics().trackedRetries());
    }

    @Test
    public void fallbackSectionChangePreventsReplacement() {
        Fixture fixture = new Fixture();
        L13MeshBuildCoordinator coordinator = new L13MeshBuildCoordinator(2);

        L13MeshBuildCoordinator.Outcome outcome = coordinator.rebuild(fixture.center.key(), 1, 7,
                fixture::acquire,
                (center, neighbors) -> new MeshBuildResult<>(MeshBuildResult.Status.FALLBACK,
                        1, 7, null, Set.of(), Set.of(5), false),
                (center, neighbors) -> {
                    center.clear();
                    return mesh(center, 7);
                }, fixture);

        assertEquals(L13MeshBuildCoordinator.State.RETRY_DEFERRED, outcome.state());
        assertEquals(0, fixture.replacements);
        assertEquals(0, fixture.fallbacks);
    }

    private static MeshBuildResult<LodSectionMesh> ready(WorldSection section, long generation) {
        return new MeshBuildResult<>(MeshBuildResult.Status.READY, 1, generation,
                mesh(section, generation), Set.of(), Set.of(), false);
    }

    private static MeshBuildResult<LodSectionMesh> retry(long generation) {
        return new MeshBuildResult<>(MeshBuildResult.Status.RETRY, 1, generation,
                null, Set.of(3), Set.of(), false);
    }

    private static LodSectionMesh mesh(WorldSection section, long generation) {
        return new LodSectionMesh(section.key(), generation, List.of());
    }

    private static final class Fixture implements L13MeshBuildCoordinator.RebuildCallbacks {
        final WorldSection center = new WorldSection(0, 0, 0, 0);
        final Map<Long, WorldSection> sections = new HashMap<>();
        final List<Long> acquired = new ArrayList<>();
        int replacements;
        int requeues;
        int fallbacks;
        int failures;
        int lastUnsupported;
        boolean rejectRequeue;
        boolean rejectReplace;

        Fixture() {
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                WorldSection section = x == 0 && y == 0 && z == 0
                        ? this.center : new WorldSection(0, x, y, z);
                this.sections.put(section.key(), section);
            }
        }

        WorldSection acquire(long key) {
            WorldSection section = this.sections.get(key);
            if (section != null && section.tryAcquire()) {
                this.acquired.add(key);
                return section;
            }
            return null;
        }

        @Override public void replace(WorldSection section, LodSectionMesh mesh) {
            if (this.rejectReplace) throw new IllegalStateException("upload failed");
            this.replacements++;
        }
        @Override public boolean requeue(long key) {
            if (this.rejectRequeue) return false;
            this.requeues++;
            return true;
        }
        @Override public void missing(long key) { }
        @Override public void fallback(long key, long modelGeneration, int unsupportedModels) {
            this.fallbacks++;
            this.lastUnsupported = unsupportedModels;
        }
        @Override public void buildFailure(long key, long modelGeneration, Throwable failure) {
            this.failures++;
        }
    }
}
