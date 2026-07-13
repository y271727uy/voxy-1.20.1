package com.y271727uy.voxy.common.voxelization;

import java.util.function.IntUnaryOperator;

public final class SectionMipper {
    private SectionMipper() {
    }

    public static void generateMipLevels(VoxelizedSection section, IntUnaryOperator opacityByBlockId) {
        generateMipLevels(section, new VoxelMipper.StateResolver() {
            @Override
            public int opacity(int blockId) {
                return opacityByBlockId.applyAsInt(blockId);
            }

            @Override
            public boolean hasFluid(int blockId) {
                return false;
            }

            @Override
            public boolean isPureFluid(int blockId) {
                return false;
            }

            @Override
            public int fluidKey(int blockId) {
                return NO_FLUID;
            }
        });
    }

    public static void generateMipLevels(VoxelizedSection section, VoxelMipper.StateResolver resolver) {
        long[] data = section.data();
        long[] children = new long[8];
        for (int level = 1; level < VoxelizedSection.LEVEL_COUNT; level++) {
            int sourceSide = 16 >> (level - 1);
            int targetSide = sourceSide >> 1;
            int sourceOffset = VoxelizedSection.levelOffset(level - 1);
            int targetOffset = VoxelizedSection.levelOffset(level);
            for (int y = 0; y < targetSide; y++) {
                for (int z = 0; z < targetSide; z++) {
                    for (int x = 0; x < targetSide; x++) {
                        int childIndex = 0;
                        for (int dy = 0; dy < 2; dy++) {
                            for (int dz = 0; dz < 2; dz++) {
                                for (int dx = 0; dx < 2; dx++) {
                                    int sx = x * 2 + dx;
                                    int sy = y * 2 + dy;
                                    int sz = z * 2 + dz;
                                    children[childIndex++] = data[sourceOffset + (sy * sourceSide + sz) * sourceSide + sx];
                                }
                            }
                        }
                        data[targetOffset + (y * targetSide + z) * targetSide + x] = VoxelMipper.mip(children, resolver);
                    }
                }
            }
        }
    }
}
