package com.y271727uy.voxy.common.importing.job;

import java.util.concurrent.CompletableFuture;

public interface ImportJobHandle {
    String jobId();

    ImportProgressSnapshot snapshot();

    boolean cancel();

    CompletableFuture<ImportProgressSnapshot> completion();

    default ImportProgressSnapshot await() {
        return completion().join();
    }
}
