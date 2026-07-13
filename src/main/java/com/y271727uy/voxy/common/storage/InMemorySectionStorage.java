package com.y271727uy.voxy.common.storage;

import com.y271727uy.voxy.common.world.WorldSection;
import com.y271727uy.voxy.common.world.WorldSectionKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

public final class InMemorySectionStorage implements SectionStorage {
    private final InMemoryMappingStorage mappings = new InMemoryMappingStorage();
    private final Long2ObjectOpenHashMap<byte[]> sections = new Long2ObjectOpenHashMap<>();
    private boolean closed;

    @Override
    public synchronized int loadSection(WorldSection section) {
        requireOpen();
        byte[] serialized = this.sections.get(section.key());
        if (serialized == null) {
            return LOAD_ABSENT;
        }
        try {
            WorldSectionCodec.deserialize(section, serialized.clone());
            return LOAD_SUCCESS;
        } catch (RuntimeException exception) {
            this.sections.remove(section.key());
            section.clear();
            return LOAD_CORRUPT;
        }
    }

    @Override
    public synchronized void saveSection(WorldSection section) {
        requireOpen();
        this.sections.put(section.key(), WorldSectionCodec.serialize(section).clone());
    }

    @Override
    public synchronized void iteratePositions(int level, LongConsumer consumer) {
        requireOpen();
        for (Long2ObjectMap.Entry<byte[]> entry : this.sections.long2ObjectEntrySet()) {
            if (level == -1 || WorldSectionKey.level(entry.getLongKey()) == level) {
                consumer.accept(entry.getLongKey());
            }
        }
    }

    @Override
    public void putIdMapping(int key, ByteBuffer data) {
        requireOpen();
        this.mappings.putIdMapping(key, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        requireOpen();
        return this.mappings.getIdMappingsData();
    }

    @Override
    public void flush() {
        requireOpen();
        this.mappings.flush();
    }

    @Override
    public synchronized void close() {
        if (!this.closed) {
            this.closed = true;
            this.mappings.close();
            this.sections.clear();
        }
    }

    public synchronized void putRawSectionData(long key, byte[] serialized) {
        requireOpen();
        this.sections.put(key, serialized.clone());
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Section storage is closed");
        }
    }
}
