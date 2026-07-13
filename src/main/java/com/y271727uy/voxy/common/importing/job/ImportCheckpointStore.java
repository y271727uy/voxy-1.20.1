package com.y271727uy.voxy.common.importing.job;

import java.util.Optional;

public interface ImportCheckpointStore {
    Optional<ImportCheckpoint> load(String jobId) throws Exception;

    void save(ImportCheckpoint checkpoint) throws Exception;

    void clear(String jobId) throws Exception;
}
