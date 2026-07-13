package com.y271727uy.voxy.common.importer.dh;

import java.io.IOException;
import java.util.List;

public interface DhIndexedRecordSource extends AutoCloseable {
    List<DhFullDataIndex> scan(DhImportCancellation cancellation) throws IOException;

    DhFullDataRecord fetch(DhFullDataIndex index, DhImportCancellation cancellation) throws IOException;

    @Override
    void close() throws IOException;
}
