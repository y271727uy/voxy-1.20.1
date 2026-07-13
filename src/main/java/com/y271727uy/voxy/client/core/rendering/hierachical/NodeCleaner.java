package com.y271727uy.voxy.client.core.rendering.hierachical;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** CPU lifecycle mirror of the upstream visibility cleaner; GPU selection is supplied by the caller. */
public final class NodeCleaner implements AutoCloseable {
    public static final int DEFAULT_OUTPUT_LIMIT = 256;

    private final int maxNodeCount;
    private final int staleFrameThreshold;
    private final int[] lastVisibleFrame;
    private final BitSet allocated;
    private int frame;
    private boolean closed;

    public NodeCleaner(int maxNodeCount, int staleFrameThreshold) {
        if (maxNodeCount <= 0) throw new IllegalArgumentException("maxNodeCount must be positive");
        if (staleFrameThreshold < 0) throw new IllegalArgumentException("staleFrameThreshold must be non-negative");
        this.maxNodeCount = maxNodeCount;
        this.staleFrameThreshold = staleFrameThreshold;
        this.lastVisibleFrame = new int[maxNodeCount];
        Arrays.fill(this.lastVisibleFrame, -1);
        this.allocated = new BitSet(maxNodeCount);
    }

    public synchronized int advanceFrame() {
        ensureOpen();
        if (this.frame != Integer.MAX_VALUE) this.frame++;
        return this.frame;
    }

    public synchronized void allocated(int nodeId) {
        ensureOpen();
        checkNodeId(nodeId);
        this.allocated.set(nodeId);
        this.lastVisibleFrame[nodeId] = this.frame;
    }

    public synchronized void moved(int fromId, int toId) {
        ensureOpen();
        checkNodeId(fromId);
        checkNodeId(toId);
        if (!this.allocated.get(fromId)) throw new IllegalStateException("Source node is not allocated");
        this.allocated.set(toId);
        this.lastVisibleFrame[toId] = this.lastVisibleFrame[fromId];
        if (fromId != toId) {
            this.allocated.clear(fromId);
            this.lastVisibleFrame[fromId] = -1;
        }
    }

    public synchronized void freed(int nodeId) {
        ensureOpen();
        checkNodeId(nodeId);
        this.allocated.clear(nodeId);
        this.lastVisibleFrame[nodeId] = -1;
    }

    public synchronized void markVisible(int nodeId) {
        ensureOpen();
        checkNodeId(nodeId);
        if (!this.allocated.get(nodeId)) throw new IllegalStateException("Node is not allocated");
        this.lastVisibleFrame[nodeId] = this.frame;
    }

    public synchronized int collectExpired(IntPredicate eligible, int limit, IntConsumer output) {
        ensureOpen();
        Objects.requireNonNull(eligible, "eligible");
        Objects.requireNonNull(output, "output");
        if (limit < 0) throw new IllegalArgumentException("limit must be non-negative");
        int emitted = 0;
        for (int nodeId = this.allocated.nextSetBit(0);
             nodeId >= 0 && emitted < limit;
             nodeId = this.allocated.nextSetBit(nodeId + 1)) {
            int lastVisible = this.lastVisibleFrame[nodeId];
            if ((long) this.frame - lastVisible < this.staleFrameThreshold || !eligible.test(nodeId)) continue;
            output.accept(nodeId);
            this.lastVisibleFrame[nodeId] = this.frame;
            emitted++;
        }
        return emitted;
    }

    public synchronized void apply(AsyncNodeManager.SyncBatch batch) {
        ensureOpen();
        Objects.requireNonNull(batch, "batch");
        batch.cleanerActions().forEach((nodeId, action) -> {
            if (action == AsyncNodeManager.CleanerAction.RESET) allocated(nodeId);
            else freed(nodeId);
        });
    }

    public synchronized int frame() {
        return this.frame;
    }

    public synchronized boolean isAllocated(int nodeId) {
        checkNodeId(nodeId);
        return this.allocated.get(nodeId);
    }

    public synchronized int allocatedCount() {
        return this.allocated.cardinality();
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        this.allocated.clear();
        Arrays.fill(this.lastVisibleFrame, -1);
    }

    public synchronized boolean isClosed() {
        return this.closed;
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("NodeCleaner is closed");
    }

    private void checkNodeId(int nodeId) {
        if (nodeId < 0 || nodeId >= this.maxNodeCount) throw new IndexOutOfBoundsException(nodeId);
    }
}
