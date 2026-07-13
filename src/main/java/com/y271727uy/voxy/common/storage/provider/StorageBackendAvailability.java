package com.y271727uy.voxy.common.storage.provider;

import java.util.List;
import java.util.Objects;

/** A non-throwing explanation of whether a provider can be opened in this runtime. */
public record StorageBackendAvailability(Status status, List<String> missingDependencies, String detail) {
    public StorageBackendAvailability {
        status = Objects.requireNonNull(status, "status");
        missingDependencies = List.copyOf(Objects.requireNonNull(missingDependencies, "missingDependencies"));
        detail = detail == null ? "" : detail;
        if (status == Status.AVAILABLE && (!missingDependencies.isEmpty() || !detail.isEmpty())) {
            throw new IllegalArgumentException("Available backend must not contain failure diagnostics");
        }
        if (status == Status.MISSING_DEPENDENCIES && missingDependencies.isEmpty()) {
            throw new IllegalArgumentException("Missing dependency status requires dependency names");
        }
    }

    public static StorageBackendAvailability available() {
        return new StorageBackendAvailability(Status.AVAILABLE, List.of(), "");
    }

    public boolean isAvailable() {
        return this.status == Status.AVAILABLE;
    }

    public enum Status {
        AVAILABLE,
        MISSING_DEPENDENCIES,
        ADAPTER_NOT_IMPLEMENTED,
        REFERENCE_CONTRACT_INCOMPLETE
    }
}
