package com.y271727uy.voxy.common.storage;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;

public class InMemoryMappingStorageTest {
    @Test
    public void snapshotsRemainingBufferAndReturnedData() {
        InMemoryMappingStorage storage = new InMemoryMappingStorage();
        byte[] source = {9, 1, 2, 3};
        ByteBuffer buffer = ByteBuffer.wrap(source);
        buffer.position(1);
        storage.putIdMapping(7, buffer);
        source[2] = 99;

        byte[] first = storage.getIdMappingsData().get(7);
        assertArrayEquals(new byte[]{1, 2, 3}, first);
        first[0] = 88;
        assertArrayEquals(new byte[]{1, 2, 3}, storage.getIdMappingsData().get(7));
    }
}
