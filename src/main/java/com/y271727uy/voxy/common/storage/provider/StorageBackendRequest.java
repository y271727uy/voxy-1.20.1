package com.y271727uy.voxy.common.storage.provider;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral construction input; individual providers validate the fields they consume. */
public record StorageBackendRequest(Path root, Map<String, String> options) {
    public StorageBackendRequest {
        options = Map.copyOf(Objects.requireNonNull(options, "options"));
    }

    public static StorageBackendRequest inMemory() {
        return new StorageBackendRequest(null, Map.of());
    }
}
