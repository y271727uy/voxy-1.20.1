package com.y271727uy.voxy.common.importer.dh;

import com.y271727uy.voxy.common.voxelization.PackedVoxel;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DhFullDataDecoderTest {
    @Test
    public void decodesMappingRunsLightAndSectionCoordinates() throws IOException {
        List<Section> sections = new ArrayList<>();
        DhFullDataDecoder decoder = new DhFullDataDecoder(-64, 16,
                (biome, block) -> block.equals("AIR") ? PackedVoxel.AIR : PackedVoxel.compose(0, 7, 3),
                (x, y, z, voxels, nonAir) -> sections.add(new Section(x, y, z, voxels, nonAir)));
        DhFullDataRecord record = new DhFullDataRecord(2, -3, 0, 1, 3,
                columns(singleRun(1, 3, 2, 4, 5)), mappings(
                "minecraft:plains_DH-BSW_AIR",
                "minecraft:forest_DH-BSW_minecraft:stone_STATE_{axis:y}"));

        assertEquals(16, decoder.decode(record, (mode, bytes) -> new ByteArrayInputStream(bytes)));
        assertEquals(16, sections.size());
        Section first = sections.get(0);
        assertEquals(8, first.x);
        assertEquals(-4, first.y);
        assertEquals(-12, first.z);
        assertEquals(2, first.nonAir);
        assertEquals(7, PackedVoxel.blockId(first.voxels[WorldIndex.at(0, 3, 0)]));
        assertEquals(0x54, PackedVoxel.light(first.voxels[WorldIndex.at(0, 3, 0)]));
        assertEquals(PackedVoxel.AIR, first.voxels[WorldIndex.at(0, 2, 0)]);
        assertEquals(PackedVoxel.AIR, first.voxels[WorldIndex.at(0, 5, 0)]);
    }

    @Test
    public void rejectsUnsupportedAndCorruptRecordsBeforeWriting() throws IOException {
        List<Section> sections = new ArrayList<>();
        DhFullDataDecoder decoder = new DhFullDataDecoder(0, 16, (a, b) -> 0,
                (x, y, z, voxels, count) -> sections.add(new Section(x, y, z, voxels, count)));
        DhFullDataRecord unsupported = new DhFullDataRecord(0, 0, 0, 2, 3, new byte[0], new byte[0]);
        assertThrows(IOException.class,
                () -> decoder.decode(unsupported, (mode, bytes) -> new ByteArrayInputStream(bytes)));

        DhFullDataRecord corrupt = new DhFullDataRecord(0, 0, 0, 1, 3,
                columns(singleRun(4, 0, 1, 0, 0)), mappings("minecraft:plains_DH-BSW_AIR"));
        assertThrows(IOException.class,
                () -> decoder.decode(corrupt, (mode, bytes) -> new ByteArrayInputStream(bytes)));
        assertTrue(sections.isEmpty());
    }

    @Test
    public void cancellationDuringColumnsStopsBeforeAnySectionSink() throws IOException {
        List<Section> sections = new ArrayList<>();
        AtomicInteger checks = new AtomicInteger();
        DhFullDataDecoder decoder = new DhFullDataDecoder(0, 16, (a, b) -> PackedVoxel.AIR,
                (x, y, z, voxels, count) -> sections.add(new Section(x, y, z, voxels, count)));
        DhFullDataRecord record = new DhFullDataRecord(0, 0, 0, 1, 3,
                columns(singleRun(0, 0, 1, 0, 0)), mappings("minecraft:plains_DH-BSW_AIR"));

        assertThrows(DhImportCancelledException.class, () -> decoder.decode(record,
                (mode, bytes) -> new ByteArrayInputStream(bytes), () -> checks.incrementAndGet() > 20));
        assertTrue(sections.isEmpty());
    }

    private static byte[] mappings(String... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(entries.length);
            for (String entry : entries) output.writeUTF(entry);
        }
        return bytes.toByteArray();
    }

    private static byte[] columns(long firstColumnRun) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            for (int x = 0; x < 64; x++) {
                for (int z = 0; z < 64; z++) {
                    if (x == 0 && z == 0) {
                        output.writeShort(1);
                        output.writeLong(firstColumnRun);
                    } else {
                        output.writeShort(0);
                    }
                }
            }
        }
        return bytes.toByteArray();
    }

    private static long singleRun(int mapping, int minimum, int height, int skyLight, int blockLight) {
        return (mapping & Integer.MAX_VALUE)
                | ((long) height << 32)
                | ((long) minimum << 44)
                | ((long) skyLight << 56)
                | ((long) blockLight << 60);
    }

    private record Section(int x, int y, int z, long[] voxels, int nonAir) {
    }

    private static final class WorldIndex {
        private static int at(int x, int y, int z) {
            return (y * 16 + z) * 16 + x;
        }
    }
}
