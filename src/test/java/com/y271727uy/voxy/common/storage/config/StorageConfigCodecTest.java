package com.y271727uy.voxy.common.storage.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StorageConfigCodecTest {
    @Test
    public void nestedConfigurationRoundTrips() {
        JsonObject options = new JsonObject();
        options.addProperty("level", 3);
        StorageGraphConfig source = new StorageGraphConfig(new StorageNodeConfig("CompressionAdaptor", options,
                List.of(new StorageNodeConfig("RocksDB", new JsonObject(), List.of()))));

        StorageGraphConfig decoded = StorageConfigCodec.decode(StorageConfigCodec.encode(source));

        assertEquals(1, decoded.formatVersion());
        assertEquals("CompressionAdaptor", decoded.root().type());
        assertEquals(3, decoded.root().options().get("level").getAsInt());
        assertEquals("RocksDB", decoded.root().children().get(0).type());
    }

    @Test
    public void unknownFieldsAndVersionsFailExplicitly() {
        assertThrows(JsonParseException.class, () -> StorageConfigCodec.decode(
                "{\"version\":1,\"root\":{\"type\":\"Memory\",\"bogus\":true}}"));
        JsonParseException version = assertThrows(JsonParseException.class, () -> StorageConfigCodec.decode(
                "{\"version\":99,\"root\":{\"type\":\"Memory\"}}"));
        assertTrue(version.getMessage().contains("Unsupported storage config format version"));
    }

    @Test
    public void missingRequiredFieldsFailExplicitly() {
        JsonParseException exception = assertThrows(JsonParseException.class,
                () -> StorageConfigCodec.decode("{\"version\":1}"));
        assertTrue(exception.getMessage().contains("missing required field 'root'"));
    }
}
