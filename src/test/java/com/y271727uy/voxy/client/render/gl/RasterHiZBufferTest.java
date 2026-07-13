package com.y271727uy.voxy.client.render.gl;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RasterHiZBufferTest {
    @Test
    public void buildsEveryRasterLevelAndRestoresExactGlState() {
        FakeGl gl = new FakeGl();
        RasterHiZBuffer.GlState original = gl.state;
        RasterHiZBuffer buffer = new RasterHiZBuffer(gl);

        buffer.build(91, 1920, 720);

        assertEquals(List.of("1024x512", "512x256", "256x128", "128x64", "64x32",
                "32x16", "16x8", "8x4", "4x2", "2x1"), gl.viewports);
        assertEquals(List.of(91, 5, 5, 5, 5, 5, 5, 5, 5, 5), gl.boundTextures);
        assertEquals(10, gl.draws);
        assertEquals(10, gl.barriers);
        assertTrue(gl.events.contains("raster:false:false"));
        assertTrue(gl.events.indexOf("draw") < gl.events.indexOf("range:0"));
        assertTrue(gl.events.indexOf("range:0") < gl.events.indexOf("bind:5"));
        assertEquals(original, gl.state);
        assertEquals(5, buffer.textureId());
        assertEquals(10, buffer.mipCount());
        assertEquals(1024, HiZLayoutPlanner.unpackWidth(buffer.packedDimensions()));
        assertEquals(512, HiZLayoutPlanner.unpackHeight(buffer.packedDimensions()));
    }

    @Test
    public void resizeReusesMatchingAllocationAndDeletesReplacedTexture() {
        FakeGl gl = new FakeGl();
        RasterHiZBuffer buffer = new RasterHiZBuffer(gl);

        buffer.resize(1000, 700);
        buffer.resize(800, 600);
        assertEquals(1, gl.createdTextures);
        buffer.resize(1920, 1080);

        assertEquals(2, gl.createdTextures);
        assertEquals(List.of(5), gl.deletedTextures);
        assertEquals(6, buffer.textureId());
    }

    @Test
    public void drawFailureRestoresStateAndPermanentlyDisablesResource() {
        FakeGl gl = new FakeGl();
        RasterHiZBuffer.GlState original = gl.state;
        gl.failDraw = true;
        RasterHiZBuffer buffer = new RasterHiZBuffer(gl);

        assertThrows(IllegalStateException.class, () -> buffer.build(91, 1280, 720));
        assertEquals(original, gl.state);
        assertThrows(IllegalStateException.class, () -> buffer.build(91, 1280, 720));
    }

    @Test
    public void restoreFailureIsSuppressedBehindOriginalDrawFailure() {
        FakeGl gl = new FakeGl();
        gl.failDraw = true;
        gl.failRestore = true;
        RasterHiZBuffer buffer = new RasterHiZBuffer(gl);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> buffer.build(91, 1280, 720));

        assertEquals("draw failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("restore failure", failure.getSuppressed()[0].getMessage());
    }

    @Test
    public void constructorAndResizeFailuresDeletePartialResources() {
        FakeGl constructorGl = new FakeGl();
        constructorGl.failConfigureSampler = true;
        assertThrows(IllegalStateException.class, () -> new RasterHiZBuffer(constructorGl));
        assertEquals(List.of(4), constructorGl.deletedPrograms);
        assertEquals(List.of(3), constructorGl.deletedVertexArrays);
        assertEquals(List.of(2), constructorGl.deletedSamplers);
        assertEquals(List.of(1), constructorGl.deletedFramebuffers);

        FakeGl resizeGl = new FakeGl();
        RasterHiZBuffer buffer = new RasterHiZBuffer(resizeGl);
        resizeGl.failVerify = true;
        assertThrows(IllegalStateException.class, () -> buffer.resize(640, 480));
        assertEquals(List.of(5), resizeGl.deletedTextures);
        assertThrows(IllegalStateException.class, () -> buffer.resize(640, 480));
    }

    @Test
    public void closeDeletesAllOwnedObjectsOnce() {
        FakeGl gl = new FakeGl();
        RasterHiZBuffer buffer = new RasterHiZBuffer(gl);
        buffer.resize(640, 480);

        buffer.close();
        buffer.close();

        assertEquals(List.of(5), gl.deletedTextures);
        assertEquals(List.of(4), gl.deletedPrograms);
        assertEquals(List.of(3), gl.deletedVertexArrays);
        assertEquals(List.of(2), gl.deletedSamplers);
        assertEquals(List.of(1), gl.deletedFramebuffers);
        assertThrows(IllegalStateException.class, buffer::textureId);
    }

    private static final class FakeGl implements RasterHiZBuffer.GlApi {
        private int nextId = 1;
        private int createdTextures;
        private int draws;
        private int barriers;
        private boolean failDraw;
        private boolean failVerify;
        private boolean failConfigureSampler;
        private boolean failRestore;
        private RasterHiZBuffer.GlState state = new RasterHiZBuffer.GlState(
                31, 7, 9, 811, 613, 32, 33, 0x84C3, 34, 35,
                false, 0x0203, false, true, 2, 3, 400, 300, true, 0x0404);
        private final List<String> viewports = new ArrayList<>();
        private final List<Integer> boundTextures = new ArrayList<>();
        private final List<Integer> deletedTextures = new ArrayList<>();
        private final List<Integer> deletedPrograms = new ArrayList<>();
        private final List<Integer> deletedVertexArrays = new ArrayList<>();
        private final List<Integer> deletedSamplers = new ArrayList<>();
        private final List<Integer> deletedFramebuffers = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        @Override public int createFramebuffer() { return nextId++; }
        @Override public int createSampler() { return nextId++; }
        @Override public int createVertexArray() { return nextId++; }
        @Override public int createProgram() { return nextId++; }
        @Override public int createDepthTexture(int width, int height, int mipCount) {
            createdTextures++;
            return nextId++;
        }
        @Override public void configureFramebuffer(int framebuffer) { }
        @Override public void configureSampler(int sampler) {
            if (failConfigureSampler) throw new IllegalStateException("sampler failure");
        }
        @Override public void attachDepth(int framebuffer, int texture, int mip) { }
        @Override public void verifyFramebuffer(int framebuffer) {
            if (failVerify) throw new IllegalStateException("incomplete framebuffer");
        }
        @Override public RasterHiZBuffer.GlState captureState() { return state; }
        @Override public void restoreState(RasterHiZBuffer.GlState state) {
            if (failRestore) throw new IllegalStateException("restore failure");
            this.state = state;
        }
        @Override public void bindFramebuffer(int framebuffer) { mutate(framebuffer, state.vertexArray(), state.program()); }
        @Override public void bindVertexArray(int vertexArray) { mutate(state.drawFramebuffer(), vertexArray, state.program()); }
        @Override public void useProgram(int program) { mutate(state.drawFramebuffer(), state.vertexArray(), program); }
        private void mutate(int framebuffer, int vao, int program) {
            state = new RasterHiZBuffer.GlState(framebuffer, 0, 0, 1, 1, vao, program,
                    state.activeTexture(), state.texture0(), state.sampler0(), true, 0x0207, true,
                    state.scissorTest(), state.scissorX(), state.scissorY(), state.scissorWidth(),
                    state.scissorHeight(), state.cullFace(), state.cullMode());
        }
        @Override public void setDepthState(boolean enabled, int function, boolean mask) { }
        @Override public void setRasterState(boolean scissorTest, boolean cullFace, int cullMode) {
            state = new RasterHiZBuffer.GlState(state.drawFramebuffer(), state.viewportX(),
                    state.viewportY(), state.viewportWidth(), state.viewportHeight(), state.vertexArray(),
                    state.program(), state.activeTexture(), state.texture0(), state.sampler0(),
                    state.depthTest(), state.depthFunc(), state.depthMask(), scissorTest,
                    state.scissorX(), state.scissorY(), state.scissorWidth(), state.scissorHeight(),
                    cullFace, cullMode);
            events.add("raster:" + scissorTest + ':' + cullFace);
        }
        @Override public void bindTexture0(int texture) {
            boundTextures.add(texture);
            events.add("bind:" + texture);
        }
        @Override public void bindSampler0(int sampler) { }
        @Override public void setTextureMipRange(int texture, int base, int max) {
            events.add("range:" + base);
        }
        @Override public void viewport(int width, int height) { viewports.add(width + "x" + height); }
        @Override public void drawFullscreenQuad() {
            if (failDraw) throw new IllegalStateException("draw failure");
            draws++;
            events.add("draw");
        }
        @Override public void textureAndFramebufferBarrier() { barriers++; }
        @Override public void deleteTexture(int texture) { deletedTextures.add(texture); }
        @Override public void deleteProgram(int program) { deletedPrograms.add(program); }
        @Override public void deleteVertexArray(int vertexArray) { deletedVertexArrays.add(vertexArray); }
        @Override public void deleteSampler(int sampler) { deletedSamplers.add(sampler); }
        @Override public void deleteFramebuffer(int framebuffer) { deletedFramebuffers.add(framebuffer); }
    }
}
