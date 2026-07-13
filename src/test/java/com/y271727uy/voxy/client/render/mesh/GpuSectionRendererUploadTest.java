package com.y271727uy.voxy.client.render.mesh;

import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GpuSectionRendererUploadTest {
    @Test
    public void failedUploadPreservesOldEntryAndBytesAndClosesNewStreams() {
        long key = 7L;
        CacheEntry previous = new CacheEntry(40);
        Map<Long, CacheEntry> entries = new HashMap<>();
        entries.put(key, previous);
        TestBuffer opaque = new TestBuffer(false);
        TestBuffer translucent = new TestBuffer(true);
        GpuSectionRenderer.UploadTransaction<CacheEntry> transaction =
                new GpuSectionRenderer.UploadTransaction<>(entries, key, previous.bytes,
                        entry -> entry.bytes, ignored -> { });

        try (transaction) {
            transaction.own(opaque);
            transaction.own(translucent);
            throw new IllegalStateException("simulated stream creation failure");
        } catch (IllegalStateException expected) {
            assertEquals("simulated stream creation failure", expected.getMessage());
        }

        assertSame(previous, entries.get(key));
        assertEquals(40, transaction.bytes());
        assertTrue(opaque.closed);
        assertTrue(translucent.closed);
    }

    @Test
    public void oculusOnlySchedulesRetriesForTheNonShadowPass() {
        assertTrue(GpuSectionRenderer.shouldScheduleL13Retries(false));
        assertFalse(GpuSectionRenderer.shouldScheduleL13Retries(true));
    }

    @Test
    public void throwingTranslucentBufferDoesNotSkipOpaqueBufferClose() {
        TestBuffer translucent = new TestBuffer(true);
        TestBuffer opaque = new TestBuffer(false);

        Throwable failure = GpuSectionRenderer.closeAllResources(translucent, opaque);

        assertTrue(translucent.closed);
        assertTrue(opaque.closed);
        assertEquals("close failed", failure.getMessage());
    }

    @Test
    public void throwingSectionDoesNotPreventLaterSectionsFromClosing() {
        TestBuffer firstSection = new TestBuffer(true);
        TestBuffer secondSection = new TestBuffer(false);
        TestBuffer thirdSection = new TestBuffer(false);

        Throwable failure = GpuSectionRenderer.closeAllResources(
                firstSection, secondSection, thirdSection);

        assertTrue(firstSection.closed);
        assertTrue(secondSection.closed);
        assertTrue(thirdSection.closed);
        assertEquals("close failed", failure.getMessage());
    }

    private static final class CacheEntry {
        private final long bytes;

        private CacheEntry(long bytes) {
            this.bytes = bytes;
        }
    }

    private static final class TestBuffer implements GpuSectionRenderer.SectionBuffer {
        private final boolean failOnClose;
        private boolean closed;

        private TestBuffer(boolean failOnClose) {
            this.failOnClose = failOnClose;
        }

        @Override public long byteSize() { return 1; }

        @Override
        public void draw(Matrix4f modelView, Matrix4f projection,
                         VoxyGeometryProgram terrainProgram, ShaderInstance fallbackShader) {
        }

        @Override
        public void close() {
            this.closed = true;
            if (this.failOnClose) throw new IllegalStateException("close failed");
        }
    }
}
