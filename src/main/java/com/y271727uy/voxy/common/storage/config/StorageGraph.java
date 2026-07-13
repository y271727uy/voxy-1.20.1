package com.y271727uy.voxy.common.storage.config;

import com.y271727uy.voxy.common.storage.SectionStorage;
import com.y271727uy.voxy.common.world.WorldSection;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

public final class StorageGraph implements SectionStorage {
    private final SectionStorage root;
    private final List<SectionStorage> resources;
    private boolean closed;

    StorageGraph(SectionStorage root, List<SectionStorage> resources) {
        this.root = root;
        this.resources = List.copyOf(resources);
    }

    @Override
    public synchronized int loadSection(WorldSection section) {
        requireOpen();
        return this.root.loadSection(section);
    }

    @Override
    public synchronized void saveSection(WorldSection section) {
        requireOpen();
        this.root.saveSection(section);
    }

    @Override
    public synchronized void iteratePositions(int level, LongConsumer consumer) {
        requireOpen();
        this.root.iteratePositions(level, consumer);
    }

    @Override
    public synchronized void putIdMapping(int key, ByteBuffer data) {
        requireOpen();
        this.root.putIdMapping(key, data);
    }

    @Override
    public synchronized Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        requireOpen();
        return this.root.getIdMappingsData();
    }

    @Override
    public synchronized void flush() {
        requireOpen();
        this.root.flush();
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        RuntimeException failure = closeResources(this.resources, null);
        if (failure != null) {
            throw failure;
        }
    }

    static RuntimeException closeResources(List<SectionStorage> constructionOrder, Throwable primary) {
        ArrayList<Throwable> failures = new ArrayList<>();
        for (int index = constructionOrder.size() - 1; index >= 0; index--) {
            try {
                constructionOrder.get(index).close();
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        if (primary != null) {
            failures.forEach(primary::addSuppressed);
            return null;
        }
        if (failures.isEmpty()) {
            return null;
        }
        RuntimeException result = new IllegalStateException("Failed to close storage graph", failures.get(0));
        for (int index = 1; index < failures.size(); index++) {
            result.addSuppressed(failures.get(index));
        }
        return result;
    }

    private synchronized void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Storage graph is closed");
        }
    }
}
