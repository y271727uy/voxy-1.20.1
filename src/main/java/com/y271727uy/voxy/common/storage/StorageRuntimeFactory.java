package com.y271727uy.voxy.common.storage;

import com.google.gson.JsonObject;
import com.y271727uy.voxy.common.storage.config.StorageBuildContext;
import com.y271727uy.voxy.common.storage.config.StorageFactoryRegistry;
import com.y271727uy.voxy.common.storage.config.StorageGraph;
import com.y271727uy.voxy.common.storage.config.StorageGraphConfig;
import com.y271727uy.voxy.common.storage.config.StorageNodeConfig;
import com.y271727uy.voxy.common.storage.config.StorageNodeFactory;
import com.y271727uy.voxy.common.storage.provider.StorageBackendAvailability;
import com.y271727uy.voxy.common.storage.provider.StorageBackendProvider;
import com.y271727uy.voxy.common.storage.provider.StorageBackendProviders;
import com.y271727uy.voxy.common.storage.provider.StorageBackendRequest;
import com.y271727uy.voxy.common.storage.provider.StorageBackendUnavailableException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the configured leaf as a validated graph, then adds bounded asynchronous persistence. */
public final class StorageRuntimeFactory {
    private StorageRuntimeFactory() {
    }

    public static RuntimeStorage open(Path root, String backendId, int queueCapacity) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(backendId, "backendId");
        StorageBackendProvider provider = StorageBackendProviders.find(backendId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Voxy storage backend: " + backendId));
        StorageBackendAvailability availability = provider.availability();
        if (!availability.isAvailable()) {
            throw new StorageBackendUnavailableException(backendId, availability);
        }

        StorageFactoryRegistry registry = new StorageFactoryRegistry().register(providerFactory(provider));
        StorageGraphConfig config = new StorageGraphConfig(
                new StorageNodeConfig(backendId, new JsonObject(), List.of()));
        StorageGraph graph = registry.build(config, new StorageBuildContext(root, Map.of()));
        try {
            BoundedAsyncSectionStorage storage = new BoundedAsyncSectionStorage(
                    graph, queueCapacity, "Voxy storage [" + backendId + "]");
            return new RuntimeStorage(storage, provider, availability, config);
        } catch (RuntimeException | Error failure) {
            graph.close();
            throw failure;
        }
    }

    private static StorageNodeFactory providerFactory(StorageBackendProvider provider) {
        return new StorageNodeFactory() {
            @Override
            public String type() {
                return provider.descriptor().id();
            }

            @Override
            public void validateOptions(JsonObject options) {
                if (options.size() != 0) {
                    throw new IllegalArgumentException("Leaf backend options are not supported yet");
                }
            }

            @Override
            public SectionStorage create(StorageBuildContext context, JsonObject options,
                                         List<SectionStorage> children) {
                return provider.open(new StorageBackendRequest(context.storageRoot(), Map.of()));
            }
        };
    }

    public record RuntimeStorage(BoundedAsyncSectionStorage storage, StorageBackendProvider provider,
                                 StorageBackendAvailability availability, StorageGraphConfig graphConfig) {
        public RuntimeStorage {
            Objects.requireNonNull(storage, "storage");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(graphConfig, "graphConfig");
        }
    }
}
