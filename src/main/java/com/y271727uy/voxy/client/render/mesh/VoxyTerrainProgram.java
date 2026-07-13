package com.y271727uy.voxy.client.render.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.GlShader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL13C;

final class VoxyTerrainProgram implements VoxyGeometryProgram, AutoCloseable {
    static final String VERTEX_SOURCE = """
            #version 150

            in vec3 Position;
            in vec4 Color;
            in vec2 UV0;
            in uvec2 LightUV;

            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform int FogShape;

            out vec4 vertexColor;
            out vec2 atlasUv;
            out vec2 lightUv;
            out float vertexDistance;

            float voxyFogDistance(mat4 modelView, vec3 position, int shape) {
                if (shape == 0) {
                    return length((modelView * vec4(position, 1.0)).xyz);
                }
                float horizontal = length((modelView * vec4(position.x, 0.0, position.z, 1.0)).xyz);
                float vertical = length((modelView * vec4(0.0, position.y, 0.0, 1.0)).xyz);
                return max(horizontal, vertical);
            }

            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                vertexColor = Color;
                atlasUv = UV0;
                lightUv = (vec2(LightUV) + vec2(0.5)) / 256.0;
                vertexDistance = voxyFogDistance(ModelViewMat, Position, FogShape);
            }
            """;

    static final String FRAGMENT_SOURCE = """
            #version 150

            uniform sampler2D BlockAtlas;
            uniform sampler2D LightMap;
            uniform vec4 ColorModulator;
            uniform float FogStart;
            uniform float FogEnd;
            uniform vec4 FogColor;

            in vec4 vertexColor;
            in vec2 atlasUv;
            in vec2 lightUv;
            in float vertexDistance;

            out vec4 fragColor;

            vec4 voxyLinearFog(vec4 color) {
                if (vertexDistance <= FogStart) {
                    return color;
                }
                float amount = vertexDistance < FogEnd
                        ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0;
                return vec4(mix(color.rgb, FogColor.rgb, amount * FogColor.a), color.a);
            }

            void main() {
                vec4 color = texture(BlockAtlas, atlasUv) * vertexColor * ColorModulator;
                if (color.a < 0.1) {
                    discard;
                }
                color *= texture(LightMap, lightUv);
                fragColor = voxyLinearFog(color);
            }
            """;

    private static final ResourceLocation PROGRAM_ID = ResourceLocation.fromNamespaceAndPath("voxy", "terrain");
    private final GlProgram<Uniforms> program;
    private boolean closed;

    private VoxyTerrainProgram(GlProgram<Uniforms> program) {
        this.program = program;
    }

    static VoxyTerrainProgram create() {
        RenderSystem.assertOnRenderThread();
        RenderDevice.enterManagedCode();
        GlShader vertex = null;
        GlShader fragment = null;
        try {
            vertex = new GlShader(ShaderType.VERTEX,
                    ResourceLocation.fromNamespaceAndPath("voxy", "terrain_vertex"), VERTEX_SOURCE);
            fragment = new GlShader(ShaderType.FRAGMENT,
                    ResourceLocation.fromNamespaceAndPath("voxy", "terrain_fragment"), FRAGMENT_SOURCE);
            GlProgram<Uniforms> program = GlProgram.builder(PROGRAM_ID)
                    .attachShader(vertex)
                    .attachShader(fragment)
                    .bindAttribute("Position", 0)
                    .bindAttribute("Color", 1)
                    .bindAttribute("UV0", 2)
                    .bindAttribute("LightUV", 3)
                    .bindFragmentData("fragColor", 0)
                    .link(Uniforms::new);
            return new VoxyTerrainProgram(program);
        } finally {
            if (fragment != null) fragment.delete();
            if (vertex != null) vertex.delete();
            RenderDevice.exitManagedCode();
        }
    }

    public void bind(Matrix4f modelView, Matrix4f projection) {
        if (this.closed) {
            throw new IllegalStateException("Cannot bind a deleted Voxy terrain program");
        }
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        bindTextureUnit(0, RenderSystem.getShaderTexture(0));
        bindTextureUnit(2, RenderSystem.getShaderTexture(2));
        this.program.bind();
        Uniforms uniforms = this.program.getInterface();
        uniforms.modelView.set(modelView);
        uniforms.projection.set(projection);
        uniforms.blockAtlas.setInt(0);
        uniforms.lightMap.setInt(2);
        uniforms.colorModulator.set(RenderSystem.getShaderColor());
        uniforms.fogStart.setFloat(RenderSystem.getShaderFogStart());
        uniforms.fogEnd.setFloat(RenderSystem.getShaderFogEnd());
        uniforms.fogColor.set(RenderSystem.getShaderFogColor());
        uniforms.fogShape.setInt(RenderSystem.getShaderFogShape().getIndex());
    }

    public void unbind() {
        this.program.unbind();
    }

    @Override
    public void close() {
        if (this.closed) return;
        RenderSystem.assertOnRenderThread();
        RenderDevice.enterManagedCode();
        try {
            this.program.delete();
            this.closed = true;
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    private static void bindTextureUnit(int unit, int texture) {
        RenderSystem.activeTexture(GL13C.GL_TEXTURE0 + unit);
        RenderSystem.bindTexture(texture);
        RenderSystem.activeTexture(GL13C.GL_TEXTURE0);
    }

    private static final class Uniforms {
        private final GlUniformMatrix4f modelView;
        private final GlUniformMatrix4f projection;
        private final GlUniformInt blockAtlas;
        private final GlUniformInt lightMap;
        private final GlUniformFloat4v colorModulator;
        private final GlUniformFloat fogStart;
        private final GlUniformFloat fogEnd;
        private final GlUniformFloat4v fogColor;
        private final GlUniformInt fogShape;

        private Uniforms(me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext context) {
            this.modelView = context.bindUniform("ModelViewMat", GlUniformMatrix4f::new);
            this.projection = context.bindUniform("ProjMat", GlUniformMatrix4f::new);
            this.blockAtlas = context.bindUniform("BlockAtlas", GlUniformInt::new);
            this.lightMap = context.bindUniform("LightMap", GlUniformInt::new);
            this.colorModulator = context.bindUniform("ColorModulator", GlUniformFloat4v::new);
            this.fogStart = context.bindUniform("FogStart", GlUniformFloat::new);
            this.fogEnd = context.bindUniform("FogEnd", GlUniformFloat::new);
            this.fogColor = context.bindUniform("FogColor", GlUniformFloat4v::new);
            this.fogShape = context.bindUniform("FogShape", GlUniformInt::new);
        }
    }
}
