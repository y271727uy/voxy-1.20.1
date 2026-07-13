package com.y271727uy.voxy.common.world;

import com.y271727uy.voxy.common.voxelization.PackedVoxel;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorldSection {
    public static final int SIDE_LENGTH = 32;
    public static final int SECTION_VOLUME = SIDE_LENGTH * SIDE_LENGTH * SIDE_LENGTH;

    private final int level;
    private final int x;
    private final int y;
    private final int z;
    private final long key;
    private final AtomicInteger state = new AtomicInteger(1);
    private final AtomicInteger nonEmptyChildren = new AtomicInteger();
    private final AtomicInteger nonEmptyBlockCount = new AtomicInteger();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean inSaveQueue = new AtomicBoolean();
    private volatile long[] data = new long[SECTION_VOLUME];
    private volatile long revision;

    public WorldSection(int level, int x, int y, int z) {
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.key = WorldSectionKey.pack(level, x, y, z);
    }

    public int level() { return this.level; }
    public int x() { return this.x; }
    public int y() { return this.y; }
    public int z() { return this.z; }
    public long key() { return this.key; }

    public static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= SIDE_LENGTH || y >= SIDE_LENGTH || z >= SIDE_LENGTH) {
            throw new IllegalArgumentException("World-section coordinate out of bounds: " + x + ", " + y + ", " + z);
        }
        return (y << 10) | (z << 5) | x;
    }

    public static int childIndex(int x, int y, int z) {
        return (x & 1) | ((z & 1) << 1) | ((y & 1) << 2);
    }

    public long get(int x, int y, int z) {
        return requireData()[index(x, y, z)];
    }

    public synchronized long set(int x, int y, int z, long voxel) {
        beginWrite();
        try {
            long[] values = requireData();
            int index = index(x, y, z);
            long previous = values[index];
            values[index] = voxel;
            if (this.level == 0 && PackedVoxel.isAir(previous) != PackedVoxel.isAir(voxel)) {
                this.nonEmptyBlockCount.addAndGet(PackedVoxel.isAir(voxel) ? -1 : 1);
                this.nonEmptyChildren.set(this.nonEmptyBlockCount.get() == 0 ? 0 : 0xFF);
            }
            return previous;
        } finally {
            endWrite();
        }
    }

    public long[] copyData() {
        return requireData().clone();
    }

    public void copyDataTo(long[] destination, int offset) {
        long[] values = requireData();
        if (offset < 0 || destination.length - offset < values.length) {
            throw new IllegalArgumentException("Destination cannot hold world section");
        }
        System.arraycopy(values, 0, destination, offset, values.length);
    }

    public long revision() {
        return this.revision;
    }

    public boolean copyRegionAtRevision(long expectedRevision,
                                        int minX, int minY, int minZ,
                                        int sizeX, int sizeY, int sizeZ,
                                        long[] destination, int destinationOffset,
                                        int destinationYStride, int destinationZStride) {
        if ((expectedRevision & 1L) != 0 || this.revision != expectedRevision) return false;
        if (minX < 0 || minY < 0 || minZ < 0 || sizeX < 0 || sizeY < 0 || sizeZ < 0
                || minX + sizeX > SIDE_LENGTH || minY + sizeY > SIDE_LENGTH
                || minZ + sizeZ > SIDE_LENGTH) {
            throw new IllegalArgumentException("Invalid world-section copy region");
        }
        long[] values = this.data;
        if (values == null) return false;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                int source = index(minX, minY + y, minZ + z);
                int target = destinationOffset + y * destinationYStride + z * destinationZStride;
                System.arraycopy(values, source, destination, target, sizeX);
            }
        }
        return this.revision == expectedRevision;
    }

    public int nonEmptyBlockCount() {
        return this.nonEmptyBlockCount.get();
    }

    public int nonEmptyChildren() {
        return this.nonEmptyChildren.get();
    }

    public synchronized UpdateResult updateRegion(int baseX, int baseY, int baseZ, int side,
                                                   long[] source, WorldSection child) {
        if (side < 0 || source.length != side * side * side || baseX < 0 || baseY < 0 || baseZ < 0
                || baseX + side > SIDE_LENGTH || baseY + side > SIDE_LENGTH
                || baseZ + side > SIDE_LENGTH) {
            throw new IllegalArgumentException("Invalid world-section update region");
        }
        beginWrite();
        try {
            long[] values = requireData();
            boolean dataChanged = false;
            int sourceIndex = 0;
            for (int y = 0; y < side; y++) {
                for (int z = 0; z < side; z++) {
                    for (int x = 0; x < side; x++) {
                        int targetIndex = index(baseX + x, baseY + y, baseZ + z);
                        long previous = values[targetIndex];
                        long next = source[sourceIndex++];
                        if (previous == next) continue;
                        values[targetIndex] = next;
                        dataChanged = true;
                        if (this.level == 0 && PackedVoxel.isAir(previous) != PackedVoxel.isAir(next)) {
                            this.nonEmptyBlockCount.addAndGet(PackedVoxel.isAir(next) ? -1 : 1);
                        }
                    }
                }
            }

            int childChange = 0;
            if (this.level == 0) {
                int previous = this.nonEmptyChildren.get();
                int next = this.nonEmptyBlockCount.get() == 0 ? 0 : 0xFF;
                this.nonEmptyChildren.set(next);
                childChange = (previous == 0) != (next == 0) ? 2 : previous == next ? 0 : 1;
            } else if (child != null) {
                int mask = 1 << childIndex(child.x, child.y, child.z);
                int previous = this.nonEmptyChildren.get();
                int next = child.nonEmptyChildren() == 0 ? previous & ~mask : previous | mask;
                this.nonEmptyChildren.set(next);
                childChange = (previous == 0) != (next == 0) ? 2 : previous == next ? 0 : 1;
            }
            return new UpdateResult(dataChanged, childChange);
        } finally {
            endWrite();
        }
    }

    public synchronized StorageSnapshot captureStorageSnapshot() {
        return new StorageSnapshot(requireData().clone(), this.nonEmptyChildren.get());
    }

    public synchronized boolean updateLevelZeroState() {
        if (this.level != 0) {
            throw new IllegalStateException("Level-zero state requested for LOD " + this.level);
        }
        beginWrite();
        try {
            int next = this.nonEmptyBlockCount.get() == 0 ? 0 : 0xFF;
            return this.nonEmptyChildren.getAndSet(next) != next;
        } finally {
            endWrite();
        }
    }

    public synchronized int updateEmptyChildState(WorldSection child) {
        beginWrite();
        try {
            int mask = 1 << childIndex(child.x, child.y, child.z);
            int previous = this.nonEmptyChildren.get();
            int next = child.nonEmptyChildren() == 0 ? previous & ~mask : previous | mask;
            this.nonEmptyChildren.set(next);
            if ((previous == 0) != (next == 0)) {
                return 2;
            }
            return previous == next ? 0 : 1;
        } finally {
            endWrite();
        }
    }

    public boolean tryAcquire() {
        int previous;
        do {
            previous = this.state.get();
            if ((previous & 1) == 0) {
                return false;
            }
        } while (!this.state.compareAndSet(previous, previous + 2));
        return true;
    }

    public int acquire() {
        if (!tryAcquire()) {
            throw new IllegalStateException("Cannot acquire freed section " + WorldSectionKey.describe(this.key));
        }
        return refCount();
    }

    public int release() {
        int previous;
        int next;
        do {
            previous = this.state.get();
            if ((previous & 1) == 0 || previous < 3) {
                throw new IllegalStateException("Cannot release unacquired section " + WorldSectionKey.describe(this.key));
            }
            next = previous - 2;
        } while (!this.state.compareAndSet(previous, next));
        return next >>> 1;
    }

    public int refCount() {
        return this.state.get() >>> 1;
    }

    public synchronized boolean tryFree() {
        if (this.dirty.get() || this.inSaveQueue.get()) {
            throw new IllegalStateException("Cannot free dirty or queued section");
        }
        if (!this.state.compareAndSet(1, 0)) {
            return false;
        }
        beginWrite();
        try {
            this.data = null;
        } finally {
            endWrite();
        }
        return true;
    }

    public boolean isFreed() {
        return (this.state.get() & 1) == 0;
    }

    public boolean markDirty() {
        return !this.dirty.getAndSet(true);
    }

    public boolean setNotDirty() {
        return this.dirty.getAndSet(false);
    }

    public boolean isDirty() {
        return this.dirty.get();
    }

    public boolean exchangeInSaveQueue(boolean queued) {
        return this.inSaveQueue.getAndSet(queued) != queued;
    }

    public boolean isInSaveQueue() {
        return this.inSaveQueue.get();
    }

    public synchronized void replaceDataFromStorage(long[] values, int childMask, int nonAirCount) {
        if (values.length != SECTION_VOLUME) {
            throw new IllegalArgumentException("Invalid world-section data length: " + values.length);
        }
        beginWrite();
        try {
            this.data = values;
            this.nonEmptyChildren.set(childMask & 0xFF);
            this.nonEmptyBlockCount.set(nonAirCount);
        } finally {
            endWrite();
        }
    }

    public synchronized void clear() {
        beginWrite();
        try {
            Arrays.fill(requireData(), PackedVoxel.AIR);
            this.nonEmptyChildren.set(0);
            this.nonEmptyBlockCount.set(0);
        } finally {
            endWrite();
        }
    }

    private long[] requireData() {
        long[] values = this.data;
        if (values == null) {
            throw new IllegalStateException("World section has been freed");
        }
        return values;
    }

    private void beginWrite() {
        this.revision++;
    }

    private void endWrite() {
        this.revision++;
    }

    public record UpdateResult(boolean dataChanged, int childChange) {
    }

    public record StorageSnapshot(long[] data, int nonEmptyChildren) {
    }
}
