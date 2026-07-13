package com.y271727uy.voxy.common.importer.chunky;

import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Objects;

/** Optional boundary for a version-specific Chunky Forge hook; this class never links Chunky APIs. */
public final class ChunkyLiveIntegration {
    private static final String FORGE_WORLD_CLASS = "org.popcraft.chunky.platform.ForgeWorld";

    private ChunkyLiveIntegration() {
    }

    public static Availability detect(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        try {
            Class.forName(FORGE_WORLD_CLASS, false, loader);
            return new Availability(true, false,
                    "Chunky Forge detected, but no version-specific completion hook is linked");
        } catch (ClassNotFoundException | LinkageError exception) {
            return new Availability(false, false, "Chunky Forge is not present");
        }
    }

    @FunctionalInterface
    public interface GeneratedChunkBridge {
        void onGeneratedChunk(LevelChunk chunk);
    }

    public record Availability(boolean chunkyPresent, boolean liveHookLinked, String detail) {
        public Availability {
            Objects.requireNonNull(detail, "detail");
            if (liveHookLinked && !chunkyPresent) {
                throw new IllegalArgumentException("A live hook cannot be linked without Chunky");
            }
        }
    }
}
