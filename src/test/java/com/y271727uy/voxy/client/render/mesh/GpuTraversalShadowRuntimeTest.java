package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import com.y271727uy.voxy.client.core.rendering.hierachical.GpuTraversalShadowController;
import com.y271727uy.voxy.client.core.rendering.hierachical.NodeGpuTraversalSnapshot;
import com.y271727uy.voxy.common.world.WorldEngine;
import com.y271727uy.voxy.common.world.WorldSectionKey;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpuTraversalShadowRuntimeTest {
    @Test
    public void syncsNodePayloadBeforeDispatchAndNeverReturnsADrawList() throws Exception {
        FakeDriver driver = new FakeDriver();
        GpuTraversalShadowRuntime runtime = new GpuTraversalShadowRuntime(driver);
        long key = WorldSectionKey.pack(2, 1, 0, -1);

        runtime.frame(frame(1), List.of(key), List.of(key));
        Thread.sleep(250);
        GpuTraversalShadowRuntime.Metrics metrics = runtime.frame(frame(2), List.of(key), List.of(key));

        assertTrue(driver.events.contains("upload"));
        assertTrue(driver.events.lastIndexOf("upload") < driver.events.lastIndexOf("frame"));
        assertTrue(metrics.available());
        assertEquals(2, metrics.dispatches());
        runtime.close();
        runtime.close();
        assertEquals(1, driver.closes);
    }

    @Test
    public void frameFailurePermanentlyDisablesRuntime() {
        FakeDriver driver = new FakeDriver();
        driver.failFrame = true;
        GpuTraversalShadowRuntime runtime = new GpuTraversalShadowRuntime(driver);
        long key = WorldSectionKey.pack(1, 0, 0, 0);

        GpuTraversalShadowRuntime.Metrics failed = runtime.frame(frame(1), List.of(key), List.of(key));
        driver.failFrame = false;
        GpuTraversalShadowRuntime.Metrics stillDisabled = runtime.frame(frame(2), List.of(key), List.of(key));

        assertFalse(failed.available());
        assertFalse(stillDisabled.available());
        assertTrue(stillDisabled.disableReason().contains("shadow frame failed"));
        assertEquals(1, driver.frames);
        runtime.close();
    }

    @Test
    public void initializationFailureNeverStartsDispatch() {
        FakeDriver driver = new FakeDriver();
        driver.available = false;
        driver.reason = "injected compile failure";
        GpuTraversalShadowRuntime runtime = new GpuTraversalShadowRuntime(driver);

        GpuTraversalShadowRuntime.Metrics metrics = runtime.frame(frame(1), List.of(), List.of());

        assertFalse(metrics.available());
        assertEquals("injected compile failure", metrics.disableReason());
        assertEquals(0, driver.frames);
        runtime.close();
    }

    @Test
    public void readyTopologyUsesWorldRootsInsteadOfVisibleCutRoots() throws Exception {
        FakeDriver driver = new FakeDriver();
        GpuTraversalShadowRuntime runtime = new GpuTraversalShadowRuntime(driver);
        long root = WorldSectionKey.pack(WorldEngine.MAX_LOD_LEVEL, 0, 0, 0);
        long child = LodCoverageSelector.childKey(root, 0);
        Map<Long, LodCoverageSelector.Node> topology = new LinkedHashMap<>();
        topology.put(root, new LodCoverageSelector.Node(root, 1, false));
        topology.put(child, new LodCoverageSelector.Node(child, 0, true));

        GpuTraversalShadowRuntime.Metrics first = runtime.frame(frame(1), topology, List.of(child),
                new GpuTraversalShadowRuntime.PhaseCounts(1, 1, 1, -1, 1));
        Thread.sleep(250);
        runtime.frame(frame(2), topology, List.of(child),
                new GpuTraversalShadowRuntime.PhaseCounts(1, 1, 1, -1, 1));

        assertEquals(1, first.lastCpuOnly());
        assertTrue(first.lastCpuOnlyByLod().contains("1"));
        assertFalse(driver.rootUploads.isEmpty());
        int[] uploadedRoots = driver.rootUploads.get(driver.rootUploads.size() - 1);
        assertEquals("GPU roots must be the world hierarchy roots, not the visible child cut",
                1, uploadedRoots.length);
        runtime.close();
    }

    private static GpuTraversalShadowRuntime.FrameInput frame(long id) {
        float[] identity = new float[16];
        identity[0] = identity[5] = identity[10] = identity[15] = 1;
        return new GpuTraversalShadowRuntime.FrameInput(id, 7, 1280, 720, identity,
                0, 64, 0, 2048);
    }

    private static final class FakeDriver implements GpuTraversalShadowRuntime.Driver {
        private final List<String> events = new ArrayList<>();
        private final List<int[]> rootUploads = new ArrayList<>();
        private boolean available = true;
        private boolean failFrame;
        private String reason = "";
        private int frames;
        private int closes;

        @Override public void initialize(NodeGpuTraversalSnapshot snapshot) { events.add("initialize"); }
        @Override public void uploadNodesAndRoots(AsyncNodeManager.SyncBatch batch) {
            events.add("upload");
            rootUploads.add(batch.rootNodeIds());
        }

        @Override
        public GpuTraversalShadowRuntime.FrameResult runFrame(GpuTraversalShadowRuntime.FrameInput frame) {
            events.add("frame");
            frames++;
            if (failFrame) throw new IllegalStateException("injected dispatch failure");
            return new GpuTraversalShadowRuntime.FrameResult(true,
                    Optional.of(GpuTraversalShadowController.GpuReadback.ready(frame.frameId(), List.of())));
        }

        @Override public boolean isAvailable() { return available; }
        @Override public String disableReason() { return reason; }
        @Override public void close() { closes++; }
    }
}
