package com.y271727uy.voxy.common.storage;

import com.y271727uy.voxy.common.world.WorldSection;

import java.util.function.LongConsumer;

public interface SectionStorage extends MappingStorage {
    int LOAD_SUCCESS = 0;
    int LOAD_ABSENT = 1;
    int LOAD_CORRUPT = -1;

    int loadSection(WorldSection section);

    void saveSection(WorldSection section);

    void iteratePositions(int level, LongConsumer consumer);
}
