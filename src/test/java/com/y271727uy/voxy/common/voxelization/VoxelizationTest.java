package com.y271727uy.voxy.common.voxelization;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VoxelizationTest {
    private static final VoxelMipper.StateResolver TEST_RESOLVER = new VoxelMipper.StateResolver() {
        @Override
        public int opacity(int blockId) {
            return blockId == 1 ? 15 : 1;
        }

        @Override
        public boolean hasFluid(int blockId) {
            return blockId >= 2 && blockId <= 7;
        }

        @Override
        public boolean isPureFluid(int blockId) {
            return blockId >= 2 && blockId <= 5;
        }

        @Override
        public int fluidKey(int blockId) {
            return hasFluid(blockId) ? 42 : NO_FLUID;
        }
    };

    @Test
    public void packedVoxelRoundTrips() {
        long voxel = PackedVoxel.compose(0xAF, 12345, 321);
        assertEquals(0xAF, PackedVoxel.light(voxel));
        assertEquals(12345, PackedVoxel.blockId(voxel));
        assertEquals(321, PackedVoxel.biomeId(voxel));
    }

    @Test
    public void packedLightingUsesMinecraftNibbleOrder() {
        byte[] block = new byte[2048];
        byte[] sky = new byte[2048];
        block[0] = (byte) 0xA3;
        sky[0] = (byte) 0x5C;
        LightingSupplier lighting = new ArrayLightingSupplier(block, sky);
        assertEquals(0x3C, lighting.light(0, 0, 0));
        assertEquals(0xA5, lighting.light(1, 0, 0));
    }

    @Test
    public void mipChainDoesNotPromoteSparseSolidVoxel() {
        VoxelizedSection section = VoxelizedSection.empty();
        long solid = PackedVoxel.compose(0xFF, 1, 1);
        section.setBase(15, 15, 15, solid);
        SectionMipper.generateMipLevels(section, blockId -> 15);
        assertTrue(PackedVoxel.isAir(section.get(4, 0, 0, 0)));
        assertTrue(section.get(0, 0, 0, 0) == PackedVoxel.AIR);
    }

    @Test
    public void mipPreservesVisibleFluidSurfaceOverOpaqueBed() {
        long stone = PackedVoxel.compose(0x00, 1, 2);
        long water = PackedVoxel.compose(0xAF, 2, 3);
        long[] children = {stone, stone, stone, stone, water, water, water, water};

        long parent = VoxelMipper.mip(children, TEST_RESOLVER);

        assertEquals(2, PackedVoxel.blockId(parent));
        assertEquals(3, PackedVoxel.biomeId(parent));
        assertEquals(0xAF, PackedVoxel.light(parent));
    }

    @Test
    public void mipDoesNotPromoteMinorityFluidThroughOpaqueSurface() {
        long stone = PackedVoxel.compose(0x00, 1, 2);
        long water = PackedVoxel.compose(0xAF, 2, 3);
        long[] children = {stone, stone, stone, stone, water, stone, stone, stone};

        long parent = VoxelMipper.mip(children, TEST_RESOLVER);

        assertEquals(1, PackedVoxel.blockId(parent));
    }

    @Test
    public void mipDoesNotPromoteFluidHiddenBelowOpaqueLayer() {
        long stone = PackedVoxel.compose(0x00, 1, 2);
        long water = PackedVoxel.compose(0xAF, 2, 3);
        long[] children = {water, water, water, water, stone, stone, stone, stone};

        long parent = VoxelMipper.mip(children, TEST_RESOLVER);

        assertEquals(1, PackedVoxel.blockId(parent));
    }

    @Test
    public void mipPreservesHalfFilledFluidSurface() {
        long water = PackedVoxel.compose(0x0F, 2, 3);
        long[] children = {PackedVoxel.AIR, PackedVoxel.AIR, PackedVoxel.AIR, PackedVoxel.AIR,
                water, water, water, water};

        long parent = VoxelMipper.mip(children, TEST_RESOLVER);

        assertEquals(2, PackedVoxel.blockId(parent));
    }

    @Test
    public void mipKeepsVisibleFluidSurfaceWithMinorityOpaqueState() {
        long stone = PackedVoxel.compose(0x00, 1, 2);
        long water = PackedVoxel.compose(0x0F, 2, 3);
        long[] children = {PackedVoxel.AIR, PackedVoxel.AIR, PackedVoxel.AIR, PackedVoxel.AIR,
                water, water, water, stone};

        long parent = VoxelMipper.mip(children, TEST_RESOLVER);

        assertEquals(2, PackedVoxel.blockId(parent));
    }

    @Test
    public void mipRejectsSparseOpaqueChildrenAndKeepsMaximumSkyLight() {
        long stone = PackedVoxel.compose(0x00, 1, 2);
        long[] children = {stone, stone, stone, PackedVoxel.airWithLight(0x0E),
                PackedVoxel.airWithLight(0x03), PackedVoxel.AIR, PackedVoxel.AIR, PackedVoxel.AIR};

        long parent = VoxelMipper.mip(children, blockId -> 15);

        assertTrue(PackedVoxel.isAir(parent));
        assertEquals(0x0E, PackedVoxel.light(parent));
    }

    @Test
    public void mipDoesNotTreatOrdinaryTransparentBlockAsFluid() {
        long stone = PackedVoxel.compose(0x00, 1, 2);
        long glass = PackedVoxel.compose(0x0F, 8, 3);
        long[] children = {stone, stone, stone, stone, glass, glass, glass, glass};

        long parent = VoxelMipper.mip(children, TEST_RESOLVER);

        assertEquals(1, PackedVoxel.blockId(parent));
    }

    @Test
    public void mipAggregatesDifferentBlockStatesOfSameFluid() {
        long fluidLevelZero = PackedVoxel.compose(0x0F, 2, 3);
        long fluidLevelOne = PackedVoxel.compose(0x0E, 3, 3);
        long fluidFalling = PackedVoxel.compose(0x0D, 4, 3);
        long fluidOtherLevel = PackedVoxel.compose(0x0C, 5, 3);
        long[] children = {PackedVoxel.AIR, PackedVoxel.AIR, PackedVoxel.AIR, PackedVoxel.AIR,
                fluidLevelZero, fluidLevelOne, fluidFalling, fluidOtherLevel};

        long parent = VoxelMipper.mip(children, TEST_RESOLVER);

        assertEquals(42, TEST_RESOLVER.fluidKey(PackedVoxel.blockId(parent)));
    }

    @Test
    public void mipAggregatesDifferentWaterloggedHostsAgainstOpaqueStates() {
        long stone = PackedVoxel.compose(0x00, 1, 2);
        long waterloggedStairs = PackedVoxel.compose(0x0F, 6, 3);
        long waterloggedSlab = PackedVoxel.compose(0x0E, 7, 3);
        long[] children = {PackedVoxel.AIR, PackedVoxel.AIR, PackedVoxel.AIR, PackedVoxel.AIR,
                waterloggedStairs, waterloggedSlab, stone, stone};

        long parent = VoxelMipper.mip(children, TEST_RESOLVER);

        assertEquals(42, TEST_RESOLVER.fluidKey(PackedVoxel.blockId(parent)));
    }

    @Test
    public void sectionMipChainRetainsFluidTopLayer() {
        VoxelizedSection section = VoxelizedSection.empty();
        long stone = PackedVoxel.compose(0x00, 1, 2);
        long water = PackedVoxel.compose(0xAF, 2, 3);
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                section.setBase(x, 0, z, stone);
                section.setBase(x, 1, z, water);
            }
        }

        SectionMipper.generateMipLevels(section, TEST_RESOLVER);

        assertEquals(2, PackedVoxel.blockId(section.get(1, 0, 0, 0)));
    }
}
