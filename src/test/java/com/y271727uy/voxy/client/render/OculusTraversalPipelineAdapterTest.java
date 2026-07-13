package com.y271727uy.voxy.client.render;

import org.junit.Test;

import static org.junit.Assert.*;

public class OculusTraversalPipelineAdapterTest {
    @Test
    public void currentPassExposesGeometryAndNodeAbiWithoutClaimingComputeOrHiZ() {
        OculusTraversalPipelineAdapter.Capability capability =
                OculusTraversalPipelineAdapter.resolve(
                        OculusTraversalPipelineAdapter.Pass.OPAQUE, true, 17, true);

        assertTrue(capability.geometryPathAvailable());
        assertEquals(17, capability.framebufferId());
        assertTrue(capability.depthAttachmentAvailable());
        assertTrue(capability.nodeSsboAbiAvailable());
        assertFalse(capability.computeTraversalAvailable());
        assertFalse(capability.hiZAvailable());
        assertTrue(capability.limitation().contains("not implemented"));
    }

    @Test
    public void missingSourcesOrFramebufferDisableGeometryPath() {
        OculusTraversalPipelineAdapter.Capability noSources =
                OculusTraversalPipelineAdapter.resolve(
                        OculusTraversalPipelineAdapter.Pass.TRANSLUCENT, false, 4, true);
        OculusTraversalPipelineAdapter.Capability noFramebuffer =
                OculusTraversalPipelineAdapter.resolve(
                        OculusTraversalPipelineAdapter.Pass.SHADOW, true, 0, false);

        assertFalse(noSources.geometryPathAvailable());
        assertEquals("required shader sources are unavailable", noSources.limitation());
        assertFalse(noFramebuffer.geometryPathAvailable());
        assertEquals("render-pass framebuffer is unavailable", noFramebuffer.limitation());
    }

    @Test
    public void depthlessFramebufferRemainsDrawableButCannotBackHiZ() {
        OculusTraversalPipelineAdapter.Capability capability =
                OculusTraversalPipelineAdapter.resolve(
                        OculusTraversalPipelineAdapter.Pass.SHADOW, true, 23, false);

        assertTrue(capability.geometryPathAvailable());
        assertFalse(capability.depthAttachmentAvailable());
        assertFalse(capability.hiZAvailable());
        assertTrue(capability.limitation().contains("no depth attachment"));
    }
}
