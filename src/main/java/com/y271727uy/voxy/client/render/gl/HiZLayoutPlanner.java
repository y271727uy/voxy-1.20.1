package com.y271727uy.voxy.client.render.gl;

import java.util.ArrayList;
import java.util.List;

/** Computes the raster Hi-Z texture layout used by the production Voxy traversal shader. */
public final class HiZLayoutPlanner {
    private static final int MAX_PACKED_DIMENSION = 0xFFFF;

    private HiZLayoutPlanner() {
    }

    public static Layout plan(int viewportWidth, int viewportHeight) {
        int width = normalizeDimension(viewportWidth);
        int height = normalizeDimension(viewportHeight);
        if (width > MAX_PACKED_DIMENSION || height > MAX_PACKED_DIMENSION) {
            throw new IllegalArgumentException("Hi-Z dimensions exceed the packed 16-bit shader ABI");
        }

        int maxExponent = Math.max(Integer.numberOfTrailingZeros(width),
                Integer.numberOfTrailingZeros(height));
        int mipCount = Math.max(1, maxExponent);
        List<Level> levels = new ArrayList<>(mipCount);
        int levelWidth = width;
        int levelHeight = height;
        for (int mip = 0; mip < mipCount; mip++) {
            levels.add(new Level(mip, levelWidth, levelHeight));
            levelWidth = Math.max(1, levelWidth >>> 1);
            levelHeight = Math.max(1, levelHeight >>> 1);
        }
        return new Layout(viewportWidth, viewportHeight, width, height, levels,
                packDimensions(width, height));
    }

    /** Matches the production HiZBuffer allocation: largest power of two not exceeding the viewport. */
    public static int normalizeDimension(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }
        return Integer.highestOneBit(dimension);
    }

    /** Packs actual texture width/height as decoded by hierarchical/screenspace.glsl. */
    public static int packDimensions(int normalizedWidth, int normalizedHeight) {
        requirePackedPowerOfTwo(normalizedWidth, "width");
        requirePackedPowerOfTwo(normalizedHeight, "height");
        return (normalizedWidth << 16) | normalizedHeight;
    }

    public static int unpackWidth(int packedDimensions) {
        return packedDimensions >>> 16;
    }

    public static int unpackHeight(int packedDimensions) {
        return packedDimensions & 0xFFFF;
    }

    private static void requirePackedPowerOfTwo(int value, String name) {
        if (value <= 0 || value > MAX_PACKED_DIMENSION || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException("Normalized " + name
                    + " must be a packed positive power of two");
        }
    }

    public record Layout(int viewportWidth, int viewportHeight,
                         int width, int height, List<Level> levels,
                         int packedDimensions) {
        public Layout {
            levels = List.copyOf(levels);
        }

        public int mipCount() {
            return this.levels.size();
        }

        public Level level(int mip) {
            return this.levels.get(mip);
        }
    }

    public record Level(int mip, int width, int height) {
    }
}
