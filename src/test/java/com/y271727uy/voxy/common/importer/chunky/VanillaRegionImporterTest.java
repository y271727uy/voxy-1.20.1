package com.y271727uy.voxy.common.importer.chunky;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class VanillaRegionImporterTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void discoveryIsStrictAndCoordinateOrdered() throws Exception {
        Path region = temporary.newFolder("world", "region").toPath();
        Files.createFile(region.resolve("r.4.-1.mca"));
        Files.createFile(region.resolve("r.-2.8.mca"));
        Files.createFile(region.resolve("r.1.2.mca.backup"));
        Files.createFile(region.resolve("notes.txt"));

        List<RegionSource.RegionFile> files = RegionSource.discover(region.getParent());

        assertEquals(2, files.size());
        assertEquals(-2, files.get(0).regionX());
        assertEquals(8, files.get(0).regionZ());
        assertEquals(4, files.get(1).regionX());
        assertEquals(-1, files.get(1).regionZ());
    }

    @Test
    public void truncatedHeaderIsRejectedWithoutReadingChunks() throws Exception {
        Path region = temporary.newFile("r.0.0.mca").toPath();
        Files.write(region, new byte[4096]);

        IOException failure = assertThrows(IOException.class,
                () -> new AnvilRegionReader().scan(new RegionSource.RegionFile(region, 0, 0)));

        assertTrue(failure.getMessage().contains("Truncated region header"));
    }

    @Test
    public void malformedChunkIsIsolatedAndStillConsumesOneProgressUnit() throws Exception {
        Path regionDirectory = temporary.newFolder("generated", "region").toPath();
        Path region = regionDirectory.resolve("r.0.0.mca");
        writeRegionWithValidAndOutOfBoundsChunk(region);
        AtomicInteger importedX = new AtomicInteger(-1);
        List<String> progress = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();

        VanillaRegionImporter.ImportReport report = new VanillaRegionImporter().importFrom(
                regionDirectory.getParent(),
                (x, z, chunk) -> {
                    importedX.set(chunk.getInt("xPos"));
                    return true;
                },
                (completed, total) -> progress.add(completed + "/" + total),
                (path, chunk, failure) -> failures.incrementAndGet());

        assertEquals(2, report.totalChunks());
        assertEquals(1, report.importedChunks());
        assertEquals(0, report.skippedChunks());
        assertEquals(1, report.failedChunks());
        assertEquals(0, report.failedRegions());
        assertEquals(0, importedX.get());
        assertEquals(1, failures.get());
        assertEquals(List.of("0/2", "1/2", "2/2"), progress);
    }

    @Test
    public void overlappingSectorAllocationsRejectTheRegionPlan() throws Exception {
        Path region = temporary.newFile("r.0.0.mca").toPath();
        ByteBuffer file = ByteBuffer.allocate(AnvilRegionReader.HEADER_BYTES + AnvilRegionReader.SECTOR_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        file.putInt((2 << 8) | 1);
        file.putInt((2 << 8) | 1);
        Files.write(region, file.array());

        IOException failure = assertThrows(IOException.class,
                () -> new AnvilRegionReader().scan(new RegionSource.RegionFile(region, 0, 0)));

        assertTrue(failure.getMessage().contains("Overlapping"));
    }

    @Test
    public void externalMccUsesOnlyTheHeaderSlotCoordinatePath() throws Exception {
        Path directory = temporary.newFolder("external").toPath();
        Path region = directory.resolve("r.-1.2.mca");
        ByteBuffer file = ByteBuffer.allocate(AnvilRegionReader.HEADER_BYTES + AnvilRegionReader.SECTOR_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        int index = (3 << 5) | 4;
        file.putInt(index * Integer.BYTES, (2 << 8) | 1);
        file.position(AnvilRegionReader.HEADER_BYTES);
        file.putInt(1);
        file.put((byte) (0x80 | 3));
        Files.write(region, file.array());

        CompoundTag external = new CompoundTag();
        external.putInt("xPos", -28);
        external.putInt("zPos", 67);
        Files.write(directory.resolve("c.-28.67.mcc"), writeNbt(external));
        RegionSource.RegionFile source = new RegionSource.RegionFile(region.toAbsolutePath(), -1, 2);
        AnvilRegionReader reader = new AnvilRegionReader();
        AnvilRegionReader.ChunkLocation location = reader.scan(source).get(0);

        CompoundTag decoded = reader.read(location);

        assertEquals(-28, decoded.getInt("xPos"));
        assertEquals(67, decoded.getInt("zPos"));
    }

    private static void writeRegionWithValidAndOutOfBoundsChunk(Path path) throws IOException {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("xPos", 0);
        chunk.putInt("zPos", 0);
        byte[] nbt = writeNbt(chunk);

        ByteBuffer file = ByteBuffer.allocate(AnvilRegionReader.HEADER_BYTES + AnvilRegionReader.SECTOR_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        file.putInt((2 << 8) | 1);
        file.putInt((250 << 8) | 1);
        file.position(AnvilRegionReader.HEADER_BYTES);
        file.putInt(nbt.length + 1);
        file.put((byte) 3);
        file.put(nbt);
        Files.write(path, file.array());
    }

    private static byte[] writeNbt(CompoundTag tag) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
            return bytes.toByteArray();
        }
    }
}
