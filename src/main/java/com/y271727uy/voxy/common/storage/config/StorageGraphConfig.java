package com.y271727uy.voxy.common.storage.config;

import java.util.Objects;

public record StorageGraphConfig(int formatVersion, StorageNodeConfig root) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public StorageGraphConfig {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported storage config format version: " + formatVersion);
        }
        Objects.requireNonNull(root, "root");
    }

    public StorageGraphConfig(StorageNodeConfig root) {
        this(CURRENT_FORMAT_VERSION, root);
    }
}
