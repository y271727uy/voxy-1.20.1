package com.y271727uy.voxy.common.world;

import com.y271727uy.voxy.common.voxelization.PackedVoxel;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WorldSectionTest {
    @Test
    public void indexAndChildContractsMatchWorldLayout() {
        assertEquals(0, WorldSection.index(0, 0, 0));
        assertEquals(WorldSection.SECTION_VOLUME - 1, WorldSection.index(31, 31, 31));
        assertEquals(7, WorldSection.childIndex(1, 1, 1));
        assertEquals(2, WorldSection.childIndex(0, 0, 1));
    }

    @Test
    public void levelZeroOccupancyAndReferencesStayConsistent() {
        WorldSection section = new WorldSection(0, 1, 2, 3);
        section.set(4, 5, 6, PackedVoxel.compose(0xFF, 1, 1));
        assertEquals(1, section.nonEmptyBlockCount());
        assertEquals(0xFF, section.nonEmptyChildren());
        assertFalse(section.updateLevelZeroState());
        assertTrue(section.tryAcquire());
        assertEquals(1, section.refCount());
        assertEquals(0, section.release());
        assertFalse(section.isFreed());
        assertTrue(section.tryFree());
        assertTrue(section.isFreed());
    }

    @Test
    public void failedWriterRestoresEvenRevision() {
        WorldSection section = new WorldSection(0, 0, 0, 0);
        long before = section.revision();

        assertThrows(IllegalArgumentException.class,
                () -> section.set(WorldSection.SIDE_LENGTH, 0, 0, 1L));

        assertEquals(before + 2, section.revision());
        assertEquals(0, section.revision() & 1L);
        long[] copy = new long[WorldSection.SECTION_VOLUME];
        assertTrue(section.copyRegionAtRevision(section.revision(), 0, 0, 0,
                WorldSection.SIDE_LENGTH, WorldSection.SIDE_LENGTH, WorldSection.SIDE_LENGTH,
                copy, 0, WorldSection.SIDE_LENGTH * WorldSection.SIDE_LENGTH,
                WorldSection.SIDE_LENGTH));
    }

    @Test
    public void concurrentWritersNeverExposeTornLongRegion() throws Exception {
        WorldSection section = new WorldSection(0, 0, 0, 0);
        long firstPattern = 0xAAAA_AAAA_5555_5555L;
        long secondPattern = ~firstPattern;
        long[] first = filledRegion(firstPattern);
        long[] second = filledRegion(secondPattern);
        section.updateRegion(0, 0, 0, 2, first, null);
        CountDownLatch start = new CountDownLatch(1);
        Thread firstWriter = writer(section, first, second, start, 750);
        Thread secondWriter = writer(section, second, first, start, 750);
        firstWriter.start();
        secondWriter.start();
        start.countDown();

        for (int iteration = 0; iteration < 500; iteration++) {
            long[] captured = captureRegion(section);
            if (captured == null) continue;
            long pattern = captured[0];
            assertTrue(pattern == firstPattern || pattern == secondPattern);
            for (long value : captured) assertEquals(pattern, value);
        }

        firstWriter.join(5_000);
        secondWriter.join(5_000);
        assertFalse(firstWriter.isAlive());
        assertFalse(secondWriter.isAlive());
        assertEquals(0, section.revision() & 1L);
    }

    private static Thread writer(WorldSection section, long[] first, long[] second,
                                 CountDownLatch start, int iterations) {
        return new Thread(() -> {
            try {
                start.await();
                for (int iteration = 0; iteration < iterations; iteration++) {
                    section.updateRegion(0, 0, 0, 2,
                            (iteration & 1) == 0 ? first : second, null);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
    }

    private static long[] filledRegion(long pattern) {
        long[] values = new long[8];
        Arrays.fill(values, pattern);
        return values;
    }

    private static long[] captureRegion(WorldSection section) {
        for (int attempt = 0; attempt < 10; attempt++) {
            long revision = section.revision();
            long[] copy = new long[8];
            if (section.copyRegionAtRevision(revision, 0, 0, 0, 2, 2, 2,
                    copy, 0, 4, 2)) return copy;
            Thread.onSpinWait();
        }
        return null;
    }
}
