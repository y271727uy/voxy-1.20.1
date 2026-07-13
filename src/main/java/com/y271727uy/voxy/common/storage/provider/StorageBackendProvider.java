package com.y271727uy.voxy.common.storage.provider;

import com.y271727uy.voxy.common.storage.SectionStorage;

/** SPI for constructing a section storage backend after availability has been diagnosed. */
public interface StorageBackendProvider {
    StorageBackendDescriptor descriptor();

    StorageBackendAvailability availability(StorageDependencyProbe dependencies);

    default StorageBackendAvailability availability() {
        return availability(StorageDependencyProbe.contextClassLoader());
    }

    SectionStorage open(StorageBackendRequest request);
}
