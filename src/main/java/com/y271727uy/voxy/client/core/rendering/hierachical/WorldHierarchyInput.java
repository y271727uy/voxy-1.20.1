package com.y271727uy.voxy.client.core.rendering.hierachical;

import com.y271727uy.voxy.common.world.WorldSectionKey;

import java.util.Objects;
import java.util.LinkedHashMap;

/**
 * Platform-neutral bridge that turns streamed world-section topology into the complete READY/world
 * hierarchy the traversal backend consumes. It replaces the flat "per-frame selected keys as
 * unrelated roots" model: roots are whole {@code maxLevel} world roots with an explicit, bounded
 * lifecycle, and interior child existence is authored from {@code WorldSection.nonEmptyChildren()}
 * rather than inferred from whichever descendants happen to be READY.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #updateSection(long, byte, int)} is the single authority for a position's world
 *       child topology and its geometry. Unbuilt children are materialised as {@code NULL} geometry
 *       leaves; a real empty mesh must be reported with {@code EMPTY} geometry. Ordering is free:
 *       geometry-before-parent and child-mask-before-parent are held by {@link NodeManager}'s
 *       bounded pending replay and applied once the node exists.</li>
 *   <li>Any reported section is anchored to its {@code maxLevel} ancestor root, which is registered
 *       as a top-level node on demand. Touching any position under a root refreshes that root's
 *       recency so live subtrees are never recycled.</li>
 *   <li>{@link #removeSection(long)} on an interior position clears only its geometry (topology with
 *       live world children survives); on a root it recycles the whole root subtree.</li>
 *   <li>Root count is hard-bounded. When the bound is exceeded the least-recently-touched whole
 *       root is recycled as a unit, never a sibling or subtree of a still-topological root.</li>
 * </ul>
 *
 * <p>This type only issues mutations through a {@link HierarchyMutator}; it never touches GL, holds
 * no lock across mutator calls beyond its own instance monitor, and never blocks on a worker. Wrap
 * an {@link AsyncNodeManager} with {@link #forAsync} for production, or a {@link NodeManager} with
 * {@link #forManager} for deterministic tests.</p>
 */
public final class WorldHierarchyInput {
    /** Mutation sink. Both the async and synchronous node managers satisfy this via adapters. */
    public interface HierarchyMutator {
        void addRoot(long position);
        void removeRoot(long position);
        void setChildExistence(long position, byte existence);
        void setGeometry(long position, int geometryId);
    }

    private final HierarchyMutator mutator;
    private final int maxLevel;
    private final int maxRootCount;
    // Access-ordered: eldest entry is the least-recently-touched live root.
    private final LinkedHashMap<Long, Boolean> roots;

    private WorldHierarchyInput(HierarchyMutator mutator, int maxLevel, int maxRootCount) {
        this.mutator = Objects.requireNonNull(mutator, "mutator");
        if (maxLevel < 0 || maxLevel > WorldSectionKey.MAX_LOD_LEVEL) {
            throw new IllegalArgumentException("Invalid maximum level: " + maxLevel);
        }
        if (maxRootCount <= 0) throw new IllegalArgumentException("Root capacity must be positive");
        this.maxLevel = maxLevel;
        this.maxRootCount = maxRootCount;
        this.roots = new LinkedHashMap<>(16, 0.75f, true);
    }

    public static WorldHierarchyInput forAsync(AsyncNodeManager manager, int maxLevel, int maxRootCount) {
        Objects.requireNonNull(manager, "manager");
        return new WorldHierarchyInput(new AsyncMutator(manager), maxLevel, maxRootCount);
    }

    public static WorldHierarchyInput forManager(NodeManager manager, int maxLevel, int maxRootCount) {
        Objects.requireNonNull(manager, "manager");
        return new WorldHierarchyInput(new SyncMutator(manager), maxLevel, maxRootCount);
    }

    public static WorldHierarchyInput withMutator(HierarchyMutator mutator, int maxLevel, int maxRootCount) {
        return new WorldHierarchyInput(mutator, maxLevel, maxRootCount);
    }

    /**
     * Reports a section's authoritative world topology and geometry.
     *
     * @param key             world-section key; its level must not exceed the configured maximum
     * @param nonEmptyChildren child-existence mask, e.g. {@code WorldSection.nonEmptyChildren()}
     * @param geometryId       {@code NULL_GEOMETRY_ID} when unbuilt, {@code EMPTY_GEOMETRY_ID} for a
     *                         real empty mesh, otherwise a valid geometry id
     */
    public synchronized void updateSection(long key, byte nonEmptyChildren, int geometryId) {
        int level = WorldSectionKey.level(key);
        if (level > this.maxLevel) {
            throw new IllegalArgumentException("Section level " + level + " exceeds maximum " + this.maxLevel);
        }
        int effectiveMask = level == 0 ? 0 : Byte.toUnsignedInt(nonEmptyChildren);
        touchRoot(rootOf(key, level));
        // Geometry first so a node that only just materialised through its parent's mask carries the
        // latest mesh; NodeManager applies whichever arrives last and replays pending updates in order.
        this.mutator.setGeometry(key, geometryId);
        this.mutator.setChildExistence(key, (byte) effectiveMask);
    }

    /**
     * Removes a section. A root recycles its entire subtree; an interior position only drops its
     * geometry (its world child topology, and any live descendants, are preserved).
     */
    public synchronized void removeSection(long key) {
        int level = WorldSectionKey.level(key);
        if (level > this.maxLevel) return;
        if (level == this.maxLevel) {
            if (this.roots.remove(key) != null) this.mutator.removeRoot(key);
            return;
        }
        touchRoot(rootOf(key, level));
        this.mutator.setGeometry(key, NodeManager.NULL_GEOMETRY_ID);
    }

    /** Recycles a whole root subtree by world root key, if present. */
    public synchronized boolean removeRoot(long rootKey) {
        if (WorldSectionKey.level(rootKey) != this.maxLevel) {
            throw new IllegalArgumentException("Not a root-level key: " + WorldSectionKey.describe(rootKey));
        }
        if (this.roots.remove(rootKey) == null) return false;
        this.mutator.removeRoot(rootKey);
        return true;
    }

    public synchronized int rootCount() { return this.roots.size(); }

    public synchronized boolean containsRoot(long rootKey) { return this.roots.containsKey(rootKey); }

    public int maxLevel() { return this.maxLevel; }

    public int maxRootCount() { return this.maxRootCount; }

    private void touchRoot(long rootKey) {
        if (this.roots.get(rootKey) != null) return; // access-order refresh happens inside get()
        // Recycle whole least-recently-touched roots until the new one fits within the bound.
        while (this.roots.size() >= this.maxRootCount) {
            Long eldest = this.roots.keySet().iterator().next();
            this.roots.remove(eldest);
            this.mutator.removeRoot(eldest);
        }
        this.roots.put(rootKey, Boolean.TRUE);
        this.mutator.addRoot(rootKey);
    }

    private long rootOf(long key, int level) {
        long ancestor = key;
        for (int current = level; current < this.maxLevel; current++) {
            ancestor = NodeManager.parentPosition(ancestor);
        }
        return ancestor;
    }

    private record AsyncMutator(AsyncNodeManager manager) implements HierarchyMutator {
        @Override public void addRoot(long position) { this.manager.addTopLevel(position); }
        @Override public void removeRoot(long position) { this.manager.removeTopLevel(position); }
        @Override public void setChildExistence(long position, byte existence) {
            this.manager.submitChildChange(position, existence);
        }
        @Override public void setGeometry(long position, int geometryId) {
            this.manager.submitGeometry(position, geometryId);
        }
    }

    private record SyncMutator(NodeManager manager) implements HierarchyMutator {
        @Override public void addRoot(long position) { this.manager.insertTopLevelNode(position); }
        @Override public void removeRoot(long position) { this.manager.removeTopLevelNode(position); }
        @Override public void setChildExistence(long position, byte existence) {
            this.manager.setNodeChildExistence(position, existence);
        }
        @Override public void setGeometry(long position, int geometryId) {
            this.manager.setNodeGeometry(position, geometryId);
        }
    }
}
