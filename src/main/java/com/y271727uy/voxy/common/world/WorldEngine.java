package com.y271727uy.voxy.common.world;

import com.y271727uy.voxy.common.storage.SectionStorage;
import com.y271727uy.voxy.common.voxelization.PackedVoxel;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class WorldEngine implements AutoCloseable {
    public static final int MAX_LOD_LEVEL = 4;

    private final SectionStorage storage;
    private final MappingRegistry mapping;
    private final ConcurrentHashMap<Long, WorldSection> loadedSections = new ConcurrentHashMap<>();
    private final Set<Long> storedSectionKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final AtomicInteger restoredSections = new AtomicInteger();
    private final AtomicInteger newSections = new AtomicInteger();
    private final AtomicInteger corruptSections = new AtomicInteger();
    private volatile Consumer<WorldSection> dirtyListener;
    private final ConcurrentLinkedQueue<Long> saveQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queuedForSave = ConcurrentHashMap.newKeySet();

    public WorldEngine(SectionStorage storage) {
        this.storage = storage;
        this.storage.iteratePositions(-1, this.storedSectionKeys::add);
        this.mapping = new MappingRegistry(storage);
    }

    public MappingRegistry mapping() {
        return this.mapping;
    }

    public boolean isLive() {
        return this.live.get();
    }

    public int loadedSectionCount() {
        return this.loadedSections.size();
    }

    public int restoredSectionCount() { return this.restoredSections.get(); }
    public int newSectionCount() { return this.newSections.get(); }
    public int corruptSectionCount() { return this.corruptSections.get(); }

    public void setDirtyListener(Consumer<WorldSection> listener) {
        this.dirtyListener = listener;
    }

    public Set<Long> loadedSectionKeys() {
        return Set.copyOf(this.loadedSections.keySet());
    }

    public Set<Long> storedSectionKeys(int level) {
        Set<Long> keys = new java.util.HashSet<>();
        for (long key : this.storedSectionKeys) {
            if (level == -1 || WorldSectionKey.level(key) == level) {
                keys.add(key);
            }
        }
        return Set.copyOf(keys);
    }

    public WorldSection acquireLoaded(long key) {
        requireLive();
        WorldSection section = this.loadedSections.get(key);
        return section != null && section.tryAcquire() ? section : null;
    }

    public WorldSection acquire(int level, int x, int y, int z) {
        requireLive();
        long key = WorldSectionKey.pack(level, x, y, z);
        WorldSection section = this.loadedSections.computeIfAbsent(key, ignored -> loadSection(level, x, y, z));
        section.acquire();
        return section;
    }

    public WorldSection acquireIfExists(int level, int x, int y, int z) {
        requireLive();
        long key = WorldSectionKey.pack(level, x, y, z);
        WorldSection section = this.loadedSections.get(key);
        if (section == null && !hasStoredSection(key)) {
            return null;
        }
        if (section == null) {
            section = this.loadedSections.computeIfAbsent(key, ignored -> loadSection(level, x, y, z));
        }
        return section.tryAcquire() ? section : null;
    }

    public WorldSection acquireIfExists(long key) {
        return acquireIfExists(WorldSectionKey.level(key), WorldSectionKey.x(key),
                WorldSectionKey.y(key), WorldSectionKey.z(key));
    }

    public void markDirty(WorldSection section) {
        requireLive();
        WorldSection owned = this.loadedSections.get(section.key());
        if (owned != section) {
            throw new IllegalArgumentException("World section does not belong to this engine");
        }
        if (section.markDirty() && this.queuedForSave.add(section.key())) {
            this.saveQueue.add(section.key());
        }
        Consumer<WorldSection> listener = this.dirtyListener;
        if (listener != null) {
            listener.accept(section);
        }
    }

    public void saveDirtySections() {
        requireLive();
        while (!this.saveQueue.isEmpty()) {
            saveDirtySections(256);
        }
        this.storage.flush();
    }

    public int saveDirtySections(int budget) {
        requireLive();
        int saved = 0;
        while (saved < budget) {
            Long key = this.saveQueue.poll();
            if (key == null) {
                break;
            }
            this.queuedForSave.remove(key);
            WorldSection section = this.loadedSections.get(key);
            if (section != null && section.setNotDirty()) {
                this.storage.saveSection(section);
                this.storedSectionKeys.add(key);
                saved++;
            }
            if (section != null && section.isDirty() && this.queuedForSave.add(key)) {
                this.saveQueue.add(key);
            }
        }
        return saved;
    }

    @Override
    public void close() {
        if (!this.live.compareAndSet(true, false)) {
            return;
        }
        for (WorldSection section : this.loadedSections.values()) {
            if (section.refCount() != 0) {
                throw new IllegalStateException("Closing world with acquired section " + WorldSectionKey.describe(section.key()));
            }
            if (section.setNotDirty()) {
                this.storage.saveSection(section);
                this.storedSectionKeys.add(section.key());
            }
        }
        this.mapping.close();
        this.storage.flush();
        for (WorldSection section : this.loadedSections.values()) {
            section.tryFree();
        }
        this.loadedSections.clear();
        this.storedSectionKeys.clear();
        this.saveQueue.clear();
        this.queuedForSave.clear();
        this.storage.close();
    }

    private WorldSection loadSection(int level, int x, int y, int z) {
        WorldSection section = new WorldSection(level, x, y, z);
        int status = this.storage.loadSection(section);
        if (status == SectionStorage.LOAD_SUCCESS) {
            this.restoredSections.incrementAndGet();
        } else {
            if (status == SectionStorage.LOAD_CORRUPT) {
                this.corruptSections.incrementAndGet();
            } else {
                this.newSections.incrementAndGet();
            }
            long[] skyLitAir = new long[WorldSection.SECTION_VOLUME];
            Arrays.fill(skyLitAir, PackedVoxel.airWithLight(0x0F));
            section.replaceDataFromStorage(skyLitAir, 0, 0);
        }
        return section;
    }

    private boolean hasStoredSection(long key) {
        return this.storedSectionKeys.contains(key);
    }

    private void requireLive() {
        if (!this.live.get()) {
            throw new IllegalStateException("World engine is closed");
        }
    }
}
