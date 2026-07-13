package com.y271727uy.voxy.client.render.gl;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RenderBackendPolicyTest {
    @Test
    public void completeAbiSelectsGpuDrivenBackendWhenImplementationIsAvailable() {
        GpuCapabilities capabilities = GpuCapabilities.detect(
                () -> GpuCapabilitiesTest.capableRaw("AMD Radeon"));

        RenderBackendPolicy.Selection selection = RenderBackendPolicy.select(capabilities,
                RenderBackendPolicy.GpuBackendAvailability.AVAILABLE);

        assertEquals(RenderBackendPolicy.RenderBackend.GPU_DRIVEN_HIERARCHICAL, selection.backend());
        assertEquals(RenderBackendPolicy.FallbackReason.NONE, selection.primaryReason());
        assertTrue(selection.fallbackReasons().isEmpty());
        assertTrue(selection.hardwareEligible());
        assertTrue(selection.usesGpuDrivenTraversal());
    }

    @Test
    public void defaultSelectionNeverActivatesUnconnectedGpuBackend() {
        GpuCapabilities capabilities = GpuCapabilities.detect(
                () -> GpuCapabilitiesTest.capableRaw("NVIDIA Corporation"));

        RenderBackendPolicy.Selection selection = RenderBackendPolicy.select(capabilities);

        assertEquals(RenderBackendPolicy.RenderBackend.EMBEDDIUM_COMPATIBILITY, selection.backend());
        assertTrue(selection.hardwareEligible());
        assertEquals(RenderBackendPolicy.FallbackReason.GPU_DRIVEN_BACKEND_NOT_IMPLEMENTED,
                selection.primaryReason());
    }

    @Test
    public void absentContextSelectsCompatibilityWithExactReason() {
        GpuCapabilities capabilities = GpuCapabilities.detect(() -> {
            throw new GpuCapabilities.NoGlContextException("not initialized");
        });

        RenderBackendPolicy.Selection selection = RenderBackendPolicy.select(capabilities);

        assertEquals(RenderBackendPolicy.RenderBackend.EMBEDDIUM_COMPATIBILITY, selection.backend());
        assertEquals(RenderBackendPolicy.FallbackReason.NO_GL_CONTEXT, selection.primaryReason());
        assertEquals(List.of(RenderBackendPolicy.FallbackReason.NO_GL_CONTEXT), selection.fallbackReasons());
        assertFalse(selection.hardwareEligible());
        assertFalse(selection.usesGpuDrivenTraversal());
    }

    @Test
    public void incompleteAbiReportsEveryFallbackInStablePriorityOrder() {
        GpuCapabilities.RawCapabilities raw = new GpuCapabilities.RawCapabilities(
                "Intel", "4.3", false, true, true, false,
                false, false, false, false, false, 4, 64L * 1024L * 1024L);

        RenderBackendPolicy.Selection selection = RenderBackendPolicy.select(
                GpuCapabilities.detect(() -> raw));

        assertEquals(RenderBackendPolicy.FallbackReason.COMPUTE_SHADERS_UNAVAILABLE,
                selection.primaryReason());
        assertEquals(List.of(
                RenderBackendPolicy.FallbackReason.COMPUTE_SHADERS_UNAVAILABLE,
                RenderBackendPolicy.FallbackReason.INDIRECT_DRAW_COUNT_UNAVAILABLE,
                RenderBackendPolicy.FallbackReason.BUFFER_STORAGE_UNAVAILABLE,
                RenderBackendPolicy.FallbackReason.DIRECT_STATE_ACCESS_UNAVAILABLE,
                RenderBackendPolicy.FallbackReason.INSUFFICIENT_SSBO_BINDINGS,
                RenderBackendPolicy.FallbackReason.SSBO_BLOCK_TOO_SMALL
        ), selection.fallbackReasons());
    }

    @Test
    public void traversalRequiresEveryBindingThroughIndexNine() {
        GpuCapabilities.RawCapabilities raw = new GpuCapabilities.RawCapabilities(
                "AMD Radeon", "4.6", true, true, true, true,
                true, true, false, false, false,
                RenderBackendPolicy.REQUIRED_SSBO_BINDINGS - 1,
                RenderBackendPolicy.REQUIRED_SSBO_BLOCK_SIZE);

        RenderBackendPolicy.Selection selection = RenderBackendPolicy.select(
                GpuCapabilities.detect(() -> raw),
                RenderBackendPolicy.GpuBackendAvailability.AVAILABLE);

        assertFalse(selection.hardwareEligible());
        assertEquals(RenderBackendPolicy.FallbackReason.INSUFFICIENT_SSBO_BINDINGS,
                selection.primaryReason());
    }
}
