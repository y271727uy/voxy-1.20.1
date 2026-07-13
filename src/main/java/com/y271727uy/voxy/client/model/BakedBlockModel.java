package com.y271727uy.voxy.client.model;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record BakedBlockModel(int blockId,
                              boolean ambientOcclusion,
                              boolean customRenderer,
                              int emission,
                              List<Quad> quads) {
    public BakedBlockModel {
        quads = List.copyOf(quads);
    }

    public BakedBlockModel(int blockId, boolean ambientOcclusion, boolean customRenderer, List<Quad> quads) {
        this(blockId, ambientOcclusion, customRenderer, 0, quads);
    }

    public enum MaterialPass {
        OPAQUE_OR_CUTOUT, TRANSLUCENT, TRIPWIRE, UNSUPPORTED;

        public static MaterialPass classify(String renderType) {
            String normalized = renderType.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("translucent")) return TRANSLUCENT;
            if (normalized.contains("tripwire")) return TRIPWIRE;
            if (normalized.contains("solid") || normalized.contains("cutout") || normalized.equals("default")) {
                return OPAQUE_OR_CUTOUT;
            }
            return UNSUPPORTED;
        }
    }

    public enum TintSource { NONE, BLOCK, FLUID }

    public enum MaterialType {
        SOLID, CUTOUT, TRANSLUCENT, OTHER;

        public static MaterialType classify(String renderType) {
            String normalized = renderType.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("translucent")) return TRANSLUCENT;
            if (normalized.contains("cutout") || normalized.contains("tripwire")) return CUTOUT;
            if (normalized.contains("solid") || normalized.equals("default")) return SOLID;
            return OTHER;
        }
    }

    public static final class Quad {
        private final int[] vertices;
        private final int tintIndex;
        private final Direction cullFace;
        private final ResourceLocation sprite;
        private final String renderType;
        private final boolean shade;
        private final boolean ambientOcclusion;
        private final MaterialPass materialPass;
        private final boolean useSelfLight;
        private final TintSource tintSource;
        private final boolean fluidOverlay;
        private final ResourceLocation fluidKey;
        private final float fluidHeight;
        private final MaterialType materialType;
        private final float alphaCoverage;
        private final float opaqueCoverage;

        public Quad(int[] vertices, int tintIndex, Direction cullFace, ResourceLocation sprite,
                    String renderType, boolean shade, boolean ambientOcclusion) {
            this(vertices, tintIndex, cullFace, sprite, renderType, shade, ambientOcclusion,
                    MaterialPass.classify(renderType), cullFace == null, null);
        }

        public Quad(int[] vertices, int tintIndex, Direction cullFace, ResourceLocation sprite,
                    String renderType, boolean shade, boolean ambientOcclusion,
                    MaterialPass materialPass, boolean useSelfLight) {
            this(vertices, tintIndex, cullFace, sprite, renderType, shade, ambientOcclusion,
                    materialPass, useSelfLight, null);
        }

        public Quad(int[] vertices, int tintIndex, Direction cullFace, ResourceLocation sprite,
                    String renderType, boolean shade, boolean ambientOcclusion,
                    MaterialPass materialPass, boolean useSelfLight, ResourceLocation fluidKey) {
            this(vertices, tintIndex, cullFace, sprite, renderType, shade, ambientOcclusion,
                    materialPass, useSelfLight, fluidKey, fluidKey == null ? Float.NaN : 1.0F);
        }

        public Quad(int[] vertices, int tintIndex, Direction cullFace, ResourceLocation sprite,
                    String renderType, boolean shade, boolean ambientOcclusion,
                    MaterialPass materialPass, boolean useSelfLight, ResourceLocation fluidKey,
                    float fluidHeight) {
            this(vertices, tintIndex, cullFace, sprite, renderType, shade, ambientOcclusion,
                    materialPass, useSelfLight, fluidKey, fluidHeight, MaterialType.classify(renderType),
                    MaterialType.classify(renderType) == MaterialType.SOLID ? 1.0F : 0.0F,
                    MaterialType.classify(renderType) == MaterialType.SOLID ? 1.0F : 0.0F);
        }

        public Quad(int[] vertices, int tintIndex, Direction cullFace, ResourceLocation sprite,
                    String renderType, boolean shade, boolean ambientOcclusion,
                    MaterialPass materialPass, boolean useSelfLight, ResourceLocation fluidKey,
                    float fluidHeight, MaterialType materialType, float alphaCoverage,
                    float opaqueCoverage) {
            this.vertices = vertices.clone();
            this.tintIndex = tintIndex;
            this.cullFace = cullFace;
            this.sprite = sprite;
            this.renderType = renderType;
            this.shade = shade;
            this.ambientOcclusion = ambientOcclusion;
            this.materialPass = materialPass;
            this.useSelfLight = useSelfLight;
            this.fluidOverlay = fluidKey != null;
            this.fluidKey = fluidKey;
            this.fluidHeight = this.fluidOverlay ? fluidHeight : Float.NaN;
            this.materialType = materialType;
            this.alphaCoverage = clampCoverage(alphaCoverage);
            this.opaqueCoverage = clampCoverage(opaqueCoverage);
            this.tintSource = this.fluidOverlay ? TintSource.FLUID
                    : tintIndex >= 0 ? TintSource.BLOCK : TintSource.NONE;
        }

        public int[] vertices() { return this.vertices.clone(); }
        public int tintIndex() { return this.tintIndex; }
        public Direction cullFace() { return this.cullFace; }
        public ResourceLocation sprite() { return this.sprite; }
        public String renderType() { return this.renderType; }
        public boolean shade() { return this.shade; }
        public boolean ambientOcclusion() { return this.ambientOcclusion; }
        public MaterialPass materialPass() { return this.materialPass; }
        public boolean useSelfLight() { return this.useSelfLight; }
        public TintSource tintSource() { return this.tintSource; }
        public boolean fluidOverlay() { return this.fluidOverlay; }
        public ResourceLocation fluidKey() { return this.fluidKey; }
        public float fluidHeight() { return this.fluidHeight; }
        public MaterialType materialType() { return this.materialType; }
        public float alphaCoverage() { return this.alphaCoverage; }
        public float opaqueCoverage() { return this.opaqueCoverage; }

        private static float clampCoverage(float coverage) {
            return Math.max(0.0F, Math.min(1.0F, coverage));
        }
    }
}
