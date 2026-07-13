package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.client.model.BakedBlockModel;
import com.y271727uy.voxy.client.model.VoxyModelCatalog;
import com.y271727uy.voxy.common.voxelization.PackedVoxel;
import com.y271727uy.voxy.common.world.WorldSection;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Function;

public final class LodSectionMeshBuilder {
    private LodSectionMeshBuilder() {
    }

    public static LodSectionMesh build(WorldSection section, VoxyModelCatalog catalog) {
        return build(section, catalog.generation(), catalog::get, catalog::tintColor,
                catalog::isFullyOpaque, ignored -> null);
    }

    public static LodSectionMesh build(WorldSection section, VoxyModelCatalog catalog,
                                       Function<Direction, WorldSection> neighborLookup) {
        return build(section, catalog.generation(), catalog::get, catalog::tintColor,
                catalog::isFullyOpaque, neighborLookup);
    }

    static LodSectionMesh build(WorldSection section, long modelGeneration,
                                IntFunction<BakedBlockModel> modelLookup) {
        return build(section, modelGeneration, modelLookup,
                (blockId, biomeId, quad) -> 0xFFFFFFFF,
                blockId -> modelLookup.apply(blockId) != null, ignored -> null);
    }

    static LodSectionMesh build(WorldSection section, long modelGeneration,
                                IntFunction<BakedBlockModel> modelLookup,
                                Function<Direction, WorldSection> neighborLookup) {
        return build(section, modelGeneration, modelLookup,
                (blockId, biomeId, quad) -> 0xFFFFFFFF,
                blockId -> modelLookup.apply(blockId) != null, neighborLookup);
    }

    static LodSectionMesh build(WorldSection section, long modelGeneration,
                                IntFunction<BakedBlockModel> modelLookup, TintResolver tintResolver,
                                Function<Direction, WorldSection> neighborLookup) {
        return build(section, modelGeneration, modelLookup, tintResolver,
                blockId -> modelLookup.apply(blockId) != null, neighborLookup);
    }

    static LodSectionMesh build(WorldSection section, long modelGeneration,
                                IntFunction<BakedBlockModel> modelLookup, TintResolver tintResolver,
                                IntPredicate fullyOpaque, Function<Direction, WorldSection> neighborLookup) {
        int scale = 1 << section.level();
        int sectionSpan = WorldSection.SIDE_LENGTH * scale;
        int sectionOriginX = section.x() * sectionSpan;
        int sectionOriginY = section.y() * sectionSpan;
        int sectionOriginZ = section.z() * sectionSpan;
        List<LodSectionMesh.QuadInstance> output = new ArrayList<>();
        for (int y = 0; y < WorldSection.SIDE_LENGTH; y++) {
            for (int z = 0; z < WorldSection.SIDE_LENGTH; z++) {
                for (int x = 0; x < WorldSection.SIDE_LENGTH; x++) {
                    long voxel = section.get(x, y, z);
                    if (PackedVoxel.isAir(voxel)) {
                        continue;
                    }
                    int blockId = PackedVoxel.blockId(voxel);
                    BakedBlockModel model = modelLookup.apply(blockId);
                    if (model == null || model.customRenderer()) {
                        continue;
                    }
                    int worldX = sectionOriginX + x * scale;
                    int worldY = sectionOriginY + y * scale;
                    int worldZ = sectionOriginZ + z * scale;
                    for (BakedBlockModel.Quad quad : model.quads()) {
                        if (quad.cullFace() == null || faceExposed(section, x, y, z, blockId, quad,
                                modelLookup, fullyOpaque, neighborLookup)) {
                            long lightingVoxel = quad.useSelfLight() ? voxel
                                    : adjacentVoxel(section, x, y, z, quad.cullFace(), neighborLookup, voxel);
                            int packedLight = resolveLight(lightingVoxel, model.emission());
                            int tintColor = tintResolver.color(blockId, PackedVoxel.biomeId(voxel), quad);
                            int ambientOcclusion = resolveAmbientOcclusion(section, x, y, z, model, quad,
                                    fullyOpaque, neighborLookup);
                            output.add(new LodSectionMesh.QuadInstance(blockId, voxel, worldX, worldY, worldZ,
                                    scale, packedLight, tintColor, ambientOcclusion, quad));
                        }
                    }
                }
            }
        }
        return new LodSectionMesh(section.key(), modelGeneration, output);
    }

    static int resolveLight(long lightingVoxel, int emission) {
        int light = PackedVoxel.light(lightingVoxel);
        int blockLight = Math.max((light >>> 4) & 0xF, emission);
        return (blockLight << 4) | (light & 0xF);
    }

    static int resolveAmbientOcclusion(WorldSection section, int x, int y, int z,
                                       BakedBlockModel model, BakedBlockModel.Quad quad,
                                       IntPredicate fullyOpaque,
                                       Function<Direction, WorldSection> neighborLookup) {
        if (!model.ambientOcclusion() || !quad.ambientOcclusion()
                || quad.materialPass() != BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT
                || quad.cullFace() == null) {
            return -1;
        }
        int[] vertices = quad.vertices();
        int stride = vertices.length / 4;
        if (stride < 3) return -1;
        Direction face = quad.cullFace();
        Direction.Axis[] tangents = tangentAxes(face.getAxis());
        int packed = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            int firstSign = vertexSign(vertices, base, tangents[0]);
            int secondSign = vertexSign(vertices, base, tangents[1]);
            int sampleX = x + face.getStepX();
            int sampleY = y + face.getStepY();
            int sampleZ = z + face.getStepZ();
            boolean first = occupied(section,
                    offset(sampleX, tangents[0], firstSign, Direction.Axis.X),
                    offset(sampleY, tangents[0], firstSign, Direction.Axis.Y),
                    offset(sampleZ, tangents[0], firstSign, Direction.Axis.Z),
                    fullyOpaque, neighborLookup);
            boolean second = occupied(section,
                    offset(sampleX, tangents[1], secondSign, Direction.Axis.X),
                    offset(sampleY, tangents[1], secondSign, Direction.Axis.Y),
                    offset(sampleZ, tangents[1], secondSign, Direction.Axis.Z),
                    fullyOpaque, neighborLookup);
            boolean corner = first && second || occupied(section,
                    offset(offset(sampleX, tangents[0], firstSign, Direction.Axis.X),
                            tangents[1], secondSign, Direction.Axis.X),
                    offset(offset(sampleY, tangents[0], firstSign, Direction.Axis.Y),
                            tangents[1], secondSign, Direction.Axis.Y),
                    offset(offset(sampleZ, tangents[0], firstSign, Direction.Axis.Z),
                            tangents[1], secondSign, Direction.Axis.Z),
                    fullyOpaque, neighborLookup);
            int occluders = (first ? 1 : 0) + (second ? 1 : 0) + (corner ? 1 : 0);
            int ao = switch (occluders) {
                case 1 -> 204;
                case 2 -> 153;
                case 3 -> 128;
                default -> 255;
            };
            packed |= ao << (vertex * Byte.SIZE);
        }
        return packed;
    }

    private static Direction.Axis[] tangentAxes(Direction.Axis normal) {
        return switch (normal) {
            case X -> new Direction.Axis[]{Direction.Axis.Y, Direction.Axis.Z};
            case Y -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z};
            case Z -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Y};
        };
    }

    private static int vertexSign(int[] vertices, int base, Direction.Axis axis) {
        int component = switch (axis) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
        return Float.intBitsToFloat(vertices[base + component]) < 0.5F ? -1 : 1;
    }

    private static int offset(int coordinate, Direction.Axis tangent, int sign, Direction.Axis axis) {
        return tangent == axis ? coordinate + sign : coordinate;
    }

    private static boolean occupied(WorldSection section, int x, int y, int z,
                                    IntPredicate fullyOpaque,
                                    Function<Direction, WorldSection> neighborLookup) {
        if (x >= 0 && y >= 0 && z >= 0 && x < WorldSection.SIDE_LENGTH
                && y < WorldSection.SIDE_LENGTH && z < WorldSection.SIDE_LENGTH) {
            long voxel = section.get(x, y, z);
            return !PackedVoxel.isAir(voxel) && fullyOpaque.test(PackedVoxel.blockId(voxel));
        }
        Direction boundary = null;
        if (x < 0 || x >= WorldSection.SIDE_LENGTH) boundary = x < 0 ? Direction.WEST : Direction.EAST;
        if (y < 0 || y >= WorldSection.SIDE_LENGTH) {
            if (boundary != null) return false;
            boundary = y < 0 ? Direction.DOWN : Direction.UP;
        }
        if (z < 0 || z >= WorldSection.SIDE_LENGTH) {
            if (boundary != null) return false;
            boundary = z < 0 ? Direction.NORTH : Direction.SOUTH;
        }
        WorldSection neighbor = boundary == null ? section : neighborLookup.apply(boundary);
        if (neighbor == null) return false;
        long voxel = neighbor.get(Math.floorMod(x, WorldSection.SIDE_LENGTH),
                Math.floorMod(y, WorldSection.SIDE_LENGTH), Math.floorMod(z, WorldSection.SIDE_LENGTH));
        return !PackedVoxel.isAir(voxel) && fullyOpaque.test(PackedVoxel.blockId(voxel));
    }

    private static long adjacentVoxel(WorldSection section, int x, int y, int z, Direction face,
                                      Function<Direction, WorldSection> neighborLookup, long fallback) {
        if (face == null) return fallback;
        int adjacentX = x + face.getStepX();
        int adjacentY = y + face.getStepY();
        int adjacentZ = z + face.getStepZ();
        if (adjacentX >= 0 && adjacentY >= 0 && adjacentZ >= 0
                && adjacentX < WorldSection.SIDE_LENGTH
                && adjacentY < WorldSection.SIDE_LENGTH
                && adjacentZ < WorldSection.SIDE_LENGTH) {
            return section.get(adjacentX, adjacentY, adjacentZ);
        }
        WorldSection neighbor = neighborLookup.apply(face);
        return neighbor == null ? fallback : neighbor.get(
                Math.floorMod(adjacentX, WorldSection.SIDE_LENGTH),
                Math.floorMod(adjacentY, WorldSection.SIDE_LENGTH),
                Math.floorMod(adjacentZ, WorldSection.SIDE_LENGTH));
    }

    @FunctionalInterface
    interface TintResolver {
        int color(int blockId, int biomeId, BakedBlockModel.Quad quad);
    }

    private static boolean faceExposed(WorldSection section, int x, int y, int z, int blockId,
                                       BakedBlockModel.Quad quad,
                                       IntFunction<BakedBlockModel> modelLookup,
                                       IntPredicate fullyOpaque,
                                       Function<Direction, WorldSection> neighborLookup) {
        Direction face = quad.cullFace();
        int adjacentX = x + face.getStepX();
        int adjacentY = y + face.getStepY();
        int adjacentZ = z + face.getStepZ();
        if (adjacentX < 0 || adjacentY < 0 || adjacentZ < 0
                || adjacentX >= WorldSection.SIDE_LENGTH
                || adjacentY >= WorldSection.SIDE_LENGTH
                || adjacentZ >= WorldSection.SIDE_LENGTH) {
            WorldSection neighbor = neighborLookup.apply(face);
            if (neighbor == null) {
                return true;
            }
            int neighborX = Math.floorMod(adjacentX, WorldSection.SIDE_LENGTH);
            int neighborY = Math.floorMod(adjacentY, WorldSection.SIDE_LENGTH);
            int neighborZ = Math.floorMod(adjacentZ, WorldSection.SIDE_LENGTH);
            return faceExposedTo(neighbor.get(neighborX, neighborY, neighborZ), blockId, quad,
                    modelLookup, fullyOpaque);
        }
        return faceExposedTo(section.get(adjacentX, adjacentY, adjacentZ), blockId, quad,
                modelLookup, fullyOpaque);
    }

    private static boolean faceExposedTo(long neighborVoxel, int blockId,
                                         BakedBlockModel.Quad quad,
                                         IntFunction<BakedBlockModel> modelLookup,
                                         IntPredicate fullyOpaque) {
        if (PackedVoxel.isAir(neighborVoxel)) return true;
        int neighborBlockId = PackedVoxel.blockId(neighborVoxel);
        if (fullyOpaque.test(neighborBlockId)) return false;
        BakedBlockModel neighborModel = modelLookup.apply(neighborBlockId);
        if (neighborModel == null) return true;
        if (quad.fluidOverlay()) {
            return neighborModel.quads().stream().noneMatch(neighborQuad -> neighborQuad.fluidOverlay()
                    && quad.fluidKey().equals(neighborQuad.fluidKey())
                    && Math.abs(quad.fluidHeight() - neighborQuad.fluidHeight()) < 0.0001F);
        }
        if (quad.materialPass() == BakedBlockModel.MaterialPass.TRANSLUCENT) {
            return neighborBlockId != blockId;
        }
        return true;
    }
}
