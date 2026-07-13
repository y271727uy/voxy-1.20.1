package com.y271727uy.voxy.common.importer.chunky;

import com.y271727uy.voxy.common.importing.job.ImportCheckpoint;
import com.y271727uy.voxy.common.importing.job.ImportCheckpointStore;
import com.y271727uy.voxy.common.importing.job.ImportJobExecutor;
import com.y271727uy.voxy.common.importing.job.ImportJobState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegionImportJobTaskTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void cancelledJobResumesAfterLastDurableChunk() throws Exception {
        Path world = temporary.newFolder("world").toPath();
        Path regionDirectory = Files.createDirectory(world.resolve("region"));
        writeTwoChunkRegion(regionDirectory.resolve("r.0.0.mca"));
        BlockingCheckpointStore checkpoints = new BlockingCheckpointStore();
        List<Integer> firstPass = new ArrayList<>();

        try (ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 1, "region-cancel-test")) {
            var handle = executor.submit("region-job", new RegionImportJobTask(world,
                    (x, z, nbt) -> { firstPass.add(x); return true; }, null));
            assertTrue(checkpoints.firstSave.await(2, TimeUnit.SECONDS));
            assertTrue(handle.cancel());
            checkpoints.releaseSave.countDown();
            assertEquals(ImportJobState.CANCELLED, handle.await().state());
        }
        assertEquals(List.of(0), firstPass);
        assertTrue(checkpoints.load("region-job").isPresent());

        checkpoints.blockFirstSave = false;
        List<Integer> resumed = new ArrayList<>();
        try (ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 1, "region-resume-test")) {
            var result = executor.submit("region-job", new RegionImportJobTask(world,
                    (x, z, nbt) -> { resumed.add(x); return true; }, null)).await();
            assertEquals(ImportJobState.SUCCEEDED, result.state());
            assertTrue(result.resumed());
        }
        assertEquals(List.of(1), resumed);
        assertFalse(checkpoints.load("region-job").isPresent());
    }

    private static void writeTwoChunkRegion(Path path) throws Exception {
        byte[] first = nbt(0, 0);
        byte[] second = nbt(1, 0);
        ByteBuffer file = ByteBuffer.allocate(AnvilRegionReader.HEADER_BYTES
                + 2 * AnvilRegionReader.SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
        file.putInt((2 << 8) | 1);
        file.putInt((3 << 8) | 1);
        writeChunk(file, 2, first);
        writeChunk(file, 3, second);
        Files.write(path, file.array());
    }

    private static void writeChunk(ByteBuffer file, int sector, byte[] nbt) {
        file.position(sector * AnvilRegionReader.SECTOR_BYTES);
        file.putInt(nbt.length + 1);
        file.put((byte) 3);
        file.put(nbt);
    }

    private static byte[] nbt(int x, int z) throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putInt("xPos", x);
        tag.putInt("zPos", z);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
            return bytes.toByteArray();
        }
    }

    private static final class BlockingCheckpointStore implements ImportCheckpointStore {
        private final ConcurrentHashMap<String, ImportCheckpoint> values = new ConcurrentHashMap<>();
        private final CountDownLatch firstSave = new CountDownLatch(1);
        private final CountDownLatch releaseSave = new CountDownLatch(1);
        private volatile boolean blockFirstSave = true;

        @Override public Optional<ImportCheckpoint> load(String jobId) {
            return Optional.ofNullable(this.values.get(jobId));
        }

        @Override public void save(ImportCheckpoint checkpoint) throws Exception {
            this.values.put(checkpoint.jobId(), checkpoint);
            if (this.blockFirstSave) {
                this.firstSave.countDown();
                this.releaseSave.await(2, TimeUnit.SECONDS);
            }
        }

        @Override public void clear(String jobId) {
            this.values.remove(jobId);
        }
    }
}
