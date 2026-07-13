package com.y271727uy.voxy.common.importing.job;

public record ImportProgressSnapshot(String jobId, ImportJobState state, long completed, long total,
                                     String checkpointCursor, boolean resumed, long startedAtMillis,
                                     long finishedAtMillis, String failureType, String failureMessage) {
    public boolean isTerminal() {
        return this.state.isTerminal();
    }

    public double fraction() {
        if (this.total <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) this.completed / (double) this.total);
    }
}
