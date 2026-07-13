package com.y271727uy.voxy.client.render;

import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.SodiumTerrainPipeline;

import java.util.Objects;

/** Describes the Oculus render-pass boundary available to a future GPU traversal backend. */
public final class OculusTraversalPipelineAdapter {
    private OculusTraversalPipelineAdapter() {
    }

    public static Capability probe(OculusShaderBridge.ShaderState state,
                                   SodiumTerrainPipeline pipeline, Pass pass) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(pass, "pass");
        if (!state.shaderPackInUse()) return unavailable(pass, "shader pack is not active");
        if (!state.terrainPipelineAvailable() || pipeline == null) {
            return unavailable(pass, "Oculus terrain pipeline is unavailable");
        }
        if (pass == Pass.SHADOW && (!state.shadowProgramAvailable() || !pipeline.hasShadowPass())) {
            return unavailable(pass, "shader pack has no shadow terrain pass");
        }

        boolean sources;
        GlFramebuffer framebuffer;
        if (pass == Pass.SHADOW) {
            sources = pipeline.getShadowVertexShaderSource().isPresent()
                    && (pipeline.getShadowCutoutFragmentShaderSource().isPresent()
                    || pipeline.getShadowFragmentShaderSource().isPresent());
            framebuffer = pipeline.getShadowFramebuffer();
        } else if (pass == Pass.TRANSLUCENT) {
            sources = pipeline.getTranslucentVertexShaderSource().isPresent()
                    && pipeline.getTranslucentFragmentShaderSource().isPresent();
            framebuffer = pipeline.getTranslucentFramebuffer();
        } else {
            sources = (pipeline.getTerrainCutoutVertexShaderSource().isPresent()
                    || pipeline.getTerrainSolidVertexShaderSource().isPresent())
                    && (pipeline.getTerrainCutoutFragmentShaderSource().isPresent()
                    || pipeline.getTerrainSolidFragmentShaderSource().isPresent());
            framebuffer = pipeline.getTerrainCutoutFramebuffer();
        }
        return resolve(pass, sources, framebuffer == null ? 0 : framebuffer.getId(),
                framebuffer != null && framebuffer.hasDepthAttachment());
    }

    public static Capability resolve(Pass pass, boolean shaderSourcesAvailable,
                                     int framebufferId, boolean depthAttachmentAvailable) {
        Objects.requireNonNull(pass, "pass");
        if (!shaderSourcesAvailable) return unavailable(pass, "required shader sources are unavailable");
        if (framebufferId <= 0) return unavailable(pass, "render-pass framebuffer is unavailable");
        String limitation = depthAttachmentAvailable
                ? "compute traversal and Hi-Z construction are not implemented"
                : "render pass exposes no depth attachment; compute traversal and Hi-Z construction are not implemented";
        return new Capability(pass, true, framebufferId, depthAttachmentAvailable,
                true, false, false, limitation);
    }

    private static Capability unavailable(Pass pass, String reason) {
        return new Capability(pass, false, 0, false, true, false, false, reason);
    }

    public enum Pass { OPAQUE, TRANSLUCENT, SHADOW }

    public record Capability(Pass pass, boolean geometryPathAvailable, int framebufferId,
                             boolean depthAttachmentAvailable, boolean nodeSsboAbiAvailable,
                             boolean computeTraversalAvailable, boolean hiZAvailable,
                             String limitation) {
    }
}
