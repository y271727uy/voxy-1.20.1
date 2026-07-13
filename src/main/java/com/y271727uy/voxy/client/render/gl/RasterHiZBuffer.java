package com.y271727uy.voxy.client.render.gl;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL45C;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Owns the raster depth pyramid used by the production hierarchical traversal shader. */
public final class RasterHiZBuffer implements AutoCloseable {
    private final GlApi gl;
    private final int framebuffer;
    private final int sampler;
    private final int vertexArray;
    private final int program;
    private int texture;
    private HiZLayoutPlanner.Layout layout;
    private boolean failed;
    private boolean closed;

    public RasterHiZBuffer() {
        this(new LwjglGlApi());
    }

    RasterHiZBuffer(GlApi gl) {
        this.gl = Objects.requireNonNull(gl, "gl");
        int newFramebuffer = 0;
        int newSampler = 0;
        int newVertexArray = 0;
        int newProgram = 0;
        try {
            newFramebuffer = gl.createFramebuffer();
            newSampler = gl.createSampler();
            newVertexArray = gl.createVertexArray();
            newProgram = gl.createProgram();
            gl.configureFramebuffer(newFramebuffer);
            gl.configureSampler(newSampler);
        } catch (RuntimeException | LinkageError failure) {
            deleteConstructed(gl, newProgram, newVertexArray, newSampler, newFramebuffer);
            throw failure;
        }
        this.framebuffer = newFramebuffer;
        this.sampler = newSampler;
        this.vertexArray = newVertexArray;
        this.program = newProgram;
    }

    public void resize(int viewportWidth, int viewportHeight) {
        requireUsable();
        HiZLayoutPlanner.Layout nextLayout = HiZLayoutPlanner.plan(viewportWidth, viewportHeight);
        if (this.layout != null && this.layout.width() == nextLayout.width()
                && this.layout.height() == nextLayout.height()) {
            this.layout = nextLayout;
            return;
        }

        int nextTexture = 0;
        try {
            nextTexture = this.gl.createDepthTexture(nextLayout.width(), nextLayout.height(),
                    nextLayout.mipCount());
            this.gl.attachDepth(this.framebuffer, nextTexture, 0);
            this.gl.verifyFramebuffer(this.framebuffer);
        } catch (RuntimeException | LinkageError failure) {
            if (nextTexture != 0) this.gl.deleteTexture(nextTexture);
            this.failed = true;
            throw failure;
        }

        int oldTexture = this.texture;
        this.texture = nextTexture;
        this.layout = nextLayout;
        if (oldTexture != 0) this.gl.deleteTexture(oldTexture);
    }

    public void build(int sourceDepthTexture, int viewportWidth, int viewportHeight) {
        requireUsable();
        if (sourceDepthTexture <= 0) {
            throw new IllegalArgumentException("sourceDepthTexture must be a live OpenGL texture");
        }
        resize(viewportWidth, viewportHeight);
        GlState state = this.gl.captureState();
        Throwable failure = null;
        try {
            this.gl.bindFramebuffer(this.framebuffer);
            this.gl.bindVertexArray(this.vertexArray);
            this.gl.useProgram(this.program);
            this.gl.setDepthState(true, GL11C.GL_ALWAYS, true);
            this.gl.setRasterState(false, false, GL11C.GL_BACK);
            this.gl.bindSampler0(this.sampler);

            int inputTexture = sourceDepthTexture;
            for (HiZLayoutPlanner.Level level : this.layout.levels()) {
                this.gl.attachDepth(this.framebuffer, this.texture, level.mip());
                this.gl.verifyFramebuffer(this.framebuffer);
                this.gl.viewport(level.width(), level.height());
                this.gl.bindTexture0(inputTexture);
                this.gl.drawFullscreenQuad();
                this.gl.textureAndFramebufferBarrier();
                // The level just written becomes the source selected by texture() next iteration.
                this.gl.setTextureMipRange(this.texture, level.mip(), level.mip());
                inputTexture = this.texture;
            }
            this.gl.setTextureMipRange(this.texture, 0, this.layout.mipCount() - 1);
        } catch (RuntimeException | LinkageError buildFailure) {
            this.failed = true;
            failure = buildFailure;
        }
        try {
            this.gl.restoreState(state);
        } catch (RuntimeException | LinkageError restoreFailure) {
            this.failed = true;
            if (failure == null) failure = restoreFailure;
            else failure.addSuppressed(restoreFailure);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof LinkageError linkage) throw linkage;
    }

    public int textureId() {
        requireUsable();
        if (this.texture == 0) throw new IllegalStateException("Hi-Z texture has not been allocated");
        return this.texture;
    }

    /** Sampler configured for exact depth-pyramid texel fetches. */
    public int samplerId() {
        requireUsable();
        return this.sampler;
    }

    public int packedDimensions() {
        requireUsable();
        if (this.layout == null) throw new IllegalStateException("Hi-Z texture has not been allocated");
        return this.layout.packedDimensions();
    }

    public int mipCount() {
        requireUsable();
        return this.layout == null ? 0 : this.layout.mipCount();
    }

    private void requireUsable() {
        if (this.closed) throw new IllegalStateException("Raster Hi-Z buffer is closed");
        if (this.failed) throw new IllegalStateException("Raster Hi-Z buffer was disabled after an OpenGL failure");
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        Throwable failure = null;
        if (this.texture != 0) failure = delete(failure, () -> this.gl.deleteTexture(this.texture));
        failure = delete(failure, () -> this.gl.deleteProgram(this.program));
        failure = delete(failure, () -> this.gl.deleteVertexArray(this.vertexArray));
        failure = delete(failure, () -> this.gl.deleteSampler(this.sampler));
        failure = delete(failure, () -> this.gl.deleteFramebuffer(this.framebuffer));
        this.texture = 0;
        this.layout = null;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof LinkageError linkage) throw linkage;
    }

    private static void deleteConstructed(GlApi gl, int program, int vao, int sampler, int framebuffer) {
        if (program != 0) gl.deleteProgram(program);
        if (vao != 0) gl.deleteVertexArray(vao);
        if (sampler != 0) gl.deleteSampler(sampler);
        if (framebuffer != 0) gl.deleteFramebuffer(framebuffer);
    }

    private static Throwable delete(Throwable first, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException | LinkageError failure) {
            if (first == null) return failure;
            first.addSuppressed(failure);
        }
        return first;
    }

    record GlState(int drawFramebuffer, int viewportX, int viewportY, int viewportWidth,
                   int viewportHeight, int vertexArray, int program, int activeTexture,
                   int texture0, int sampler0, boolean depthTest, int depthFunc,
                   boolean depthMask, boolean scissorTest, int scissorX, int scissorY,
                   int scissorWidth, int scissorHeight, boolean cullFace, int cullMode) {
    }

    interface GlApi {
        int createFramebuffer();
        int createSampler();
        int createVertexArray();
        int createProgram();
        int createDepthTexture(int width, int height, int mipCount);
        void configureFramebuffer(int framebuffer);
        void configureSampler(int sampler);
        void attachDepth(int framebuffer, int texture, int mip);
        void verifyFramebuffer(int framebuffer);
        GlState captureState();
        void restoreState(GlState state);
        void bindFramebuffer(int framebuffer);
        void bindVertexArray(int vertexArray);
        void useProgram(int program);
        void setDepthState(boolean enabled, int function, boolean mask);
        void setRasterState(boolean scissorTest, boolean cullFace, int cullMode);
        void bindTexture0(int texture);
        void bindSampler0(int sampler);
        void setTextureMipRange(int texture, int base, int max);
        void viewport(int width, int height);
        void drawFullscreenQuad();
        void textureAndFramebufferBarrier();
        void deleteTexture(int texture);
        void deleteProgram(int program);
        void deleteVertexArray(int vertexArray);
        void deleteSampler(int sampler);
        void deleteFramebuffer(int framebuffer);
    }

    private static final class LwjglGlApi implements GlApi {
        private static final String VERTEX_SHADER = "/assets/voxy/shaders/hiz/blit.vsh";
        private static final String FRAGMENT_SHADER = "/assets/voxy/shaders/hiz/blit.fsh";

        @Override public int createFramebuffer() { return GL45C.glCreateFramebuffers(); }
        @Override public int createSampler() { return GL33C.glGenSamplers(); }
        @Override public int createVertexArray() { return GL45C.glCreateVertexArrays(); }

        @Override
        public int createProgram() {
            int vertex = compile(GL20C.GL_VERTEX_SHADER, load(VERTEX_SHADER));
            int fragment = 0;
            int program = 0;
            try {
                fragment = compile(GL20C.GL_FRAGMENT_SHADER, load(FRAGMENT_SHADER));
                program = GL20C.glCreateProgram();
                GL20C.glAttachShader(program, vertex);
                GL20C.glAttachShader(program, fragment);
                GL20C.glLinkProgram(program);
                if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                    throw new IllegalStateException("Could not link raster Hi-Z program: "
                            + GL20C.glGetProgramInfoLog(program));
                }
                return program;
            } catch (RuntimeException | LinkageError failure) {
                if (program != 0) GL20C.glDeleteProgram(program);
                throw failure;
            } finally {
                if (fragment != 0) GL20C.glDeleteShader(fragment);
                GL20C.glDeleteShader(vertex);
            }
        }

        @Override
        public int createDepthTexture(int width, int height, int mipCount) {
            int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            try {
                GL45C.glTextureStorage2D(texture, mipCount, GL30C.GL_DEPTH24_STENCIL8, width, height);
                GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_NEAREST);
                GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                GL45C.glTextureParameteri(texture, GL14Compat.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
                GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_WRAP_S, GL12Compat.GL_CLAMP_TO_EDGE);
                GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_WRAP_T, GL12Compat.GL_CLAMP_TO_EDGE);
                return texture;
            } catch (RuntimeException | LinkageError failure) {
                GL11C.glDeleteTextures(texture);
                throw failure;
            }
        }

        @Override public void configureFramebuffer(int framebuffer) {
            GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL11C.GL_NONE);
            GL45C.glNamedFramebufferReadBuffer(framebuffer, GL11C.GL_NONE);
        }

        @Override public void configureSampler(int sampler) {
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_NEAREST);
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL33C.glSamplerParameteri(sampler, GL14Compat.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_S, GL12Compat.GL_CLAMP_TO_EDGE);
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_T, GL12Compat.GL_CLAMP_TO_EDGE);
        }

        @Override public void attachDepth(int framebuffer, int texture, int mip) {
            GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_DEPTH_ATTACHMENT, texture, mip);
        }

        @Override public void verifyFramebuffer(int framebuffer) {
            int status = GL45C.glCheckNamedFramebufferStatus(framebuffer, GL30C.GL_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Raster Hi-Z framebuffer is incomplete: 0x"
                        + Integer.toHexString(status));
            }
        }

        @Override public GlState captureState() {
            int[] viewport = new int[4];
            int[] scissor = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissor);
            int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            int texture0 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL13C.glActiveTexture(activeTexture);
            return new GlState(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                    viewport[0], viewport[1], viewport[2], viewport[3],
                    GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),
                    GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), activeTexture, texture0,
                    GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, 0),
                    GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                    GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC),
                    GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                    GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                    scissor[0], scissor[1], scissor[2], scissor[3],
                    GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                    GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE));
        }

        @Override public void restoreState(GlState state) {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, state.drawFramebuffer());
            GL11C.glViewport(state.viewportX(), state.viewportY(), state.viewportWidth(), state.viewportHeight());
            GL11C.glScissor(state.scissorX(), state.scissorY(), state.scissorWidth(), state.scissorHeight());
            GL30C.glBindVertexArray(state.vertexArray());
            GL20C.glUseProgram(state.program());
            GL45C.glBindTextureUnit(0, state.texture0());
            GL33C.glBindSampler(0, state.sampler0());
            if (state.depthTest()) GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(state.depthFunc());
            GL11C.glDepthMask(state.depthMask());
            if (state.scissorTest()) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            else GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glCullFace(state.cullMode());
            if (state.cullFace()) GL11C.glEnable(GL11C.GL_CULL_FACE);
            else GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL13C.glActiveTexture(state.activeTexture());
        }

        @Override public void bindFramebuffer(int framebuffer) { GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer); }
        @Override public void bindVertexArray(int vertexArray) { GL30C.glBindVertexArray(vertexArray); }
        @Override public void useProgram(int program) { GL20C.glUseProgram(program); }
        @Override public void setDepthState(boolean enabled, int function, boolean mask) {
            if (enabled) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(function);
            GL11C.glDepthMask(mask);
        }
        @Override public void setRasterState(boolean scissorTest, boolean cullFace, int cullMode) {
            if (scissorTest) GL11C.glEnable(GL11C.GL_SCISSOR_TEST); else GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glCullFace(cullMode);
            if (cullFace) GL11C.glEnable(GL11C.GL_CULL_FACE); else GL11C.glDisable(GL11C.GL_CULL_FACE);
        }
        @Override public void bindTexture0(int texture) { GL45C.glBindTextureUnit(0, texture); }
        @Override public void bindSampler0(int sampler) { GL33C.glBindSampler(0, sampler); }
        @Override public void setTextureMipRange(int texture, int base, int max) {
            GL45C.glTextureParameteri(texture, GL12Compat.GL_TEXTURE_BASE_LEVEL, base);
            GL45C.glTextureParameteri(texture, GL12Compat.GL_TEXTURE_MAX_LEVEL, max);
        }
        @Override public void viewport(int width, int height) { GL11C.glViewport(0, 0, width, height); }
        @Override public void drawFullscreenQuad() { GL11C.glDrawArrays(GL11C.GL_TRIANGLE_FAN, 0, 4); }
        @Override public void textureAndFramebufferBarrier() {
            GL45C.glTextureBarrier();
            GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
        }
        @Override public void deleteTexture(int texture) { GL11C.glDeleteTextures(texture); }
        @Override public void deleteProgram(int program) { GL20C.glDeleteProgram(program); }
        @Override public void deleteVertexArray(int vertexArray) { GL30C.glDeleteVertexArrays(vertexArray); }
        @Override public void deleteSampler(int sampler) { GL33C.glDeleteSamplers(sampler); }
        @Override public void deleteFramebuffer(int framebuffer) { GL30C.glDeleteFramebuffers(framebuffer); }

        private static int compile(int type, String source) {
            int shader = GL20C.glCreateShader(type);
            GL20C.glShaderSource(shader, source);
            GL20C.glCompileShader(shader);
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
                String log = GL20C.glGetShaderInfoLog(shader);
                GL20C.glDeleteShader(shader);
                throw new IllegalStateException("Could not compile raster Hi-Z shader: " + log);
            }
            return shader;
        }

        private static String load(String path) {
            try (InputStream stream = RasterHiZBuffer.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing raster Hi-Z shader " + path);
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read raster Hi-Z shader " + path, exception);
            }
        }
    }

    // Constants live in older LWJGL classes; aliases keep the production adapter readable.
    private static final class GL12Compat {
        private static final int GL_CLAMP_TO_EDGE = 0x812F;
        private static final int GL_TEXTURE_BASE_LEVEL = 0x813C;
        private static final int GL_TEXTURE_MAX_LEVEL = 0x813D;
    }

    private static final class GL14Compat {
        private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;
    }
}
