package com.y271727uy.voxy.common.storage;

import com.y271727uy.voxy.common.voxelization.PackedVoxel;
import com.y271727uy.voxy.common.world.WorldSection;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BoundedAsyncSectionStorageTest {
    @Test
    public void saveCapturesGenerationAndFlushIsBarrier() throws Exception {
        BlockingStorage delegate = new BlockingStorage();
        BoundedAsyncSectionStorage storage = new BoundedAsyncSectionStorage(delegate, 4, "snapshot-test");
        WorldSection source = section(1, 0, 0, 0, 11);
        storage.saveSection(source);
        assertTrue(delegate.saveEntered.await(2, TimeUnit.SECONDS));
        source.set(0, 0, 0, PackedVoxel.compose(0xFF, 22, 0));

        AtomicBoolean flushed = new AtomicBoolean();
        Thread flush = new Thread(() -> {
            storage.flush();
            flushed.set(true);
        });
        flush.start();
        Thread.sleep(30);
        assertFalse(flushed.get());
        delegate.allowSave.countDown();
        flush.join(2_000);
        assertTrue(flushed.get());

        WorldSection loaded = new WorldSection(0, 0, 0, 0);
        assertEquals(SectionStorage.LOAD_SUCCESS, storage.loadSection(loaded));
        assertEquals(11, PackedVoxel.blockId(loaded.get(0, 0, 0)));
        storage.close();
    }

    @Test
    public void boundedQueueAppliesBackpressureAndReportsMetrics() throws Exception {
        BlockingStorage delegate = new BlockingStorage();
        BoundedAsyncSectionStorage storage = new BoundedAsyncSectionStorage(delegate, 1, "backpressure-test");
        storage.saveSection(section(1, 0, 0, 0, 1));
        assertTrue(delegate.saveEntered.await(2, TimeUnit.SECONDS));
        storage.saveSection(section(1, 1, 0, 0, 2));

        AtomicBoolean submitted = new AtomicBoolean();
        Thread producer = new Thread(() -> {
            storage.saveSection(section(1, 2, 0, 0, 3));
            submitted.set(true);
        });
        producer.start();
        Thread.sleep(30);
        assertFalse(submitted.get());
        delegate.allowSave.countDown();
        producer.join(2_000);
        assertTrue(submitted.get());
        storage.flush();

        BoundedAsyncSectionStorage.Metrics metrics = storage.metrics();
        assertEquals(1, metrics.capacity());
        assertEquals(1, metrics.highWaterMark());
        assertTrue(metrics.backpressureEvents() >= 1);
        assertEquals(3, delegate.saved.get());
        storage.close();
    }

    @Test
    public void closeDrainsWritesAndOwnsDelegateLifecycle() {
        CountingStorage delegate = new CountingStorage();
        BoundedAsyncSectionStorage storage = new BoundedAsyncSectionStorage(delegate, 2, "close-test");
        storage.saveSection(section(1, 0, 0, 0, 1));
        storage.saveSection(section(1, 1, 0, 0, 2));
        storage.close();

        assertEquals(2, delegate.saved.get());
        assertEquals(1, delegate.flushed.get());
        assertTrue(delegate.closed.get());
        assertTrue(storage.metrics().closingOrClosed());
        assertThrows(IllegalStateException.class, () -> storage.saveSection(section(1, 2, 0, 0, 3)));
    }

    @Test
    public void workerFailurePropagatesThroughBarrierAndCloseStillClosesDelegate() {
        CountingStorage delegate = new CountingStorage();
        delegate.failSave = true;
        BoundedAsyncSectionStorage storage = new BoundedAsyncSectionStorage(delegate, 2, "failure-test");
        storage.saveSection(section(1, 0, 0, 0, 1));

        IllegalStateException failure = assertThrows(IllegalStateException.class, storage::flush);
        assertEquals("test save failure", failure.getMessage());
        assertTrue(storage.metrics().failed());
        assertThrows(IllegalStateException.class, storage::getIdMappingsData);
        assertThrows(IllegalStateException.class, storage::close);
        assertTrue(delegate.closed.get());
    }

    private static WorldSection section(int children, int x, int y, int z, int blockId) {
        WorldSection section = new WorldSection(0, x, y, z);
        section.set(0, 0, 0, PackedVoxel.compose(0xFF, blockId, 0));
        section.replaceDataFromStorage(section.copyData(), children, 1);
        return section;
    }

    private static class CountingStorage implements SectionStorage {
        final InMemorySectionStorage delegate = new InMemorySectionStorage();
        final AtomicInteger saved = new AtomicInteger();
        final AtomicInteger flushed = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean failSave;

        @Override
        public int loadSection(WorldSection section) {
            return this.delegate.loadSection(section);
        }

        @Override
        public void saveSection(WorldSection section) {
            if (this.failSave) throw new IllegalStateException("test save failure");
            this.delegate.saveSection(section);
            this.saved.incrementAndGet();
        }

        @Override
        public void iteratePositions(int level, LongConsumer consumer) {
            this.delegate.iteratePositions(level, consumer);
        }

        @Override
        public void putIdMapping(int key, ByteBuffer data) {
            this.delegate.putIdMapping(key, data);
        }

        @Override
        public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
            return this.delegate.getIdMappingsData();
        }

        @Override
        public void flush() {
            this.flushed.incrementAndGet();
            this.delegate.flush();
        }

        @Override
        public void close() {
            this.closed.set(true);
            this.delegate.close();
        }
    }

    private static final class BlockingStorage extends CountingStorage {
        final CountDownLatch saveEntered = new CountDownLatch(1);
        final CountDownLatch allowSave = new CountDownLatch(1);

        @Override
        public void saveSection(WorldSection section) {
            this.saveEntered.countDown();
            try {
                if (!this.allowSave.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test save timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            super.saveSection(section);
        }
    }
}
