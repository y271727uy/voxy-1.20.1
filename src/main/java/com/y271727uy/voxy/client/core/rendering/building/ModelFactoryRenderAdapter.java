package com.y271727uy.voxy.client.core.rendering.building;

import com.y271727uy.voxy.client.core.model.ModelFactory;
import com.y271727uy.voxy.client.core.model.snapshot.ModelSnapshot;
import com.y271727uy.voxy.client.core.rendering.building.result.SnapshotBuildGuard;
import com.y271727uy.voxy.client.model.BakedBlockModel;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.function.LongSupplier;

/** Explicit bridge between the L13 model store and the CPU render-data builder. */
public final class ModelFactoryRenderAdapter implements RenderDataFactory.ModelResolver {
    private final SnapshotBuildGuard guard;
    private final Map<Integer, ModelFactory.ModelEntry> resolvedEntries = new HashMap<>();

    public ModelFactoryRenderAdapter(ModelSnapshot snapshot) {
        this.guard = new SnapshotBuildGuard(Objects.requireNonNull(snapshot, "snapshot"));
    }

    @Override
    public RenderDataFactory.ModelView resolve(int stateId) {
        ModelFactory.ModelEntry entry = this.guard.resolve(stateId);
        if (entry == null) {
            return new RenderDataFactory.ModelView(stateId, true, false, false,
                    0, 0, 0, 0, null);
        }
        this.resolvedEntries.put(stateId, entry);
        ModelFactory.ModelDescriptor descriptor = entry.descriptor();
        ModelFactory.FluidDescriptor fluidDescriptor = descriptor.fluid();
        RenderDataFactory.FluidView fluid = null;
        if (fluidDescriptor != null) {
            if (fluidDescriptor.fluidKey() < 0) {
                throw new IllegalStateException("Model " + stateId
                        + " has fluid geometry but no resolved fluidKey descriptor");
            }
            fluid = new RenderDataFactory.FluidView(fluidDescriptor.fluidKey(), fluidDescriptor.pure(),
                    fluidDescriptor.ownHeight(), fluidDescriptor.flowKnown(),
                    fluidDescriptor.flowX(), fluidDescriptor.flowZ(),
                    fluidDescriptor.openFacesMask());
        }
        boolean empty = entry.blockModel().quads().isEmpty() && fluid == null;
        int greedyFaces = 0;
        int effectiveOccludes = descriptor.faceOccludesMask();
        for (Direction direction : Direction.values()) {
            List<BakedBlockModel.Quad> faceQuads = entry.blockModel().quads().stream()
                    .filter(quad -> quad.cullFace() == direction).toList();
            ModelFactory.FaceMetadata face = entry.metadata().face(direction);
            if (faceQuads.stream().anyMatch(quad -> !vertexAlphaOpaque(quad))) {
                effectiveOccludes &= ~(1 << direction.ordinal());
            }
            if ((descriptor.fullyOpaque() || descriptor.cullsSame())
                    && faceQuads.size() == 1 && vertexAlphaUniform(faceQuads.get(0))
                    && face != null && face.coversFullFace()) {
                greedyFaces |= 1 << direction.ordinal();
            }
        }
        Map<Direction, RenderDataFactory.HostSurface> hostSurfaces = new java.util.EnumMap<>(Direction.class);
        descriptor.faceSurfaces().forEach((direction, surface) -> {
            boolean vertexOpaque = entry.blockModel().quads().stream()
                    .filter(quad -> quad.cullFace() == direction
                            && quad.materialType() == BakedBlockModel.MaterialType.SOLID)
                    .allMatch(ModelFactoryRenderAdapter::vertexAlphaOpaque);
            hostSurfaces.put(direction, vertexOpaque
                    ? new RenderDataFactory.HostSurface(surface.mask0(), surface.mask1(), surface.mask2(),
                    surface.mask3(), surface.minDepth(), surface.maxDepth())
                    : new RenderDataFactory.HostSurface(0, 0, 0, 0, Float.NaN, Float.NaN));
        });
        boolean fullyOpaque = descriptor.fullyOpaque() && effectiveOccludes == RenderDataFactory.ALL_FACES;
        return new RenderDataFactory.ModelView(stateId, empty, fullyOpaque,
                descriptor.cullsSame(), descriptor.faceExistsMask(), descriptor.faceCanBeOccludedMask(),
                effectiveOccludes, greedyFaces, descriptor.solidFacesMask(),
                descriptor.cutoutFacesMask(), descriptor.translucentFacesMask(), hostSurfaces, fluid);
    }

    public com.y271727uy.voxy.client.core.rendering.building.result.MeshBuildResult<BuiltMesh>
    build(int side, RenderDataFactory.StateSource states) {
        RenderDataFactory.RenderData data = RenderDataFactory.build(side, states, this);
        List<MeshQuad> opaque = new ArrayList<>();
        List<MeshQuad> nonOpaque = new ArrayList<>();
        List<MeshQuad> fluid = new ArrayList<>();
        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    int stateId = states.cell(x, y, z).stateId();
                    if (stateId == RenderDataFactory.EMPTY_STATE) {
                        continue;
                    }
                    ModelFactory.ModelEntry entry = this.resolvedEntries.get(stateId);
                    if (entry == null) {
                        continue;
                    }
                    for (BakedBlockModel.Quad quad : entry.blockModel().quads()) {
                        if (quad.cullFace() == null) {
                            RenderDataFactory.Layer layer = quad.materialPass()
                                    == BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT
                                    ? RenderDataFactory.Layer.OPAQUE : RenderDataFactory.Layer.NON_OPAQUE;
                            MeshQuad meshQuad = new MeshQuad(stateId, layer, null, 1, 1,
                                    translatedVertices(quad, x, y, z), quad);
                            (layer == RenderDataFactory.Layer.OPAQUE ? opaque : nonOpaque).add(meshQuad);
                        }
                    }
                }
            }
        }
        appendGreedy(data.greedyOpaqueFaces(), opaque);
        appendGreedy(data.greedyNonOpaqueFaces(), nonOpaque);
        for (RenderDataFactory.FluidFace face : data.fluidFaces()) {
            ModelFactory.ModelEntry entry = this.resolvedEntries.get(stateAt(states, face.x(), face.y(), face.z()));
            if (entry == null) continue;
            BakedBlockModel.Quad source = entry.fluidModel().quads().stream()
                    .filter(quad -> quad.cullFace() == face.direction()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No fluid quad for " + face.direction()
                    + " in state " + entry.combinedModel().blockId()));
            BakedBlockModel.Quad uvSource = face.direction() == Direction.UP
                    && (Math.abs(face.flowX()) > 0.0001F || Math.abs(face.flowZ()) > 0.0001F)
                    ? entry.fluidModel().quads().stream()
                    .filter(quad -> quad.cullFace() != null && quad.cullFace().getAxis().isHorizontal())
                    .findFirst().orElse(source) : source;
            fluid.add(new MeshQuad(entry.combinedModel().blockId(), RenderDataFactory.Layer.FLUID,
                    face.direction(), 1, 1, fluidVertices(source, uvSource, face), source));
        }
        List<MeshQuad> quads = new ArrayList<>(opaque.size() + nonOpaque.size() + fluid.size());
        quads.addAll(opaque);
        quads.addAll(nonOpaque);
        quads.addAll(fluid);
        int[] bucketOffsets = {0, opaque.size(), opaque.size() + nonOpaque.size(), quads.size()};
        return this.guard.finish(new BuiltMesh(data, quads, bucketOffsets));
    }

    private void appendGreedy(List<RenderDataFactory.GreedyBlockFace> faces, List<MeshQuad> output) {
        for (RenderDataFactory.GreedyBlockFace face : faces) {
            ModelFactory.ModelEntry entry = this.resolvedEntries.get(face.stateId());
            if (entry == null) continue;
            for (BakedBlockModel.Quad source : entry.blockModel().quads()) {
                if (source.cullFace() == face.direction() && material(source) == face.material()) {
                    output.add(new MeshQuad(face.stateId(), face.layer(), face.direction(),
                            face.length(), face.width(), blockVertices(source, face), source));
                }
            }
        }
    }

    private static int stateAt(RenderDataFactory.StateSource states, int x, int y, int z) {
        int state = states.cell(x, y, z).stateId();
        if (state == RenderDataFactory.EMPTY_STATE) throw new IllegalStateException("Missing interior state");
        return state;
    }

    public static ModelSnapshot captureSnapshot(ModelFactory factory, long epoch, LongSupplier currentEpoch,
                                                int side, RenderDataFactory.StateSource states) {
        Set<Integer> ids = new HashSet<>();
        for (int y = -1; y <= side; y++) {
            for (int z = -1; z <= side; z++) {
                for (int x = -1; x <= side; x++) {
                    boolean interior = 0 <= x && x < side && 0 <= y && y < side && 0 <= z && z < side;
                    if (interior || x == -1 || x == side || y == -1 || y == side || z == -1 || z == side) {
                        addState(ids, states, x, y, z);
                    }
                }
            }
        }
        return ModelSnapshot.capture(factory, epoch, currentEpoch,
                ids.stream().mapToInt(Integer::intValue).toArray());
    }

    private static void addState(Set<Integer> ids, RenderDataFactory.StateSource states,
                                 int x, int y, int z) {
        int state = states.cell(x, y, z).stateId();
        if (state != RenderDataFactory.EMPTY_STATE) ids.add(state);
    }

    private static RenderDataFactory.MaterialType material(BakedBlockModel.Quad quad) {
        if (quad.materialPass() == BakedBlockModel.MaterialPass.TRANSLUCENT) {
            return RenderDataFactory.MaterialType.TRANSLUCENT;
        }
        return quad.renderType().toLowerCase(java.util.Locale.ROOT).contains("cutout")
                ? RenderDataFactory.MaterialType.CUTOUT : RenderDataFactory.MaterialType.SOLID;
    }

    private static int[] translatedVertices(BakedBlockModel.Quad source, int x, int y, int z) {
        int[] vertices = source.vertices();
        int stride = vertices.length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            vertices[base] = Float.floatToRawIntBits(Float.intBitsToFloat(vertices[base]) + x);
            vertices[base + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(vertices[base + 1]) + y);
            vertices[base + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(vertices[base + 2]) + z);
        }
        return vertices;
    }

    private static int[] blockVertices(BakedBlockModel.Quad source, RenderDataFactory.GreedyBlockFace face) {
        int[] vertices = source.vertices();
        int stride = vertices.length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            float x = Float.intBitsToFloat(vertices[base]);
            float y = Float.intBitsToFloat(vertices[base + 1]);
            float z = Float.intBitsToFloat(vertices[base + 2]);
            switch (face.direction().getAxis()) {
                case X -> {
                    x = face.plane();
                    y = face.v() + y * face.width();
                    z = face.u() + z * face.length();
                }
                case Y -> {
                    x = face.u() + x * face.length();
                    y = face.plane();
                    z = face.v() + z * face.width();
                }
                case Z -> {
                    x = face.u() + x * face.length();
                    y = face.v() + y * face.width();
                    z = face.plane();
                }
            }
            vertices[base] = Float.floatToRawIntBits(x);
            vertices[base + 1] = Float.floatToRawIntBits(y);
            vertices[base + 2] = Float.floatToRawIntBits(z);
        }
        return vertices;
    }

    private static int[] fluidVertices(BakedBlockModel.Quad source, BakedBlockModel.Quad uvSource,
                                       RenderDataFactory.FluidFace face) {
        int[] vertices = source.vertices();
        int stride = vertices.length / 4;
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        int[] uvVertices = uvSource.vertices();
        int uvStride = uvVertices.length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * uvStride;
            float u = Float.intBitsToFloat(uvVertices[base + 4]);
            float v = Float.intBitsToFloat(uvVertices[base + 5]);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        float flowLength = (float) Math.sqrt(face.flowX() * face.flowX() + face.flowZ() * face.flowZ());
        float cosine = flowLength > 0.0001F ? face.flowX() / flowLength : 1.0F;
        float sine = flowLength > 0.0001F ? face.flowZ() / flowLength : 0.0F;
        float safeScale = 1.0F / (Math.abs(cosine) + Math.abs(sine));
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            float sourceX = Float.intBitsToFloat(vertices[base]);
            float localY = Float.intBitsToFloat(vertices[base + 1]);
            float sourceZ = Float.intBitsToFloat(vertices[base + 2]);
            float sourceHeight = Math.max(1.0F / 9.0F, source.fluidHeight());
            float normalizedY = Math.min(1.0F, localY / sourceHeight);
            float localX;
            float localZ;
            if (face.direction().getAxis() == Direction.Axis.Y) {
                localX = lerp(face.cropMinU(), face.cropMaxU(), sourceX);
                localZ = lerp(face.cropMinV(), face.cropMaxV(), sourceZ);
            } else if (face.direction().getAxis() == Direction.Axis.Z) {
                localX = lerp(face.cropMinU(), face.cropMaxU(), sourceX);
                localZ = sourceZ;
                localY = lerp(face.cropMinV(), face.cropMaxV(), normalizedY);
            } else {
                localX = sourceX;
                localZ = lerp(face.cropMinU(), face.cropMaxU(), sourceZ);
                localY = lerp(face.cropMinV(), face.cropMaxV(), normalizedY);
            }
            float x = face.x() + localX;
            float y;
            float z = face.z() + localZ;
            float edgeHeight = 1.0F;
            if (face.direction() == Direction.UP) {
                y = face.y() + bilinear(face, localX, localZ);
            } else if (face.direction() == Direction.DOWN) {
                y = face.y();
            } else {
                float edge = switch (face.direction()) {
                    case NORTH -> lerp(face.northWest(), face.northEast(), localX);
                    case SOUTH -> lerp(face.southWest(), face.southEast(), localX);
                    case WEST -> lerp(face.northWest(), face.southWest(), localZ);
                    case EAST -> lerp(face.northEast(), face.southEast(), localZ);
                    default -> throw new IllegalStateException();
                };
                edgeHeight = edge;
                float adjacent = switch (face.direction()) {
                    case NORTH, WEST -> lerp(face.adjacentRight(), face.adjacentLeft(),
                            face.direction() == Direction.NORTH ? localX : localZ);
                    case SOUTH, EAST -> lerp(face.adjacentLeft(), face.adjacentRight(),
                            face.direction() == Direction.SOUTH ? localX : localZ);
                    default -> 0.0F;
                };
                y = face.y() + Math.max(adjacent, Math.min(edge, localY));
            }
            vertices[base] = Float.floatToRawIntBits(x);
            vertices[base + 1] = Float.floatToRawIntBits(y);
            vertices[base + 2] = Float.floatToRawIntBits(z);
            if (face.direction() == Direction.UP && flowLength > 0.0001F) {
                float centeredX = (localX - 0.5F) * safeScale;
                float centeredZ = (localZ - 0.5F) * safeScale;
                float rotatedU = 0.5F + centeredX * cosine - centeredZ * sine;
                float rotatedV = 0.5F + centeredX * sine + centeredZ * cosine;
                vertices[base + 4] = Float.floatToRawIntBits(lerp(minU, maxU, rotatedU));
                vertices[base + 5] = Float.floatToRawIntBits(lerp(minV, maxV, rotatedV));
            } else if (face.direction().getAxis() == Direction.Axis.Y) {
                vertices[base + 4] = Float.floatToRawIntBits(lerp(minU, maxU, localX));
                vertices[base + 5] = Float.floatToRawIntBits(lerp(minV, maxV, localZ));
            } else {
                float horizontal = face.direction().getAxis() == Direction.Axis.Z ? localX : localZ;
                float vertical = 1.0F - (y - face.y()) / Math.max(0.0001F, edgeHeight);
                vertices[base + 4] = Float.floatToRawIntBits(lerp(minU, maxU, horizontal));
                vertices[base + 5] = Float.floatToRawIntBits(lerp(minV, maxV,
                        Math.max(0.0F, Math.min(1.0F, vertical))));
            }
        }
        return vertices;
    }

    private static boolean vertexAlphaOpaque(BakedBlockModel.Quad quad) {
        int[] vertices = quad.vertices();
        int stride = vertices.length / 4;
        if (stride < 4) return false;
        for (int vertex = 0; vertex < 4; vertex++) {
            if (((vertices[vertex * stride + 3] >>> 24) & 0xFF) != 0xFF) return false;
        }
        return true;
    }

    private static boolean vertexAlphaUniform(BakedBlockModel.Quad quad) {
        int[] vertices = quad.vertices();
        int stride = vertices.length / 4;
        if (stride < 4) return false;
        int alpha = (vertices[3] >>> 24) & 0xFF;
        for (int vertex = 1; vertex < 4; vertex++) {
            if (((vertices[vertex * stride + 3] >>> 24) & 0xFF) != alpha) return false;
        }
        return true;
    }

    private static float bilinear(RenderDataFactory.FluidFace face, float x, float z) {
        float north = lerp(face.northWest(), face.northEast(), x);
        float south = lerp(face.southWest(), face.southEast(), x);
        return lerp(north, south, z);
    }

    private static float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }

    public record BuiltMesh(RenderDataFactory.RenderData renderData,
                            List<MeshQuad> quads, int[] bucketOffsets) {
        public BuiltMesh {
            quads = List.copyOf(quads);
            bucketOffsets = bucketOffsets.clone();
        }

        @Override public int[] bucketOffsets() { return bucketOffsets.clone(); }
    }

    public record MeshQuad(int stateId, RenderDataFactory.Layer layer, Direction direction,
                           int length, int width, int[] vertices, BakedBlockModel.Quad source) {
        public MeshQuad {
            vertices = vertices.clone();
        }

        @Override public int[] vertices() { return vertices.clone(); }
    }
}
