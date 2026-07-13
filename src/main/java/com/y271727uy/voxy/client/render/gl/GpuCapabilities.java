package com.y271727uy.voxy.client.render.gl;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;

import java.util.Locale;
import java.util.Objects;

/** A context-safe snapshot of the OpenGL features relevant to Voxy's render backends. */
public record GpuCapabilities(
        DetectionStatus status,
        String vendor,
        String version,
        boolean computeShaders,
        boolean shaderStorageBuffers,
        boolean multiDrawIndirect,
        boolean indirectDrawCount,
        boolean bufferStorage,
        boolean directStateAccess,
        boolean sparseBuffers,
        boolean shaderInt64,
        boolean shaderSubgroups,
        int maxShaderStorageBindings,
        long maxShaderStorageBlockSize,
        String detectionDetail
) {
    public GpuCapabilities {
        status = Objects.requireNonNull(status, "status");
        vendor = vendor == null ? "unknown" : vendor;
        version = version == null ? "unknown" : version;
        detectionDetail = detectionDetail == null ? "" : detectionDetail;
        if (maxShaderStorageBindings < 0) {
            throw new IllegalArgumentException("maxShaderStorageBindings must not be negative");
        }
        if (maxShaderStorageBlockSize < 0) {
            throw new IllegalArgumentException("maxShaderStorageBlockSize must not be negative");
        }
    }

    /** Detects the current context. Calling this before OpenGL initialization is safe. */
    public static GpuCapabilities detect() {
        return detect(new LwjglCapabilityProbe());
    }

    /** Detects through an injectable probe so policy tests never need an OpenGL context. */
    public static GpuCapabilities detect(CapabilityProbe probe) {
        Objects.requireNonNull(probe, "probe");
        try {
            RawCapabilities raw = Objects.requireNonNull(probe.query(), "probe result");
            return new GpuCapabilities(DetectionStatus.AVAILABLE, raw.vendor(), raw.version(),
                    raw.computeShaders(), raw.shaderStorageBuffers(), raw.multiDrawIndirect(),
                    raw.indirectDrawCount(), raw.bufferStorage(), raw.directStateAccess(),
                    raw.sparseBuffers(), raw.shaderInt64(), raw.shaderSubgroups(),
                    raw.maxShaderStorageBindings(), raw.maxShaderStorageBlockSize(), "");
        } catch (NoGlContextException exception) {
            return unavailable(DetectionStatus.NO_CONTEXT, exception);
        } catch (RuntimeException | LinkageError exception) {
            return unavailable(DetectionStatus.PROBE_FAILED, exception);
        }
    }

    public boolean isIntel() {
        return normalizedVendor().contains("intel");
    }

    public boolean isNvidia() {
        return normalizedVendor().contains("nvidia");
    }

    public boolean isAmd() {
        String normalized = normalizedVendor();
        return normalized.contains("amd") || normalized.contains("ati") || normalized.contains("radeon");
    }

    public boolean isMesa() {
        return this.version.toLowerCase(Locale.ROOT).contains("mesa");
    }

    private String normalizedVendor() {
        return this.vendor.toLowerCase(Locale.ROOT);
    }

    private static GpuCapabilities unavailable(DetectionStatus status, Throwable failure) {
        String message = failure.getMessage();
        String detail = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return new GpuCapabilities(status, "unknown", "unknown",
                false, false, false, false, false, false, false, false, false,
                0, 0, detail);
    }

    public enum DetectionStatus {
        AVAILABLE,
        NO_CONTEXT,
        PROBE_FAILED
    }

    @FunctionalInterface
    public interface CapabilityProbe {
        RawCapabilities query();
    }

    public static final class NoGlContextException extends RuntimeException {
        public NoGlContextException(String message) {
            super(message);
        }
    }

    /** Raw values supplied by a real or test probe before detection status is applied. */
    public record RawCapabilities(
            String vendor,
            String version,
            boolean computeShaders,
            boolean shaderStorageBuffers,
            boolean multiDrawIndirect,
            boolean indirectDrawCount,
            boolean bufferStorage,
            boolean directStateAccess,
            boolean sparseBuffers,
            boolean shaderInt64,
            boolean shaderSubgroups,
            int maxShaderStorageBindings,
            long maxShaderStorageBlockSize
    ) {
        public RawCapabilities {
            if (maxShaderStorageBindings < 0 || maxShaderStorageBlockSize < 0) {
                throw new IllegalArgumentException("GPU limits must not be negative");
            }
        }
    }

    private static final class LwjglCapabilityProbe implements CapabilityProbe {
        @Override
        public RawCapabilities query() {
            final org.lwjgl.opengl.GLCapabilities capabilities;
            try {
                capabilities = GL.getCapabilities();
            } catch (IllegalStateException exception) {
                throw new NoGlContextException("OpenGL capabilities are not initialized on this thread");
            }

            String vendor = GL11C.glGetString(GL11C.GL_VENDOR);
            String version = GL11C.glGetString(GL11C.GL_VERSION);
            if (vendor == null || version == null) {
                throw new NoGlContextException("OpenGL context did not expose vendor/version strings");
            }

            boolean compute = capabilities.glDispatchCompute != 0
                    && capabilities.glDispatchComputeIndirect != 0;
            boolean shaderStorage = capabilities.OpenGL43
                    || capabilities.GL_ARB_shader_storage_buffer_object;
            boolean multiDrawIndirect = capabilities.glMultiDrawElementsIndirect != 0;
            boolean indirectCount = capabilities.glMultiDrawElementsIndirectCountARB != 0;
            int bindings = shaderStorage
                    ? GL11C.glGetInteger(GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0;
            long blockSize = shaderStorage
                    ? GL43C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE) : 0;

            return new RawCapabilities(vendor, version, compute, shaderStorage,
                    multiDrawIndirect, indirectCount,
                    capabilities.GL_ARB_buffer_storage || capabilities.OpenGL44,
                    capabilities.GL_ARB_direct_state_access || capabilities.OpenGL45,
                    capabilities.GL_ARB_sparse_buffer,
                    capabilities.GL_ARB_gpu_shader_int64 || capabilities.GL_AMD_gpu_shader_int64,
                    capabilities.GL_KHR_shader_subgroup,
                    bindings, blockSize);
        }
    }
}
