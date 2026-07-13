package com.y271727uy.voxy.common.storage;

import com.y271727uy.voxy.common.voxelization.PackedVoxel;
import com.y271727uy.voxy.common.world.WorldSection;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;

public class SectionStorageTest {
    @Test
    public void sectionRoundTripPreservesDataAndMetadata() {
        InMemorySectionStorage storage = new InMemorySectionStorage();
        WorldSection source = new WorldSection(0, -2, 3, 4);
        long voxel = PackedVoxel.compose(0xAB, 42, 7);
        source.set(31, 12, 6, voxel);
        source.updateLevelZeroState();
        storage.saveSection(source);

        WorldSection loaded = new WorldSection(0, -2, 3, 4);
        assertEquals(SectionStorage.LOAD_SUCCESS, storage.loadSection(loaded));
        assertEquals(voxel, loaded.get(31, 12, 6));
        assertEquals(1, loaded.nonEmptyBlockCount());
        assertEquals(0xFF, loaded.nonEmptyChildren());
    }

    @Test
    public void loadStatusesAndLevelIterationAreDistinct() {
        InMemorySectionStorage storage = new InMemorySectionStorage();
        WorldSection levelZero = new WorldSection(0, 0, 0, 0);
        WorldSection levelTwo = new WorldSection(2, 1, 1, 1);
        assertEquals(SectionStorage.LOAD_ABSENT, storage.loadSection(levelZero));
        storage.saveSection(levelZero);
        storage.saveSection(levelTwo);

        List<Long> positions = new ArrayList<>();
        storage.iteratePositions(2, positions::add);
        assertEquals(List.of(levelTwo.key()), positions);

        storage.putRawSectionData(levelZero.key(), new byte[]{1, 2, 3});
        assertEquals(SectionStorage.LOAD_CORRUPT, storage.loadSection(levelZero));
        assertEquals(SectionStorage.LOAD_ABSENT, storage.loadSection(levelZero));
    }

    @Test
    public void concurrentCodecSnapshotKeepsDataAndChildMaskInSameGeneration() throws Exception {
        WorldSection source = new WorldSection(0, 0, 0, 0);
        long solid = PackedVoxel.compose(0xFF, 42, 7);
        CountDownLatch start = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int iteration = 0; iteration < 2_000; iteration++) {
                    source.updateRegion(0, 0, 0, 1,
                            new long[]{(iteration & 1) == 0 ? solid : PackedVoxel.AIR}, null);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        writer.start();
        start.countDown();

        for (int iteration = 0; iteration < 300; iteration++) {
            WorldSection decoded = new WorldSection(0, 0, 0, 0);
            WorldSectionCodec.deserialize(decoded, WorldSectionCodec.serialize(source));
            boolean air = PackedVoxel.isAir(decoded.get(0, 0, 0));
            assertEquals(air ? 0 : 0xFF, decoded.nonEmptyChildren());
            assertEquals(air ? 0 : 1, decoded.nonEmptyBlockCount());
        }

        writer.join(5_000);
        assertEquals(false, writer.isAlive());
    }
}
