package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.common.world.WorldSectionKey;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HierarchicalOcclusionTraverserTest {
    @Test
    public void rejectedAncestorPrunesSelectedDescendantsWithOneTest() {
        HierarchicalOcclusionTraverser traverser = new HierarchicalOcclusionTraverser(2);
        long first = WorldSectionKey.pack(0, 0, 0, 0);
        long second = WorldSectionKey.pack(0, 1, 0, 0);

        HierarchicalOcclusionTraverser.Traversal result = traverser.traverse(
                Set.of(first, second), bounds -> false);

        assertTrue(result.visibleKeys().isEmpty());
        assertEquals(1, result.metrics().visited());
        assertEquals(1, result.metrics().prunedBranches());
        assertEquals(2, result.metrics().candidates());
    }

    @Test
    public void visibleHierarchyPreservesMixedLevelCoverageCut() {
        HierarchicalOcclusionTraverser traverser = new HierarchicalOcclusionTraverser(2);
        long coarse = WorldSectionKey.pack(1, 0, 0, 0);
        long fine = WorldSectionKey.pack(0, 2, 0, 0);
        Set<Long> selected = Set.of(coarse, fine);

        HierarchicalOcclusionTraverser.Traversal result = traverser.traverse(selected, bounds -> true);

        assertEquals(selected, result.visibleKeys());
        assertEquals(2, result.metrics().emitted());
        assertEquals(1, result.metrics().roots());
    }

    @Test
    public void negativeSectionBoundsUseFloorHierarchyCoordinates() {
        HierarchicalOcclusionTraverser traverser = new HierarchicalOcclusionTraverser(2);
        long selected = WorldSectionKey.pack(0, -1, -2, -3);

        HierarchicalOcclusionTraverser.Traversal result = traverser.traverse(Set.of(selected), bounds -> {
            if (bounds.maxX() == 0 && bounds.minX() == -128) {
                return true;
            }
            return bounds.minX() < 0 && bounds.minY() < 0 && bounds.minZ() < 0;
        });

        assertEquals(Set.of(selected), result.visibleKeys());
        assertEquals(new HierarchicalOcclusionTraverser.Bounds(-32, -64, -96, 0, -32, -64),
                HierarchicalOcclusionTraverser.bounds(selected));
    }

    @Test
    public void rootsAreCulledIndependently() {
        HierarchicalOcclusionTraverser traverser = new HierarchicalOcclusionTraverser(1);
        long west = WorldSectionKey.pack(0, -1, 0, 0);
        long east = WorldSectionKey.pack(0, 2, 0, 0);

        HierarchicalOcclusionTraverser.Traversal result = traverser.traverse(
                Set.of(west, east), bounds -> bounds.minX() < 0);

        assertEquals(Set.of(west), result.visibleKeys());
        assertEquals(2, result.metrics().roots());
        assertEquals(1, result.metrics().prunedBranches());
    }
}
