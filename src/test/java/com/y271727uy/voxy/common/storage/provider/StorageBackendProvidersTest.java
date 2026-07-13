package com.y271727uy.voxy.common.storage.provider;

import com.y271727uy.voxy.common.storage.SectionStorage;
import com.y271727uy.voxy.common.world.WorldSection;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StorageBackendProvidersTest {
    @Test
    public void catalogExposesReferenceAndExternalBackendsWithoutFalseAvailability() {
        Map<String, StorageBackendProvider> providers = StorageBackendProviders.index(
                StorageBackendProviders.builtIns());
        StorageDependencyProbe noDependencies = ignored -> false;

        assertEquals(List.of("file", "lmdb", "memory", "redis", "rocksdb"),
                providers.keySet().stream().sorted().toList());
        assertTrue(providers.get("file").availability(noDependencies).isAvailable());
        assertTrue(providers.get("memory").availability(noDependencies).isAvailable());
        assertEquals(StorageBackendAvailability.Status.MISSING_DEPENDENCIES,
                providers.get("rocksdb").availability(noDependencies).status());
        assertEquals(StorageBackendAvailability.Status.MISSING_DEPENDENCIES,
                providers.get("lmdb").availability(noDependencies).status());
        assertEquals(StorageBackendAvailability.Status.MISSING_DEPENDENCIES,
                providers.get("redis").availability(noDependencies).status());
    }

    @Test
    public void classPresenceAloneNeverClaimsUnlinkedAdapterIsAvailable() {
        StorageDependencyProbe allClassesPresent = ignored -> true;

        assertEquals(StorageBackendAvailability.Status.ADAPTER_NOT_IMPLEMENTED,
                provider("rocksdb").availability(allClassesPresent).status());
        assertEquals(StorageBackendAvailability.Status.ADAPTER_NOT_IMPLEMENTED,
                provider("lmdb").availability(allClassesPresent).status());
        assertEquals(StorageBackendAvailability.Status.REFERENCE_CONTRACT_INCOMPLETE,
                provider("redis").availability(allClassesPresent).status());
        assertFalse(provider("redis").descriptor().supportsPositionIteration());
    }

    @Test
    public void memoryProviderCreatesWorkingSectionStorage() {
        try (SectionStorage storage = provider("memory").open(StorageBackendRequest.inMemory())) {
            WorldSection source = new WorldSection(0, 2, 3, 4);
            source.set(1, 2, 3, 0x1234L);
            storage.saveSection(source);

            WorldSection loaded = new WorldSection(0, 2, 3, 4);
            assertEquals(SectionStorage.LOAD_SUCCESS, storage.loadSection(loaded));
            assertEquals(0x1234L, loaded.get(1, 2, 3));
        }
    }

    @Test
    public void unavailableProviderFailsClosedWithDiagnostic() {
        StorageBackendUnavailableException exception = assertThrows(
                StorageBackendUnavailableException.class,
                () -> provider("rocksdb").open(new StorageBackendRequest(null, Map.of())));

        assertEquals("rocksdb", exception.backendId());
        assertFalse(exception.availability().isAvailable());
    }

    private static StorageBackendProvider provider(String id) {
        return StorageBackendProviders.find(id).orElseThrow();
    }
}
