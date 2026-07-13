package com.y271727uy.voxy.common.voxelization;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

public final class WorldConversionFactory {
    private WorldConversionFactory() {
    }

    public static VoxelizedSection convert(VoxelizedSection output,
                                           VoxelMapping mapping,
                                           PalettedContainer<BlockState> blocks,
                                           PalettedContainerRO<Holder<Biome>> biomes,
                                           LightingSupplier lighting) {
        int nonAirCount = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int light = lighting == null ? 0 : lighting.light(x, y, z);
                    BlockState state = blocks == null ? null : blocks.get(x, y, z);
                    if (state == null || state.isAir()) {
                        output.setBase(x, y, z, PackedVoxel.airWithLight(light));
                        continue;
                    }
                    int blockId = mapping.blockId(state);
                    int biomeId = mapping.biomeId(biomes.get(x >> 2, y >> 2, z >> 2));
                    output.setBase(x, y, z, PackedVoxel.compose(light, blockId, biomeId));
                    nonAirCount++;
                }
            }
        }
        output.nonAirBlockCount(nonAirCount);
        return output;
    }
}
