package com.y271727uy.voxy.common.storage.config;

import com.google.gson.JsonObject;
import com.y271727uy.voxy.common.storage.SectionStorage;

import java.util.List;

public interface StorageNodeFactory {
    String type();

    default int minimumChildren() {
        return 0;
    }

    default int maximumChildren() {
        return 0;
    }

    default List<Dependency> requiredDependencies() {
        return List.of();
    }

    default void validateOptions(JsonObject options) {
    }

    /** The graph owns child lifetimes; implementations must not close children from their own close method. */
    SectionStorage create(StorageBuildContext context, JsonObject options, List<SectionStorage> children);

    record Dependency(String name, Class<?> type) {
        public Dependency {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Storage dependency name cannot be blank");
            }
            if (type == null) {
                throw new IllegalArgumentException("Storage dependency type cannot be null");
            }
        }
    }
}
