package com.y271727uy.voxy.client.render.mesh;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.y271727uy.voxy.config.VoxyClientConfig;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.GlShader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;

final class VoxySsaoPipeline implements AutoCloseable {
    private static final String NORMAL_VERTEX = """
            #version 150
            in vec3 Position;
            in vec2 UV0;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            out vec3 viewPosition;
            out vec2 atlasUv;
            void main() {
                vec4 view = ModelViewMat * vec4(Position, 1.0);
                viewPosition = view.xyz;
                atlasUv = UV0;
                gl_Position = ProjMat * view;
            }
            """;
    private static final String NORMAL_FRAGMENT = """
            #version 150
            uniform sampler2D BlockAtlas;
            in vec3 viewPosition;
            in vec2 atlasUv;
            out vec4 normalData;
            void main() {
                if (texture(BlockAtlas, atlasUv).a < 0.1) discard;
                vec3 normal = normalize(cross(dFdx(viewPosition), dFdy(viewPosition)));
                if (dot(normal, -viewPosition) < 0.0) normal = -normal;
                normalData = vec4(normal * 0.5 + 0.5, 1.0);
            }
            """;
    private static final String COMPOSITE_VERTEX = """
            #version 150
            out vec2 texCoord;
            void main() {
                vec2 position = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                texCoord = position;
                gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String COMPOSITE_FRAGMENT = """
            #version 150
            uniform sampler2D NormalTexture;
            uniform sampler2D DepthTexture;
            uniform mat4 InvProjMat;
            uniform vec3 ScreenSize;
            uniform float FogStart;
            uniform float FogEnd;
            uniform float SsaoStrength;
            in vec2 texCoord;
            out vec4 fragColor;
            const vec2 DIRECTIONS[8] = vec2[8](
                vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0),
                vec2(0.7071, 0.7071), vec2(-0.7071, 0.7071),
                vec2(0.7071, -0.7071), vec2(-0.7071, -0.7071));
            vec3 reconstruct(vec2 uv, float depth) {
                vec4 view = InvProjMat * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                return view.xyz / view.w;
            }
            void main() {
                vec4 encodedNormal = texture(NormalTexture, texCoord);
                float depth = texture(DepthTexture, texCoord).r;
                if (encodedNormal.a < 0.5 || depth >= 0.999999) {
                    fragColor = vec4(1.0);
                    return;
                }
                vec3 center = reconstruct(texCoord, depth);
                vec3 normal = normalize(encodedNormal.xyz * 2.0 - 1.0);
                vec2 pixel = vec2(ScreenSize.z, 1.0 / ScreenSize.y);
                float occlusion = 0.0;
                for (int i = 0; i < 8; i++) {
                    vec2 sampleUv = clamp(texCoord + DIRECTIONS[i] * pixel * 3.0,
                            pixel * 0.5, vec2(1.0) - pixel * 0.5);
                    float sampleDepth = texture(DepthTexture, sampleUv).r;
                    if (sampleDepth >= 0.999999) continue;
                    vec3 delta = reconstruct(sampleUv, sampleDepth) - center;
                    float distanceToSample = length(delta);
                    if (distanceToSample < 0.01 || distanceToSample > 6.0) continue;
                    float hemisphere = max(dot(normal, delta / distanceToSample) - 0.08, 0.0);
                    occlusion += hemisphere * (1.0 - smoothstep(0.2, 6.0, distanceToSample));
                }
                float factor = 1.0 - min(occlusion * 0.055 * SsaoStrength, 0.32 * SsaoStrength);
                float fog = FogEnd > FogStart
                        ? smoothstep(FogStart, FogEnd, length(center)) : 1.0;
                factor = mix(factor, 1.0, fog);
                fragColor = vec4(vec3(factor), 1.0);
            }
            """;

    private TextureTarget target;
    private int width;
    private int height;
    private final NormalProgram normalProgram;
    private final GlProgram<CompositeUniforms> compositeProgram;
    private final int fullscreenVao;
    private boolean closed;

    private VoxySsaoPipeline(NormalProgram normalProgram,
                             GlProgram<CompositeUniforms> compositeProgram, int fullscreenVao) {
        this.normalProgram = normalProgram;
        this.compositeProgram = compositeProgram;
        this.fullscreenVao = fullscreenVao;
    }

    static VoxySsaoPipeline create() {
        RenderSystem.assertOnRenderThread();
        RenderDevice.enterManagedCode();
        NormalProgram normal = null;
        try {
            normal = NormalProgram.create();
            GlProgram<CompositeUniforms> composite = link("ssao_composite", COMPOSITE_VERTEX,
                    COMPOSITE_FRAGMENT, builder -> builder.bindFragmentData("fragColor", 0),
                    CompositeUniforms::new);
            return new VoxySsaoPipeline(normal, composite, GL30C.glGenVertexArrays());
        } catch (RuntimeException | Error failure) {
            if (normal != null) normal.close();
            throw failure;
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    VoxyGeometryProgram begin(RenderTarget mainTarget) {
        ensureTarget(mainTarget.width, mainTarget.height);
        this.target.copyDepthFrom(mainTarget);
        this.target.bindWrite(true);
        GL11C.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();
        return this.normalProgram;
    }

    void composite(RenderTarget mainTarget, Matrix4f projection) {
        mainTarget.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
        bindTexture(4, this.target.getColorTextureId());
        bindTexture(5, this.target.getDepthTextureId());
        this.compositeProgram.bind();
        CompositeUniforms uniforms = this.compositeProgram.getInterface();
        uniforms.normalTexture.setInt(4);
        uniforms.depthTexture.setInt(5);
        uniforms.inverseProjection.set(new Matrix4f(projection).invert());
        uniforms.screenSize.set((float) this.width, (float) this.height, 1.0F / this.width);
        uniforms.fogStart.setFloat(RenderSystem.getShaderFogStart());
        uniforms.fogEnd.setFloat(RenderSystem.getShaderFogEnd());
        uniforms.ssaoStrength.setFloat(VoxyClientConfig.SSAO_STRENGTH.get().floatValue());
        GL30C.glBindVertexArray(this.fullscreenVao);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        GL30C.glBindVertexArray(0);
        this.compositeProgram.unbind();
        bindTexture(5, 0);
        bindTexture(4, 0);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private void ensureTarget(int requestedWidth, int requestedHeight) {
        if (this.target != null && requestedWidth == this.width && requestedHeight == this.height) return;
        if (this.target != null) {
            this.target.destroyBuffers();
            this.target = null;
        }
        this.width = requestedWidth;
        this.height = requestedHeight;
        this.target = new TextureTarget(this.width, this.height, true, Minecraft.ON_OSX);
        this.target.setFilterMode(GL11C.GL_NEAREST);
    }

    @Override
    public void close() {
        if (this.closed) return;
        RenderSystem.assertOnRenderThread();
        RenderDevice.enterManagedCode();
        try {
            if (this.target != null) this.target.destroyBuffers();
            this.normalProgram.close();
            this.compositeProgram.delete();
            GL30C.glDeleteVertexArrays(this.fullscreenVao);
            this.closed = true;
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    private static void bindTexture(int unit, int texture) {
        RenderSystem.activeTexture(GL13C.GL_TEXTURE0 + unit);
        RenderSystem.bindTexture(texture);
        RenderSystem.activeTexture(GL13C.GL_TEXTURE0);
    }

    private interface ProgramBuilder {
        GlProgram.Builder configure(GlProgram.Builder builder);
    }

    private static <T> GlProgram<T> link(String id, String vertexSource, String fragmentSource,
                                         ProgramBuilder configure,
                                         java.util.function.Function<me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext, T> uniforms) {
        GlShader vertex = null;
        GlShader fragment = null;
        try {
            vertex = new GlShader(ShaderType.VERTEX,
                    ResourceLocation.fromNamespaceAndPath("voxy", id + "_vertex"), vertexSource);
            fragment = new GlShader(ShaderType.FRAGMENT,
                    ResourceLocation.fromNamespaceAndPath("voxy", id + "_fragment"), fragmentSource);
            GlProgram.Builder builder = GlProgram.builder(ResourceLocation.fromNamespaceAndPath("voxy", id))
                    .attachShader(vertex).attachShader(fragment);
            return configure.configure(builder).link(uniforms);
        } finally {
            if (fragment != null) fragment.delete();
            if (vertex != null) vertex.delete();
        }
    }

    private static final class NormalProgram implements VoxyGeometryProgram, AutoCloseable {
        private final GlProgram<NormalUniforms> program;
        private NormalProgram(GlProgram<NormalUniforms> program) { this.program = program; }
        static NormalProgram create() {
            return new NormalProgram(link("ssao_normal", NORMAL_VERTEX, NORMAL_FRAGMENT,
                    builder -> builder.bindAttribute("Position", 0).bindAttribute("UV0", 2)
                            .bindFragmentData("normalData", 0), NormalUniforms::new));
        }
        @Override public void bind(Matrix4f modelView, Matrix4f projection) {
            bindTexture(0, RenderSystem.getShaderTexture(0));
            this.program.bind();
            NormalUniforms uniforms = this.program.getInterface();
            uniforms.modelView.set(modelView);
            uniforms.projection.set(projection);
            uniforms.blockAtlas.setInt(0);
        }
        @Override public void unbind() { this.program.unbind(); }
        @Override public void close() { this.program.delete(); }
    }

    private static final class NormalUniforms {
        private final GlUniformMatrix4f modelView;
        private final GlUniformMatrix4f projection;
        private final GlUniformInt blockAtlas;
        private NormalUniforms(me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext context) {
            this.modelView = context.bindUniform("ModelViewMat", GlUniformMatrix4f::new);
            this.projection = context.bindUniform("ProjMat", GlUniformMatrix4f::new);
            this.blockAtlas = context.bindUniform("BlockAtlas", GlUniformInt::new);
        }
    }

    private static final class CompositeUniforms {
        private final GlUniformInt normalTexture;
        private final GlUniformInt depthTexture;
        private final GlUniformMatrix4f inverseProjection;
        private final GlUniformFloat3v screenSize;
        private final GlUniformFloat fogStart;
        private final GlUniformFloat fogEnd;
        private final GlUniformFloat ssaoStrength;
        private CompositeUniforms(me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext context) {
            this.normalTexture = context.bindUniform("NormalTexture", GlUniformInt::new);
            this.depthTexture = context.bindUniform("DepthTexture", GlUniformInt::new);
            this.inverseProjection = context.bindUniform("InvProjMat", GlUniformMatrix4f::new);
            this.screenSize = context.bindUniform("ScreenSize", GlUniformFloat3v::new);
            this.fogStart = context.bindUniform("FogStart", GlUniformFloat::new);
            this.fogEnd = context.bindUniform("FogEnd", GlUniformFloat::new);
            this.ssaoStrength = context.bindUniform("SsaoStrength", GlUniformFloat::new);
        }
    }
}
