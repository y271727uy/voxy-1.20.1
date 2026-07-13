package com.y271727uy.voxy.client.core.model;

import com.y271727uy.voxy.client.model.BakedBlockModel;
import com.y271727uy.voxy.client.core.model.snapshot.ModelSnapshot;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.Assert.*;

public class ModelFactoryTest {
    @Test
    public void separatesHostAndFluidModelsAndKeepsConnectionMetadata() {
        ResourceLocation water = new ResourceLocation("minecraft", "water");
        BakedBlockModel.Quad host = quad(Direction.EAST, 1.0F, null);
        BakedBlockModel.Quad fluid = quad(Direction.UP, 0.5F, water);
        BakedBlockModel combined = new BakedBlockModel(7, true, false, List.of(host, fluid));
        ModelFactory factory = new ModelFactory();

        ModelFactory.ModelEntry entry = factory.install(combined);

        assertEquals(1, entry.blockModel().quads().size());
        assertEquals(1, entry.fluidModel().quads().size());
        assertTrue(entry.metadata().hasFluid());
        assertEquals(water, entry.metadata().fluidKey());
        assertEquals(0.5F, entry.metadata().fluidHeight(), 0.0001F);
        assertSame(combined, factory.get(7));
    }

    @Test
    public void fullBoundaryFaceProducesOcclusionMetadata() {
        ModelFactory factory = new ModelFactory();
        ModelFactory.ModelEntry entry = factory.install(new BakedBlockModel(1, true, false,
                List.of(quad(Direction.EAST, 1.0F, null))));

        ModelFactory.FaceMetadata face = entry.metadata().face(Direction.EAST);

        assertTrue(face.present());
        assertTrue(face.coversFullFace());
        assertTrue(face.occludesNeighbor());
        assertTrue(face.canBeOccluded());
        assertFalse(entry.metadata().face(Direction.WEST).present());
    }

    @Test
    public void generationAdvancesAndClosedFactoryRejectsWrites() {
        ModelFactory factory = new ModelFactory();
        long initial = factory.generation();
        factory.install(new BakedBlockModel(1, false, false, List.of()));
        assertTrue(factory.generation() > initial);
        factory.close();
        assertTrue(factory.isClosed());
        assertThrows(IllegalStateException.class,
                () -> factory.install(new BakedBlockModel(2, false, false, List.of())));
    }

    @Test
    public void descriptorPublishesRenderDataFactoryMasksAndStableFluidData() {
        ResourceLocation water = new ResourceLocation("minecraft", "water");
        BakedBlockModel model = new BakedBlockModel(9, true, false, List.of(
                quad(Direction.EAST, 1.0F, null), quad(Direction.UP, 0.5F, water)));
        ModelFactory.FluidDescriptor fluid = new ModelFactory.FluidDescriptor(42, true,
                0.5F, 0.0F, 0.0F, -1);
        ModelFactory.ModelDescriptor descriptor = ModelFactory.descriptor(model, true, true, fluid);

        assertFalse(descriptor.fullyOpaque());
        assertTrue(descriptor.cullsSame());
        assertTrue((descriptor.faceExistsMask() & 1 << Direction.EAST.ordinal()) != 0);
        assertTrue((descriptor.faceOccludesMask() & 1 << Direction.EAST.ordinal()) != 0);
        assertEquals(42, descriptor.fluid().fluidKey());
        assertTrue(descriptor.fluid().pure());
        assertEquals(1 << Direction.UP.ordinal(), descriptor.fluid().openFacesMask());
    }

    @Test
    public void modelBatchPublishesOneGeneration() {
        ModelFactory factory = new ModelFactory();
        long initial = factory.generation();
        factory.beginBatch();
        factory.install(new BakedBlockModel(1, false, false, List.of()));
        factory.install(new BakedBlockModel(2, false, false, List.of()));
        factory.endBatch();

        assertEquals(initial + 1, factory.generation());
    }

    @Test
    public void disjointQuadsWhoseBoundsSpanFaceDoNotOcclude() {
        BakedBlockModel.Quad westStrip = eastQuad(0.0F, 0.25F);
        BakedBlockModel.Quad eastStrip = eastQuad(0.75F, 1.0F);
        ModelFactory factory = new ModelFactory();

        ModelFactory.FaceMetadata face = factory.install(new BakedBlockModel(3, true, false,
                List.of(westStrip, eastStrip))).metadata().face(Direction.EAST);

        assertEquals(0.0F, face.minA(), 0.0001F);
        assertEquals(1.0F, face.maxA(), 0.0001F);
        assertFalse(face.coversFullFace());
        assertFalse(face.occludesNeighbor());
    }

    @Test
    public void fullBoundsCutoutLeafFaceNeverBecomesOpaqueOccluder() {
        BakedBlockModel.Quad leaves = typedFace(Direction.EAST, BakedBlockModel.MaterialType.CUTOUT,
                0.65F, null, 1.0F);
        BakedBlockModel model = new BakedBlockModel(11, true, false, List.of(leaves));

        ModelFactory.ModelDescriptor descriptor = ModelFactory.descriptor(model, true, true, null);

        assertFalse(descriptor.fullyOpaque());
        assertEquals(0, descriptor.faceOccludesMask());
        assertTrue((descriptor.cutoutFacesMask() & 1 << Direction.EAST.ordinal()) != 0);
    }

    @Test
    public void alphaHoleCoverageCannotOccludeEvenWithFullGeometry() {
        BakedBlockModel.Quad alphaHole = typedFace(Direction.EAST, BakedBlockModel.MaterialType.SOLID,
                0.75F, null, 1.0F);
        ModelFactory.ModelDescriptor descriptor = ModelFactory.descriptor(
                new BakedBlockModel(12, true, false, List.of(alphaHole)), true, false, null);

        assertEquals(0, descriptor.faceOccludesMask());
        assertFalse(descriptor.fullyOpaque());
    }

    @Test
    public void waterloggedHostClosesOnlyFacesCoveredByOpaqueHostShape() {
        ResourceLocation water = new ResourceLocation("minecraft", "water");
        java.util.ArrayList<BakedBlockModel.Quad> quads = new java.util.ArrayList<>();
        quads.add(typedFace(Direction.EAST, BakedBlockModel.MaterialType.SOLID, 1.0F, null, 1.0F));
        for (Direction direction : Direction.values()) {
            quads.add(typedFace(direction, BakedBlockModel.MaterialType.TRANSLUCENT,
                    0.0F, water, 0.75F));
        }
        BakedBlockModel model = new BakedBlockModel(13, true, false, quads);
        ModelFactory.FluidDescriptor fluid = new ModelFactory.FluidDescriptor(7, false,
                0.75F, false, 0.0F, 0.0F, -1);

        ModelFactory.ModelDescriptor descriptor = ModelFactory.descriptor(model, false, false, fluid);

        assertEquals(0, descriptor.fluid().openFacesMask() & 1 << Direction.EAST.ordinal());
        assertTrue((descriptor.fluid().openFacesMask() & 1 << Direction.WEST.ordinal()) != 0);
        assertFalse(descriptor.fluid().flowKnown());
    }

    @Test
    public void slabAndStairFacesPublishPartialCoverageMasksAndDepth() {
        BakedBlockModel slab = new BakedBlockModel(20, true, false,
                List.of(eastRect(0, 1, 0, 0.5F)));
        ModelFactory.FaceSurface slabSurface = ModelFactory.descriptor(slab, false, false, null)
                .faceSurfaces().get(Direction.EAST);
        assertEquals(128, slabSurface.coveredPixels());
        assertEquals(1.0F, slabSurface.minDepth(), 0.0001F);

        BakedBlockModel stair = new BakedBlockModel(21, true, false, List.of(
                eastRect(0, 1, 0, 0.5F), eastRect(0, 0.5F, 0.5F, 1)));
        ModelFactory.FaceSurface stairSurface = ModelFactory.descriptor(stair, false, false, null)
                .faceSurfaces().get(Direction.EAST);
        assertEquals(192, stairSurface.coveredPixels());
    }

    @Test
    public void factorySnapshotBindsEntryAndGenerationAtomically() throws Exception {
        ModelFactory factory = new ModelFactory();
        Thread writer = new Thread(() -> {
            for (int generation = 1; generation <= 100; generation++) {
                factory.install(new BakedBlockModel(1, false, false, generation, List.of()));
            }
        });
        writer.start();
        while (writer.isAlive()) {
            ModelFactory.FactorySnapshot snapshot = factory.snapshot(new int[]{1});
            ModelFactory.ModelEntry entry = snapshot.entries().get(1);
            if (entry != null) assertEquals(snapshot.generation(), entry.combinedModel().emission());
        }
        writer.join();
        ModelFactory.FactorySnapshot snapshot = factory.snapshot(new int[]{1});
        assertEquals(snapshot.generation(), snapshot.entries().get(1).combinedModel().emission());
    }

    @Test
    public void bakeryProcessesQueuedCpuModelsAndShutsDown() throws Exception {
        ModelBakerySubsystem bakery = new ModelBakerySubsystem();
        bakery.submit(new BakedBlockModel(4, false, false, List.of()));

        assertTrue(bakery.awaitIdle(Duration.ofSeconds(2)));
        assertNotNull(bakery.factory().get(4));
        bakery.close();
        assertThrows(IllegalStateException.class,
                () -> bakery.submit(new BakedBlockModel(5, false, false, List.of())));
    }

    @Test
    public void workerPublicationMakesExistingSnapshotStale() throws Exception {
        ModelFactory published = new ModelFactory();
        published.install(new BakedBlockModel(6, false, false, List.of()));
        java.util.concurrent.atomic.AtomicLong catalogGeneration = new java.util.concurrent.atomic.AtomicLong(1);
        ModelSnapshot snapshot = ModelSnapshot.fromImmutable(published.snapshot(new int[]{6}).entries(),
                1, 1, () -> 1, catalogGeneration::get, 6);
        ModelBakerySubsystem bakery = new ModelBakerySubsystem(entries -> {
            published.publishPrepared(entries);
            catalogGeneration.incrementAndGet();
        });

        bakery.submit(new BakedBlockModel(6, false, false, 4, List.of()));

        assertTrue(bakery.awaitIdle(Duration.ofSeconds(2)));
        assertFalse(snapshot.isCurrent());
        assertEquals(4, published.get(6).emission());
        bakery.close();
    }

    @Test
    public void staleEpochPublisherDiscardsDetachedWorkerBatch() throws Exception {
        ModelFactory published = new ModelFactory();
        java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong(3);
        long expectedEpoch = epoch.get();
        ModelBakerySubsystem bakery = new ModelBakerySubsystem(entries -> {
            if (epoch.get() == expectedEpoch) published.publishPrepared(entries);
        });
        epoch.incrementAndGet();

        bakery.submit(new BakedBlockModel(8, false, false, List.of()));

        assertTrue(bakery.awaitIdle(Duration.ofSeconds(2)));
        assertNull(published.get(8));
        bakery.close();
    }

    private static BakedBlockModel.Quad quad(Direction face, float height, ResourceLocation fluidKey) {
        int[] vertices = new int[32];
        float[][] positions = switch (face) {
            case EAST -> new float[][]{{1, 0, 0}, {1, 0, 1}, {1, 1, 1}, {1, 1, 0}};
            case UP -> new float[][]{{0, height, 0}, {0, height, 1}, {1, height, 1}, {1, height, 0}};
            default -> throw new IllegalArgumentException("Unsupported test face " + face);
        };
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * 8;
            vertices[base] = Float.floatToRawIntBits(positions[vertex][0]);
            vertices[base + 1] = Float.floatToRawIntBits(positions[vertex][1]);
            vertices[base + 2] = Float.floatToRawIntBits(positions[vertex][2]);
        }
        return new BakedBlockModel.Quad(vertices, fluidKey == null ? -1 : 0, face, null,
                fluidKey == null ? "solid" : "translucent", true, true,
                fluidKey == null ? BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT
                        : BakedBlockModel.MaterialPass.TRANSLUCENT,
                false, fluidKey, height);
    }

    private static BakedBlockModel.Quad eastQuad(float minZ, float maxZ) {
        int[] vertices = new int[32];
        float[][] positions = {{1, 0, minZ}, {1, 0, maxZ}, {1, 1, maxZ}, {1, 1, minZ}};
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * 8;
            vertices[base] = Float.floatToRawIntBits(positions[vertex][0]);
            vertices[base + 1] = Float.floatToRawIntBits(positions[vertex][1]);
            vertices[base + 2] = Float.floatToRawIntBits(positions[vertex][2]);
        }
        return new BakedBlockModel.Quad(vertices, -1, Direction.EAST, null, "solid", true, true);
    }

    private static BakedBlockModel.Quad typedFace(Direction face, BakedBlockModel.MaterialType type,
                                                  float opaqueCoverage, ResourceLocation fluidKey,
                                                  float height) {
        int[] vertices = new int[32];
        float h = fluidKey == null ? 1.0F : height;
        float[][] positions = switch (face) {
            case DOWN -> new float[][]{{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}};
            case UP -> new float[][]{{0, h, 0}, {0, h, 1}, {1, h, 1}, {1, h, 0}};
            case NORTH -> new float[][]{{1, 0, 0}, {0, 0, 0}, {0, h, 0}, {1, h, 0}};
            case SOUTH -> new float[][]{{0, 0, 1}, {1, 0, 1}, {1, h, 1}, {0, h, 1}};
            case WEST -> new float[][]{{0, 0, 0}, {0, 0, 1}, {0, h, 1}, {0, h, 0}};
            case EAST -> new float[][]{{1, 0, 1}, {1, 0, 0}, {1, h, 0}, {1, h, 1}};
        };
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * 8;
            vertices[base] = Float.floatToRawIntBits(positions[vertex][0]);
            vertices[base + 1] = Float.floatToRawIntBits(positions[vertex][1]);
            vertices[base + 2] = Float.floatToRawIntBits(positions[vertex][2]);
        }
        BakedBlockModel.MaterialPass pass = type == BakedBlockModel.MaterialType.TRANSLUCENT
                ? BakedBlockModel.MaterialPass.TRANSLUCENT
                : BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT;
        return new BakedBlockModel.Quad(vertices, fluidKey == null ? -1 : 0, face, null,
                type.name().toLowerCase(java.util.Locale.ROOT), true, true, pass, false,
                fluidKey, height, type, opaqueCoverage > 0.0F ? 1.0F : 0.0F, opaqueCoverage);
    }

    private static BakedBlockModel.Quad eastRect(float minZ, float maxZ, float minY, float maxY) {
        int[] vertices = new int[32];
        float[][] positions = {{1, minY, maxZ}, {1, minY, minZ},
                {1, maxY, minZ}, {1, maxY, maxZ}};
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * 8;
            vertices[base] = Float.floatToRawIntBits(positions[vertex][0]);
            vertices[base + 1] = Float.floatToRawIntBits(positions[vertex][1]);
            vertices[base + 2] = Float.floatToRawIntBits(positions[vertex][2]);
        }
        return new BakedBlockModel.Quad(vertices, -1, Direction.EAST, null, "solid", true, true,
                BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT, false, null, Float.NaN,
                BakedBlockModel.MaterialType.SOLID, 1.0F, 1.0F);
    }
}
