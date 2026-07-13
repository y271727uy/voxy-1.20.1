package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.client.model.BakedBlockModel;
import com.y271727uy.voxy.common.voxelization.PackedVoxel;
import com.y271727uy.voxy.common.world.WorldSection;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class LodSectionMeshBuilderTest {
    @Test
    public void adjacentVoxelsCullTheirSharedFaces() {
        List<BakedBlockModel.Quad> quads = new ArrayList<>();
        quads.add(quad(null));
        for (Direction direction : Direction.values()) {
            quads.add(quad(direction));
        }
        BakedBlockModel model = new BakedBlockModel(1, true, false, quads);
        WorldSection section = new WorldSection(0, 0, 0, 0);
        long voxel = PackedVoxel.compose(0, 1, 0);
        section.set(1, 1, 1, voxel);
        assertEquals(7, LodSectionMeshBuilder.build(section, 3, id -> model).quads().size());

        section.set(2, 1, 1, voxel);
        LodSectionMesh mesh = LodSectionMeshBuilder.build(section, 3, id -> model);
        assertEquals(12, mesh.quads().size());
        assertEquals(3, mesh.modelGeneration());
    }

    @Test
    public void adjacentSectionsCullBoundaryFaces() {
        List<BakedBlockModel.Quad> quads = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            quads.add(quad(direction));
        }
        BakedBlockModel model = new BakedBlockModel(1, true, false, quads);
        long voxel = PackedVoxel.compose(0, 1, 0);
        WorldSection west = new WorldSection(0, 0, 0, 0);
        WorldSection east = new WorldSection(0, 1, 0, 0);
        west.set(31, 2, 2, voxel);
        east.set(0, 2, 2, voxel);

        LodSectionMesh mesh = LodSectionMeshBuilder.build(west, 1, id -> model,
                direction -> direction == Direction.EAST ? east : null);
        assertEquals(5, mesh.quads().size());
    }

    @Test
    public void materialPassesAreExplicitlyClassified() {
        assertEquals(BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT, BakedBlockModel.MaterialPass.classify("solid"));
        assertEquals(BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT, BakedBlockModel.MaterialPass.classify("cutout_mipped"));
        assertEquals(BakedBlockModel.MaterialPass.TRANSLUCENT, BakedBlockModel.MaterialPass.classify("translucent"));
        assertEquals(BakedBlockModel.MaterialPass.TRIPWIRE, BakedBlockModel.MaterialPass.classify("tripwire"));
        assertEquals(BakedBlockModel.MaterialPass.UNSUPPORTED, BakedBlockModel.MaterialPass.classify("custom_layer"));
    }

    @Test
    public void emissionRaisesBlockLightWithoutChangingSkyLight() {
        BakedBlockModel.Quad quad = materialQuad(null, -1, true);
        BakedBlockModel model = new BakedBlockModel(1, true, false, 10, List.of(quad));
        WorldSection section = new WorldSection(0, 0, 0, 0);
        section.set(1, 1, 1, PackedVoxel.compose(0x23, 1, 0));

        LodSectionMesh mesh = LodSectionMeshBuilder.build(section, 1, id -> model);

        assertEquals(0xA3, mesh.quads().get(0).packedLight());
    }

    @Test
    public void boundaryFaceSamplesAdjacentSectionLight() {
        BakedBlockModel.Quad quad = materialQuad(Direction.EAST, -1, false);
        BakedBlockModel model = new BakedBlockModel(1, true, false, 2, List.of(quad));
        WorldSection west = new WorldSection(0, 0, 0, 0);
        WorldSection east = new WorldSection(0, 1, 0, 0);
        west.set(31, 2, 2, PackedVoxel.compose(0x13, 1, 0));
        east.set(0, 2, 2, PackedVoxel.airWithLight(0x4F));

        LodSectionMesh mesh = LodSectionMeshBuilder.build(west, 1, id -> model,
                direction -> direction == Direction.EAST ? east : null);

        assertEquals(0x4F, mesh.quads().get(0).packedLight());
    }

    @Test
    public void tintedQuadRetainsBiomeResolvedColor() {
        BakedBlockModel.Quad quad = materialQuad(null, 1, true);
        BakedBlockModel model = new BakedBlockModel(1, true, false, 0, List.of(quad));
        WorldSection section = new WorldSection(0, 0, 0, 0);
        section.set(1, 1, 1, PackedVoxel.compose(0x0F, 1, 7));

        LodSectionMesh mesh = LodSectionMeshBuilder.build(section, 1, id -> model,
                (blockId, biomeId, resolvedQuad) -> {
                    assertEquals(1, blockId);
                    assertEquals(7, biomeId);
                    assertEquals(1, resolvedQuad.tintIndex());
                    return 0xFF123456;
                }, ignored -> null);

        assertEquals(0xFF123456, mesh.quads().get(0).tintColor());
    }

    @Test
    public void opaqueCornerNeighborsProducePerVertexAmbientOcclusion() {
        BakedBlockModel.Quad quad = eastFaceQuad();
        BakedBlockModel model = new BakedBlockModel(1, true, false, List.of(quad));
        WorldSection section = new WorldSection(0, 0, 0, 0);
        long opaque = PackedVoxel.compose(0, 1, 0);
        section.set(2, 0, 1, opaque);
        section.set(2, 1, 0, opaque);
        section.set(2, 0, 0, opaque);

        int packed = LodSectionMeshBuilder.resolveAmbientOcclusion(section, 1, 1, 1, model, quad,
                blockId -> blockId == 1, ignored -> null);

        assertEquals(128, packed & 0xFF);
        assertEquals(204, (packed >>> 8) & 0xFF);
        assertEquals(255, (packed >>> 16) & 0xFF);
        assertEquals(204, (packed >>> 24) & 0xFF);
    }

    @Test
    public void nonOpaqueNeighborsDoNotContributeAmbientOcclusion() {
        BakedBlockModel.Quad quad = eastFaceQuad();
        BakedBlockModel model = new BakedBlockModel(1, true, false, List.of(quad));
        WorldSection section = new WorldSection(0, 0, 0, 0);
        long translucent = PackedVoxel.compose(0, 2, 0);
        section.set(2, 0, 1, translucent);
        section.set(2, 1, 0, translucent);
        section.set(2, 0, 0, translucent);

        int packed = LodSectionMeshBuilder.resolveAmbientOcclusion(section, 1, 1, 1, model, quad,
                blockId -> blockId == 1, ignored -> null);

        assertEquals(-1, packed);
    }

    @Test
    public void sameTranslucentVoxelsCullOnlyTheirSharedFaces() {
        BakedBlockModel model = cubeModel(1, BakedBlockModel.MaterialPass.TRANSLUCENT);
        WorldSection section = new WorldSection(0, 0, 0, 0);
        long voxel = PackedVoxel.compose(0, 1, 0);
        section.set(1, 1, 1, voxel);
        section.set(2, 1, 1, voxel);

        LodSectionMesh mesh = LodSectionMeshBuilder.build(section, 1, id -> model,
                (blockId, biomeId, quad) -> 0xFFFFFFFF, ignored -> false, ignored -> null);

        assertEquals(10, mesh.quads().size());
        assertEquals(BakedBlockModel.MaterialPass.TRANSLUCENT,
                mesh.quads().get(0).quad().materialPass());
    }

    @Test
    public void opaqueAndTranslucentVoxelsKeepTheirMaterialBoundary() {
        BakedBlockModel opaque = cubeModel(1, BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT);
        BakedBlockModel translucent = cubeModel(2, BakedBlockModel.MaterialPass.TRANSLUCENT);
        WorldSection section = new WorldSection(0, 0, 0, 0);
        section.set(1, 1, 1, PackedVoxel.compose(0, 1, 0));
        section.set(2, 1, 1, PackedVoxel.compose(0, 2, 0));

        LodSectionMesh mesh = LodSectionMeshBuilder.build(section, 1,
                id -> id == 1 ? opaque : translucent,
                (blockId, biomeId, quad) -> 0xFFFFFFFF, ignored -> false, ignored -> null);

        assertEquals(12, mesh.quads().size());
    }

    @Test
    public void sameFluidAcrossDifferentStateIdsCullsSharedFaces() {
        ResourceLocation water = new ResourceLocation("minecraft", "water");
        BakedBlockModel source = fluidModel(1, water, false);
        BakedBlockModel flowing = fluidModel(2, water, false);
        WorldSection section = adjacentSection(1, 2);

        LodSectionMesh mesh = buildWithModels(section, source, flowing);

        assertEquals(10, mesh.quads().size());
    }

    @Test
    public void pureFluidAndWaterloggedHostShareOnlyFluidBoundary() {
        ResourceLocation water = new ResourceLocation("minecraft", "water");
        BakedBlockModel pure = fluidModel(1, water, false);
        BakedBlockModel waterlogged = fluidModel(2, water, true);
        WorldSection section = adjacentSection(1, 2);

        LodSectionMesh mesh = buildWithModels(section, pure, waterlogged);

        assertEquals(11, mesh.quads().size());
        assertEquals(1, mesh.quads().stream().filter(instance -> !instance.quad().fluidOverlay()).count());
    }

    @Test
    public void differentWaterloggedHostsDoNotCullHostQuadsAsFluid() {
        ResourceLocation water = new ResourceLocation("minecraft", "water");
        BakedBlockModel first = fluidModel(1, water, true);
        BakedBlockModel second = fluidModel(2, water, true);
        WorldSection section = adjacentSection(1, 2);

        LodSectionMesh mesh = buildWithModels(section, first, second);

        assertEquals(12, mesh.quads().size());
        assertEquals(2, mesh.quads().stream().filter(instance -> !instance.quad().fluidOverlay()).count());
    }

    @Test
    public void differentFluidHeightsKeepBoundaryFacesToAvoidHoles() {
        ResourceLocation water = new ResourceLocation("minecraft", "water");
        BakedBlockModel high = fluidModel(1, water, false, 1.0F);
        BakedBlockModel low = fluidModel(2, water, false, 0.5F);
        WorldSection section = adjacentSection(1, 2);

        LodSectionMesh mesh = buildWithModels(section, high, low);

        assertEquals(12, mesh.quads().size());
    }

    private static WorldSection adjacentSection(int firstId, int secondId) {
        WorldSection section = new WorldSection(0, 0, 0, 0);
        section.set(1, 1, 1, PackedVoxel.compose(0, firstId, 0));
        section.set(2, 1, 1, PackedVoxel.compose(0, secondId, 0));
        return section;
    }

    private static LodSectionMesh buildWithModels(WorldSection section, BakedBlockModel first,
                                                   BakedBlockModel second) {
        return LodSectionMeshBuilder.build(section, 1, id -> id == first.blockId() ? first : second,
                (blockId, biomeId, quad) -> 0xFFFFFFFF, ignored -> false, ignored -> null);
    }

    private static BakedBlockModel fluidModel(int id, ResourceLocation fluidKey, boolean hostQuad) {
        return fluidModel(id, fluidKey, hostQuad, 1.0F);
    }

    private static BakedBlockModel fluidModel(int id, ResourceLocation fluidKey, boolean hostQuad,
                                              float fluidHeight) {
        List<BakedBlockModel.Quad> quads = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            quads.add(new BakedBlockModel.Quad(new int[32], 0, direction, null, "translucent",
                    true, false, BakedBlockModel.MaterialPass.TRANSLUCENT, true, fluidKey, fluidHeight));
        }
        if (hostQuad) {
            quads.add(new BakedBlockModel.Quad(new int[32], -1, Direction.EAST, null, "translucent",
                    true, false, BakedBlockModel.MaterialPass.TRANSLUCENT, true));
        }
        return new BakedBlockModel(id, false, false, quads);
    }

    private static BakedBlockModel cubeModel(int id, BakedBlockModel.MaterialPass pass) {
        List<BakedBlockModel.Quad> quads = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            quads.add(new BakedBlockModel.Quad(new int[32], -1, direction, null,
                    pass == BakedBlockModel.MaterialPass.TRANSLUCENT ? "translucent" : "solid",
                    true, true, pass, false));
        }
        return new BakedBlockModel(id, true, false, quads);
    }

    private static BakedBlockModel.Quad quad(Direction direction) {
        return new BakedBlockModel.Quad(new int[32], -1, direction, null, "solid", true, true);
    }

    private static BakedBlockModel.Quad materialQuad(Direction direction, int tintIndex, boolean useSelfLight) {
        return new BakedBlockModel.Quad(new int[32], tintIndex, direction, null, "solid", true, true,
                BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT, useSelfLight);
    }

    private static BakedBlockModel.Quad eastFaceQuad() {
        int[] vertices = new int[32];
        float[][] positions = {{1, 0, 0}, {1, 0, 1}, {1, 1, 1}, {1, 1, 0}};
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * 8;
            vertices[base] = Float.floatToRawIntBits(positions[vertex][0]);
            vertices[base + 1] = Float.floatToRawIntBits(positions[vertex][1]);
            vertices[base + 2] = Float.floatToRawIntBits(positions[vertex][2]);
        }
        return new BakedBlockModel.Quad(vertices, -1, Direction.EAST, null, "solid", true, true,
                BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT, false);
    }
}
