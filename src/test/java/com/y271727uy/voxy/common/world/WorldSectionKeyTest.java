package com.y271727uy.voxy.common.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorldSectionKeyTest {
    @Test
    public void signedCoordinatesRoundTrip() {
        long key = WorldSectionKey.pack(4, -8_388_608, -128, 8_388_607);
        assertEquals(4, WorldSectionKey.level(key));
        assertEquals(-8_388_608, WorldSectionKey.x(key));
        assertEquals(-128, WorldSectionKey.y(key));
        assertEquals(8_388_607, WorldSectionKey.z(key));
    }

    @Test
    public void packabilityRejectsNeighborOverflow() {
        assertTrue(WorldSectionKey.canPack(4, WorldSectionKey.MAX_XZ, WorldSectionKey.MAX_Y,
                WorldSectionKey.MIN_XZ));
        assertFalse(WorldSectionKey.canPack(4, WorldSectionKey.MAX_XZ + 1, 0, 0));
        assertFalse(WorldSectionKey.canPack(4, 0, WorldSectionKey.MIN_Y - 1, 0));
    }
}
