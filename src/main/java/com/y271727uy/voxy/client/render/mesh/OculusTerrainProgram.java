package com.y271727uy.voxy.client.render.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.GlShader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.SodiumTerrainPipeline;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class OculusTerrainProgram implements VoxyGeometryProgram, AutoCloseable {
    enum Pass { TERRAIN, TRANSLUCENT, SHADOW }

    private final GlProgram<Void> program;
    private final ProgramUniforms uniforms;
    private final ProgramSamplers samplers;
    private final ProgramImages images;
    private final net.irisshaders.iris.gl.framebuffer.GlFramebuffer framebuffer;
    private final BlendModeOverride blendOverride;
    private final List<net.irisshaders.iris.gl.blending.BufferBlendOverride> bufferBlendOverrides;
    private final int modelViewLocation;
    private final int modelViewInverseLocation;
    private final int projectionLocation;
    private final int projectionInverseLocation;
    private final int normalMatrixLocation;
    private final int regionOffsetLocation;
    private final Diagnostics diagnostics;
    private int previousFramebuffer;
    private float regionOffsetX;
    private float regionOffsetY;
    private float regionOffsetZ;
    private boolean closed;

    private OculusTerrainProgram(GlProgram<Void> program, ProgramUniforms uniforms,
                                 ProgramSamplers samplers, ProgramImages images,
                                 net.irisshaders.iris.gl.framebuffer.GlFramebuffer framebuffer,
                                 BlendModeOverride blendOverride,
                                 List<net.irisshaders.iris.gl.blending.BufferBlendOverride> bufferBlendOverrides,
                                 Diagnostics diagnostics) {
        this.program = program;
        this.uniforms = uniforms;
        this.samplers = samplers;
        this.images = images;
        this.framebuffer = framebuffer;
        this.blendOverride = blendOverride;
        this.bufferBlendOverrides = List.copyOf(bufferBlendOverrides);
        this.modelViewLocation = firstUniform(program.handle(), "iris_ModelViewMatrix", "u_ModelViewMatrix");
        this.modelViewInverseLocation = firstUniform(program.handle(), "iris_ModelViewMatrixInverse");
        this.projectionLocation = firstUniform(program.handle(), "iris_ProjectionMatrix", "u_ProjectionMatrix");
        this.projectionInverseLocation = firstUniform(program.handle(), "iris_ProjectionMatrixInverse");
        this.normalMatrixLocation = firstUniform(program.handle(), "iris_NormalMatrix");
        this.regionOffsetLocation = GL20C.glGetUniformLocation(program.handle(), "u_RegionOffset");
        this.diagnostics = diagnostics;
    }

    static OculusTerrainProgram create(SodiumTerrainPipeline pipeline, Pass pass) {
        RenderSystem.assertOnRenderThread();
        Sources sources = Sources.from(pipeline, pass);
        RenderDevice.enterManagedCode();
        List<GlShader> shaders = new ArrayList<>();
        try {
            GlProgram.Builder builder = GlProgram.builder(ResourceLocation.fromNamespaceAndPath(
                            "voxy", pass == Pass.SHADOW ? "oculus_shadow" : "oculus_terrain"))
                    .bindAttribute("a_PosId", 0)
                    .bindAttribute("a_Color", 1)
                    .bindAttribute("a_TexCoord", 2)
                    .bindAttribute("a_LightCoord", 3);
            attach(builder, shaders, ShaderType.VERTEX, "vertex", adaptVertexSource(sources.vertex()));
            sources.geometry().ifPresent(source -> attach(builder, shaders, ShaderType.GEOM, "geometry", source));
            sources.tessControl().ifPresent(source -> attach(builder, shaders, ShaderType.TESS_CTRL,
                    "tess_control", source));
            sources.tessEvaluate().ifPresent(source -> attach(builder, shaders, ShaderType.TESS_EVALUATE,
                    "tess_evaluate", source));
            attach(builder, shaders, ShaderType.FRAGMENT, "fragment", sources.fragment());
            GlProgram<Void> program = builder.link(context -> null);
            ProgramUniforms uniforms = pipeline.initUniforms(program.handle()).buildUniforms();
            ProgramSamplers samplers = pass == Pass.SHADOW
                    ? pipeline.initShadowSamplers(program.handle()) : pipeline.initTerrainSamplers(program.handle());
            ProgramImages images = pass == Pass.SHADOW
                    ? pipeline.initShadowImages(program.handle()) : pipeline.initTerrainImages(program.handle());
            Diagnostics diagnostics = new Diagnostics(pass, sources.geometry().isPresent(),
                    sources.tessControl().isPresent(), sources.tessEvaluate().isPresent(),
                    sources.framebuffer().getId(), sources.blendOverride() != null,
                    sources.bufferBlendOverrides().size(), images.getActiveImages());
            return new OculusTerrainProgram(program, uniforms, samplers, images, sources.framebuffer(),
                    sources.blendOverride(), sources.bufferBlendOverrides(), diagnostics);
        } finally {
            shaders.forEach(GlShader::delete);
            RenderDevice.exitManagedCode();
        }
    }

    static String adaptVertexSource(String source) {
        String adapted = source
                .replace("in uvec4 a_PosId;", "in vec3 a_PosId;")
                .replace("in ivec2 a_LightCoord;", "in uvec2 a_LightCoord;")
                .replace("_vert_tex_light_coord = a_LightCoord;",
                        "_vert_tex_light_coord = ivec2(a_LightCoord);")
                .replaceAll("_vert_position\\s*=\\s*\\(vec3\\(a_PosId\\.xyz\\)[^;]*;",
                        "_vert_position = a_PosId;")
                .replaceAll("_vert_tex_diffuse_coord\\s*=\\s*\\(a_TexCoord[^;]*;",
                        "_vert_tex_diffuse_coord = a_TexCoord;")
                .replaceAll("_draw_id\\s*=\\s*[^;]+;", "_draw_id = 0u;")
                .replaceAll("_material_params\\s*=\\s*[^;]+;", "_material_params = 3u;")
                .replace("vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);",
                        "vec3 translation = u_RegionOffset;")
                .replace("return vec4(_vert_position + u_RegionOffset + _get_draw_translation(_draw_id), 1.0f);",
                        "return vec4(_vert_position + u_RegionOffset, 1.0f);");
        if (adapted.contains("in uvec4 a_PosId;") || adapted.contains("vec3(a_PosId.xyz)")) {
            throw new IllegalArgumentException("Unsupported Oculus terrain vertex ABI");
        }
        return adapted;
    }

    private static void attach(GlProgram.Builder builder, List<GlShader> shaders, ShaderType type,
                               String stage, String source) {
        GlShader shader = new GlShader(type,
                ResourceLocation.fromNamespaceAndPath("voxy", "oculus_" + stage), source);
        shaders.add(shader);
        builder.attachShader(shader);
    }

    @Override
    public void bind(Matrix4f modelView, Matrix4f projection) {
        if (this.closed) throw new IllegalStateException("Cannot bind a closed Oculus Voxy program");
        this.previousFramebuffer = GL20C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            this.framebuffer.bind();
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Oculus Voxy framebuffer is incomplete: 0x"
                        + Integer.toHexString(status));
            }
            if (this.blendOverride != null) this.blendOverride.apply();
            this.bufferBlendOverrides.forEach(
                    net.irisshaders.iris.gl.blending.BufferBlendOverride::apply);
            this.program.bind();
            this.uniforms.update();
            this.samplers.update();
            this.images.update();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                uploadMatrix4(this.modelViewLocation, modelView, stack);
                uploadMatrix4(this.modelViewInverseLocation, new Matrix4f(modelView).invert(), stack);
                uploadMatrix4(this.projectionLocation, projection, stack);
                uploadMatrix4(this.projectionInverseLocation, new Matrix4f(projection).invert(), stack);
                if (this.normalMatrixLocation >= 0) {
                    Matrix3f normal = new Matrix3f(modelView).normal();
                    GL20C.glUniformMatrix3fv(this.normalMatrixLocation, false, normal.get(stack.mallocFloat(9)));
                }
            }
            if (this.regionOffsetLocation >= 0) {
                GL20C.glUniform3f(this.regionOffsetLocation,
                        this.regionOffsetX, this.regionOffsetY, this.regionOffsetZ);
            }
        } catch (RuntimeException | Error failure) {
            this.program.unbind();
            BlendModeOverride.restore();
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, this.previousFramebuffer);
            throw failure;
        }
    }

    @Override
    public void unbind() {
        this.program.unbind();
        BlendModeOverride.restore();
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, this.previousFramebuffer);
    }

    @Override
    public void close() {
        if (this.closed) return;
        RenderDevice.enterManagedCode();
        try {
            this.uniforms.removeListeners();
            this.samplers.removeListeners();
            this.program.delete();
            this.closed = true;
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    private static int firstUniform(int program, String... names) {
        for (String name : names) {
            int location = GL20C.glGetUniformLocation(program, name);
            if (location >= 0) return location;
        }
        return -1;
    }

    Diagnostics diagnostics() {
        return this.diagnostics;
    }

    void setRegionOffset(float x, float y, float z) {
        this.regionOffsetX = x;
        this.regionOffsetY = y;
        this.regionOffsetZ = z;
    }

    private static void uploadMatrix4(int location, Matrix4f matrix, MemoryStack stack) {
        if (location >= 0) GL20C.glUniformMatrix4fv(location, false, matrix.get(stack.mallocFloat(16)));
    }

    record Diagnostics(Pass pass, boolean geometry, boolean tessControl, boolean tessEvaluate,
                       int framebuffer, boolean blendOverride, int bufferBlendOverrides, int images) {
    }

    private record Sources(String vertex, Optional<String> geometry,
                           Optional<String> tessControl, Optional<String> tessEvaluate,
                           String fragment,
                           net.irisshaders.iris.gl.framebuffer.GlFramebuffer framebuffer,
                           BlendModeOverride blendOverride,
                           List<net.irisshaders.iris.gl.blending.BufferBlendOverride> bufferBlendOverrides) {
        static Sources from(SodiumTerrainPipeline pipeline, Pass pass) {
            if (pass == Pass.SHADOW) {
                return new Sources(required(pipeline.getShadowVertexShaderSource(), "shadow vertex"),
                        pipeline.getShadowGeometryShaderSource(), pipeline.getShadowTessControlShaderSource(),
                        pipeline.getShadowTessEvalShaderSource(),
                        required(pipeline.getShadowCutoutFragmentShaderSource()
                                .or(() -> pipeline.getShadowFragmentShaderSource()), "shadow fragment"),
                        pipeline.getShadowFramebuffer(), pipeline.getShadowBlendOverride(),
                        pipeline.getShadowBufferOverrides());
            }
            if (pass == Pass.TRANSLUCENT) {
                return new Sources(required(pipeline.getTranslucentVertexShaderSource(), "translucent vertex"),
                        pipeline.getTranslucentGeometryShaderSource(),
                        pipeline.getTranslucentTessControlShaderSource(),
                        pipeline.getTranslucentTessEvalShaderSource(),
                        required(pipeline.getTranslucentFragmentShaderSource(), "translucent fragment"),
                        pipeline.getTranslucentFramebuffer(), pipeline.getTranslucentBlendOverride(),
                        pipeline.getTranslucentBufferOverrides());
            }
            return new Sources(required(pipeline.getTerrainCutoutVertexShaderSource()
                            .or(() -> pipeline.getTerrainSolidVertexShaderSource()), "terrain vertex"),
                    pipeline.getTerrainCutoutGeometryShaderSource()
                            .or(() -> pipeline.getTerrainSolidGeometryShaderSource()),
                    pipeline.getTerrainCutoutTessControlShaderSource()
                            .or(() -> pipeline.getTerrainSolidTessControlShaderSource()),
                    pipeline.getTerrainCutoutTessEvalShaderSource()
                            .or(() -> pipeline.getTerrainSolidTessEvalShaderSource()),
                    required(pipeline.getTerrainCutoutFragmentShaderSource()
                            .or(() -> pipeline.getTerrainSolidFragmentShaderSource()), "terrain fragment"),
                    pipeline.getTerrainCutoutFramebuffer(), pipeline.getTerrainCutoutBlendOverride(),
                    pipeline.getTerrainCutoutBufferOverrides());
        }

        private static String required(Optional<String> source, String name) {
            return source.orElseThrow(() -> new IllegalStateException("Oculus did not provide " + name + " source"));
        }
    }
}
