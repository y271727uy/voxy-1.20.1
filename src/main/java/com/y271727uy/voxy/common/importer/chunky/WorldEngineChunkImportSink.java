package com.y271727uy.voxy.common.importer.chunky;

import com.mojang.serialization.Codec;
import com.y271727uy.voxy.common.voxelization.ArrayLightingSupplier;
import com.y271727uy.voxy.common.voxelization.SectionMipper;
import com.y271727uy.voxy.common.voxelization.VoxelizedSection;
import com.y271727uy.voxy.common.voxelization.WorldConversionFactory;
import com.y271727uy.voxy.common.world.WorldEngine;
import com.y271727uy.voxy.common.world.WorldSectionKey;
import com.y271727uy.voxy.common.world.WorldUpdater;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.PalettedContainerRO.PackedData;

import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Decodes 1.20.1 chunk NBT and commits complete sections through the normal Voxy update path. */
public final class WorldEngineChunkImportSink implements ChunkImportSink {
    private static final int SECTION_STATE_ENTRY_COUNT = 4096;
    private static final int COMPACT_BLOCK_STATES_LENGTH_5BIT = 320;
    private static final int COMPACT_BLOCK_STATES_LENGTH_6BIT = 384;
    private final WorldEngine engine;
    private final PalettedContainerRO<Holder<Biome>> defaultBiomes;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final Codec<PalettedContainer<BlockState>> blockCodec;
    private final int minSection;
    private final int maxSection;

    public WorldEngineChunkImportSink(WorldEngine engine, Level level) {
        this.engine = Objects.requireNonNull(engine, "engine");
        Level checkedLevel = Objects.requireNonNull(level, "level");
        this.minSection = checkedLevel.getMinSection();
        this.maxSection = checkedLevel.getMaxSection();
        var biomes = checkedLevel.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        this.defaultBiomes = constantBiomes(plains);
        this.biomeCodec = PalettedContainer.codecRO(biomes.asHolderIdMap(), biomes.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, plains);
        this.blockCodec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    }

    @Override
    public boolean importChunk(int expectedChunkX, int expectedChunkZ, CompoundTag root) {
        CompoundTag chunk = root.contains("Level", Tag.TAG_COMPOUND) ? root.getCompound("Level") : root;
        validateChunkCoordinates(chunk, expectedChunkX, expectedChunkZ);
        if (chunk.contains("Status", Tag.TAG_STRING)) {
            ChunkStatus status = ChunkStatus.byName(chunk.getString("Status"));
            if (status != null && status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) {
                return false;
            }
        }
        String sectionKey = chunk.contains("sections") ? "sections" : "Sections";
        if (chunk.contains(sectionKey) && !chunk.contains(sectionKey, Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Chunk section data is not a list");
        }
        ListTag sections = chunk.getList(sectionKey, Tag.TAG_COMPOUND);
        if (sections.isEmpty()) {
            return false;
        }

        List<CompoundTag> sectionTags = new ArrayList<>(sections.size());
        for (Tag value : sections) {
            if (!(value instanceof CompoundTag section)) {
                throw new IllegalArgumentException("Chunk section list contains a non-compound entry");
            }
            sectionTags.add(section);
        }
        Set<Integer> sectionCoordinates = new HashSet<>();
        List<DecodedSection> decoded = decodeAll(sectionTags,
                section -> decodeSection(section, expectedChunkX, expectedChunkZ, sectionCoordinates));
        List<VoxelizedSection> prepared = decodeAll(decoded, this::prepareSection);
        for (VoxelizedSection section : prepared) {
            WorldUpdater.insertUpdate(this.engine, section);
        }
        return !prepared.isEmpty();
    }

    private DecodedSection decodeSection(CompoundTag section, int chunkX, int chunkZ,
                                         Set<Integer> sectionCoordinates) {
        if (!section.contains("Y", Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("Chunk section is missing its Y coordinate");
        }
        int sectionY = section.getInt("Y");
        validateSectionY(sectionY, this.minSection, this.maxSection);
        if (!sectionCoordinates.add(sectionY)) {
            throw new IllegalArgumentException("Chunk contains duplicate section Y: " + sectionY);
        }
        if (!WorldSectionKey.canPack(0, chunkX >> 1, sectionY >> 1, chunkZ >> 1)) {
            throw new IllegalArgumentException("Chunk section cannot be represented by Voxy: " + sectionY);
        }
        PalettedContainer<BlockState> blocks = decodeBlocks(section);
        if (blocks == null) {
            throw new IllegalArgumentException("Chunk section block-state palette cannot be decoded at Y " + sectionY);
        }
        PalettedContainerRO<Holder<Biome>> sectionBiomes = decodeOptionalCompound(section, "biomes",
                this.biomeCodec, this.defaultBiomes,
                "Chunk section biome palette cannot be decoded at Y " + sectionY);
        return new DecodedSection(chunkX, sectionY, chunkZ, blocks, sectionBiomes,
                readLight(section, "BlockLight", sectionY), readLight(section, "SkyLight", sectionY));
    }

    private VoxelizedSection prepareSection(DecodedSection section) {
        VoxelizedSection output = VoxelizedSection.empty().position(section.x(), section.y(), section.z());
        WorldConversionFactory.convert(output, this.engine.mapping(), section.blocks(), section.biomes(),
                new ArrayLightingSupplier(section.blockLight(), section.skyLight()));
        SectionMipper.generateMipLevels(output, this.engine.mapping());
        return output;
    }

    private PalettedContainer<BlockState> decodeBlocks(CompoundTag section) {
        CompoundTag states = section.getCompound("block_states");
        if (states.isEmpty() && section.contains("Palette", Tag.TAG_LIST)) {
            states = new CompoundTag();
            states.put("palette", section.getList("Palette", Tag.TAG_COMPOUND).copy());
            if (section.contains("BlockStates", Tag.TAG_LONG_ARRAY)) {
                states.putLongArray("data", section.getLongArray("BlockStates"));
            }
        }
        if (states.isEmpty()) {
            return null;
        }
        if (states.contains("data", Tag.TAG_LONG_ARRAY)) {
            long[] raw = states.getLongArray("data");
            if (isKnownCompactStorageLength(raw.length)) {
                long[] repacked = tryRepackCompactStorage(raw);
                if (repacked != null) {
                    CompoundTag recovered = states.copy();
                    recovered.putLongArray("data", repacked);
                    PalettedContainer<BlockState> decoded = this.blockCodec.parse(NbtOps.INSTANCE, recovered)
                            .result().orElse(null);
                    if (decoded != null) {
                        return decoded;
                    }
                }
            }
        }
        return this.blockCodec.parse(NbtOps.INSTANCE, states).result().orElse(null);
    }

    private static byte[] readLight(CompoundTag section, String key, int sectionY) {
        if (!section.contains(key)) {
            return null;
        }
        if (!section.contains(key, Tag.TAG_BYTE_ARRAY)) {
            throw new IllegalArgumentException(key + " is not a byte array at section Y " + sectionY);
        }
        byte[] light = section.getByteArray(key);
        if (light.length != 2048) {
            throw new IllegalArgumentException(key + " has invalid length at section Y " + sectionY);
        }
        return light;
    }

    static boolean isKnownCompactStorageLength(int length) {
        return length == COMPACT_BLOCK_STATES_LENGTH_5BIT || length == COMPACT_BLOCK_STATES_LENGTH_6BIT;
    }

    static long[] tryRepackCompactStorage(long[] compactData) {
        if (compactData.length == 0 || compactData.length % 64 != 0) {
            return null;
        }
        int bits = compactData.length / 64;
        if (bits <= 0 || bits >= 32) {
            return null;
        }
        int valuesPerLong = 64 / bits;
        int paddedLength = (SECTION_STATE_ENTRY_COUNT + valuesPerLong - 1) / valuesPerLong;
        if (paddedLength == compactData.length) {
            return null;
        }
        long mask = (1L << bits) - 1L;
        long[] padded = new long[paddedLength];
        for (int index = 0; index < SECTION_STATE_ENTRY_COUNT; index++) {
            int sourceBit = index * bits;
            int sourceLong = sourceBit >>> 6;
            int sourceOffset = sourceBit & 63;
            long value = compactData[sourceLong] >>> sourceOffset;
            if (sourceOffset + bits > 64) {
                if (sourceLong + 1 >= compactData.length) {
                    return null;
                }
                value |= compactData[sourceLong + 1] << (64 - sourceOffset);
            }
            value &= mask;
            int targetLong = index / valuesPerLong;
            int targetOffset = index % valuesPerLong * bits;
            padded[targetLong] |= value << targetOffset;
        }
        return padded;
    }

    static void validateChunkCoordinates(CompoundTag chunk, int expectedChunkX, int expectedChunkZ) {
        if (!chunk.contains("xPos", Tag.TAG_ANY_NUMERIC) || !chunk.contains("zPos", Tag.TAG_ANY_NUMERIC)
                || chunk.getInt("xPos") != expectedChunkX || chunk.getInt("zPos") != expectedChunkZ) {
            throw new IllegalArgumentException("Chunk coordinates do not match the Anvil header slot ["
                    + expectedChunkX + ", " + expectedChunkZ + "]");
        }
    }

    static void validateSectionY(int sectionY, int minSection, int maxSection) {
        if (sectionY < minSection || sectionY >= maxSection) {
            throw new IllegalArgumentException("Chunk section Y is outside level bounds: " + sectionY);
        }
    }

    static <T> T decodeOptionalCompound(CompoundTag owner, String key, Codec<T> codec, T fallback,
                                        String failureMessage) {
        if (!owner.contains(key)) {
            return fallback;
        }
        if (!owner.contains(key, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException(failureMessage);
        }
        return codec.parse(NbtOps.INSTANCE, owner.getCompound(key)).result()
                .orElseThrow(() -> new IllegalArgumentException(failureMessage));
    }

    static <T, R> List<R> decodeAll(List<T> inputs, Function<T, R> decoder) {
        List<R> decoded = new ArrayList<>(inputs.size());
        for (T input : inputs) {
            decoded.add(Objects.requireNonNull(decoder.apply(input), "decoded section"));
        }
        return List.copyOf(decoded);
    }

    private record DecodedSection(int x, int y, int z, PalettedContainer<BlockState> blocks,
                                  PalettedContainerRO<Holder<Biome>> biomes,
                                  byte[] blockLight, byte[] skyLight) {
    }

    private static PalettedContainerRO<Holder<Biome>> constantBiomes(Holder<Biome> biome) {
        return new PalettedContainerRO<>() {
            @Override public Holder<Biome> get(int x, int y, int z) { return biome; }
            @Override public void getAll(Consumer<Holder<Biome>> action) { action.accept(biome); }
            @Override public void write(FriendlyByteBuf buffer) { }
            @Override public int getSerializedSize() { return 0; }
            @Override public boolean maybeHas(Predicate<Holder<Biome>> predicate) { return predicate.test(biome); }
            @Override public void count(PalettedContainer.CountConsumer<Holder<Biome>> counter) { counter.accept(biome, 64); }
            @Override public PalettedContainer<Holder<Biome>> recreate() { return null; }
            @Override public PackedData<Holder<Biome>> pack(IdMap<Holder<Biome>> idMap,
                                                              PalettedContainer.Strategy strategy) { return null; }
        };
    }
}
