package com.y271727uy.voxy.client.render.gl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Selects the GPU-driven Voxy backend only when its complete OpenGL ABI is available. */
public final class RenderBackendPolicy {
    // Production traversal binds SSBOs through binding index 9. GL reports a count, not a max index.
    public static final int REQUIRED_SSBO_BINDINGS = 10;
    public static final long REQUIRED_SSBO_BLOCK_SIZE = 128L * 1024L * 1024L;

    private RenderBackendPolicy() {
    }

    /** Safe default while the GPU-driven implementation has not been connected by the caller. */
    public static Selection select(GpuCapabilities capabilities) {
        return select(capabilities, GpuBackendAvailability.COMPATIBILITY_ONLY);
    }

    public static Selection select(GpuCapabilities capabilities, GpuBackendAvailability availability) {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(availability, "availability");
        List<FallbackReason> reasons = new ArrayList<>();

        if (capabilities.status() == GpuCapabilities.DetectionStatus.NO_CONTEXT) {
            reasons.add(FallbackReason.NO_GL_CONTEXT);
        } else if (capabilities.status() == GpuCapabilities.DetectionStatus.PROBE_FAILED) {
            reasons.add(FallbackReason.CAPABILITY_PROBE_FAILED);
        } else {
            if (!capabilities.computeShaders()) reasons.add(FallbackReason.COMPUTE_SHADERS_UNAVAILABLE);
            if (!capabilities.shaderStorageBuffers()) reasons.add(FallbackReason.SHADER_STORAGE_UNAVAILABLE);
            if (!capabilities.multiDrawIndirect()) reasons.add(FallbackReason.MULTI_DRAW_INDIRECT_UNAVAILABLE);
            if (!capabilities.indirectDrawCount()) reasons.add(FallbackReason.INDIRECT_DRAW_COUNT_UNAVAILABLE);
            if (!capabilities.bufferStorage()) reasons.add(FallbackReason.BUFFER_STORAGE_UNAVAILABLE);
            if (!capabilities.directStateAccess()) reasons.add(FallbackReason.DIRECT_STATE_ACCESS_UNAVAILABLE);
            if (capabilities.maxShaderStorageBindings() < REQUIRED_SSBO_BINDINGS) {
                reasons.add(FallbackReason.INSUFFICIENT_SSBO_BINDINGS);
            }
            if (capabilities.maxShaderStorageBlockSize() < REQUIRED_SSBO_BLOCK_SIZE) {
                reasons.add(FallbackReason.SSBO_BLOCK_TOO_SMALL);
            }
        }

        boolean hardwareEligible = reasons.isEmpty();
        if (hardwareEligible && availability == GpuBackendAvailability.AVAILABLE) {
            return new Selection(RenderBackend.GPU_DRIVEN_HIERARCHICAL, true,
                    FallbackReason.NONE, List.of());
        }
        if (hardwareEligible) {
            reasons.add(FallbackReason.GPU_DRIVEN_BACKEND_NOT_IMPLEMENTED);
        }
        List<FallbackReason> immutableReasons = List.copyOf(reasons);
        return new Selection(RenderBackend.EMBEDDIUM_COMPATIBILITY, hardwareEligible,
                immutableReasons.get(0), immutableReasons);
    }

    public enum GpuBackendAvailability {
        COMPATIBILITY_ONLY,
        AVAILABLE
    }

    public enum RenderBackend {
        GPU_DRIVEN_HIERARCHICAL,
        EMBEDDIUM_COMPATIBILITY
    }

    public enum FallbackReason {
        NONE,
        NO_GL_CONTEXT,
        CAPABILITY_PROBE_FAILED,
        COMPUTE_SHADERS_UNAVAILABLE,
        SHADER_STORAGE_UNAVAILABLE,
        MULTI_DRAW_INDIRECT_UNAVAILABLE,
        INDIRECT_DRAW_COUNT_UNAVAILABLE,
        BUFFER_STORAGE_UNAVAILABLE,
        DIRECT_STATE_ACCESS_UNAVAILABLE,
        INSUFFICIENT_SSBO_BINDINGS,
        SSBO_BLOCK_TOO_SMALL,
        GPU_DRIVEN_BACKEND_NOT_IMPLEMENTED
    }

    public record Selection(RenderBackend backend, boolean hardwareEligible, FallbackReason primaryReason,
                            List<FallbackReason> fallbackReasons) {
        public Selection {
            backend = Objects.requireNonNull(backend, "backend");
            primaryReason = Objects.requireNonNull(primaryReason, "primaryReason");
            fallbackReasons = List.copyOf(Objects.requireNonNull(fallbackReasons, "fallbackReasons"));
            boolean nativeBackend = backend == RenderBackend.GPU_DRIVEN_HIERARCHICAL;
            if (nativeBackend != (primaryReason == FallbackReason.NONE && fallbackReasons.isEmpty())) {
                throw new IllegalArgumentException("Backend and fallback reasons disagree");
            }
            if (nativeBackend && !hardwareEligible) {
                throw new IllegalArgumentException("GPU-driven backend requires eligible hardware");
            }
            if (!nativeBackend && (fallbackReasons.isEmpty() || fallbackReasons.get(0) != primaryReason)) {
                throw new IllegalArgumentException("Compatibility backend requires an ordered primary reason");
            }
        }

        public boolean usesGpuDrivenTraversal() {
            return this.backend == RenderBackend.GPU_DRIVEN_HIERARCHICAL;
        }
    }
}
