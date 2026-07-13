package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.client.model.BakedBlockModel;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class EmbeddiumIndexedBufferTest {
    @Test
    public void encodesVanillaShaderLayoutAndIndexedTriangles() {
        BakedBlockModel.Quad opaque = quad(BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT);
        BakedBlockModel.Quad translucent = quad(BakedBlockModel.MaterialPass.TRANSLUCENT);
        LodSectionMesh mesh = new LodSectionMesh(1L, 2L, List.of(
                new LodSectionMesh.QuadInstance(1, 0L, 40, 64, 96, 2,
                        0xA3, 0xFFFFFFFF, -1, opaque),
                new LodSectionMesh.QuadInstance(1, 0L, 40, 64, 96, 2,
                        0xA3, 0xFFFFFFFF, -1, translucent)));

        try (EmbeddiumIndexedBuffer.EncodedMesh encoded =
                     EmbeddiumIndexedBuffer.encode(mesh, 32, 64, 96)) {
            ByteBuffer vertices = encoded.vertices();
            assertEquals(4 * EmbeddiumIndexedBuffer.VERTEX_STRIDE, vertices.remaining());
            assertEquals(6 * Integer.BYTES, encoded.indices().remaining());
            assertEquals(6, encoded.indexCount());

            assertEquals(10.0F, vertices.getFloat(0), 0.0001F);
            assertEquals(4.0F, vertices.getFloat(4), 0.0001F);
            assertEquals(6.0F, vertices.getFloat(8), 0.0001F);
            assertEquals(19, Byte.toUnsignedInt(vertices.get(12)));
            assertEquals(38, Byte.toUnsignedInt(vertices.get(13)));
            assertEquals(77, Byte.toUnsignedInt(vertices.get(14)));
            assertEquals(255, Byte.toUnsignedInt(vertices.get(15)));
            assertEquals(0.25F, vertices.getFloat(16), 0.0001F);
            assertEquals(0.75F, vertices.getFloat(20), 0.0001F);
            assertEquals(160, Short.toUnsignedInt(vertices.getShort(24)));
            assertEquals(48, Short.toUnsignedInt(vertices.getShort(26)));

            int[] expected = {0, 1, 2, 2, 3, 0};
            for (int index = 0; index < expected.length; index++) {
                assertEquals(expected[index], encoded.indices().getInt(index * Integer.BYTES));
            }
        }
    }

    @Test
    public void translucentQuadsAreEncodedBackToFront() {
        BakedBlockModel.Quad translucent = quad(BakedBlockModel.MaterialPass.TRANSLUCENT);
        LodSectionMesh mesh = new LodSectionMesh(1L, 2L, List.of(
                new LodSectionMesh.QuadInstance(1, 0L, 10, 0, 0, 2,
                        0xFF, 0xFFFFFFFF, -1, translucent),
                new LodSectionMesh.QuadInstance(1, 0L, 100, 0, 0, 2,
                        0xFF, 0xFFFFFFFF, -1, translucent)));

        try (EmbeddiumIndexedBuffer.EncodedMesh encoded = EmbeddiumIndexedBuffer.encode(mesh,
                0, 0, 0, BakedBlockModel.MaterialPass.TRANSLUCENT, new Vec3(0, 0, 0))) {
            assertEquals(12, encoded.indexCount());
            assertEquals(102.0F, encoded.vertices().getFloat(0), 0.0001F);
            assertEquals(12.0F, encoded.vertices().getFloat(4 * EmbeddiumIndexedBuffer.VERTEX_STRIDE),
                    0.0001F);
        }
    }

    @Test
    public void multipliesSourceAndTintAlpha() {
        BakedBlockModel.Quad translucent = quad(BakedBlockModel.MaterialPass.TRANSLUCENT, 0x80FFFFFF);
        LodSectionMesh mesh = new LodSectionMesh(1L, 2L, List.of(
                new LodSectionMesh.QuadInstance(1, 0L, 0, 0, 0, 1,
                        0xFF, 0x80FFFFFF, -1, translucent)));

        try (EmbeddiumIndexedBuffer.EncodedMesh encoded = EmbeddiumIndexedBuffer.encode(mesh,
                0, 0, 0, BakedBlockModel.MaterialPass.TRANSLUCENT, Vec3.ZERO)) {
            assertEquals(64, Byte.toUnsignedInt(encoded.vertices().get(15)));
        }
    }

    private static BakedBlockModel.Quad quad(BakedBlockModel.MaterialPass pass) {
        return quad(pass, 0xFF804020);
    }

    private static BakedBlockModel.Quad quad(BakedBlockModel.MaterialPass pass, int color) {
        int[] vertices = new int[32];
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * 8;
            vertices[base] = Float.floatToRawIntBits(1.0F);
            vertices[base + 1] = Float.floatToRawIntBits(2.0F);
            vertices[base + 2] = Float.floatToRawIntBits(3.0F);
            vertices[base + 3] = color;
            vertices[base + 4] = Float.floatToRawIntBits(0.25F);
            vertices[base + 5] = Float.floatToRawIntBits(0.75F);
        }
        return new BakedBlockModel.Quad(vertices, -1, Direction.EAST, null, "test", true, true,
                pass, false);
    }
}
