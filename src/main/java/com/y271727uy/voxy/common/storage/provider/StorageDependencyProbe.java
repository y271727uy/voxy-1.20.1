package com.y271727uy.voxy.common.storage.provider;

@FunctionalInterface
public interface StorageDependencyProbe {
    boolean isClassPresent(String className);

    static StorageDependencyProbe contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = StorageDependencyProbe.class.getClassLoader();
        ClassLoader finalLoader = loader;
        return className -> {
            try {
                Class.forName(className, false, finalLoader);
                return true;
            } catch (ClassNotFoundException | LinkageError | SecurityException exception) {
                return false;
            }
        };
    }
}
