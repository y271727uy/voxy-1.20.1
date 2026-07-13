package com.y271727uy.voxy.common.release;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CloseSequenceTest {
    @Test
    public void laterStepsRunAndFailuresAreSuppressed() {
        List<String> closed = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> CloseSequence.run(
                CloseSequence.Step.of("first", () -> {
                    closed.add("first");
                    throw new IllegalStateException("first failure");
                }),
                CloseSequence.Step.of("second", () -> {
                    closed.add("second");
                    throw new IllegalArgumentException("second failure");
                }),
                CloseSequence.Step.of("third", () -> closed.add("third"))));

        assertEquals(List.of("first", "second", "third"), closed);
        assertEquals("first failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("second failure", failure.getSuppressed()[0].getMessage());
    }
}
