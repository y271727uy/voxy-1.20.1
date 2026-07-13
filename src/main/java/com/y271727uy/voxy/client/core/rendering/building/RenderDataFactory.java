package com.y271727uy.voxy.client.core.rendering.building;

import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** CPU-side topology builder. Model baking and GPU encoding are deliberately kept outside this class. */
public final class RenderDataFactory {
    public static final int EMPTY_STATE = -1;
    public static final int ALL_FACES = (1 << Direction.values().length) - 1;
    private static final float HEIGHT_EPSILON = 1.0F / 1024.0F;

    private RenderDataFactory() {
    }

    public static RenderData build(int side, StateSource states, ModelResolver models) {
        if (side < 1 || side > 32) {
            throw new IllegalArgumentException("side must be between 1 and 32: " + side);
        }
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(models, "models");

        int[] opaqueMasks = new int[side * side];
        int[] nonOpaqueMasks = new int[side * side];
        int[] fluidMasks = new int[side * side];
        List<BlockFace> opaqueFaces = new ArrayList<>();
        List<BlockFace> nonOpaqueFaces = new ArrayList<>();
        List<FluidFace> fluidFaces = new ArrayList<>();

        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                int maskIndex = y * side + z;
                for (int x = 0; x < side; x++) {
                    CellData cell = states.cell(x, y, z);
                    ModelView model = resolve(cell, models);
                    if (model.empty()) {
                        continue;
                    }
                    int bit = 1 << x;
                    if (model.fullyOpaque()) {
                        opaqueMasks[maskIndex] |= bit;
                    } else if (model.fluid() == null || !model.fluid().pure()) {
                        nonOpaqueMasks[maskIndex] |= bit;
                    }
                    if (model.fluid() != null) {
                        fluidMasks[maskIndex] |= bit;
                    }

                    if (model.fluid() == null || !model.fluid().pure()) {
                        for (Direction direction : Direction.values()) {
                            ModelView neighbor = resolve(states, models,
                                    x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
                            if (shouldEmitBlockFace(model, neighbor, direction)) {
                                int aoSignature = aoSignature(states, models, x, y, z, direction);
                                if (model.hasSolidFace(direction)) {
                                    opaqueFaces.add(new BlockFace(x, y, z, direction, cell,
                                            MaterialType.SOLID, aoSignature));
                                }
                                if (model.hasCutoutFace(direction)) {
                                    opaqueFaces.add(new BlockFace(x, y, z, direction, cell,
                                            MaterialType.CUTOUT, aoSignature));
                                }
                                if (model.hasTranslucentFace(direction)) {
                                    nonOpaqueFaces.add(new BlockFace(x, y, z, direction, cell,
                                            MaterialType.TRANSLUCENT, aoSignature));
                                }
                            }
                        }
                    }

                    if (model.fluid() != null) {
                        FluidSurface surface = surface(states, models, x, y, z, model.fluid().key());
                        FlowVector flow = model.fluid().flowKnown()
                                ? new FlowVector(model.fluid().flowX(), model.fluid().flowZ())
                                : deriveFlow(states, models, x, y, z, model.fluid());
                        for (Direction direction : Direction.values()) {
                            FluidFace face = fluidFace(states, models, x, y, z, model, surface, flow, direction);
                            if (face != null) {
                                fluidFaces.addAll(splitFluidFace(face, model.hostSurfaces().get(direction)));
                            }
                        }
                    }
                }
            }
        }
        List<GreedyBlockFace> greedyOpaque = greedy(side, opaqueFaces, models, Layer.OPAQUE);
        List<GreedyBlockFace> greedyNonOpaque = greedy(side, nonOpaqueFaces, models, Layer.NON_OPAQUE);
        return new RenderData(side, opaqueMasks, nonOpaqueMasks, fluidMasks,
                opaqueFaces, nonOpaqueFaces, fluidFaces, greedyOpaque, greedyNonOpaque);
    }

    static boolean shouldEmitBlockFace(ModelView self, ModelView neighbor, Direction direction) {
        if (!self.faceExists(direction)) {
            return false;
        }
        if (neighbor.empty()) {
            return true;
        }
        if (self.stateId() == neighbor.stateId()
                && (self.cullsSame() || self.faceOccludes(direction))) {
            return false;
        }
        return !self.faceCanBeOccluded(direction) || !neighbor.faceOccludes(direction.getOpposite());
    }

    private static FluidFace fluidFace(StateSource states, ModelResolver models, int x, int y, int z,
                                       ModelView self, FluidSurface surface, FlowVector flow,
                                       Direction direction) {
        FluidView fluid = self.fluid();
        if (!fluid.faceOpen(direction)) {
            return null;
        }
        ModelView neighbor = resolve(states, models,
                x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
        FluidView neighborFluid = neighbor.fluid();
        boolean sameFluid = neighborFluid != null && neighborFluid.key() == fluid.key();
        if (direction == Direction.UP) {
            if (sameFluid || neighbor.faceOccludes(Direction.DOWN)) {
                return null;
            }
            return new FluidFace(x, y, z, direction, fluid.key(), !fluid.pure(),
                    surface.northWest(), surface.southWest(), surface.northEast(), surface.southEast(),
                    0.0F, 0.0F, flow.x(), flow.z());
        }
        if (direction == Direction.DOWN) {
            if (sameFluid || neighbor.faceOccludes(Direction.UP)) {
                return null;
            }
            return new FluidFace(x, y, z, direction, fluid.key(), !fluid.pure(),
                    surface.northWest(), surface.southWest(), surface.northEast(), surface.southEast(),
                    0.0F, 0.0F, flow.x(), flow.z());
        }
        if (neighbor.faceOccludes(direction.getOpposite())) {
            return null;
        }
        // Equal fluids share corner samples along their common edge, so this face is internal.
        // Sampling the neighbor's full surface would incorrectly require a two-cell section shell.
        if (sameFluid) {
            return null;
        }

        float topLeft;
        float topRight;
        float bottomLeft = 0.0F;
        float bottomRight = 0.0F;
        switch (direction) {
            case NORTH -> {
                topLeft = surface.northEast();
                topRight = surface.northWest();
            }
            case SOUTH -> {
                topLeft = surface.southWest();
                topRight = surface.southEast();
            }
            case WEST -> {
                topLeft = surface.northWest();
                topRight = surface.southWest();
            }
            case EAST -> {
                topLeft = surface.southEast();
                topRight = surface.northEast();
            }
            default -> throw new IllegalStateException("Unexpected horizontal face " + direction);
        }
        return new FluidFace(x, y, z, direction, fluid.key(), !fluid.pure(),
                surface.northWest(), surface.southWest(), surface.northEast(), surface.southEast(),
                bottomLeft, bottomRight, flow.x(), flow.z());
    }

    private static FlowVector deriveFlow(StateSource states, ModelResolver models,
                                         int x, int y, int z, FluidView fluid) {
        float west = flowNeighborHeight(states, models, x - 1, y, z, fluid);
        float east = flowNeighborHeight(states, models, x + 1, y, z, fluid);
        float north = flowNeighborHeight(states, models, x, y, z - 1, fluid);
        float south = flowNeighborHeight(states, models, x, y, z + 1, fluid);
        float flowX = west - east;
        float flowZ = north - south;
        float length = (float) Math.sqrt(flowX * flowX + flowZ * flowZ);
        return length < HEIGHT_EPSILON ? new FlowVector(0.0F, 0.0F)
                : new FlowVector(flowX / length, flowZ / length);
    }

    private static List<FluidFace> splitFluidFace(FluidFace face, HostSurface host) {
        if (host == null || host.empty() || !host.blocksBoundary(face.direction(), face)) {
            return List.of(face);
        }
        boolean[][] available = new boolean[16][16];
        for (int v = 0; v < 16; v++) {
            for (int u = 0; u < 16; u++) available[v][u] = !host.covered(u, v);
        }
        List<FluidFace> output = new ArrayList<>();
        for (int v = 0; v < 16; v++) {
            for (int u = 0; u < 16; u++) {
                if (!available[v][u]) continue;
                int length = 1;
                while (u + length < 16 && available[v][u + length]) length++;
                int width = 1;
                rows: while (v + width < 16) {
                    for (int offset = 0; offset < length; offset++) {
                        if (!available[v + width][u + offset]) break rows;
                    }
                    width++;
                }
                for (int row = 0; row < width; row++) {
                    for (int column = 0; column < length; column++) {
                        available[v + row][u + column] = false;
                    }
                }
                output.add(face.crop(u / 16.0F, v / 16.0F,
                        (u + length) / 16.0F, (v + width) / 16.0F));
            }
        }
        return output;
    }

    private static float flowNeighborHeight(StateSource states, ModelResolver models,
                                            int x, int y, int z, FluidView self) {
        ModelView neighbor = resolve(states, models, x, y, z);
        if (neighbor.fluid() != null && neighbor.fluid().key() == self.key()) {
            return neighbor.fluid().height();
        }
        return neighbor.empty() || !neighbor.fullyOpaque() ? 0.0F : self.height();
    }

    private static FluidSurface surface(StateSource states, ModelResolver models,
                                        int x, int y, int z, int fluidKey) {
        return new FluidSurface(
                cornerHeight(states, models, x, y, z, -1, -1, fluidKey),
                cornerHeight(states, models, x, y, z, -1, 1, fluidKey),
                cornerHeight(states, models, x, y, z, 1, -1, fluidKey),
                cornerHeight(states, models, x, y, z, 1, 1, fluidKey));
    }

    private static float cornerHeight(StateSource states, ModelResolver models, int x, int y, int z,
                                      int offsetX, int offsetZ, int fluidKey) {
        int[] sampleX = {x, x + offsetX, x, x + offsetX};
        int[] sampleZ = {z, z, z + offsetZ, z + offsetZ};
        float weightedHeight = 0.0F;
        int totalWeight = 0;
        for (int index = 0; index < 4; index++) {
            ModelView sample = resolve(states, models, sampleX[index], y, sampleZ[index]);
            FluidView sampleFluid = sample.fluid();
            if (sampleFluid != null && sampleFluid.key() == fluidKey) {
                ModelView above = resolve(states, models, sampleX[index], y + 1, sampleZ[index]);
                if (above.fluid() != null && above.fluid().key() == fluidKey) {
                    return 1.0F;
                }
                int weight = sampleFluid.height() >= 0.8F ? 10 : 1;
                weightedHeight += sampleFluid.height() * weight;
                totalWeight += weight;
            } else if (sample.empty() || !sample.fullyOpaque()) {
                totalWeight++;
            }
        }
        return totalWeight == 0 ? 0.0F : weightedHeight / totalWeight;
    }

    private static ModelView resolve(StateSource states, ModelResolver models, int x, int y, int z) {
        return resolve(states.cell(x, y, z), models);
    }

    private static ModelView resolve(CellData cell, ModelResolver models) {
        int stateId = cell.stateId();
        if (stateId == EMPTY_STATE) {
            return ModelView.EMPTY;
        }
        ModelView model = models.resolve(stateId);
        if (model == null) {
            throw new IllegalStateException("No model view for state " + stateId);
        }
        if (model.stateId() != stateId) {
            throw new IllegalStateException("Model state mismatch: requested " + stateId + ", got " + model.stateId());
        }
        return model;
    }

    private static int aoSignature(StateSource states, ModelResolver models,
                                   int x, int y, int z, Direction face) {
        int signature = 0;
        int bit = 0;
        for (int first = -1; first <= 1; first++) {
            for (int second = -1; second <= 1; second++) {
                if (first == 0 && second == 0) continue;
                int sampleX = x + face.getStepX();
                int sampleY = y + face.getStepY();
                int sampleZ = z + face.getStepZ();
                switch (face.getAxis()) {
                    case X -> { sampleY += first; sampleZ += second; }
                    case Y -> { sampleX += first; sampleZ += second; }
                    case Z -> { sampleX += first; sampleY += second; }
                }
                if (resolve(states, models, sampleX, sampleY, sampleZ).fullyOpaque()) {
                    signature |= 1 << bit;
                }
                bit++;
            }
        }
        return signature;
    }

    private static List<GreedyBlockFace> greedy(int side, List<BlockFace> faces,
                                                ModelResolver models, Layer layer) {
        Map<PlaneKey, BlockFace[][]> planes = new HashMap<>();
        for (BlockFace face : faces) {
            int plane = plane(face);
            int u = u(face);
            int v = v(face);
            planes.computeIfAbsent(new PlaneKey(face.direction(), plane),
                    ignored -> new BlockFace[side][side])[v][u] = face;
        }
        List<GreedyBlockFace> output = new ArrayList<>();
        planes.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<PlaneKey, BlockFace[][]> entry) ->
                        entry.getKey().direction().ordinal()).thenComparingInt(entry -> entry.getKey().plane()))
                .forEach(entry -> scanPlane(side, entry.getKey(), entry.getValue(), models, layer, output));
        return output;
    }

    private static void scanPlane(int side, PlaneKey key, BlockFace[][] cells,
                                  ModelResolver models, Layer layer, List<GreedyBlockFace> output) {
        boolean[][] consumed = new boolean[side][side];
        for (int v = 0; v < side; v++) {
            for (int u = 0; u < side; u++) {
                BlockFace first = cells[v][u];
                if (first == null || consumed[v][u]) {
                    continue;
                }
                ModelView model = models.resolve(first.stateId());
                boolean eligible = model != null && model.faceGreedyEligible(first.direction());
                int length = 1;
                if (eligible) {
                    while (length < 16 && u + length < side
                            && compatible(first, cells[v][u + length], consumed[v][u + length])) {
                        length++;
                    }
                }
                int width = 1;
                if (eligible) {
                    rows: while (width < 16 && v + width < side) {
                        for (int offset = 0; offset < length; offset++) {
                            if (!compatible(first, cells[v + width][u + offset], consumed[v + width][u + offset])) {
                                break rows;
                            }
                        }
                        width++;
                    }
                }
                for (int row = 0; row < width; row++) {
                    for (int column = 0; column < length; column++) {
                        consumed[v + row][u + column] = true;
                    }
                }
                output.add(new GreedyBlockFace(key.direction(), first.stateId(), first.material(), layer,
                        key.plane(), u, v, length, width));
            }
        }
    }

    private static boolean compatible(BlockFace expected, BlockFace actual, boolean consumed) {
        return !consumed && actual != null && actual.cell().equals(expected.cell())
                && actual.aoSignature() == expected.aoSignature()
                && actual.material() == expected.material() && actual.direction() == expected.direction();
    }

    private static int plane(BlockFace face) {
        int coordinate = face.direction().getAxis() == Direction.Axis.X ? face.x()
                : face.direction().getAxis() == Direction.Axis.Y ? face.y() : face.z();
        return coordinate + (face.direction().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0);
    }

    private static int u(BlockFace face) {
        return switch (face.direction().getAxis()) {
            case X -> face.z();
            case Y, Z -> face.x();
        };
    }

    private static int v(BlockFace face) {
        return switch (face.direction().getAxis()) {
            case X, Z -> face.y();
            case Y -> face.z();
        };
    }

    public interface StateSource {
        CellData cell(int x, int y, int z);
    }

    @FunctionalInterface
    public interface IdStateSource {
        int stateId(int x, int y, int z);
    }

    public static StateSource stateIds(IdStateSource source) {
        Objects.requireNonNull(source, "source");
        return (x, y, z) -> CellData.ofState(source.stateId(x, y, z));
    }

    public record CellData(int stateId, int biomeId, int packedLight, int shadingKey) {
        public static final CellData EMPTY = new CellData(EMPTY_STATE, 0, 0, 0);

        public static CellData ofState(int stateId) {
            return stateId == EMPTY_STATE ? EMPTY : new CellData(stateId, 0, 0, 0);
        }
    }

    @FunctionalInterface
    public interface ModelResolver {
        ModelView resolve(int stateId);
    }

    public record ModelView(int stateId, boolean empty, boolean fullyOpaque, boolean cullsSame,
                            int faceExistsMask, int faceCanBeOccludedMask, int faceOccludesMask,
                            int greedyFacesMask, int solidFacesMask, int cutoutFacesMask,
                            int translucentFacesMask, Map<Direction, HostSurface> hostSurfaces,
                            FluidView fluid) {
        public static final ModelView EMPTY = new ModelView(EMPTY_STATE, true, false, false,
                0, 0, 0, 0, 0, 0, 0, Map.of(), null);

        public ModelView(int stateId, boolean empty, boolean fullyOpaque, boolean cullsSame,
                         int faceExistsMask, int faceCanBeOccludedMask, int faceOccludesMask,
                         FluidView fluid) {
            this(stateId, empty, fullyOpaque, cullsSame, faceExistsMask, faceCanBeOccludedMask,
                    faceOccludesMask, fullyOpaque ? faceExistsMask : 0,
                    fullyOpaque ? faceExistsMask : 0, 0, fullyOpaque ? 0 : faceExistsMask,
                    Map.of(), fluid);
        }

        public ModelView(int stateId, boolean empty, boolean fullyOpaque, boolean cullsSame,
                         int faceExistsMask, int faceCanBeOccludedMask, int faceOccludesMask,
                         int greedyFacesMask, FluidView fluid) {
            this(stateId, empty, fullyOpaque, cullsSame, faceExistsMask, faceCanBeOccludedMask,
                    faceOccludesMask, greedyFacesMask, fullyOpaque ? faceExistsMask : 0,
                    0, fullyOpaque ? 0 : faceExistsMask, Map.of(), fluid);
        }

        public ModelView {
            hostSurfaces = Map.copyOf(hostSurfaces);
        }

        public boolean faceExists(Direction direction) {
            return bit(faceExistsMask, direction);
        }

        public boolean faceCanBeOccluded(Direction direction) {
            return bit(faceCanBeOccludedMask, direction);
        }

        public boolean faceOccludes(Direction direction) {
            return bit(faceOccludesMask, direction);
        }

        public boolean faceGreedyEligible(Direction direction) {
            return bit(greedyFacesMask, direction);
        }

        public boolean hasSolidFace(Direction direction) { return bit(solidFacesMask, direction); }
        public boolean hasCutoutFace(Direction direction) { return bit(cutoutFacesMask, direction); }
        public boolean hasTranslucentFace(Direction direction) { return bit(translucentFacesMask, direction); }
    }

    public record FluidView(int key, boolean pure, float height, boolean flowKnown,
                            float flowX, float flowZ,
                            int openFacesMask) {
        public FluidView(int key, boolean pure, float height, float flowX, float flowZ,
                         int openFacesMask) {
            this(key, pure, height, true, flowX, flowZ, openFacesMask);
        }

        public FluidView {
            if (key < 0) throw new IllegalArgumentException("fluid key must be non-negative");
            if (!Float.isFinite(height) || height < 0.0F || height > 1.0F) {
                throw new IllegalArgumentException("fluid height must be within 0..1: " + height);
            }
            if (flowKnown && (!Float.isFinite(flowX) || !Float.isFinite(flowZ))) {
                throw new IllegalArgumentException("fluid flow must be finite");
            }
            if (!flowKnown) {
                flowX = 0.0F;
                flowZ = 0.0F;
            }
        }

        public boolean faceOpen(Direction direction) {
            return bit(openFacesMask, direction);
        }
    }

    public record RenderData(int side, int[] opaqueMasks, int[] nonOpaqueMasks, int[] fluidMasks,
                             List<BlockFace> opaqueFaces, List<BlockFace> nonOpaqueFaces,
                             List<FluidFace> fluidFaces, List<GreedyBlockFace> greedyOpaqueFaces,
                             List<GreedyBlockFace> greedyNonOpaqueFaces) {
        public RenderData {
            opaqueMasks = opaqueMasks.clone();
            nonOpaqueMasks = nonOpaqueMasks.clone();
            fluidMasks = fluidMasks.clone();
            opaqueFaces = List.copyOf(opaqueFaces);
            nonOpaqueFaces = List.copyOf(nonOpaqueFaces);
            fluidFaces = List.copyOf(fluidFaces);
            greedyOpaqueFaces = List.copyOf(greedyOpaqueFaces);
            greedyNonOpaqueFaces = List.copyOf(greedyNonOpaqueFaces);
        }

        @Override public int[] opaqueMasks() { return opaqueMasks.clone(); }
        @Override public int[] nonOpaqueMasks() { return nonOpaqueMasks.clone(); }
        @Override public int[] fluidMasks() { return fluidMasks.clone(); }
    }

    public record BlockFace(int x, int y, int z, Direction direction, CellData cell,
                            MaterialType material, int aoSignature) {
        public int stateId() { return cell.stateId(); }
    }

    public enum Layer { OPAQUE, NON_OPAQUE, FLUID }
    public enum MaterialType { SOLID, CUTOUT, TRANSLUCENT }

    public record GreedyBlockFace(Direction direction, int stateId, MaterialType material, Layer layer,
                                  int plane, int u, int v, int length, int width) {
    }

    public record FluidFace(int x, int y, int z, Direction direction, int fluidKey, boolean waterlogged,
                            float northWest, float southWest, float northEast, float southEast,
                            float adjacentLeft, float adjacentRight, float flowX, float flowZ,
                            float cropMinU, float cropMinV, float cropMaxU, float cropMaxV) {
        public FluidFace(int x, int y, int z, Direction direction, int fluidKey, boolean waterlogged,
                         float northWest, float southWest, float northEast, float southEast,
                         float adjacentLeft, float adjacentRight, float flowX, float flowZ) {
            this(x, y, z, direction, fluidKey, waterlogged, northWest, southWest, northEast, southEast,
                    adjacentLeft, adjacentRight, flowX, flowZ, 0.0F, 0.0F, 1.0F, 1.0F);
        }

        FluidFace crop(float minU, float minV, float maxU, float maxV) {
            return new FluidFace(x, y, z, direction, fluidKey, waterlogged,
                    northWest, southWest, northEast, southEast, adjacentLeft, adjacentRight,
                    flowX, flowZ, minU, minV, maxU, maxV);
        }
    }

    public record HostSurface(long mask0, long mask1, long mask2, long mask3,
                              float minDepth, float maxDepth) {
        boolean empty() { return (mask0 | mask1 | mask2 | mask3) == 0; }

        boolean covered(int u, int v) {
            int index = v * 16 + u;
            long mask = switch (index >>> 6) { case 0 -> mask0; case 1 -> mask1; case 2 -> mask2; default -> mask3; };
            return (mask & (1L << (index & 63))) != 0;
        }

        boolean blocksBoundary(Direction direction, FluidFace face) {
            if (!Float.isFinite(minDepth) || !Float.isFinite(maxDepth)) return false;
            return switch (direction.getAxisDirection()) {
                case POSITIVE -> maxDepth >= 1.0F - HEIGHT_EPSILON;
                case NEGATIVE -> minDepth <= HEIGHT_EPSILON;
            };
        }
    }

    private record FluidSurface(float northWest, float southWest, float northEast, float southEast) {
    }

    private record FlowVector(float x, float z) {
    }

    private record PlaneKey(Direction direction, int plane) {
    }

    private static boolean bit(int mask, Direction direction) {
        return (mask & (1 << direction.ordinal())) != 0;
    }
}
