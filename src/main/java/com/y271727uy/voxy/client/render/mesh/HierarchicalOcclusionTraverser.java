package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.common.world.WorldSection;
import com.y271727uy.voxy.common.world.WorldSectionKey;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Traverses a previously selected hole-free LOD cut through ancestor bounds. This CPU path keeps
 * the renderer contract independent of the later GPU/Hi-Z traversal backend.
 */
public final class HierarchicalOcclusionTraverser {
    private final int maxLevel;

    public HierarchicalOcclusionTraverser(int maxLevel) {
        if (maxLevel < 0 || maxLevel > WorldSectionKey.MAX_LOD_LEVEL) {
            throw new IllegalArgumentException("Invalid maximum LOD level: " + maxLevel);
        }
        this.maxLevel = maxLevel;
    }

    public Traversal traverse(Set<Long> selectedKeys, Visibility visibility) {
        Objects.requireNonNull(selectedKeys, "selectedKeys");
        Objects.requireNonNull(visibility, "visibility");
        if (selectedKeys.isEmpty()) return Traversal.EMPTY;

        Set<Long> closure = new HashSet<>(selectedKeys);
        Set<Long> roots = new LinkedHashSet<>();
        for (long selected : selectedKeys) {
            int level = WorldSectionKey.level(selected);
            if (level > this.maxLevel) {
                throw new IllegalArgumentException("Selected node exceeds traverser maximum level");
            }
            long ancestor = selected;
            while (level < this.maxLevel) {
                ancestor = parentKey(ancestor);
                closure.add(ancestor);
                level++;
            }
            roots.add(ancestor);
        }

        MutableMetrics metrics = new MutableMetrics(selectedKeys.size(), roots.size());
        Set<Long> visible = new LinkedHashSet<>();
        for (long root : roots) {
            visit(root, closure, selectedKeys, visibility, visible, metrics);
        }
        return new Traversal(Collections.unmodifiableSet(visible), metrics.freeze());
    }

    private static void visit(long key, Set<Long> closure, Set<Long> selectedKeys,
                              Visibility visibility, Set<Long> output, MutableMetrics metrics) {
        metrics.visited++;
        if (!visibility.isVisible(bounds(key))) {
            metrics.prunedBranches++;
            return;
        }
        if (selectedKeys.contains(key)) {
            output.add(key);
            metrics.emitted++;
            return;
        }
        int level = WorldSectionKey.level(key);
        if (level == 0) return;
        for (int child = 0; child < 8; child++) {
            long childKey = LodCoverageSelector.childKey(key, child);
            if (closure.contains(childKey)) {
                visit(childKey, closure, selectedKeys, visibility, output, metrics);
            }
        }
    }

    static Bounds bounds(long key) {
        int level = WorldSectionKey.level(key);
        long span = (long) WorldSection.SIDE_LENGTH << level;
        long minX = (long) WorldSectionKey.x(key) * span;
        long minY = (long) WorldSectionKey.y(key) * span;
        long minZ = (long) WorldSectionKey.z(key) * span;
        return new Bounds(minX, minY, minZ, minX + span, minY + span, minZ + span);
    }

    private static long parentKey(long child) {
        int level = WorldSectionKey.level(child);
        int parentLevel = level + 1;
        int x = WorldSectionKey.x(child) >> 1;
        int y = WorldSectionKey.y(child) >> 1;
        int z = WorldSectionKey.z(child) >> 1;
        if (!WorldSectionKey.canPack(parentLevel, x, y, z)) {
            throw new IllegalArgumentException("Selected node has no packable ancestor");
        }
        return WorldSectionKey.pack(parentLevel, x, y, z);
    }

    @FunctionalInterface
    public interface Visibility {
        boolean isVisible(Bounds bounds);
    }

    public record Bounds(long minX, long minY, long minZ,
                         long maxX, long maxY, long maxZ) {
    }

    public record Traversal(Set<Long> visibleKeys, Metrics metrics) {
        private static final Traversal EMPTY = new Traversal(Set.of(), new Metrics(0, 0, 0, 0, 0));
    }

    public record Metrics(int candidates, int roots, int visited, int prunedBranches, int emitted) {
    }

    private static final class MutableMetrics {
        final int candidates;
        final int roots;
        int visited;
        int prunedBranches;
        int emitted;

        MutableMetrics(int candidates, int roots) {
            this.candidates = candidates;
            this.roots = roots;
        }

        Metrics freeze() {
            return new Metrics(this.candidates, this.roots, this.visited,
                    this.prunedBranches, this.emitted);
        }
    }
}
