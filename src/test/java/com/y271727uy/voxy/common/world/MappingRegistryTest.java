package com.y271727uy.voxy.common.world;

import com.y271727uy.voxy.common.voxelization.VoxelMipper;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

public class MappingRegistryTest {
    @Test
    public void storageKeysKeepTypeAndDenseIdIndependent() {
        int blockKey = MappingRegistry.storageKey(MappingRegistry.BLOCK_STATE_TYPE, 1234);
        int biomeKey = MappingRegistry.storageKey(MappingRegistry.BIOME_TYPE, 1234);
        assertEquals(MappingRegistry.BLOCK_STATE_TYPE, MappingRegistry.storageType(blockKey));
        assertEquals(MappingRegistry.BIOME_TYPE, MappingRegistry.storageType(biomeKey));
        assertEquals(1234, MappingRegistry.storageId(blockKey));
        assertEquals(1234, MappingRegistry.storageId(biomeKey));
    }

    @Test
    public void fluidKeyNormalizesFlowingFluidToSource() throws ReflectiveOperationException {
        // Full Forge bootstrap initializes a broken NetworkEvent path in plain JUnit. Temporarily lifting
        // vanilla's guard is enough to initialize the real built-in fluid registry used by production.
        Field bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        boolean previous = bootstrapped.getBoolean(null);
        bootstrapped.setBoolean(null, true);
        try {
            assertEquals(MappingRegistry.fluidKey(Fluids.WATER),
                    MappingRegistry.fluidKey(Fluids.FLOWING_WATER));
            assertEquals(VoxelMipper.StateResolver.NO_FLUID, MappingRegistry.fluidKey(Fluids.EMPTY));
        } finally {
            bootstrapped.setBoolean(null, previous);
        }
    }
}
