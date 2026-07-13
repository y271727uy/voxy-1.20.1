package com.y271727uy.voxy.common.importing.job;

import java.util.Optional;

public interface ImportJobContext {
    String jobId();

    Optional<ImportCheckpoint> resumeCheckpoint();

    boolean isCancellationRequested();

    void throwIfCancellationRequested() throws ImportCancelledException;

    /** Publishes transient progress without changing the durable recovery cursor. */
    void reportProgress(long completed, long total);

    /** Durably saves the cursor, then publishes its progress as the recoverable position. */
    void checkpoint(String cursor, long completed, long total) throws Exception;
}
