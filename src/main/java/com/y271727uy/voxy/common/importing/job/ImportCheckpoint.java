package com.y271727uy.voxy.common.importing.job;

import java.util.Objects;

/** A durable importer cursor. A total of -1 means that discovery is incomplete. */
public record ImportCheckpoint(int schemaVersion, String jobId, String cursor, long completed, long total) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final long UNKNOWN_TOTAL = -1;

    public ImportCheckpoint {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported import checkpoint schema: " + schemaVersion);
        }
        jobId = requireText(jobId, "jobId");
        cursor = Objects.requireNonNull(cursor, "cursor");
        validateProgress(completed, total);
    }

    public ImportCheckpoint(String jobId, String cursor, long completed, long total) {
        this(CURRENT_SCHEMA_VERSION, jobId, cursor, completed, total);
    }

    static void validateProgress(long completed, long total) {
        if (completed < 0) {
            throw new IllegalArgumentException("Completed work cannot be negative");
        }
        if (total < UNKNOWN_TOTAL || (total != UNKNOWN_TOTAL && completed > total)) {
            throw new IllegalArgumentException("Invalid import progress " + completed + "/" + total);
        }
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
