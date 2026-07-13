package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.client.model.BakedBlockModel;

import java.util.List;

public record LodSectionMesh(long sectionKey, long modelGeneration, List<QuadInstance> quads) {
    public LodSectionMesh {
        quads = List.copyOf(quads);
    }

    public record QuadInstance(int blockId,
                               long voxel,
                               int worldX,
                               int worldY,
                               int worldZ,
                               int scale,
                               int packedLight,
                               int tintColor,
                               int packedAmbientOcclusion,
                               BakedBlockModel.Quad quad) {
        public int ambientOcclusion(int vertex) {
            return (this.packedAmbientOcclusion >>> (vertex * Byte.SIZE)) & 0xFF;
        }
    }
}
