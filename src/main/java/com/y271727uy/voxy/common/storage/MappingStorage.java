package com.y271727uy.voxy.common.storage;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.nio.ByteBuffer;

public interface MappingStorage extends AutoCloseable {
    void putIdMapping(int key, ByteBuffer data);

    Int2ObjectOpenHashMap<byte[]> getIdMappingsData();

    void flush();

    @Override
    void close();
}
