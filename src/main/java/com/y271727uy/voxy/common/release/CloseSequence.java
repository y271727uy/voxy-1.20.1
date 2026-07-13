package com.y271727uy.voxy.common.release;

import java.util.Objects;

/** Runs every teardown step and rethrows the first failure with later failures suppressed. */
public final class CloseSequence {
    private CloseSequence() {
    }

    public static void run(Step... steps) {
        Throwable failure = null;
        for (Step step : steps) {
            Objects.requireNonNull(step, "step");
            try {
                step.action().run();
            } catch (Throwable stepFailure) {
                if (failure == null) {
                    failure = stepFailure;
                } else if (failure != stepFailure) {
                    failure.addSuppressed(stepFailure);
                }
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new IllegalStateException("Lifecycle close failed", failure);
    }

    public record Step(String name, Action action) {
        public Step {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Close step name is required");
            Objects.requireNonNull(action, "action");
        }

        public static Step of(String name, Action action) {
            return new Step(name, action);
        }
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }
}
