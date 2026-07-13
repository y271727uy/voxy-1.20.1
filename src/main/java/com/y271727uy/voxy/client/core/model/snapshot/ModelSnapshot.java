package com.y271727uy.voxy.client.core.model.snapshot;

import com.y271727uy.voxy.client.core.model.ModelFactory;
import com.y271727uy.voxy.client.model.BakedBlockModel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Immutable, point-in-time model view for one mesh build. */
public final class ModelSnapshot {
    private final long epoch;
    private final long generation;
    private final LongSupplier currentEpoch;
    private final LongSupplier currentGeneration;
    private final Set<Integer> capturedIds;
    private final Map<Integer, ModelFactory.ModelEntry> ready;
    private final Set<Integer> pending;
    private final Set<Integer> unsupported;

    private ModelSnapshot(long epoch, long generation, LongSupplier currentEpoch,
                          LongSupplier currentGeneration, Set<Integer> capturedIds,
                          Map<Integer, ModelFactory.ModelEntry> ready, Set<Integer> pending,
                          Set<Integer> unsupported) {
        this.epoch = epoch;
        this.generation = generation;
        this.currentEpoch = currentEpoch;
        this.currentGeneration = currentGeneration;
        this.capturedIds = Set.copyOf(capturedIds);
        this.ready = Map.copyOf(ready);
        this.pending = Set.copyOf(pending);
        this.unsupported = Set.copyOf(unsupported);
    }

    public static ModelSnapshot capture(ModelFactory factory, long epoch, LongSupplier currentEpoch,
                                        int... stateIds) {
        Objects.requireNonNull(factory, "factory");
        return capture(factory::entry, factory::generation, epoch, currentEpoch,
                ModelSnapshot::unsupportedByDefault, stateIds);
    }

    public static ModelSnapshot capture(IntFunction<ModelFactory.ModelEntry> resolver,
                                        LongSupplier currentGeneration, long epoch,
                                        LongSupplier currentEpoch,
                                        Predicate<ModelFactory.ModelEntry> unsupportedPolicy,
                                        int... stateIds) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(currentGeneration, "currentGeneration");
        Objects.requireNonNull(currentEpoch, "currentEpoch");
        Objects.requireNonNull(unsupportedPolicy, "unsupportedPolicy");
        Objects.requireNonNull(stateIds, "stateIds");

        long generation = currentGeneration.getAsLong();
        Set<Integer> captured = new HashSet<>();
        Map<Integer, ModelFactory.ModelEntry> ready = new HashMap<>();
        Set<Integer> pending = new HashSet<>();
        Set<Integer> unsupported = new HashSet<>();
        Arrays.stream(stateIds).forEach(stateId -> {
            if (!captured.add(stateId)) return;
            ModelFactory.ModelEntry entry = resolver.apply(stateId);
            if (entry == null) pending.add(stateId);
            else if (unsupportedPolicy.test(entry)) unsupported.add(stateId);
            else ready.put(stateId, entry);
        });
        long capturedGeneration = currentGeneration.getAsLong();
        if (capturedGeneration != generation) {
            throw new StaleModelSnapshotException(generation, capturedGeneration);
        }
        return new ModelSnapshot(epoch, generation, currentEpoch, currentGeneration,
                captured, ready, pending, unsupported);
    }

    public static ModelSnapshot fromImmutable(Map<Integer, ModelFactory.ModelEntry> entries,
                                              long epoch, long generation,
                                              LongSupplier currentEpoch,
                                              LongSupplier currentGeneration,
                                              int... stateIds) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(currentEpoch, "currentEpoch");
        Objects.requireNonNull(currentGeneration, "currentGeneration");
        Objects.requireNonNull(stateIds, "stateIds");
        Set<Integer> captured = new HashSet<>();
        Map<Integer, ModelFactory.ModelEntry> ready = new HashMap<>();
        Set<Integer> pending = new HashSet<>();
        Set<Integer> unsupported = new HashSet<>();
        Arrays.stream(stateIds).forEach(stateId -> {
            if (!captured.add(stateId)) return;
            ModelFactory.ModelEntry entry = entries.get(stateId);
            if (entry == null) pending.add(stateId);
            else if (unsupportedByDefault(entry)) unsupported.add(stateId);
            else ready.put(stateId, entry);
        });
        return new ModelSnapshot(epoch, generation, currentEpoch, currentGeneration,
                captured, ready, pending, unsupported);
    }

    public ModelLookup<ModelFactory.ModelEntry> lookup(int stateId) {
        if (!this.capturedIds.contains(stateId)) {
            throw new IllegalArgumentException("State " + stateId + " was not captured by this snapshot");
        }
        ModelFactory.ModelEntry entry = this.ready.get(stateId);
        if (entry != null) return ModelLookup.ready(entry);
        return this.unsupported.contains(stateId) ? ModelLookup.unsupported() : ModelLookup.pending();
    }

    public boolean isCurrent() {
        return this.currentEpoch.getAsLong() == this.epoch
                && this.currentGeneration.getAsLong() == this.generation;
    }

    public long epoch() {
        return this.epoch;
    }

    public long generation() {
        return this.generation;
    }

    public Set<Integer> capturedIds() {
        return this.capturedIds;
    }

    public Set<Integer> pendingIds() {
        return this.pending;
    }

    public Set<Integer> unsupportedIds() {
        return this.unsupported;
    }

    private static boolean unsupportedByDefault(ModelFactory.ModelEntry entry) {
        BakedBlockModel model = entry.combinedModel();
        if (model.customRenderer()) return true;
        return !model.quads().isEmpty() && model.quads().stream()
                .allMatch(quad -> quad.materialPass() == BakedBlockModel.MaterialPass.UNSUPPORTED);
    }
}
