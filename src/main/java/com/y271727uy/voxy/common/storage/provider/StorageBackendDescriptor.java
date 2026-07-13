package com.y271727uy.voxy.common.storage.provider;

import java.util.List;
import java.util.Objects;

/** Static backend metadata suitable for config screens and diagnostics. */
public record StorageBackendDescriptor(
        String id,
        String displayName,
        StorageKind kind,
        boolean persistent,
        boolean requiresNativeRuntime,
        boolean supportsPositionIteration,
        List<String> requiredClasses
) {
    public StorageBackendDescriptor {
        id = requireText(id, "id");
        displayName = requireText(displayName, "displayName");
        kind = Objects.requireNonNull(kind, "kind");
        requiredClasses = List.copyOf(Objects.requireNonNull(requiredClasses, "requiredClasses"));
        for (String className : requiredClasses) requireText(className, "required class");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    public enum StorageKind {
        MEMORY,
        LOCAL_DATABASE,
        REMOTE_DATABASE
    }
}
