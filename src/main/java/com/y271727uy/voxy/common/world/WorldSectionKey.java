package com.y271727uy.voxy.common.world;

public final class WorldSectionKey {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_LOD_LEVEL = 4;
    public static final int MIN_XZ = -8_388_608;
    public static final int MAX_XZ = 8_388_607;
    public static final int MIN_Y = -128;
    public static final int MAX_Y = 127;

    private WorldSectionKey() {
    }

    public static long pack(int level, int x, int y, int z) {
        if (level < 0 || level > 15) {
            throw new IllegalArgumentException("Level must fit in four bits: " + level);
        }
        if (y < MIN_Y || y > MAX_Y) {
            throw new IllegalArgumentException("Y must fit in a signed byte: " + y);
        }
        if (x < MIN_XZ || x > MAX_XZ || z < MIN_XZ || z > MAX_XZ) {
            throw new IllegalArgumentException("X and Z must fit in signed 24-bit values");
        }
        return ((long) level << 60)
                | ((long) (y & 0xFF) << 52)
                | ((long) (z & 0xFFFFFF) << 28)
                | ((long) (x & 0xFFFFFF) << 4);
    }

    public static boolean canPack(int level, int x, int y, int z) {
        return level >= 0 && level <= 15
                && y >= MIN_Y && y <= MAX_Y
                && x >= MIN_XZ && x <= MAX_XZ
                && z >= MIN_XZ && z <= MAX_XZ;
    }

    public static int level(long key) {
        return (int) ((key >>> 60) & 0xF);
    }

    public static int x(long key) {
        return (int) (key << 36 >> 40);
    }

    public static int y(long key) {
        return (int) (key << 4 >> 56);
    }

    public static int z(long key) {
        return (int) (key << 12 >> 40);
    }

    public static String describe(long key) {
        return level(key) + "@[" + x(key) + ", " + y(key) + ", " + z(key) + "]";
    }
}
