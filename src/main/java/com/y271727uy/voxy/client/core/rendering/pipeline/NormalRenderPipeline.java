package com.y271727uy.voxy.client.core.rendering.pipeline;

/** The forward, non-shader pipeline. Translucency is completed in the same frame. */
public final class NormalRenderPipeline<C> extends AbstractRenderPipeline<C> {
    public NormalRenderPipeline(Backend<C> backend) {
        super(backend, false);
    }
}
