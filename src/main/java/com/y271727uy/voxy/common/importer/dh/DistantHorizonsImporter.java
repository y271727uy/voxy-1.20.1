package com.y271727uy.voxy.common.importer.dh;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DistantHorizonsImporter {
    private final DhFullDataDecoder decoder;
    private final DhDecompressor decompressor;

    public DistantHorizonsImporter(DhFullDataDecoder decoder, DhDecompressor decompressor) {
        this.decoder = decoder;
        this.decompressor = decompressor;
    }

    public DhImportReport importRecords(Iterable<DhFullDataRecord> source,
                                        DhImportCancellation cancellation,
                                        DhImportProgress progress) throws IOException {
        List<DhFullDataRecord> accepted = new ArrayList<>();
        int skipped = 0;
        for (DhFullDataRecord record : source) {
            if (record.detailLevel() == 0 && record.dataFormatVersion() == 1
                    && (record.compressionMode() == 3 || record.compressionMode() == 4)) {
                accepted.add(record);
            } else {
                skipped++;
            }
        }
        accepted.sort(Comparator.comparingLong(DistantHorizonsImporter::distanceFromOrigin));

        int importedChunks = 0;
        int acceptedRecords = 0;
        int totalChunks = accepted.size() * 16;
        progress.update(0, totalChunks);
        for (DhFullDataRecord record : accepted) {
            try {
                cancellation.throwIfCancelled();
                importedChunks += this.decoder.decode(record, this.decompressor, cancellation);
            } catch (DhImportCancelledException exception) {
                return new DhImportReport(acceptedRecords, skipped, importedChunks, true);
            }
            acceptedRecords++;
            progress.update(importedChunks, totalChunks);
        }
        return new DhImportReport(acceptedRecords, skipped, importedChunks, false);
    }

    public DhImportReport importSource(DhIndexedRecordSource source,
                                       DhImportCancellation cancellation,
                                       DhImportProgress progress) throws IOException {
        List<DhFullDataIndex> accepted = new ArrayList<>();
        int skipped = 0;
        List<DhFullDataIndex> scanned;
        try {
            scanned = source.scan(cancellation);
        } catch (DhImportCancelledException exception) {
            return new DhImportReport(0, 0, 0, true);
        }
        for (DhFullDataIndex index : scanned) {
            if (cancellation.isCancelled()) {
                return new DhImportReport(0, skipped, 0, true);
            }
            if (index.detailLevel() == 0 && index.dataFormatVersion() == 1
                    && (index.compressionMode() == 3 || index.compressionMode() == 4)) {
                accepted.add(index);
            } else {
                skipped++;
            }
        }
        if (cancellation.isCancelled()) {
            return new DhImportReport(0, skipped, 0, true);
        }
        accepted.sort(Comparator.comparingLong(index -> distanceFromOrigin(index.x(), index.z())));
        if (cancellation.isCancelled()) {
            return new DhImportReport(0, skipped, 0, true);
        }

        int importedChunks = 0;
        int acceptedRecords = 0;
        int totalChunks = accepted.size() * 16;
        progress.update(0, totalChunks);
        for (DhFullDataIndex index : accepted) {
            try {
                cancellation.throwIfCancelled();
                DhFullDataRecord record = source.fetch(index, cancellation);
                cancellation.throwIfCancelled();
                importedChunks += this.decoder.decode(record, this.decompressor, cancellation);
            } catch (DhImportCancelledException exception) {
                return new DhImportReport(acceptedRecords, skipped, importedChunks, true);
            }
            acceptedRecords++;
            progress.update(importedChunks, totalChunks);
        }
        return new DhImportReport(acceptedRecords, skipped, importedChunks, false);
    }

    private static long distanceFromOrigin(DhFullDataRecord record) {
        return distanceFromOrigin(record.x(), record.z());
    }

    static long distanceFromOrigin(int x, int z) {
        long xSquare = (long) x * x;
        long zSquare = (long) z * z;
        return Long.MAX_VALUE - xSquare < zSquare ? Long.MAX_VALUE : xSquare + zSquare;
    }
}
