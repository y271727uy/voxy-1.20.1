package com.y271727uy.voxy.client.core.rendering.hierachical;

import com.y271727uy.voxy.common.world.WorldSectionKey;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/** Platform-neutral CPU lifecycle manager for the authoritative packed node contract. */
public final class NodeManager {
    public static final int DEFAULT_PENDING_UPDATE_LIMIT = 1024;
    public static final int NULL_GEOMETRY_ID = NodeStore.NULL_GEOMETRY_ID;
    public static final int EMPTY_GEOMETRY_ID = NodeStore.EMPTY_GEOMETRY_ID;
    public static final int NULL_REQUEST_ID = NodeStore.REQUEST_ID_MSK;
    public static final int SENTINEL_EMPTY_CHILD_PTR = NodeStore.NODE_ID_MSK - 1;
    public static final int NODE_TYPE_LEAF = 0;
    public static final int NODE_TYPE_INNER = 1 << 30;
    public static final int NODE_TYPE_REQUEST = 2 << 30;

    private final NodeStore nodes;
    private final GeometryLifecycle geometry;
    private final int pendingUpdateLimit;
    public final int maxNodeCount;
    private final Map<Long, Integer> byPosition = new ConcurrentHashMap<>();
    private final Set<Long> topLevelPositions = new TreeSet<>();
    private final Set<Integer> topLevelIds = new TreeSet<>();
    private final LinkedHashSet<Integer> dirty = new LinkedHashSet<>();
    private final LinkedHashMap<Long, PendingUpdate> pendingUpdates = new LinkedHashMap<>();
    private final List<Integer> releasedGeometry = new ArrayList<>();
    private ICleaner cleaner;
    private IntConsumer onTopLevelAdded;
    private IntConsumer onTopLevelRemoved;

    public NodeManager(int maxNodeCount) { this(maxNodeCount, geometryId -> { }); }

    public NodeManager(int maxNodeCount, GeometryLifecycle geometry) {
        this(maxNodeCount, geometry, DEFAULT_PENDING_UPDATE_LIMIT);
    }

    public NodeManager(int maxNodeCount, GeometryLifecycle geometry, int pendingUpdateLimit) {
        if ((maxNodeCount & (maxNodeCount - 1)) != 0) {
            throw new IllegalArgumentException("Max node count must be a power of two");
        }
        if (pendingUpdateLimit <= 0) throw new IllegalArgumentException("Pending update limit must be positive");
        this.maxNodeCount = maxNodeCount;
        this.nodes = new NodeStore(maxNodeCount);
        this.geometry = java.util.Objects.requireNonNull(geometry, "geometry");
        this.pendingUpdateLimit = pendingUpdateLimit;
    }

    public NodeStore nodeStore() { return this.nodes; }

    public synchronized void setClear(ICleaner cleaner) { this.cleaner = cleaner; }

    public synchronized void setTLNCallbacks(IntConsumer onAdd, IntConsumer onRemove) {
        this.onTopLevelAdded = onAdd;
        this.onTopLevelRemoved = onRemove;
    }

    public synchronized void insertTopLevelNode(long position) {
        if (this.byPosition.containsKey(position)) return;
        int id = allocateNode(position);
        this.topLevelPositions.add(position);
        this.topLevelIds.add(id);
        if (this.onTopLevelAdded != null) this.onTopLevelAdded.accept(id);
        replayPending(position, id);
    }

    public synchronized void removeTopLevelNode(long position) {
        Integer id = this.byPosition.get(position);
        if (id == null || !this.topLevelIds.contains(id)) return;
        if (this.onTopLevelRemoved != null) this.onTopLevelRemoved.accept(id);
        removeSubtree(id);
        this.topLevelPositions.remove(position);
        this.topLevelIds.remove(id);
    }

    public synchronized int nodeId(long position) { return this.byPosition.getOrDefault(position, -1); }

    public synchronized int installChildren(int parentId, byte existence) {
        requireNode(parentId);
        int currentMask = Byte.toUnsignedInt(this.nodes.getNodeChildExistence(parentId));
        int mask = Byte.toUnsignedInt(existence);
        if (currentMask == mask) {
            return currentMask == 0 ? -1 : this.nodes.getChildPtr(parentId);
        }
        if (mask == 0) {
            removeChildren(parentId);
            return -1;
        }
        long parentPosition = this.nodes.nodePosition(parentId);
        if (WorldSectionKey.level(parentPosition) == 0) throw new IllegalStateException("Level-zero node cannot have children");

        int[] oldIds = new int[8];
        java.util.Arrays.fill(oldIds, -1);
        for (int child = 0; child < 8; child++) {
            if ((currentMask & (1 << child)) != 0) oldIds[child] = childNodeId(parentId, child);
        }
        int count = Integer.bitCount(mask);
        int[] sources = new int[count];
        long[] positions = new long[count];
        BitSet releasable = new BitSet(this.maxNodeCount);
        for (int child = 0; child < 8; child++) {
            if (oldIds[child] < 0) continue;
            if ((mask & (1 << child)) != 0) releasable.set(oldIds[child]);
            else collectSubtreeIds(oldIds[child], releasable);
        }
        int slot = 0;
        for (int childIndex = 0; childIndex < 8; childIndex++) {
            if ((mask & (1 << childIndex)) == 0) continue;
            long position = childPosition(parentPosition, childIndex);
            int source = oldIds[childIndex];
            if (source < 0 && this.byPosition.containsKey(position)) {
                throw new IllegalStateException("Duplicate node position " + WorldSectionKey.describe(position));
            }
            sources[slot] = source;
            positions[slot++] = position;
        }
        int base = this.nodes.findConsecutiveRange(count, releasable);

        for (int child = 0; child < 8; child++) {
            if (oldIds[child] >= 0 && (mask & (1 << child)) == 0) removeSubtree(oldIds[child]);
        }
        this.nodes.remapNodes(sources, base);
        remapRetainedNodes(sources, base, positions);
        for (int index = 0; index < count; index++) {
            if (sources[index] < 0) initializeAllocatedNode(base + index, positions[index]);
        }
        this.nodes.setChildPtr(parentId, base);
        this.nodes.setChildPtrCount(parentId, count);
        this.nodes.setNodeChildExistence(parentId, existence);
        this.nodes.setNodeType(parentId, NODE_TYPE_INNER);
        markDirty(parentId);
        for (int index = 0; index < count; index++) {
            if (sources[index] < 0) replayPending(positions[index], base + index);
        }
        return base;
    }

    public synchronized void removeChildren(int parentId) {
        requireNode(parentId);
        int mask = Byte.toUnsignedInt(this.nodes.getNodeChildExistence(parentId));
        int base = this.nodes.getChildPtr(parentId);
        if (mask != 0 && base >= 0) {
            int count = Integer.bitCount(mask);
            for (int slot = 0; slot < count; slot++) removeSubtree(base + slot);
        }
        this.nodes.setNodeChildExistence(parentId, (byte) 0);
        this.nodes.setChildPtr(parentId, -1);
        this.nodes.setChildPtrCount(parentId, 1);
        this.nodes.setAllChildrenAreLeaf(parentId, false);
        this.nodes.setNodeType(parentId, NODE_TYPE_LEAF);
        markDirty(parentId);
    }

    public synchronized int childNodeId(int parentId, int childIndex) {
        if (childIndex < 0 || childIndex > 7) throw new IllegalArgumentException("Invalid child index");
        int mask = Byte.toUnsignedInt(this.nodes.getNodeChildExistence(parentId));
        if ((mask & (1 << childIndex)) == 0) return -1;
        int lower = mask & ((1 << childIndex) - 1);
        return this.nodes.getChildPtr(parentId) + Integer.bitCount(lower);
    }

    public synchronized void setNodeGeometry(int nodeId, int geometryId) {
        requireNode(nodeId);
        int previous = this.nodes.getNodeGeometry(nodeId);
        if (previous == geometryId) return;
        this.nodes.setNodeGeometry(nodeId, geometryId);
        if (previous >= 0) releaseGeometry(previous);
        markDirty(nodeId);
    }

    /** Applies immediately when present, otherwise retains the newest update for bounded replay. */
    public synchronized boolean setNodeGeometry(long position, int geometryId) {
        requireGeometryId(geometryId);
        Integer nodeId = this.byPosition.get(position);
        if (nodeId != null) {
            setNodeGeometry(nodeId, geometryId);
            return true;
        }
        PendingUpdate pending = pendingUpdate(position);
        if (pending.hasGeometry && pending.geometryId != geometryId && pending.geometryId >= 0) {
            releaseGeometry(pending.geometryId);
        }
        pending.geometryId = geometryId;
        pending.hasGeometry = true;
        return false;
    }

    public synchronized void setNodeChildExistence(int nodeId, byte existence) {
        installChildren(nodeId, existence);
    }

    /** Applies immediately when present, otherwise retains the newest update for bounded replay. */
    public synchronized boolean setNodeChildExistence(long position, byte existence) {
        if (existence != 0 && WorldSectionKey.level(position) == 0) {
            throw new IllegalStateException("Level-zero node cannot have children");
        }
        Integer nodeId = this.byPosition.get(position);
        if (nodeId != null) {
            setNodeChildExistence(nodeId, existence);
            return true;
        }
        PendingUpdate pending = pendingUpdate(position);
        pending.childExistence = existence;
        pending.hasChildExistence = true;
        return false;
    }

    public synchronized int[] drainDirtyNodeIds() {
        int[] result = this.dirty.stream().mapToInt(Integer::intValue).toArray();
        this.dirty.clear();
        return result;
    }

    public synchronized int[] drainGeometryReleased() {
        int[] result = this.releasedGeometry.stream().mapToInt(Integer::intValue).toArray();
        this.releasedGeometry.clear();
        return result;
    }

    public synchronized int pendingUpdateCount() { return this.pendingUpdates.size(); }

    public synchronized Set<Integer> topLevelNodeIds() { return Set.copyOf(this.topLevelIds); }
    public synchronized Set<Long> topLevelNodePositions() { return Set.copyOf(this.topLevelPositions); }
    public synchronized int getCurrentMaxNodeId() { return this.nodes.getEndNodeId(); }
    public synchronized int getNodeCount() { return this.nodes.getNodeCount(); }

    public synchronized NodeGpuTraversalSnapshot captureGpuTraversalSnapshot() {
        return NodeGpuTraversalSnapshot.capture(this.nodes, this.topLevelIds);
    }

    public synchronized NodeGpuUploadBatch captureGpuUploadBatch(int... dirtyNodeIds) {
        return NodeGpuUploadBatch.capture(this.nodes, dirtyNodeIds);
    }

    public synchronized void verifyIntegrity() {
        if (this.topLevelIds.size() != this.topLevelPositions.size()) throw new IllegalStateException("Top-level index mismatch");
        Set<Integer> seen = new java.util.HashSet<>();
        for (int root : this.topLevelIds) {
            long position = this.nodes.nodePosition(root);
            if (!this.topLevelPositions.contains(position) || this.byPosition.get(position) != root) {
                throw new IllegalStateException("Top-level position mismatch");
            }
            verifySubtree(root, seen);
        }
        if (seen.size() != this.nodes.getNodeCount()) throw new IllegalStateException("Unreachable nodes exist");
        if (seen.size() != this.byPosition.size()) throw new IllegalStateException("Position index size mismatch");
        for (Map.Entry<Long, Integer> entry : this.byPosition.entrySet()) {
            if (!seen.contains(entry.getValue()) || this.nodes.nodePosition(entry.getValue()) != entry.getKey()) {
                throw new IllegalStateException("Position index mismatch");
            }
        }
    }

    private int allocateNode(long position) {
        int id = this.nodes.allocate();
        initializeAllocatedNode(id, position);
        return id;
    }

    private void initializeAllocatedNode(int id, long position) {
        if (this.byPosition.putIfAbsent(position, id) != null) {
            this.nodes.free(id);
            throw new IllegalStateException("Duplicate node position " + WorldSectionKey.describe(position));
        }
        this.nodes.setNodePosition(id, position);
        this.nodes.setNodeType(id, NODE_TYPE_LEAF);
        markDirty(id);
        if (this.cleaner != null) this.cleaner.alloc(id);
    }

    private void removeSubtree(int id) {
        int mask = Byte.toUnsignedInt(this.nodes.getNodeChildExistence(id));
        int base = this.nodes.getChildPtr(id);
        if (mask != 0 && base >= 0) {
            for (int slot = 0; slot < Integer.bitCount(mask); slot++) removeSubtree(base + slot);
        }
        int geometryId = this.nodes.getNodeGeometry(id);
        if (geometryId >= 0) releaseGeometry(geometryId);
        this.byPosition.remove(this.nodes.nodePosition(id), id);
        this.dirty.remove(id);
        if (this.cleaner != null) this.cleaner.free(id);
        this.nodes.free(id);
    }

    private void verifySubtree(int id, Set<Integer> seen) {
        requireNode(id);
        if (!seen.add(id)) throw new IllegalStateException("Node referenced twice: " + id);
        int mask = Byte.toUnsignedInt(this.nodes.getNodeChildExistence(id));
        int base = this.nodes.getChildPtr(id);
        if ((mask == 0) != (base < 0)) throw new IllegalStateException("Child mask/pointer mismatch");
        if (mask != 0 && this.nodes.getChildPtrCount(id) != Integer.bitCount(mask)) {
            throw new IllegalStateException("Child count/mask mismatch");
        }
        if ((mask == 0) != (this.nodes.getNodeType(id) == NODE_TYPE_LEAF)) {
            throw new IllegalStateException("Child mask/node type mismatch");
        }
        for (int child = 0; child < 8; child++) {
            int childId = childNodeId(id, child);
            if (childId >= 0) {
                long expected = childPosition(this.nodes.nodePosition(id), child);
                if (this.nodes.nodePosition(childId) != expected) throw new IllegalStateException("Child position mismatch");
                verifySubtree(childId, seen);
            }
        }
    }

    private void markDirty(int id) { this.dirty.add(id); }
    private void requireNode(int id) { if (!this.nodes.nodeExists(id)) throw new IllegalArgumentException("Unknown node: " + id); }

    static long childPosition(long parent, int childIndex) {
        int level = WorldSectionKey.level(parent);
        if (level == 0) throw new IllegalArgumentException("Level-zero node has no child position");
        return WorldSectionKey.pack(level - 1,
                WorldSectionKey.x(parent) * 2 + (childIndex & 1),
                WorldSectionKey.y(parent) * 2 + ((childIndex >>> 2) & 1),
                WorldSectionKey.z(parent) * 2 + ((childIndex >>> 1) & 1));
    }

    static long parentPosition(long child) {
        int level = WorldSectionKey.level(child);
        if (level == 15) throw new IllegalArgumentException("Level-fifteen node has no packable parent");
        return WorldSectionKey.pack(level + 1, WorldSectionKey.x(child) >> 1,
                WorldSectionKey.y(child) >> 1, WorldSectionKey.z(child) >> 1);
    }

    private void collectSubtreeIds(int id, BitSet output) {
        if (output.get(id)) return;
        output.set(id);
        int mask = Byte.toUnsignedInt(this.nodes.getNodeChildExistence(id));
        int base = this.nodes.getChildPtr(id);
        for (int slot = 0; slot < Integer.bitCount(mask); slot++) collectSubtreeIds(base + slot, output);
    }

    private void remapRetainedNodes(int[] sources, int base, long[] positions) {
        List<Move> moves = new ArrayList<>();
        for (int index = 0; index < sources.length; index++) {
            int source = sources[index];
            if (source < 0) continue;
            int target = base + index;
            if (!this.byPosition.replace(positions[index], source, target)) {
                throw new IllegalStateException("Position index changed during node remap");
            }
            this.dirty.remove(source);
            markDirty(source);
            markDirty(target);
            if (source != target) moves.add(new Move(source, target));
        }
        while (!moves.isEmpty()) {
            int movable = -1;
            for (int index = 0; index < moves.size(); index++) {
                int target = moves.get(index).to;
                boolean occupiedByPendingSource = false;
                for (Move move : moves) {
                    if (move.from == target) {
                        occupiedByPendingSource = true;
                        break;
                    }
                }
                if (!occupiedByPendingSource) {
                    movable = index;
                    break;
                }
            }
            if (movable < 0) throw new IllegalStateException("Cyclic node remap");
            Move move = moves.remove(movable);
            if (this.cleaner != null) this.cleaner.move(move.from, move.to);
        }
    }

    private PendingUpdate pendingUpdate(long position) {
        PendingUpdate pending = this.pendingUpdates.get(position);
        if (pending != null) return pending;
        while (this.pendingUpdates.size() >= this.pendingUpdateLimit) {
            Map.Entry<Long, PendingUpdate> oldest = this.pendingUpdates.entrySet().iterator().next();
            this.pendingUpdates.remove(oldest.getKey());
            PendingUpdate removed = oldest.getValue();
            if (removed.hasGeometry && removed.geometryId >= 0) releaseGeometry(removed.geometryId);
        }
        pending = new PendingUpdate();
        this.pendingUpdates.put(position, pending);
        return pending;
    }

    private void replayPending(long position, int nodeId) {
        PendingUpdate pending = this.pendingUpdates.get(position);
        if (pending == null) return;
        if (pending.hasChildExistence) setNodeChildExistence(nodeId, pending.childExistence);
        if (pending.hasGeometry) setNodeGeometry(nodeId, pending.geometryId);
        this.pendingUpdates.remove(position);
    }

    private void releaseGeometry(int geometryId) {
        this.geometry.release(geometryId);
        this.releasedGeometry.add(geometryId);
    }

    private static void requireGeometryId(int geometryId) {
        if (geometryId < EMPTY_GEOMETRY_ID || geometryId > NodeStore.MAX_GEOMETRY_ID) {
            throw new IllegalArgumentException("Invalid geometry id: " + geometryId);
        }
    }

    private record Move(int from, int to) { }

    private static final class PendingUpdate {
        private int geometryId;
        private byte childExistence;
        private boolean hasGeometry;
        private boolean hasChildExistence;
    }

    public interface ICleaner {
        void alloc(int id);
        void move(int from, int to);
        void free(int id);
    }

    @FunctionalInterface
    public interface GeometryLifecycle { void release(int geometryId); }
}
