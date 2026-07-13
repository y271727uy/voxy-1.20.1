package com.y271727uy.voxy.common.importer.chunky;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class WorldEngineChunkImportSinkTest {
    @Test
    public void compactFiveAndSixBitStorageIsRepackedToPaddedLayout() {
        assertCompactRepack(5);
        assertCompactRepack(6);
    }

    @Test
    public void allSectionsDecodeBeforeCallerCanCommitAny() {
        AtomicInteger decoded = new AtomicInteger();
        AtomicInteger committed = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () -> {
            List<Integer> result = WorldEngineChunkImportSink.decodeAll(List.of(1, 2, 3), value -> {
                decoded.incrementAndGet();
                if (value == 2) {
                    throw new IllegalArgumentException("bad section");
                }
                return value;
            });
            result.forEach(value -> committed.incrementAndGet());
        });

        assertEquals(2, decoded.get());
        assertEquals(0, committed.get());
    }

    @Test
    public void coordinatesMustExistAndMatchHeaderSlot() {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("xPos", 4);
        chunk.putInt("zPos", -7);
        WorldEngineChunkImportSink.validateChunkCoordinates(chunk, 4, -7);

        assertThrows(IllegalArgumentException.class,
                () -> WorldEngineChunkImportSink.validateChunkCoordinates(chunk, 5, -7));
        chunk.remove("zPos");
        assertThrows(IllegalArgumentException.class,
                () -> WorldEngineChunkImportSink.validateChunkCoordinates(chunk, 4, -7));
    }

    @Test
    public void levelSectionBoundsAreMinInclusiveMaxExclusive() {
        WorldEngineChunkImportSink.validateSectionY(-4, -4, 20);
        WorldEngineChunkImportSink.validateSectionY(19, -4, 20);
        assertThrows(IllegalArgumentException.class,
                () -> WorldEngineChunkImportSink.validateSectionY(-5, -4, 20));
        assertThrows(IllegalArgumentException.class,
                () -> WorldEngineChunkImportSink.validateSectionY(20, -4, 20));
    }

    @Test
    public void missingBiomeUsesFallbackButPresentInvalidBiomeFails() {
        CompoundTag section = new CompoundTag();
        Integer fallback = 19;
        assertSame(fallback, WorldEngineChunkImportSink.decodeOptionalCompound(
                section, "biomes", Codec.INT, fallback, "bad biome"));

        section.put("biomes", new CompoundTag());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WorldEngineChunkImportSink.decodeOptionalCompound(
                        section, "biomes", Codec.INT, fallback, "bad biome"));
        assertEquals("bad biome", failure.getMessage());
    }

    private static void assertCompactRepack(int bits) {
        long mask = (1L << bits) - 1L;
        long[] compact = new long[bits * 64];
        for (int index = 0; index < 4096; index++) {
            long value = index & mask;
            int bitIndex = index * bits;
            int word = bitIndex >>> 6;
            int offset = bitIndex & 63;
            compact[word] |= value << offset;
            if (offset + bits > 64) {
                compact[word + 1] |= value >>> (64 - offset);
            }
        }

        long[] padded = WorldEngineChunkImportSink.tryRepackCompactStorage(compact);
        int valuesPerLong = 64 / bits;
        assertEquals((4096 + valuesPerLong - 1) / valuesPerLong, padded.length);
        for (int index = 0; index < 4096; index++) {
            long actual = padded[index / valuesPerLong] >>> (index % valuesPerLong * bits) & mask;
            assertEquals((long) index & mask, actual);
        }
    }
}
