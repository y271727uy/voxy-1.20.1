package com.y271727uy.voxy.client.render.gl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpuCapabilitiesTest {
    @Test
    public void injectableProbeCreatesAvailableSnapshot() {
        GpuCapabilities capabilities = GpuCapabilities.detect(() -> capableRaw("NVIDIA Corporation"));

        assertEquals(GpuCapabilities.DetectionStatus.AVAILABLE, capabilities.status());
        assertTrue(capabilities.computeShaders());
        assertTrue(capabilities.isNvidia());
        assertFalse(capabilities.isIntel());
        assertTrue(capabilities.detectionDetail().isEmpty());
    }

    @Test
    public void missingContextIsSafeAndRetainsDiagnostic() {
        GpuCapabilities capabilities = GpuCapabilities.detect(() -> {
            throw new GpuCapabilities.NoGlContextException("test has no current context");
        });

        assertEquals(GpuCapabilities.DetectionStatus.NO_CONTEXT, capabilities.status());
        assertFalse(capabilities.computeShaders());
        assertTrue(capabilities.detectionDetail().contains("test has no current context"));
    }

    @Test
    public void unexpectedProbeFailureDoesNotEscape() {
        GpuCapabilities capabilities = GpuCapabilities.detect(() -> {
            throw new IllegalStateException("driver query failed");
        });

        assertEquals(GpuCapabilities.DetectionStatus.PROBE_FAILED, capabilities.status());
        assertTrue(capabilities.detectionDetail().contains("driver query failed"));
    }

    static GpuCapabilities.RawCapabilities capableRaw(String vendor) {
        return new GpuCapabilities.RawCapabilities(vendor, "4.6 Mesa 24.0",
                true, true, true, true, true, true, false, true, true,
                16, 256L * 1024L * 1024L);
    }
}
