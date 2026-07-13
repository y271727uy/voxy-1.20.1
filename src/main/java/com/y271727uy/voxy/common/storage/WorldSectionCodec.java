package com.y271727uy.voxy.common.storage;

import com.y271727uy.voxy.common.voxelization.PackedVoxel;
import com.y271727uy.voxy.common.world.WorldSection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorldSectionCodec {
    private static final int HEADER_SIZE = Long.BYTES * 2;
    private static final int INDEX_SIZE = WorldSection.SECTION_VOLUME * Short.BYTES;

    private WorldSectionCodec() {
    }

    public static byte[] serialize(WorldSection section) {
        WorldSection.StorageSnapshot snapshot = section.captureStorageSnapshot();
        long[] values = snapshot.data();
        Map<Long, Integer> lookup = new LinkedHashMap<>();
        for (long value : values) {
            lookup.computeIfAbsent(value, ignored -> lookup.size());
        }
        if (lookup.size() > 0xFFFF) {
            throw new IllegalStateException("World section mapping table exceeds 16-bit indices");
        }

        ByteBuffer output = ByteBuffer.allocate(HEADER_SIZE + INDEX_SIZE + lookup.size() * Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        output.putLong(section.key());
        output.putLong((lookup.size() & 0xFFFFL) | ((long) snapshot.nonEmptyChildren() << 16));
        for (long value : values) {
            output.putShort((short) (int) lookup.get(value));
        }
        for (long value : lookup.keySet()) {
            output.putLong(value);
        }
        return output.array();
    }

    public static void deserialize(WorldSection section, byte[] serialized) {
        if (serialized.length < HEADER_SIZE + INDEX_SIZE + Long.BYTES) {
            throw new IllegalStateException("Serialized world section is truncated");
        }
        ByteBuffer input = ByteBuffer.wrap(serialized).order(ByteOrder.LITTLE_ENDIAN);
        long storedKey = input.getLong();
        if (storedKey != section.key()) {
            throw new IllegalStateException("Stored section key does not match requested section");
        }
        long metadata = input.getLong();
        int lookupSize = (int) (metadata & 0xFFFF);
        int expectedSize = HEADER_SIZE + INDEX_SIZE + lookupSize * Long.BYTES;
        if (lookupSize == 0 || serialized.length != expectedSize) {
            throw new IllegalStateException("Invalid serialized world-section lookup table");
        }

        short[] indices = new short[WorldSection.SECTION_VOLUME];
        for (int index = 0; index < indices.length; index++) {
            indices[index] = input.getShort();
        }
        long[] lookup = new long[lookupSize];
        for (int index = 0; index < lookup.length; index++) {
            lookup[index] = input.getLong();
        }

        long[] values = new long[WorldSection.SECTION_VOLUME];
        int nonAir = 0;
        for (int index = 0; index < values.length; index++) {
            int mapping = Short.toUnsignedInt(indices[index]);
            if (mapping >= lookup.length) {
                throw new IllegalStateException("World-section voxel references invalid mapping " + mapping);
            }
            values[index] = lookup[mapping];
            if (section.level() == 0 && !PackedVoxel.isAir(values[index])) {
                nonAir++;
            }
        }
        section.replaceDataFromStorage(values, (int) ((metadata >>> 16) & 0xFF), nonAir);
    }
}
