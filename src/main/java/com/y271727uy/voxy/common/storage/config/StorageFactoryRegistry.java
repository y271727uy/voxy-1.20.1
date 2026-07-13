package com.y271727uy.voxy.common.storage.config;

import com.google.gson.JsonObject;
import com.y271727uy.voxy.common.storage.SectionStorage;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StorageFactoryRegistry {
    private final Map<String, StorageNodeFactory> factories = new LinkedHashMap<>();

    public StorageFactoryRegistry register(StorageNodeFactory factory) {
        Objects.requireNonNull(factory, "factory");
        String type = factory.type();
        if (type == null || !type.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid storage factory type: " + type);
        }
        if (factory.minimumChildren() < 0 || factory.maximumChildren() < factory.minimumChildren()) {
            throw new IllegalArgumentException("Invalid child limits for storage factory '" + type + "'");
        }
        if (this.factories.putIfAbsent(type, factory) != null) {
            throw new IllegalArgumentException("Storage factory type is already registered: " + type);
        }
        return this;
    }

    public StorageGraph build(StorageGraphConfig config, StorageBuildContext context) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(context, "context");
        preflight(config.root(), context, "root");

        ArrayList<SectionStorage> resources = new ArrayList<>();
        IdentityHashMap<SectionStorage, Boolean> identities = new IdentityHashMap<>();
        try {
            SectionStorage root = buildNode(config.root(), context, resources, identities, "root");
            return new StorageGraph(root, resources);
        } catch (RuntimeException | Error failure) {
            StorageGraph.closeResources(resources, failure);
            throw failure;
        }
    }

    private void preflight(StorageNodeConfig config, StorageBuildContext context, String location) {
        StorageNodeFactory factory = this.factories.get(config.type());
        if (factory == null) {
            throw new IllegalArgumentException("Unknown storage node type '" + config.type() + "' at " + location);
        }
        int childCount = config.children().size();
        if (childCount < factory.minimumChildren() || childCount > factory.maximumChildren()) {
            throw new IllegalArgumentException("Storage node '" + config.type() + "' at " + location + " requires "
                    + childRange(factory) + " children, got " + childCount);
        }
        JsonObject options = config.options();
        try {
            factory.validateOptions(options);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid options for storage node '" + config.type() + "' at "
                    + location + ": " + exception.getMessage(), exception);
        }
        for (StorageNodeFactory.Dependency dependency : factory.requiredDependencies()) {
            if (!context.hasDependency(dependency.name(), dependency.type())) {
                throw new IllegalStateException("Storage node '" + config.type() + "' at " + location
                        + " requires unavailable dependency '" + dependency.name() + "' of type "
                        + dependency.type().getName());
            }
        }
        for (int index = 0; index < childCount; index++) {
            preflight(config.children().get(index), context, location + ".children[" + index + "]");
        }
    }

    private SectionStorage buildNode(StorageNodeConfig config, StorageBuildContext context,
                                     List<SectionStorage> resources,
                                     IdentityHashMap<SectionStorage, Boolean> identities,
                                     String location) {
        ArrayList<SectionStorage> children = new ArrayList<>(config.children().size());
        for (int index = 0; index < config.children().size(); index++) {
            children.add(buildNode(config.children().get(index), context, resources, identities,
                    location + ".children[" + index + "]"));
        }
        SectionStorage storage;
        try {
            storage = Objects.requireNonNull(this.factories.get(config.type())
                    .create(context, config.options(), List.copyOf(children)), "factory result");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to build storage node '" + config.type() + "' at " + location,
                    exception);
        }
        if (identities.put(storage, Boolean.TRUE) != null) {
            throw new IllegalStateException("Storage factory '" + config.type() + "' reused a backend instance at "
                    + location);
        }
        resources.add(storage);
        return storage;
    }

    private static String childRange(StorageNodeFactory factory) {
        return factory.minimumChildren() == factory.maximumChildren()
                ? Integer.toString(factory.minimumChildren())
                : factory.minimumChildren() + ".." + factory.maximumChildren();
    }
}
