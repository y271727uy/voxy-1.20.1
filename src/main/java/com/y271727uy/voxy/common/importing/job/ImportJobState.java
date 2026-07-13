package com.y271727uy.voxy.common.importing.job;

public enum ImportJobState {
    QUEUED,
    RUNNING,
    CANCELLING,
    CANCELLED,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == CANCELLED || this == SUCCEEDED || this == FAILED;
    }
}
