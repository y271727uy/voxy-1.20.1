package com.y271727uy.voxy.common.voxelization;

import java.util.function.IntUnaryOperator;

public final class VoxelMipper {
    private static final int FULLY_OPAQUE = 15;

    public interface StateResolver {
        int NO_FLUID = -1;

        int opacity(int blockId);

        boolean hasFluid(int blockId);

        boolean isPureFluid(int blockId);

        default int fluidKey(int blockId) {
            return hasFluid(blockId) ? blockId : NO_FLUID;
        }
    }

    private VoxelMipper() {
    }

    public static long mip(long[] children, IntUnaryOperator opacityByBlockId) {
        return mip(children, new StateResolver() {
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

    public static long mip(long[] children, StateResolver resolver) {
        if (children.length != 8) {
            throw new IllegalArgumentException("A mip cell requires eight children");
        }

        int visibleFluid = chooseVisibleFluid(children, resolver);
        if (visibleFluid >= 0) {
            return children[visibleFluid];
        }

        int nonAir = 0;
        for (long child : children) {
            nonAir += PackedVoxel.isAir(child) ? 0 : 1;
        }
        if (nonAir >= 4) {
            return children[chooseDominantState(children, resolver)];
        }

        int blockLight = 0;
        int skyLight = 0;
        for (long child : children) {
            int light = PackedVoxel.light(child);
            blockLight += light >>> 4;
            skyLight = Math.max(skyLight, light & 0xF);
        }
        int mergedLight = ((blockLight / 8) << 4) | skyLight;
        return PackedVoxel.withLight(nonAir == 0 ? children[7] : PackedVoxel.AIR, mergedLight);
    }

    private static int chooseVisibleFluid(long[] children, StateResolver resolver) {
        int fluidLayer = -1;
        for (int y = 1; y >= 0 && fluidLayer < 0; y--) {
            for (int index = y << 2; index < (y + 1) << 2; index++) {
                long child = children[index];
                if (!PackedVoxel.isAir(child)
                        && resolver.fluidKey(PackedVoxel.blockId(child)) != StateResolver.NO_FLUID) {
                    fluidLayer = y;
                    break;
                }
            }
        }
        if (fluidLayer < 0) {
            return -1;
        }

        for (int index = (fluidLayer + 1) << 2; index < children.length; index++) {
            long child = children[index];
            if (!PackedVoxel.isAir(child)
                    && resolver.opacity(PackedVoxel.blockId(child)) >= FULLY_OPAQUE) {
                return -1;
            }
        }

        int layerStart = fluidLayer << 2;
        int layerEnd = layerStart + 4;
        int opaqueScore = 0;
        int bestFluidKey = StateResolver.NO_FLUID;
        int bestFluidScore = Integer.MIN_VALUE;
        for (int index = layerStart; index < layerEnd; index++) {
            long child = children[index];
            if (PackedVoxel.isAir(child)) {
                continue;
            }
            int blockId = PackedVoxel.blockId(child);
            int fluidKey = resolver.fluidKey(blockId);
            if (fluidKey == StateResolver.NO_FLUID) {
                if (resolver.opacity(blockId) >= FULLY_OPAQUE) {
                    opaqueScore += 6;
                }
                continue;
            }
            int score = 0;
            for (int other = layerStart; other < layerEnd; other++) {
                long otherChild = children[other];
                int otherBlockId = PackedVoxel.blockId(otherChild);
                if (!PackedVoxel.isAir(otherChild) && resolver.fluidKey(otherBlockId) == fluidKey) {
                    score += resolver.isPureFluid(otherBlockId) ? 8 : 6;
                }
            }
            if (score > bestFluidScore) {
                bestFluidScore = score;
                bestFluidKey = fluidKey;
            }
        }
        if (bestFluidKey == StateResolver.NO_FLUID || opaqueScore > bestFluidScore) {
            return -1;
        }
        return chooseFluidRepresentative(children, bestFluidKey, fluidLayer, resolver);
    }

    private static int chooseDominantState(long[] children, StateResolver resolver) {
        int selected = -1;
        int selectedScore = Integer.MIN_VALUE;
        for (int index = 0; index < children.length; index++) {
            long child = children[index];
            if (PackedVoxel.isAir(child)) {
                continue;
            }
            int blockId = PackedVoxel.blockId(child);
            int count = 0;
            int highestY = 0;
            for (int other = 0; other < children.length; other++) {
                if (!PackedVoxel.isAir(children[other]) && PackedVoxel.blockId(children[other]) == blockId) {
                    count++;
                    highestY = Math.max(highestY, other >>> 2);
                }
            }
            int opacity = resolver.opacity(blockId);
            int score = (count << 8) + (opacity << 3) + (highestY << 2)
                    + (resolver.hasFluid(blockId) ? 2 : 0)
                    + (resolver.isPureFluid(blockId) ? 1 : 0);
            if (score > selectedScore) {
                selectedScore = score;
                selected = chooseRepresentative(children, blockId, highestY, resolver, false);
            }
        }
        return selected;
    }

    private static int chooseRepresentative(long[] children, int blockId, int preferredY,
                                            StateResolver resolver, boolean requireFluid) {
        int selected = -1;
        int selectedScore = Integer.MIN_VALUE;
        for (int index = 0; index < children.length; index++) {
            long child = children[index];
            if (PackedVoxel.isAir(child) || PackedVoxel.blockId(child) != blockId
                    || (requireFluid && !resolver.hasFluid(blockId))) {
                continue;
            }
            int score = ((index >>> 2) == preferredY ? 128 : 0)
                    + (resolver.isPureFluid(blockId) ? 32 : 0)
                    + PackedVoxel.light(child);
            if (score > selectedScore) {
                selectedScore = score;
                selected = index;
            }
        }
        return selected;
    }

    private static int chooseFluidRepresentative(long[] children, int fluidKey, int preferredY,
                                                  StateResolver resolver) {
        int selected = -1;
        int selectedScore = Integer.MIN_VALUE;
        for (int index = 0; index < children.length; index++) {
            long child = children[index];
            if (PackedVoxel.isAir(child)
                    || resolver.fluidKey(PackedVoxel.blockId(child)) != fluidKey) {
                continue;
            }
            int blockId = PackedVoxel.blockId(child);
            int score = ((index >>> 2) == preferredY ? 128 : 0)
                    + (resolver.isPureFluid(blockId) ? 32 : 0)
                    + PackedVoxel.light(child);
            if (score > selectedScore) {
                selectedScore = score;
                selected = index;
            }
        }
        return selected;
    }
}
