package com.y271727uy.voxy.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class VoxyClientConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue RENDERING_ENABLED = BUILDER
            .comment("Enable Voxy distant rendering. Disabled until the renderer port is ready.")
            .define("renderingEnabled", false);

    public static final ForgeConfigSpec.IntValue SECTION_RENDER_DISTANCE = BUILDER
            .comment("LOD section render distance (multiplied by 32 for actual chunk distance).",
                    "Example: 16 = 512 chunks render distance.")
            .defineInRange("sectionRenderDistance", 16, 2, 64);

    public static final ForgeConfigSpec.BooleanValue SSAO_ENABLED = BUILDER
            .comment("Apply Voxy screen-space ambient occlusion in the non-shader terrain pipeline.")
            .define("ssaoEnabled", false);

    public static final ForgeConfigSpec.DoubleValue SSAO_STRENGTH = BUILDER
            .comment("Strength of Voxy screen-space ambient occlusion.")
            .defineInRange("ssaoStrength", 1.0, 0.0, 1.0);

    public static final ForgeConfigSpec.BooleanValue GPU_SHADOW_VALIDATION = BUILDER
            .comment("Run the experimental GPU traversal shadow validator. This is diagnostic-only and does not draw terrain.")
            .define("gpuShadowValidation", false);

    public static final ForgeConfigSpec.IntValue MAX_GPU_MIB = BUILDER
            .comment("Maximum device allocation used by Voxy terrain buffers, in MiB.")
            .defineInRange("maxGpuMiB", 256, 64, 2048);

    public static final ForgeConfigSpec.IntValue INGEST_QUEUE_CAPACITY = BUILDER
            .comment("Maximum queued voxel-ingest snapshots before backpressure is applied.")
            .defineInRange("ingestQueueCapacity", 512, 32, 8192);

    public static final ForgeConfigSpec.ConfigValue<String> STORAGE_BACKEND = BUILDER
            .comment("Section storage backend. External database backends fail closed when their runtime is absent.")
            .defineInList("storageBackend", "file", List.of("file", "memory", "rocksdb", "lmdb", "redis"));

    public static final ForgeConfigSpec.IntValue STORAGE_QUEUE_CAPACITY = BUILDER
            .comment("Maximum queued asynchronous section-storage operations before backpressure is applied.")
            .defineInRange("storageQueueCapacity", 256, 8, 4096);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private VoxyClientConfig() {
    }
}
