package com.y271727uy.voxy.common.importer.dh;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DistantHorizonsImporterTest {
    @Test
    public void filtersUnsupportedRowsSortsNearestFirstAndReportsProgress() throws IOException {
        List<Integer> emittedX = new ArrayList<>();
        DhFullDataDecoder decoder = new DhFullDataDecoder(0, 16, (biome, block) -> 0,
                (x, y, z, voxels, count) -> emittedX.add(x));
        DistantHorizonsImporter importer = new DistantHorizonsImporter(decoder,
                (mode, bytes) -> new ByteArrayInputStream(bytes));
        byte[] mapping = emptyMappings();
        byte[] columns = emptyColumns();
        List<int[]> progress = new ArrayList<>();

        DhImportReport report = importer.importRecords(List.of(
                        new DhFullDataRecord(10, 0, 0, 1, 3, columns, mapping),
                        new DhFullDataRecord(0, 0, 1, 1, 3, columns, mapping),
                        new DhFullDataRecord(1, 0, 0, 1, 3, columns, mapping)),
                DhImportCancellation.never(), (processed, total) -> progress.add(new int[]{processed, total}));

        assertEquals(new DhImportReport(2, 1, 32, false), report);
        assertEquals(4, (int) emittedX.get(0));
        assertEquals(40, (int) emittedX.get(16));
        assertEquals(0, progress.get(0)[0]);
        assertEquals(32, progress.get(progress.size() - 1)[0]);
        assertEquals(32, progress.get(progress.size() - 1)[1]);
    }

    @Test
    public void cancellationStopsBeforeNextRecord() throws IOException {
        AtomicBoolean cancelled = new AtomicBoolean();
        DhFullDataDecoder decoder = new DhFullDataDecoder(0, 16, (biome, block) -> 0,
                (x, y, z, voxels, count) -> { });
        DistantHorizonsImporter importer = new DistantHorizonsImporter(decoder,
                (mode, bytes) -> new ByteArrayInputStream(bytes));
        byte[] mapping = emptyMappings();
        byte[] columns = emptyColumns();

        DhImportReport report = importer.importRecords(List.of(
                new DhFullDataRecord(0, 0, 0, 1, 3, columns, mapping),
                        new DhFullDataRecord(1, 0, 0, 1, 3, columns, mapping)),
                cancelled::get, (processed, total) -> {
                    if (processed == 16) cancelled.set(true);
                });

        assertEquals(1, report.acceptedRecords());
        assertEquals(16, report.importedChunks());
        assertTrue(report.cancelled());
    }

    @Test
    public void indexedSourceFetchesBlobsOnlyUntilCancellation() throws IOException {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger fetches = new AtomicInteger();
        byte[] mapping = emptyMappings();
        byte[] columns = emptyColumns();
        DhIndexedRecordSource source = new DhIndexedRecordSource() {
            @Override
            public List<DhFullDataIndex> scan(DhImportCancellation cancellation) {
                return List.of(new DhFullDataIndex(0, 0, 0, 1, 3),
                        new DhFullDataIndex(1, 0, 0, 1, 3));
            }

            @Override
            public DhFullDataRecord fetch(DhFullDataIndex index, DhImportCancellation cancellation) {
                fetches.incrementAndGet();
                return new DhFullDataRecord(index.x(), index.z(), index.detailLevel(),
                        index.dataFormatVersion(), index.compressionMode(), columns, mapping);
            }

            @Override
            public void close() {
            }
        };
        DistantHorizonsImporter importer = new DistantHorizonsImporter(
                new DhFullDataDecoder(0, 16, (biome, block) -> 0, (x, y, z, voxels, count) -> { }),
                (mode, bytes) -> new ByteArrayInputStream(bytes));

        DhImportReport report = importer.importSource(source, cancelled::get, (processed, total) -> {
            if (processed == 16) cancelled.set(true);
        });

        assertEquals(1, fetches.get());
        assertEquals(1, report.acceptedRecords());
        assertTrue(report.cancelled());
    }

    @Test
    public void distanceFromOriginSaturatesInsteadOfOverflowing() {
        assertEquals(25L, DistantHorizonsImporter.distanceFromOrigin(3, 4));
        assertEquals(Long.MAX_VALUE,
                DistantHorizonsImporter.distanceFromOrigin(Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    private static byte[] emptyMappings() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0);
        }
        return bytes.toByteArray();
    }

    private static byte[] emptyColumns() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            for (int index = 0; index < 64 * 64; index++) output.writeShort(0);
        }
        return bytes.toByteArray();
    }
}
