package com.y271727uy.voxy.client.core.rendering.pipeline;

import java.util.Objects;

public final class RenderPipelineFactory {
    private RenderPipelineFactory() {
    }

    public static Mode selectMode(boolean shaderPackInUse, boolean shaderPipelineAvailable) {
        return shaderPackInUse && shaderPipelineAvailable ? Mode.IRIS : Mode.NORMAL;
    }

    public static <C> AbstractRenderPipeline<C> create(Mode mode,
                                                        AbstractRenderPipeline.Backend<C> backend) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(backend, "backend");
        return mode == Mode.IRIS ? new IrisRenderPipeline<>(backend) : new NormalRenderPipeline<>(backend);
    }

    public enum Mode {
        NORMAL,
        IRIS
    }
}
