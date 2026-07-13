package com.y271727uy.voxy.common.storage.config;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

/** Immutable description of one node in a section-storage tree. */
public record StorageNodeConfig(String type, JsonObject options, List<StorageNodeConfig> children) {
    public StorageNodeConfig {
        if (type == null || !type.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid storage node type: " + type);
        }
        options = Objects.requireNonNull(options, "options").deepCopy();
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        if (children.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Storage node children cannot contain null");
        }
    }

    @Override
    public JsonObject options() {
        return this.options.deepCopy();
    }
}
