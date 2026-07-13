package com.y271727uy.voxy.common.importer.chunky;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChunkyLiveIntegrationTest {
    @Test
    public void absentChunkyFailsClosedWithoutLinkingItsTypes() {
        ClassLoader empty = new ClassLoader(null) { };

        ChunkyLiveIntegration.Availability availability = ChunkyLiveIntegration.detect(empty);

        assertFalse(availability.chunkyPresent());
        assertFalse(availability.liveHookLinked());
        assertTrue(availability.detail().contains("not present"));
    }
}
