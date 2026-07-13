package com.y271727uy.voxy.common.importer.dh;

@FunctionalInterface
public interface DhImportProgress {
    void update(int processedChunks, int totalChunks);

    static DhImportProgress ignored() {
        return (processed, total) -> { };
    }
}
