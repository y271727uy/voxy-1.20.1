package com.y271727uy.voxy.client.core.model.snapshot;

import java.util.Objects;

public record ModelLookup<T>(State state, T value) {
    public enum State { READY, PENDING, UNSUPPORTED }

    public ModelLookup {
        Objects.requireNonNull(state, "state");
        if ((state == State.READY) != (value != null)) {
            throw new IllegalArgumentException("Only READY lookups may carry a value");
        }
    }

    public static <T> ModelLookup<T> ready(T value) {
        return new ModelLookup<>(State.READY, Objects.requireNonNull(value, "value"));
    }

    public static <T> ModelLookup<T> pending() {
        return new ModelLookup<>(State.PENDING, null);
    }

    public static <T> ModelLookup<T> unsupported() {
        return new ModelLookup<>(State.UNSUPPORTED, null);
    }
}
