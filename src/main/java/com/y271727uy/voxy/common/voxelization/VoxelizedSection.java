package com.y271727uy.voxy.common.voxelization;

import java.util.Arrays;

public final class VoxelizedSection {
    public static final int LEVEL_COUNT = 5;
    public static final int STORAGE_SIZE = 4096 + 512 + 64 + 8 + 1;

    private final long[] data;
    private int x;
    private int y;
    private int z;
    private int nonAirBlockCount;

    private VoxelizedSection(long[] data) {
        if (data.length != STORAGE_SIZE) {
            throw new IllegalArgumentException("Unexpected voxel section size: " + data.length);
        }
        this.data = data;
    }

    public static VoxelizedSection empty() {
        return new VoxelizedSection(new long[STORAGE_SIZE]);
    }

    public VoxelizedSection position(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public int x() { return this.x; }
    public int y() { return this.y; }
    public int z() { return this.z; }
    public int nonAirBlockCount() { return this.nonAirBlockCount; }
    public void nonAirBlockCount(int count) { this.nonAirBlockCount = count; }

    public long get(int level, int x, int y, int z) {
        validateLevel(level);
        int sizeBits = 4 - level;
        int mask = (1 << sizeBits) - 1;
        int index = ((y & mask) << (sizeBits * 2)) | ((z & mask) << sizeBits) | (x & mask);
        return this.data[levelOffset(level) + index];
    }

    public void setBase(int x, int y, int z, long voxel) {
        this.data[(y << 8) | (z << 4) | x] = voxel;
    }

    long[] data() {
        return this.data;
    }

    public VoxelizedSection clear() {
        this.nonAirBlockCount = 0;
        Arrays.fill(this.data, 0);
        return this;
    }

    public static int levelOffset(int level) {
        validateLevel(level);
        int offset = 0;
        for (int current = 0; current < level; current++) {
            int side = 16 >> current;
            offset += side * side * side;
        }
        return offset;
    }

    private static void validateLevel(int level) {
        if (level < 0 || level >= LEVEL_COUNT) {
            throw new IllegalArgumentException("LOD level out of range: " + level);
        }
    }
}
