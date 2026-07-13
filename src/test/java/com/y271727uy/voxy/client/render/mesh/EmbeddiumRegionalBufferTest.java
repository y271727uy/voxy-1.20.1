package com.y271727uy.voxy.client.render.mesh;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EmbeddiumRegionalBufferTest {
    @Test
    public void regionKeysUseFloorDivisionAcrossSignedBoundaries() {
        assertRegion(0, 0, 0, 0, 0, 0);
        assertRegion(511, 511, 511, 0, 0, 0);
        assertRegion(512, 0, 0, 1, 0, 0);
        assertRegion(-1, -1, -1, -1, -1, -1);
        assertRegion(-512, 0, 0, -1, 0, 0);
        assertRegion(-513, 1024, -1025, -2, 2, -3);
    }

    private static void assertRegion(int blockX, int blockY, int blockZ,
                                     int regionX, int regionY, int regionZ) {
        EmbeddiumRegionalBuffer.RegionKey key =
                EmbeddiumRegionalBuffer.regionKey(blockX, blockY, blockZ);
        assertEquals(regionX, key.x());
        assertEquals(regionY, key.y());
        assertEquals(regionZ, key.z());
        assertEquals(regionX * EmbeddiumRegionalBuffer.REGION_SIZE, key.originX());
        assertEquals(regionY * EmbeddiumRegionalBuffer.REGION_SIZE, key.originY());
        assertEquals(regionZ * EmbeddiumRegionalBuffer.REGION_SIZE, key.originZ());
    }
}
