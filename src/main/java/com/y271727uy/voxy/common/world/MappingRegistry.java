package com.y271727uy.voxy.common.world;

import com.mojang.serialization.DataResult;
import com.y271727uy.voxy.common.storage.MappingStorage;
import com.y271727uy.voxy.common.voxelization.VoxelMapping;
import com.y271727uy.voxy.common.voxelization.VoxelMipper;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public final class MappingRegistry implements VoxelMapping, AutoCloseable {
    public static final int BLOCK_STATE_TYPE = 1;
    public static final int BIOME_TYPE = 2;
    private static final int TYPE_SHIFT = 30;
    private static final int ID_MASK = (1 << TYPE_SHIFT) - 1;
    private static final int MAX_BLOCK_IDS = 1 << 20;
    private static final int MAX_BIOME_IDS = 1 << 9;

    private final MappingStorage storage;
    private final ReentrantLock blockLock = new ReentrantLock();
    private final ConcurrentHashMap<BlockState, BlockEntry> blocksByState = new ConcurrentHashMap<>();
    private final List<BlockEntry> blocksById = new ArrayList<>();
    private final ReentrantLock biomeLock = new ReentrantLock();
    private final ConcurrentHashMap<ResourceLocation, BiomeEntry> biomesByLocation = new ConcurrentHashMap<>();
    private final List<BiomeEntry> biomesById = new ArrayList<>();
    private volatile Consumer<BlockEntry> blockCallback;
    private volatile Consumer<BiomeEntry> biomeCallback;

    public MappingRegistry(MappingStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        BlockEntry air = new BlockEntry(0, Blocks.AIR.defaultBlockState());
        this.blocksByState.put(air.state(), air);
        this.blocksById.add(air);
        loadFromStorage();
    }

    @Override
    public int blockId(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        BlockEntry entry = this.blocksByState.get(state);
        return entry == null ? registerBlock(state).id() : entry.id();
    }

    @Override
    public int biomeId(Holder<Biome> biome) {
        ResourceLocation location = biome.unwrapKey()
                .orElseThrow(() -> new IllegalArgumentException("Biome holder has no registry key"))
                .location();
        return biomeId(location);
    }

    public int biomeId(ResourceLocation location) {
        BiomeEntry entry = this.biomesByLocation.get(location);
        return entry == null ? registerBiome(location).id() : entry.id();
    }

    @Override
    public int opacity(int blockId) {
        this.blockLock.lock();
        try {
            return this.blocksById.get(blockId).opacity();
        } finally {
            this.blockLock.unlock();
        }
    }

    public BlockState blockState(int blockId) {
        this.blockLock.lock();
        try {
            return this.blocksById.get(blockId).state();
        } finally {
            this.blockLock.unlock();
        }
    }

    @Override
    public boolean hasFluid(int blockId) {
        return !blockState(blockId).getFluidState().isEmpty();
    }

    @Override
    public boolean isPureFluid(int blockId) {
        return blockState(blockId).getBlock() instanceof LiquidBlock;
    }

    @Override
    public int fluidKey(int blockId) {
        BlockState state = blockState(blockId);
        if (state.getFluidState().isEmpty()) {
            return VoxelMapping.super.fluidKey(blockId);
        }
        return fluidKey(state.getFluidState().getType());
    }

    static int fluidKey(Fluid fluid) {
        if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return VoxelMipper.StateResolver.NO_FLUID;
        }
        if (fluid instanceof FlowingFluid flowing) {
            fluid = flowing.getSource();
        }
        return BuiltInRegistries.FLUID.getId(fluid);
    }

    public BlockEntry[] blockEntries() {
        this.blockLock.lock();
        try {
            return this.blocksById.toArray(BlockEntry[]::new);
        } finally {
            this.blockLock.unlock();
        }
    }

    public BiomeEntry[] biomeEntries() {
        this.biomeLock.lock();
        try {
            return this.biomesById.toArray(BiomeEntry[]::new);
        } finally {
            this.biomeLock.unlock();
        }
    }

    public void onBlockRegistered(Consumer<BlockEntry> callback) {
        this.blockCallback = callback;
    }

    public void onBiomeRegistered(Consumer<BiomeEntry> callback) {
        this.biomeCallback = callback;
    }

    public void forceResave() {
        for (BlockEntry entry : blockEntries()) {
            if (entry.id() != 0) {
                persist(BLOCK_STATE_TYPE, entry.id(), serialize(entry));
            }
        }
        for (BiomeEntry entry : biomeEntries()) {
            persist(BIOME_TYPE, entry.id(), serialize(entry));
        }
        this.storage.flush();
    }

    @Override
    public void close() {
        this.storage.flush();
    }

    public static int storageKey(int type, int id) {
        if (type <= 0 || type > 3 || id < 0 || id > ID_MASK) {
            throw new IllegalArgumentException("Invalid mapping key type/id: " + type + "/" + id);
        }
        return (type << TYPE_SHIFT) | id;
    }

    public static int storageType(int key) {
        return key >>> TYPE_SHIFT;
    }

    public static int storageId(int key) {
        return key & ID_MASK;
    }

    private BlockEntry registerBlock(BlockState state) {
        BlockEntry entry;
        this.blockLock.lock();
        try {
            entry = this.blocksByState.get(state);
            if (entry != null) {
                return entry;
            }
            requireCapacity("block", this.blocksById.size(), MAX_BLOCK_IDS);
            entry = new BlockEntry(this.blocksById.size(), state);
            this.blocksById.add(entry);
            this.blocksByState.put(state, entry);
            persist(BLOCK_STATE_TYPE, entry.id(), serialize(entry));
        } finally {
            this.blockLock.unlock();
        }
        Consumer<BlockEntry> callback = this.blockCallback;
        if (callback != null) {
            callback.accept(entry);
        }
        return entry;
    }

    private BiomeEntry registerBiome(ResourceLocation location) {
        BiomeEntry entry;
        this.biomeLock.lock();
        try {
            entry = this.biomesByLocation.get(location);
            if (entry != null) {
                return entry;
            }
            requireCapacity("biome", this.biomesById.size(), MAX_BIOME_IDS);
            entry = new BiomeEntry(this.biomesById.size(), location);
            this.biomesById.add(entry);
            this.biomesByLocation.put(location, entry);
            persist(BIOME_TYPE, entry.id(), serialize(entry));
        } finally {
            this.biomeLock.unlock();
        }
        Consumer<BiomeEntry> callback = this.biomeCallback;
        if (callback != null) {
            callback.accept(entry);
        }
        return entry;
    }

    private void loadFromStorage() {
        List<BlockEntry> blocks = new ArrayList<>();
        List<BiomeEntry> biomes = new ArrayList<>();
        for (Int2ObjectMap.Entry<byte[]> stored : this.storage.getIdMappingsData().int2ObjectEntrySet()) {
            int type = stored.getIntKey() >>> TYPE_SHIFT;
            int id = stored.getIntKey() & ID_MASK;
            if (type == BLOCK_STATE_TYPE) {
                if (id == 0) {
                    throw new IllegalStateException("Persisted block id 0 is reserved for implicit air");
                }
                blocks.add(deserializeBlock(id, stored.getValue()));
            } else if (type == BIOME_TYPE) {
                biomes.add(deserializeBiome(id, stored.getValue()));
            } else {
                throw new IllegalStateException("Unknown mapping entry type: " + type);
            }
        }

        blocks.stream().sorted(Comparator.comparingInt(BlockEntry::id)).forEach(entry -> {
            requireDenseId("block", this.blocksById.size(), entry.id());
            this.blocksById.add(entry);
            this.blocksByState.putIfAbsent(entry.state(), entry);
        });
        biomes.stream().sorted(Comparator.comparingInt(BiomeEntry::id)).forEach(entry -> {
            requireDenseId("biome", this.biomesById.size(), entry.id());
            this.biomesById.add(entry);
            this.biomesByLocation.putIfAbsent(entry.location(), entry);
        });
    }

    private void persist(int type, int id, byte[] data) {
        this.storage.putIdMapping(storageKey(type, id), ByteBuffer.wrap(data));
    }

    private static byte[] serialize(BlockEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", entry.id());
        tag.put("block_state", codecValue(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, entry.state()), "encode block state"));
        return compress(tag);
    }

    private static byte[] serialize(BiomeEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", entry.id());
        tag.putString("biome_id", entry.location().toString());
        return compress(tag);
    }

    private static BlockEntry deserializeBlock(int expectedId, byte[] data) {
        CompoundTag tag = decompress(data);
        requireEncodedId(expectedId, tag.getInt("id"));
        BlockState state = codecValue(BlockState.CODEC.parse(NbtOps.INSTANCE, tag.get("block_state")), "decode block state");
        return new BlockEntry(expectedId, state);
    }

    private static BiomeEntry deserializeBiome(int expectedId, byte[] data) {
        CompoundTag tag = decompress(data);
        requireEncodedId(expectedId, tag.getInt("id"));
        ResourceLocation location = ResourceLocation.tryParse(tag.getString("biome_id"));
        if (location == null) {
            throw new IllegalStateException("Invalid persisted biome id: " + tag.getString("biome_id"));
        }
        return new BiomeEntry(expectedId, location);
    }

    private static byte[] compress(CompoundTag tag) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode mapping", exception);
        }
    }

    private static CompoundTag decompress(byte[] data) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(data));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to decode mapping", exception);
        }
    }

    private static <T> T codecValue(DataResult<T> result, String operation) {
        return result.result().orElseThrow(() -> new IllegalStateException("Unable to " + operation + ": "
                + result.error().map(DataResult.PartialResult::message).orElse("unknown codec error")));
    }

    private static void requireCapacity(String type, int id, int capacity) {
        if (id >= capacity) {
            throw new IllegalStateException("Exhausted " + type + " mapping capacity at id " + id);
        }
    }

    private static void requireDenseId(String type, int expected, int actual) {
        if (actual != expected) {
            throw new IllegalStateException("Non-contiguous " + type + " mapping: expected " + expected + ", got " + actual);
        }
    }

    private static void requireEncodedId(int expected, int actual) {
        if (actual != expected) {
            throw new IllegalStateException("Encoded mapping id " + actual + " does not match storage id " + expected);
        }
    }

    public record BlockEntry(int id, BlockState state, int opacity) {
        BlockEntry(int id, BlockState state) {
            this(id, state, state.getBlock() instanceof LeavesBlock
                    ? 15
                    : state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        }
    }

    public record BiomeEntry(int id, ResourceLocation location) {
    }
}
