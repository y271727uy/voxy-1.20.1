package com.y271727uy.voxy.common.importer.dh;

public record DhImportReport(int acceptedRecords, int skippedRecords, int importedChunks,
                             boolean cancelled) {
}
