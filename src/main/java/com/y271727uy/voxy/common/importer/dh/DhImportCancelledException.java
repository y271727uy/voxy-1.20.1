package com.y271727uy.voxy.common.importer.dh;

import java.io.IOException;

public final class DhImportCancelledException extends IOException {
    public DhImportCancelledException() {
        super("Distant Horizons import was cancelled");
    }
}
