package com.y271727uy.voxy.common.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WorldUpdaterTest {
    @Test
    public void updateFailureReleasesCurrentTargetAndPropagatedChild() {
        WorldSection target = acquired(new WorldSection(1, 0, 0, 0));
        WorldSection child = acquired(new WorldSection(0, 0, 0, 0));

        assertThrows(IllegalStateException.class, () -> WorldUpdater.updateOwnedTarget(target, child,
                () -> { throw new IllegalStateException("update failure"); }, ignored -> { }));

        assertEquals(0, target.refCount());
        assertEquals(0, child.refCount());
    }

    @Test
    public void dirtyMetadataFailureReleasesCurrentTargetAndPropagatedChild() {
        WorldSection target = acquired(new WorldSection(1, 0, 0, 0));
        WorldSection child = acquired(new WorldSection(0, 0, 0, 0));

        assertThrows(IllegalStateException.class, () -> WorldUpdater.updateOwnedTarget(target, child,
                () -> new WorldSection.UpdateResult(true, 2),
                ignored -> { throw new IllegalStateException("dirty listener failure"); }));

        assertEquals(0, target.refCount());
        assertEquals(0, child.refCount());
    }

    private static WorldSection acquired(WorldSection section) {
        section.acquire();
        return section;
    }
}
