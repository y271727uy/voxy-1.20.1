package com.y271727uy.voxy.common.voxelization;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

public interface VoxelMapping extends VoxelMipper.StateResolver {
    int blockId(BlockState state);

    int biomeId(Holder<Biome> biome);

    int opacity(int blockId);

    @Override
    default boolean hasFluid(int blockId) {
        return false;
    }

    @Override
    default boolean isPureFluid(int blockId) {
        return false;
    }

    @Override
    default int fluidKey(int blockId) {
        return VoxelMipper.StateResolver.NO_FLUID;
    }
}
