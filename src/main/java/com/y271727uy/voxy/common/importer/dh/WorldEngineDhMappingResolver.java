package com.y271727uy.voxy.common.importer.dh;

import com.y271727uy.voxy.common.voxelization.PackedVoxel;
import com.y271727uy.voxy.common.world.MappingRegistry;
import com.y271727uy.voxy.common.world.WorldEngine;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class WorldEngineDhMappingResolver implements DhMappingResolver {
    private static final String STATE_SEPARATOR = "_STATE_";
    private final MappingRegistry mapping;
    private final Registry<Biome> biomes;
    private final Registry<Block> blocks;
    private final Holder<Biome> plains;

    public WorldEngineDhMappingResolver(WorldEngine engine, Level level) {
        this(Objects.requireNonNull(engine, "engine").mapping(), level);
    }

    public WorldEngineDhMappingResolver(MappingRegistry mapping, Level level) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        var registries = Objects.requireNonNull(level, "level").registryAccess();
        this.biomes = registries.registryOrThrow(Registries.BIOME);
        this.blocks = registries.registryOrThrow(Registries.BLOCK);
        this.plains = this.biomes.getHolder(Biomes.PLAINS).orElseThrow();
    }

    @Override
    public long resolve(String biomeId, String encodedBlockState) {
        if ("AIR".equals(encodedBlockState)) return PackedVoxel.AIR;

        int stateSeparator = encodedBlockState.indexOf(STATE_SEPARATOR);
        String blockId = stateSeparator < 0
                ? encodedBlockState : encodedBlockState.substring(0, stateSeparator);
        String stateProperties = stateSeparator < 0
                ? null : encodedBlockState.substring(stateSeparator + STATE_SEPARATOR.length());
        ResourceLocation blockLocation = ResourceLocation.tryParse(blockId);
        if (blockLocation == null) return PackedVoxel.AIR;
        Block block = this.blocks.getOptional(blockLocation).orElse(null);
        if (block == null || block == Blocks.AIR) return PackedVoxel.AIR;

        BlockState state = block.defaultBlockState();
        if (stateProperties != null) {
            state = findState(block, stateProperties);
            if (state == null) return PackedVoxel.AIR;
        }
        int mappedBlock = this.mapping.blockId(state);
        if (mappedBlock == 0) return PackedVoxel.AIR;

        ResourceLocation biomeLocation = ResourceLocation.tryParse(biomeId);
        Biome biomeValue = biomeLocation == null
                ? this.plains.value() : this.biomes.getOptional(biomeLocation).orElse(this.plains.value());
        Holder<Biome> biome = this.biomes.wrapAsHolder(biomeValue);
        return PackedVoxel.compose(0, mappedBlock, this.mapping.biomeId(biome));
    }

    private static BlockState findState(Block block, String encodedProperties) {
        for (BlockState candidate : block.getStateDefinition().getPossibleStates()) {
            if (serializeProperties(candidate).equals(encodedProperties)) return candidate;
        }
        return null;
    }

    private static String serializeProperties(BlockState state) {
        List<net.minecraft.world.level.block.state.properties.Property<?>> properties =
                new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(net.minecraft.world.level.block.state.properties.Property::getName));
        StringBuilder encoded = new StringBuilder();
        for (var property : properties) {
            encoded.append('{').append(property.getName()).append(':')
                    .append(state.getValues().get(property)).append('}');
        }
        return encoded.toString();
    }
}
