package com.y271727uy.voxy.common.importer.dh;

@FunctionalInterface
public interface DhDependencyProbe {
    boolean isPresent(String className);

    static DhDependencyProbe runtime() {
        return className -> {
            try {
                Class.forName(className, false, DhDependencyProbe.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                return false;
            }
        };
    }
}
