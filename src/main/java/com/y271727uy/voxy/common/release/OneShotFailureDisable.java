package com.y271727uy.voxy.common.release;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Records and tears down a failed subsystem at most once without leaking teardown failures. */
public final class OneShotFailureDisable {
    private final AtomicBoolean disabled = new AtomicBoolean();

    public boolean disable(Throwable failure, Reporter reporter, CloseSequence.Action shutdown) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(shutdown, "shutdown");
        if (!this.disabled.compareAndSet(false, true)) return false;
        try {
            shutdown.run();
        } catch (Throwable closeFailure) {
            if (closeFailure != failure) failure.addSuppressed(closeFailure);
        }
        try {
            reporter.report(failure);
        } catch (Throwable reportFailure) {
            if (reportFailure != failure) failure.addSuppressed(reportFailure);
        }
        return true;
    }

    public boolean isDisabled() {
        return this.disabled.get();
    }

    @FunctionalInterface
    public interface Reporter {
        void report(Throwable failure);
    }
}
