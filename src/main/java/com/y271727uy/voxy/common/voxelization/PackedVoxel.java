package com.y271727uy.voxy.common.voxelization;

public final class PackedVoxel {
    private static final int BLOCK_BITS = 20;
    private static final int BIOME_BITS = 9;
    private static final int BLOCK_SHIFT = 27;
    private static final int BIOME_SHIFT = 47;
    private static final int LIGHT_SHIFT = 56;
    private static final long BLOCK_MASK = (1L << BLOCK_BITS) - 1;
    private static final long BIOME_MASK = (1L << BIOME_BITS) - 1;

    public static final long AIR = 0;

    private PackedVoxel() {
    }

    public static long compose(int light, int blockId, int biomeId) {
        requireUnsigned("light", light, 8);
        requireUnsigned("blockId", blockId, BLOCK_BITS);
        requireUnsigned("biomeId", biomeId, BIOME_BITS);
        if (blockId == 0) {
            return airWithLight(light);
        }
        return ((long) light << LIGHT_SHIFT)
                | ((long) biomeId << BIOME_SHIFT)
                | ((long) blockId << BLOCK_SHIFT);
    }

    public static long airWithLight(int light) {
        requireUnsigned("light", light, 8);
        return (long) light << LIGHT_SHIFT;
    }

    public static boolean isAir(long voxel) {
        return blockId(voxel) == 0;
    }

    public static int blockId(long voxel) {
        return (int) ((voxel >>> BLOCK_SHIFT) & BLOCK_MASK);
    }

    public static int biomeId(long voxel) {
        return (int) ((voxel >>> BIOME_SHIFT) & BIOME_MASK);
    }

    public static int light(long voxel) {
        return (int) (voxel >>> LIGHT_SHIFT);
    }

    public static long withLight(long voxel, int light) {
        requireUnsigned("light", light, 8);
        return (voxel & ~(0xFFL << LIGHT_SHIFT)) | ((long) light << LIGHT_SHIFT);
    }

    private static void requireUnsigned(String name, int value, int bits) {
        if (value < 0 || value >= (1 << bits)) {
            throw new IllegalArgumentException(name + " does not fit in " + bits + " bits: " + value);
        }
    }
}
