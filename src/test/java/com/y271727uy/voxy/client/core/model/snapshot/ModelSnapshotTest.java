package com.y271727uy.voxy.client.core.model.snapshot;

import com.y271727uy.voxy.client.core.model.ModelFactory;
import com.y271727uy.voxy.client.model.BakedBlockModel;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModelSnapshotTest {
    @Test
    public void classifiesReadyPendingAndUnsupportedModels() {
        ModelFactory factory = new ModelFactory();
        ModelFactory.ModelEntry ready = factory.install(model(1, false,
                BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT));
        factory.install(model(3, true, BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT));
        AtomicLong epoch = new AtomicLong(7);

        ModelSnapshot snapshot = ModelSnapshot.capture(factory, 7, epoch::get, 1, 2, 3);

        assertEquals(ModelLookup.State.READY, snapshot.lookup(1).state());
        assertSame(ready, snapshot.lookup(1).value());
        assertEquals(ModelLookup.State.PENDING, snapshot.lookup(2).state());
        assertEquals(ModelLookup.State.UNSUPPORTED, snapshot.lookup(3).state());
        assertEquals(java.util.Set.of(2), snapshot.pendingIds());
        assertEquals(java.util.Set.of(3), snapshot.unsupportedIds());
        assertTrue(snapshot.isCurrent());
        assertThrows(IllegalArgumentException.class, () -> snapshot.lookup(4));
    }

    @Test
    public void rejectsGenerationChangeDuringCapture() {
        AtomicLong generation = new AtomicLong(4);
        AtomicLong epoch = new AtomicLong(2);
        ModelFactory factory = new ModelFactory();
        ModelFactory.ModelEntry entry = factory.install(model(1, false,
                BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT));

        assertThrows(StaleModelSnapshotException.class, () -> ModelSnapshot.capture(stateId -> {
            generation.incrementAndGet();
            return entry;
        }, generation::get, 2, epoch::get, ignored -> false, 1));
    }

    @Test
    public void capturedEntriesStayImmutableAndBecomeStaleAfterReplacement() {
        ModelFactory factory = new ModelFactory();
        ModelFactory.ModelEntry first = factory.install(model(1, false,
                BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT));
        AtomicLong epoch = new AtomicLong(3);
        ModelSnapshot snapshot = ModelSnapshot.capture(factory, 3, epoch::get, 1);

        factory.install(model(1, false, BakedBlockModel.MaterialPass.TRANSLUCENT));

        assertSame(first, snapshot.lookup(1).value());
        assertFalse(snapshot.isCurrent());
    }

    @Test
    public void immutableCatalogSnapshotBecomesStaleAfterRebuildGeneration() {
        ModelFactory factory = new ModelFactory();
        ModelFactory.ModelEntry entry = factory.install(model(1, false,
                BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT));
        AtomicLong epoch = new AtomicLong(5);
        AtomicLong catalogGeneration = new AtomicLong(12);
        ModelSnapshot snapshot = ModelSnapshot.fromImmutable(java.util.Map.of(1, entry),
                5, 12, epoch::get, catalogGeneration::get, 1, 2);

        assertSame(entry, snapshot.lookup(1).value());
        assertEquals(ModelLookup.State.PENDING, snapshot.lookup(2).state());
        catalogGeneration.incrementAndGet();
        assertFalse(snapshot.isCurrent());
    }

    @Test
    public void immutableCatalogSnapshotBecomesStaleAfterDetachEpoch() {
        AtomicLong epoch = new AtomicLong(8);
        AtomicLong catalogGeneration = new AtomicLong(3);
        ModelSnapshot snapshot = ModelSnapshot.fromImmutable(java.util.Map.of(),
                8, 3, epoch::get, catalogGeneration::get, 4);

        assertTrue(snapshot.isCurrent());
        epoch.incrementAndGet();
        assertFalse(snapshot.isCurrent());
    }

    private static BakedBlockModel model(int id, boolean custom,
                                         BakedBlockModel.MaterialPass materialPass) {
        BakedBlockModel.Quad quad = new BakedBlockModel.Quad(new int[32], -1, null, null,
                materialPass.name(), true, false, materialPass, true);
        return new BakedBlockModel(id, false, custom, List.of(quad));
    }
}
