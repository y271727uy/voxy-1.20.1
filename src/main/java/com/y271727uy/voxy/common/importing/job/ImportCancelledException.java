package com.y271727uy.voxy.common.importing.job;

public final class ImportCancelledException extends Exception {
    public ImportCancelledException(String jobId) {
        super("Import job was cancelled: " + jobId);
    }
}
