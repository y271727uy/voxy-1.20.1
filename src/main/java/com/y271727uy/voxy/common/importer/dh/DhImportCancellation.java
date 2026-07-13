package com.y271727uy.voxy.common.importer.dh;

@FunctionalInterface
public interface DhImportCancellation {
    boolean isCancelled();

    default void throwIfCancelled() throws DhImportCancelledException {
        if (isCancelled()) throw new DhImportCancelledException();
    }

    static DhImportCancellation never() {
        return () -> false;
    }
}
