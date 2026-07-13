package com.y271727uy.voxy.common.storage.provider;

public final class StorageBackendUnavailableException extends IllegalStateException {
    private final String backendId;
    private final StorageBackendAvailability availability;

    public StorageBackendUnavailableException(String backendId, StorageBackendAvailability availability) {
        super("Storage backend '" + backendId + "' is unavailable: " + availability.status()
                + (availability.detail().isEmpty() ? "" : " (" + availability.detail() + ")"));
        this.backendId = backendId;
        this.availability = availability;
    }

    public String backendId() {
        return this.backendId;
    }

    public StorageBackendAvailability availability() {
        return this.availability;
    }
}
