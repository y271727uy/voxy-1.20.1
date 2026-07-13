package com.y271727uy.voxy.client.core.model.snapshot;

public final class StaleModelSnapshotException extends IllegalStateException {
    public StaleModelSnapshotException(long expectedGeneration, long actualGeneration) {
        super("Model generation changed while capturing snapshot: expected "
                + expectedGeneration + ", got " + actualGeneration);
    }
}
