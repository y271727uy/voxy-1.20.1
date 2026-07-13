package com.y271727uy.voxy.common.release;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OneShotFailureDisableTest {
    @Test
    public void recordsAndClosesOnlyOnce() {
        OneShotFailureDisable gate = new OneShotFailureDisable();
        AtomicInteger reports = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();

        assertTrue(gate.disable(new IllegalStateException("save failed"),
                failure -> reports.incrementAndGet(), closes::incrementAndGet));
        assertFalse(gate.disable(new IllegalStateException("again"),
                failure -> reports.incrementAndGet(), closes::incrementAndGet));

        assertTrue(gate.isDisabled());
        assertEquals(1, reports.get());
        assertEquals(1, closes.get());
    }

    @Test
    public void teardownFailureIsSuppressedAndDoesNotEscape() {
        OneShotFailureDisable gate = new OneShotFailureDisable();
        IllegalStateException saveFailure = new IllegalStateException("save failed");

        assertTrue(gate.disable(saveFailure, failure -> { },
                () -> { throw new IllegalArgumentException("close failed"); }));

        assertEquals(1, saveFailure.getSuppressed().length);
        assertEquals("close failed", saveFailure.getSuppressed()[0].getMessage());
    }
}
