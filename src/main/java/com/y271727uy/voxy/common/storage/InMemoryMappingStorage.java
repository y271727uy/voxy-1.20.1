package com.y271727uy.voxy.common.storage;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.nio.ByteBuffer;

public final class InMemoryMappingStorage implements MappingStorage {
    private final Int2ObjectOpenHashMap<byte[]> mappings = new Int2ObjectOpenHashMap<>();
    private boolean closed;

    @Override
    public synchronized void putIdMapping(int key, ByteBuffer data) {
        requireOpen();
        ByteBuffer source = data.duplicate();
        byte[] copy = new byte[source.remaining()];
        source.get(copy);
        this.mappings.put(key, copy);
    }

    @Override
    public synchronized Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        requireOpen();
        Int2ObjectOpenHashMap<byte[]> copy = new Int2ObjectOpenHashMap<>(this.mappings.size());
        for (Int2ObjectMap.Entry<byte[]> entry : this.mappings.int2ObjectEntrySet()) {
            copy.put(entry.getIntKey(), entry.getValue().clone());
        }
        return copy;
    }

    @Override
    public synchronized void flush() {
        requireOpen();
    }

    @Override
    public synchronized void close() {
        this.closed = true;
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Mapping storage is closed");
        }
    }
}
