package com.y271727uy.voxy.client.core.model;

import com.y271727uy.voxy.client.model.BakedBlockModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** CPU-side model store and metadata builder. GPU texture packing remains a renderer concern. */
public final class ModelFactory implements AutoCloseable {
    private static final float FACE_EPSILON = 0.001F;

    private final ConcurrentHashMap<Integer, ModelEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;
    private int batchDepth;
    private boolean batchDirty;

    public synchronized ModelEntry install(BakedBlockModel bakedModel) {
        return install(bakedModel, null);
    }

    public synchronized ModelEntry install(BakedBlockModel bakedModel, ModelDescriptor descriptor) {
        checkOpen();
        ModelEntry entry = prepare(bakedModel, descriptor);
        this.entries.put(bakedModel.blockId(), entry);
        markChanged();
        return entry;
    }

    public static ModelEntry prepare(BakedBlockModel model, ModelDescriptor descriptor) {
        return createEntry(model, descriptor);
    }

    public synchronized void publishPrepared(List<ModelEntry> prepared) {
        checkOpen();
        if (prepared.isEmpty()) return;
        for (ModelEntry entry : prepared) {
            this.entries.put(entry.combinedModel().blockId(), entry);
        }
        markChanged();
    }

    public synchronized void invalidate(int blockId) {
        checkOpen();
        if (this.entries.remove(blockId) != null) markChanged();
    }

    public BakedBlockModel get(int blockId) {
        ModelEntry entry = this.entries.get(blockId);
        return entry == null ? null : entry.combinedModel();
    }

    public ModelEntry entry(int blockId) {
        return this.entries.get(blockId);
    }

    public Collection<BakedBlockModel> models() {
        return this.entries.values().stream().map(ModelEntry::combinedModel).toList();
    }

    public int size() {
        return this.entries.size();
    }

    public long generation() {
        return this.generation.get();
    }

    public synchronized FactorySnapshot snapshot(int[] stateIds) {
        if (this.batchDepth != 0) throw new IllegalStateException("Cannot snapshot an active model batch");
        Map<Integer, ModelEntry> selected = new java.util.HashMap<>();
        for (int stateId : stateIds) {
            ModelEntry entry = this.entries.get(stateId);
            if (entry != null) selected.put(stateId, entry);
        }
        return new FactorySnapshot(this.generation.get(), Map.copyOf(selected));
    }

    public synchronized void clear() {
        checkOpen();
        if (!this.entries.isEmpty()) {
            this.entries.clear();
            markChanged();
        }
    }

    public synchronized void beginBatch() {
        checkOpen();
        this.batchDepth++;
    }

    public synchronized void endBatch() {
        if (this.batchDepth == 0) throw new IllegalStateException("No model batch is active");
        if (--this.batchDepth == 0 && this.batchDirty) {
            this.batchDirty = false;
            this.generation.incrementAndGet();
        }
    }

    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        this.entries.clear();
        this.generation.incrementAndGet();
    }

    private void checkOpen() {
        if (this.closed) throw new IllegalStateException("Model factory is closed");
    }

    private synchronized void markChanged() {
        if (this.batchDepth == 0) this.generation.incrementAndGet();
        else this.batchDirty = true;
    }

    static ModelEntry createEntry(BakedBlockModel model, ModelDescriptor suppliedDescriptor) {
        List<BakedBlockModel.Quad> blockQuads = new ArrayList<>();
        List<BakedBlockModel.Quad> fluidQuads = new ArrayList<>();
        for (BakedBlockModel.Quad quad : model.quads()) {
            (quad.fluidOverlay() ? fluidQuads : blockQuads).add(quad);
        }
        BakedBlockModel blockModel = copyWithQuads(model, blockQuads);
        BakedBlockModel fluidModel = fluidQuads.isEmpty() ? null : copyWithQuads(model, fluidQuads);
        ModelMetadata metadata = metadata(model);
        ModelDescriptor descriptor = suppliedDescriptor == null
                ? descriptor(model, metadata, false, false, null) : suppliedDescriptor;
        return new ModelEntry(model, blockModel, fluidModel, metadata, descriptor);
    }

    private static BakedBlockModel copyWithQuads(BakedBlockModel model, List<BakedBlockModel.Quad> quads) {
        return new BakedBlockModel(model.blockId(), model.ambientOcclusion(), model.customRenderer(),
                model.emission(), quads);
    }

    static ModelMetadata metadata(BakedBlockModel model) {
        EnumMap<Direction, FaceMetadata> faces = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) {
            faces.put(direction, faceMetadata(model.quads(), direction));
        }
        boolean opaque = model.quads().stream().anyMatch(quad ->
                quad.materialPass() == BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT);
        boolean translucent = model.quads().stream().anyMatch(quad ->
                quad.materialPass() == BakedBlockModel.MaterialPass.TRANSLUCENT);
        BakedBlockModel.Quad fluid = model.quads().stream().filter(BakedBlockModel.Quad::fluidOverlay)
                .findFirst().orElse(null);
        return new ModelMetadata(Map.copyOf(faces), opaque, translucent, fluid != null,
                fluid == null ? null : fluid.fluidKey(), fluid == null ? Float.NaN : fluid.fluidHeight());
    }

    public static ModelDescriptor descriptor(BakedBlockModel model, boolean fullyOpaque,
                                             boolean cullsSame, FluidDescriptor fluid) {
        return descriptor(model, metadata(model), fullyOpaque, cullsSame, fluid);
    }

    private static ModelDescriptor descriptor(BakedBlockModel model, ModelMetadata metadata,
                                              boolean fullyOpaque, boolean cullsSame,
                                              FluidDescriptor suppliedFluid) {
        int exists = 0;
        int canBeOccluded = 0;
        int occludes = 0;
        int fluidFaces = 0;
        int solidFaces = 0;
        int cutoutFaces = 0;
        int translucentFaces = 0;
        EnumMap<Direction, FaceSurface> surfaces = new EnumMap<>(Direction.class);
        List<BakedBlockModel.Quad> hostQuads = model.quads().stream()
                .filter(quad -> !quad.fluidOverlay()).toList();
        for (Direction direction : Direction.values()) {
            int bit = 1 << direction.ordinal();
            FaceMetadata face = metadata.face(direction);
            if (face.present()) exists |= bit;
            if (face.canBeOccluded()) canBeOccluded |= bit;
            if (face.occludesNeighbor()) occludes |= bit;
            if (face.solid()) solidFaces |= bit;
            if (face.cutout()) cutoutFaces |= bit;
            if (face.translucent()) translucentFaces |= bit;
            Direction finalDirection = direction;
            boolean fluidPresent = model.quads().stream().anyMatch(quad -> quad.fluidOverlay()
                    && quad.cullFace() == finalDirection);
            if (fluidPresent && !faceMetadata(hostQuads, direction).occludesNeighbor()) fluidFaces |= bit;
            surfaces.put(direction, faceSurface(hostQuads, direction));
        }
        FluidDescriptor fluid = suppliedFluid != null ? suppliedFluid : metadata.hasFluid()
                ? new FluidDescriptor(-1, false, metadata.fluidHeight(), false,
                0.0F, 0.0F, fluidFaces)
                : null;
        if (fluid != null && fluid.openFacesMask() < 0) {
            fluid = new FluidDescriptor(fluid.fluidKey(), fluid.pure(), fluid.ownHeight(),
                    fluid.flowKnown(), fluid.flowX(), fluid.flowZ(), fluidFaces);
        }
        return new ModelDescriptor(fullyOpaque && occludes == 0x3F, cullsSame, exists,
                canBeOccluded, occludes, solidFaces, cutoutFaces, translucentFaces,
                Map.copyOf(surfaces), fluid);
    }

    private static FaceSurface faceSurface(List<BakedBlockModel.Quad> quads, Direction face) {
        long[] mask = new long[4];
        float minDepth = Float.POSITIVE_INFINITY;
        float maxDepth = Float.NEGATIVE_INFINITY;
        for (BakedBlockModel.Quad quad : quads) {
            if (quad.cullFace() != face || quad.materialType() != BakedBlockModel.MaterialType.SOLID
                    || quad.opaqueCoverage() < 0.999F) continue;
            int[] vertices = quad.vertices();
            int stride = vertices.length / 4;
            if (stride < 3) continue;
            float minA = 1, maxA = 0, minB = 1, maxB = 0;
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * stride;
                float x = Float.intBitsToFloat(vertices[base]);
                float y = Float.intBitsToFloat(vertices[base + 1]);
                float z = Float.intBitsToFloat(vertices[base + 2]);
                float a = face.getAxis() == Direction.Axis.X ? z : x;
                float b = face.getAxis() == Direction.Axis.Y ? z : y;
                float depth = face.getAxis() == Direction.Axis.X ? x
                        : face.getAxis() == Direction.Axis.Y ? y : z;
                minA = Math.min(minA, a); maxA = Math.max(maxA, a);
                minB = Math.min(minB, b); maxB = Math.max(maxB, b);
                minDepth = Math.min(minDepth, depth); maxDepth = Math.max(maxDepth, depth);
            }
            int x0 = Math.max(0, Math.min(15, (int) Math.floor(minA * 16)));
            int x1 = Math.max(0, Math.min(15, (int) Math.ceil(maxA * 16) - 1));
            int y0 = Math.max(0, Math.min(15, (int) Math.floor(minB * 16)));
            int y1 = Math.max(0, Math.min(15, (int) Math.ceil(maxB * 16) - 1));
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                int index = y * 16 + x;
                mask[index >>> 6] |= 1L << (index & 63);
            }
        }
        if (minDepth == Float.POSITIVE_INFINITY) return new FaceSurface(0, 0, 0, 0, Float.NaN, Float.NaN);
        return new FaceSurface(mask[0], mask[1], mask[2], mask[3], minDepth, maxDepth);
    }

    private static FaceMetadata faceMetadata(List<BakedBlockModel.Quad> quads, Direction face) {
        boolean present = false;
        boolean translucent = false;
        boolean cutout = false;
        boolean solid = false;
        boolean fullBoundary = false;
        boolean fullOpaqueBoundary = false;
        float minA = 1.0F;
        float maxA = 0.0F;
        float minB = 1.0F;
        float maxB = 0.0F;
        float depth = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0.0F : 1.0F;
        for (BakedBlockModel.Quad quad : quads) {
            if (quad.cullFace() != face) continue;
            int[] vertices = quad.vertices();
            int stride = vertices.length / 4;
            if (stride < 3) continue;
            present = true;
            boolean quadOpaque = quad.materialPass() == BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT;
            translucent |= quad.materialPass() == BakedBlockModel.MaterialPass.TRANSLUCENT;
            cutout |= quad.materialType() == BakedBlockModel.MaterialType.CUTOUT;
            solid |= quad.materialType() == BakedBlockModel.MaterialType.SOLID;
            float quadMinA = 1.0F;
            float quadMaxA = 0.0F;
            float quadMinB = 1.0F;
            float quadMaxB = 0.0F;
            float quadDepth = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0.0F : 1.0F;
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * stride;
                float x = Float.intBitsToFloat(vertices[base]);
                float y = Float.intBitsToFloat(vertices[base + 1]);
                float z = Float.intBitsToFloat(vertices[base + 2]);
                float a = face.getAxis() == Direction.Axis.X ? z : x;
                float b = face.getAxis() == Direction.Axis.Y ? z : y;
                float normal = face.getAxis() == Direction.Axis.X ? x
                        : face.getAxis() == Direction.Axis.Y ? y : z;
                minA = Math.min(minA, a);
                maxA = Math.max(maxA, a);
                minB = Math.min(minB, b);
                maxB = Math.max(maxB, b);
                quadMinA = Math.min(quadMinA, a);
                quadMaxA = Math.max(quadMaxA, a);
                quadMinB = Math.min(quadMinB, b);
                quadMaxB = Math.max(quadMaxB, b);
                quadDepth = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? Math.max(quadDepth, normal) : Math.min(quadDepth, normal);
                depth = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? Math.max(depth, normal) : Math.min(depth, normal);
            }
            boolean quadFull = quadMinA <= FACE_EPSILON && quadMinB <= FACE_EPSILON
                    && quadMaxA >= 1.0F - FACE_EPSILON && quadMaxB >= 1.0F - FACE_EPSILON;
            boolean quadBoundary = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? quadDepth >= 1.0F - FACE_EPSILON : quadDepth <= FACE_EPSILON;
            fullBoundary |= quadFull && quadBoundary;
            fullOpaqueBoundary |= quadOpaque && quad.materialType() == BakedBlockModel.MaterialType.SOLID
                    && quad.opaqueCoverage() >= 0.999F && quadFull && quadBoundary;
        }
        return new FaceMetadata(present, fullBoundary, fullOpaqueBoundary,
                fullBoundary, solid, cutout, translucent, minA, maxA, minB, maxB, depth);
    }

    public record ModelEntry(BakedBlockModel combinedModel, BakedBlockModel blockModel,
                             BakedBlockModel fluidModel, ModelMetadata metadata,
                             ModelDescriptor descriptor) {
    }

    public record ModelMetadata(Map<Direction, FaceMetadata> faces, boolean hasOpaque,
                                boolean hasTranslucent, boolean hasFluid,
                                ResourceLocation fluidKey, float fluidHeight) {
        public FaceMetadata face(Direction direction) {
            return this.faces.get(direction);
        }
    }

    public record FaceMetadata(boolean present, boolean coversFullFace, boolean occludesNeighbor,
                               boolean canBeOccluded, boolean solid, boolean cutout, boolean translucent,
                               float minA, float maxA, float minB, float maxB, float depth) {
    }

    public record ModelDescriptor(boolean fullyOpaque, boolean cullsSame,
                                  int faceExistsMask, int faceCanBeOccludedMask,
                                  int faceOccludesMask, int solidFacesMask,
                                  int cutoutFacesMask, int translucentFacesMask,
                                  Map<Direction, FaceSurface> faceSurfaces, FluidDescriptor fluid) {
        public ModelDescriptor(boolean fullyOpaque, boolean cullsSame, int faceExistsMask,
                               int faceCanBeOccludedMask, int faceOccludesMask,
                               FluidDescriptor fluid) {
            this(fullyOpaque, cullsSame, faceExistsMask, faceCanBeOccludedMask,
                    faceOccludesMask, 0, 0, 0, Map.of(), fluid);
        }

        public ModelDescriptor(boolean fullyOpaque, boolean cullsSame, int faceExistsMask,
                               int faceCanBeOccludedMask, int faceOccludesMask,
                               int solidFacesMask, int cutoutFacesMask,
                               int translucentFacesMask, FluidDescriptor fluid) {
            this(fullyOpaque, cullsSame, faceExistsMask, faceCanBeOccludedMask,
                    faceOccludesMask, solidFacesMask, cutoutFacesMask,
                    translucentFacesMask, Map.of(), fluid);
        }
    }

    public record FaceSurface(long mask0, long mask1, long mask2, long mask3,
                              float minDepth, float maxDepth) {
        public int coveredPixels() {
            return Long.bitCount(mask0) + Long.bitCount(mask1)
                    + Long.bitCount(mask2) + Long.bitCount(mask3);
        }
    }

    public record FactorySnapshot(long generation, Map<Integer, ModelEntry> entries) {
    }

    public record FluidDescriptor(int fluidKey, boolean pure, float ownHeight,
                                  boolean flowKnown, float flowX, float flowZ,
                                  int openFacesMask) {
        public FluidDescriptor(int fluidKey, boolean pure, float ownHeight,
                               float flowX, float flowZ, int openFacesMask) {
            this(fluidKey, pure, ownHeight, !Float.isNaN(flowX) && !Float.isNaN(flowZ),
                    flowX, flowZ, openFacesMask);
        }
    }
}
