package com.y271727uy.voxy.common.storage.provider;

import com.y271727uy.voxy.common.storage.InMemorySectionStorage;
import com.y271727uy.voxy.common.storage.FileSectionStorage;
import com.y271727uy.voxy.common.storage.SectionStorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Built-in provider catalog. External database entries remain diagnostic-only until their adapters are linked. */
public final class StorageBackendProviders {
    private static final String ROCKS_DB_CLASS = "org.rocksdb.RocksDB";
    private static final String LMDB_CLASS = "org.lwjgl.util.lmdb.LMDB";
    private static final String JEDIS_CLASS = "redis.clients.jedis.JedisPool";
    private static final String COMMONS_POOL_CLASS = "org.apache.commons.pool2.impl.GenericObjectPool";

    private static final List<StorageBackendProvider> BUILT_INS = List.of(
            new FileProvider(),
            new InMemoryProvider(),
            descriptorOnly(new StorageBackendDescriptor("rocksdb", "RocksDB",
                            StorageBackendDescriptor.StorageKind.LOCAL_DATABASE,
                            true, true, true, List.of(ROCKS_DB_CLASS)),
                    StorageBackendAvailability.Status.ADAPTER_NOT_IMPLEMENTED,
                    "rocksdbjni 10.2.1 is not bundled; the Forge SectionStorage adapter is not linked"),
            descriptorOnly(new StorageBackendDescriptor("lmdb", "LMDB",
                            StorageBackendDescriptor.StorageKind.LOCAL_DATABASE,
                            true, true, true, List.of(LMDB_CLASS)),
                    StorageBackendAvailability.Status.ADAPTER_NOT_IMPLEMENTED,
                    "LWJGL LMDB 3.3.1 and its platform native are not bundled; the adapter is not linked"),
            descriptorOnly(new StorageBackendDescriptor("redis", "Redis",
                            StorageBackendDescriptor.StorageKind.REMOTE_DATABASE,
                            true, false, false, List.of(JEDIS_CLASS, COMMONS_POOL_CLASS)),
                    StorageBackendAvailability.Status.REFERENCE_CONTRACT_INCOMPLETE,
                    "the Voxy reference backend does not implement iteratePositions required by SectionStorage")
    );

    private StorageBackendProviders() {
    }

    private static final class FileProvider implements StorageBackendProvider {
        private static final StorageBackendDescriptor DESCRIPTOR = new StorageBackendDescriptor(
                "file", "Atomic file storage", StorageBackendDescriptor.StorageKind.LOCAL_DATABASE,
                true, false, true, List.of());

        @Override
        public StorageBackendDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public StorageBackendAvailability availability(StorageDependencyProbe dependencies) {
            Objects.requireNonNull(dependencies, "dependencies");
            return StorageBackendAvailability.available();
        }

        @Override
        public SectionStorage open(StorageBackendRequest request) {
            Objects.requireNonNull(request, "request");
            if (request.root() == null) {
                throw new IllegalArgumentException("File storage requires a root path");
            }
            return new FileSectionStorage(request.root());
        }
    }

    public static List<StorageBackendProvider> builtIns() {
        return BUILT_INS;
    }

    public static Optional<StorageBackendProvider> find(String id) {
        Objects.requireNonNull(id, "id");
        return BUILT_INS.stream().filter(provider -> provider.descriptor().id().equals(id)).findFirst();
    }

    /** Validates and indexes an extended provider list for the future ServiceLoader integration point. */
    public static Map<String, StorageBackendProvider> index(Iterable<StorageBackendProvider> providers) {
        LinkedHashMap<String, StorageBackendProvider> indexed = new LinkedHashMap<>();
        for (StorageBackendProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String id = provider.descriptor().id();
            if (indexed.putIfAbsent(id, provider) != null) {
                throw new IllegalArgumentException("Duplicate storage backend provider id: " + id);
            }
        }
        return Map.copyOf(indexed);
    }

    private static StorageBackendProvider descriptorOnly(StorageBackendDescriptor descriptor,
                                                          StorageBackendAvailability.Status linkedStatus,
                                                          String detail) {
        return new DescriptorOnlyProvider(descriptor, linkedStatus, detail);
    }

    private static final class InMemoryProvider implements StorageBackendProvider {
        private static final StorageBackendDescriptor DESCRIPTOR = new StorageBackendDescriptor(
                "memory", "In-memory reference", StorageBackendDescriptor.StorageKind.MEMORY,
                false, false, true, List.of());

        @Override
        public StorageBackendDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public StorageBackendAvailability availability(StorageDependencyProbe dependencies) {
            Objects.requireNonNull(dependencies, "dependencies");
            return StorageBackendAvailability.available();
        }

        @Override
        public SectionStorage open(StorageBackendRequest request) {
            Objects.requireNonNull(request, "request");
            return new InMemorySectionStorage();
        }
    }

    private static final class DescriptorOnlyProvider implements StorageBackendProvider {
        private final StorageBackendDescriptor descriptor;
        private final StorageBackendAvailability.Status linkedStatus;
        private final String detail;

        private DescriptorOnlyProvider(StorageBackendDescriptor descriptor,
                                       StorageBackendAvailability.Status linkedStatus, String detail) {
            this.descriptor = descriptor;
            this.linkedStatus = linkedStatus;
            this.detail = detail;
        }

        @Override
        public StorageBackendDescriptor descriptor() {
            return this.descriptor;
        }

        @Override
        public StorageBackendAvailability availability(StorageDependencyProbe dependencies) {
            Objects.requireNonNull(dependencies, "dependencies");
            List<String> missing = new ArrayList<>();
            for (String className : this.descriptor.requiredClasses()) {
                if (!dependencies.isClassPresent(className)) missing.add(className);
            }
            if (!missing.isEmpty()) {
                return new StorageBackendAvailability(
                        StorageBackendAvailability.Status.MISSING_DEPENDENCIES, missing, this.detail);
            }
            return new StorageBackendAvailability(this.linkedStatus, List.of(), this.detail);
        }

        @Override
        public SectionStorage open(StorageBackendRequest request) {
            Objects.requireNonNull(request, "request");
            throw new StorageBackendUnavailableException(this.descriptor.id(), availability());
        }
    }
}
