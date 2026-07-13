package com.y271727uy.voxy.common.storage.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class StorageBuildContext {
    private final Path storageRoot;
    private final Map<String, ?> dependencies;

    public StorageBuildContext(Path storageRoot, Map<String, ?> dependencies) {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.dependencies = Map.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
    }

    public Path storageRoot() {
        return this.storageRoot;
    }

    public Path resolvePath(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("Storage path must be relative: " + relativePath);
        }
        Path resolved = this.storageRoot.resolve(path).normalize();
        if (!resolved.startsWith(this.storageRoot)) {
            throw new IllegalArgumentException("Storage path escapes its root: " + relativePath);
        }
        return resolved;
    }

    public boolean hasDependency(String name, Class<?> type) {
        Object dependency = this.dependencies.get(name);
        return dependency != null && type.isInstance(dependency);
    }

    public <T> T requireDependency(String name, Class<T> type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Object dependency = this.dependencies.get(name);
        if (dependency == null) {
            throw new IllegalStateException("Required storage dependency '" + name + "' is unavailable");
        }
        if (!type.isInstance(dependency)) {
            throw new IllegalStateException("Storage dependency '" + name + "' has type "
                    + dependency.getClass().getName() + ", expected " + type.getName());
        }
        return type.cast(dependency);
    }
}
