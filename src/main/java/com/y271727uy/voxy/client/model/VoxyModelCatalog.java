package com.y271727uy.voxy.client.model;

import com.mojang.logging.LogUtils;
import com.y271727uy.voxy.client.core.model.ModelBakerySubsystem;
import com.y271727uy.voxy.client.core.model.ModelFactory;
import com.y271727uy.voxy.client.core.model.snapshot.ModelSnapshot;
import com.y271727uy.voxy.common.world.MappingRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.data.ModelData;
import org.embeddedt.embeddium.api.model.EmbeddiumBakedModelExtension;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class VoxyModelCatalog implements ResourceManagerReloadListener {
    public static final VoxyModelCatalog INSTANCE = new VoxyModelCatalog();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Direction[] FACES = Direction.values();

    private final ConcurrentHashMap<Integer, Biome> biomes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TintKey, Integer> tintColors = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong attachmentEpoch = new AtomicLong();
    private final Object publicationLock = new Object();
    private volatile MappingRegistry mapping;
    private volatile ModelBakerySubsystem bakerySubsystem;

    private VoxyModelCatalog() {
    }

    public void attach(MappingRegistry mappingRegistry) {
        ModelBakerySubsystem previous;
        synchronized (this.publicationLock) {
            long epoch = this.attachmentEpoch.incrementAndGet();
            previous = this.bakerySubsystem;
            this.bakerySubsystem = new ModelBakerySubsystem(
                    prepared -> publishPrepared(mappingRegistry, epoch, prepared));
            this.mapping = mappingRegistry;
            mappingRegistry.onBlockRegistered(entry -> scheduleBake(mappingRegistry, epoch, entry));
            mappingRegistry.onBiomeRegistered(entry -> scheduleBiome(mappingRegistry, epoch, entry));
        }
        if (previous != null) previous.close();
        rebuild(mappingRegistry, this.attachmentEpoch.get());
    }

    public void detach() {
        ModelBakerySubsystem bakery;
        synchronized (this.publicationLock) {
            this.attachmentEpoch.incrementAndGet();
            MappingRegistry current = this.mapping;
            if (current != null) {
                current.onBlockRegistered(null);
                current.onBiomeRegistered(null);
            }
            this.mapping = null;
            this.biomes.clear();
            this.tintColors.clear();
            bakery = this.bakerySubsystem;
            this.bakerySubsystem = null;
            this.generation.incrementAndGet();
        }
        if (bakery != null) bakery.close();
    }

    public BakedBlockModel get(int blockId) {
        ModelBakerySubsystem bakery = this.bakerySubsystem;
        return bakery == null ? null : bakery.factory().get(blockId);
    }

    public boolean isFullyOpaque(int blockId) {
        ModelFactory.ModelEntry entry = modelEntry(blockId);
        return entry != null && entry.descriptor().fullyOpaque();
    }

    public int size() {
        ModelBakerySubsystem bakery = this.bakerySubsystem;
        return bakery == null ? 0 : bakery.factory().size();
    }

    public ModelFactory.ModelEntry modelEntry(int blockId) {
        ModelBakerySubsystem bakery = this.bakerySubsystem;
        return bakery == null ? null : bakery.factory().entry(blockId);
    }

    public CatalogModelSnapshot captureModelSnapshot(int[] stateIds) {
        synchronized (this.publicationLock) {
            ModelBakerySubsystem bakery = this.bakerySubsystem;
            ModelFactory.FactorySnapshot factory = bakery == null
                    ? new ModelFactory.FactorySnapshot(0, java.util.Map.of())
                    : bakery.factory().snapshot(stateIds);
            return new CatalogModelSnapshot(this.attachmentEpoch.get(), this.generation.get(),
                    factory.generation(), factory.entries());
        }
    }

    public ModelSnapshot captureIntegrationSnapshot(int[] stateIds) {
        synchronized (this.publicationLock) {
            ModelBakerySubsystem bakery = this.bakerySubsystem;
            ModelFactory.FactorySnapshot factory = bakery == null
                    ? new ModelFactory.FactorySnapshot(0, java.util.Map.of())
                    : bakery.factory().snapshot(stateIds);
            long epoch = this.attachmentEpoch.get();
            long catalogGeneration = this.generation.get();
            return ModelSnapshot.fromImmutable(factory.entries(), epoch, catalogGeneration,
                    this.attachmentEpoch::get, this.generation::get, stateIds);
        }
    }

    public long generation() {
        return this.generation.get();
    }

    public int tintCacheSize() {
        return this.tintColors.size();
    }

    public int tintColor(int blockId, int biomeId, int tintIndex) {
        if (tintIndex < 0) return 0xFFFFFF;
        return this.tintColors.computeIfAbsent(new TintKey(blockId, biomeId, tintIndex,
                BakedBlockModel.TintSource.BLOCK), this::computeTintColor);
    }

    public int tintColor(int blockId, int biomeId, BakedBlockModel.Quad quad) {
        if (quad.tintSource() == BakedBlockModel.TintSource.NONE) return 0xFFFFFFFF;
        return this.tintColors.computeIfAbsent(new TintKey(blockId, biomeId, quad.tintIndex(),
                quad.tintSource()), this::computeTintColor);
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        rebuild();
    }

    private void rebuild() {
        rebuild(this.mapping, this.attachmentEpoch.get());
    }

    private void rebuild(MappingRegistry expectedMapping, long expectedEpoch) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> rebuild(expectedMapping, expectedEpoch));
            return;
        }
        synchronized (this.publicationLock) {
        if (!isCurrent(expectedMapping, expectedEpoch)) return;
        MappingRegistry current = expectedMapping;
        ModelBakerySubsystem bakery = this.bakerySubsystem;
        if (bakery == null) return;
        ModelFactory factory = bakery.factory();
        factory.beginBatch();
        try {
            factory.clear();
            this.tintColors.clear();
            rebuildBiomes(current);
            if (current != null) {
                for (MappingRegistry.BlockEntry entry : current.blockEntries()) {
                    if (entry.id() != 0) {
                        bake(entry);
                    }
                }
            }
        } finally {
            factory.endBatch();
        }
        long nextGeneration = this.generation.incrementAndGet();
        var models = factory.models();
        long emissive = models.stream().filter(model -> model.emission() > 0).count();
        long tintedQuads = models.stream().flatMap(model -> model.quads().stream())
                .filter(quad -> quad.tintIndex() >= 0).count();
        long adjacentLightQuads = models.stream().flatMap(model -> model.quads().stream())
                .filter(quad -> !quad.useSelfLight()).count();
        LOGGER.info("Voxy model catalog generation {} contains {} block models: emissive={}, tintedQuads={}, adjacentLightQuads={}",
                nextGeneration, factory.size(), emissive, tintedQuads, adjacentLightQuads);
        }
    }

    private boolean isCurrent(MappingRegistry expectedMapping, long expectedEpoch) {
        return this.mapping == expectedMapping && this.attachmentEpoch.get() == expectedEpoch;
    }

    private void publishPrepared(MappingRegistry expectedMapping, long expectedEpoch,
                                 List<ModelFactory.ModelEntry> prepared) {
        synchronized (this.publicationLock) {
            if (!isCurrent(expectedMapping, expectedEpoch)) return;
            ModelBakerySubsystem bakery = this.bakerySubsystem;
            if (bakery == null) return;
            bakery.factory().publishPrepared(prepared);
            this.generation.incrementAndGet();
        }
    }

    private void scheduleBake(MappingRegistry expectedMapping, long expectedEpoch,
                              MappingRegistry.BlockEntry entry) {
        Minecraft.getInstance().execute(() -> {
            synchronized (this.publicationLock) {
                if (!isCurrent(expectedMapping, expectedEpoch)) return;
                bake(entry);
                this.generation.incrementAndGet();
            }
        });
    }

    private void scheduleBiome(MappingRegistry expectedMapping, long expectedEpoch,
                               MappingRegistry.BiomeEntry entry) {
        Minecraft.getInstance().execute(() -> {
            synchronized (this.publicationLock) {
                if (!isCurrent(expectedMapping, expectedEpoch)) return;
                resolveBiome(entry);
                this.tintColors.clear();
                this.generation.incrementAndGet();
            }
        });
    }

    private void bake(MappingRegistry.BlockEntry entry) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getBlockRenderer().getBlockModel(entry.state());
        ModelData modelData;
        boolean dynamicCaptureFailed = false;
        try {
            modelData = model.getModelData(new SingleBiomeView(entry.state(), null), BlockPos.ZERO,
                    entry.state(), ModelData.EMPTY);
        } catch (RuntimeException exception) {
            modelData = ModelData.EMPTY;
            dynamicCaptureFailed = true;
            LOGGER.warn("Voxy cannot capture dynamic model data for block state {}; marking unsupported",
                    entry.id(), exception);
        }
        boolean dynamicUnsupported = dynamicCaptureFailed || !modelData.getProperties().isEmpty();
        if (dynamicUnsupported) {
            LOGGER.warn("Voxy block state {} requires contextual ModelData and will remain unsupported",
                    entry.id());
        }
        List<BakedBlockModel.Quad> quads = new ArrayList<>();
        ChunkRenderTypeSet renderTypes = model.getRenderTypes(entry.state(), RandomSource.create(42L), modelData);
        if (renderTypes.isEmpty()) {
            collectQuads(model, entry, null, modelData, quads);
        } else {
            for (RenderType renderType : renderTypes) {
                collectQuads(model, entry, renderType, modelData, quads);
            }
        }
        appendFluidQuads(entry.state(), quads);
        boolean ambientOcclusion = model.useAmbientOcclusion(entry.state());
        if (model instanceof EmbeddiumBakedModelExtension extension && !renderTypes.isEmpty()) {
            for (RenderType renderType : renderTypes) {
                ambientOcclusion |= extension.useAmbientOcclusionWithLightEmission(entry.state(), renderType);
            }
        }
        ModelBakerySubsystem bakery = this.bakerySubsystem;
        if (bakery != null) {
            BakedBlockModel baked = new BakedBlockModel(entry.id(), ambientOcclusion,
                    model.isCustomRenderer() || dynamicUnsupported,
                    Math.min(15, entry.state().getLightEmission()), quads);
            FluidState fluidState = entry.state().getFluidState();
            ModelFactory.FluidDescriptor fluid = fluidState.isEmpty() ? null
                    : new ModelFactory.FluidDescriptor(this.mapping.fluidKey(entry.id()),
                    entry.state().getBlock() instanceof LiquidBlock, fluidState.getOwnHeight(),
                    false, 0.0F, 0.0F, -1);
            boolean cullsSame = java.util.Arrays.stream(FACES)
                    .allMatch(direction -> entry.state().skipRendering(entry.state(), direction));
            ModelFactory.ModelDescriptor descriptor = ModelFactory.descriptor(baked,
                    entry.opacity() >= 15 && entry.state().canOcclude(), cullsSame, fluid);
            bakery.factory().invalidate(entry.id());
            bakery.submit(baked, descriptor);
        }
    }

    private static void appendFluidQuads(BlockState blockState, List<BakedBlockModel.Quad> output) {
        FluidState fluidState = blockState.getFluidState();
        if (fluidState.isEmpty()) return;
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluidState);
        SingleBiomeView view = new SingleBiomeView(blockState, null);
        ResourceLocation stillLocation = extensions.getStillTexture(fluidState,
                view, BlockPos.ZERO);
        ResourceLocation flowingLocation = extensions.getFlowingTexture(fluidState,
                view, BlockPos.ZERO);
        if (stillLocation == null || flowingLocation == null) return;
        var atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite still = atlas.getSprite(stillLocation);
        TextureAtlasSprite flowing = atlas.getSprite(flowingLocation);
        output.addAll(createFluidQuads(still, flowing, fluidState.getOwnHeight(), fluidKey(fluidState)));
    }

    static List<BakedBlockModel.Quad> createFluidQuads(TextureAtlasSprite still,
                                                        TextureAtlasSprite flowing, float height,
                                                        ResourceLocation fluidKey) {
        List<BakedBlockModel.Quad> quads = new ArrayList<>(6);
        for (Direction face : FACES) {
            TextureAtlasSprite sprite = face.getAxis() == Direction.Axis.Y ? still : flowing;
            quads.add(new BakedBlockModel.Quad(fluidVertices(face, sprite, height), 0, face,
                    sprite.contents().name(), "translucent", true, false,
                    BakedBlockModel.MaterialPass.TRANSLUCENT, true, fluidKey, height));
        }
        return quads;
    }

    private static ResourceLocation fluidKey(FluidState state) {
        var fluid = state.getType();
        if (fluid instanceof FlowingFluid flowing) fluid = flowing.getSource();
        return BuiltInRegistries.FLUID.getKey(fluid);
    }

    private static int[] fluidVertices(Direction face, TextureAtlasSprite sprite, float height) {
        return fluidVertices(face, height, sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1());
    }

    static int[] fluidVertices(Direction face, float height, float u0, float u1, float v0, float v1) {
        float top = Math.max(1.0F / 9.0F, Math.min(1.0F, height));
        float[][] positions = switch (face) {
            case DOWN -> new float[][]{{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}};
            case UP -> new float[][]{{0, top, 0}, {0, top, 1}, {1, top, 1}, {1, top, 0}};
            case NORTH -> new float[][]{{1, 0, 0}, {0, 0, 0}, {0, top, 0}, {1, top, 0}};
            case SOUTH -> new float[][]{{0, 0, 1}, {1, 0, 1}, {1, top, 1}, {0, top, 1}};
            case WEST -> new float[][]{{0, 0, 0}, {0, 0, 1}, {0, top, 1}, {0, top, 0}};
            case EAST -> new float[][]{{1, 0, 1}, {1, 0, 0}, {1, top, 0}, {1, top, 1}};
        };
        int[] vertices = new int[32];
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * 8;
            vertices[base] = Float.floatToRawIntBits(positions[vertex][0]);
            vertices[base + 1] = Float.floatToRawIntBits(positions[vertex][1]);
            vertices[base + 2] = Float.floatToRawIntBits(positions[vertex][2]);
            vertices[base + 3] = 0xFFFFFFFF;
            float horizontal = face.getAxis() == Direction.Axis.Y ? positions[vertex][0]
                    : face.getAxis() == Direction.Axis.Z ? positions[vertex][0] : positions[vertex][2];
            float vertical = face.getAxis() == Direction.Axis.Y ? positions[vertex][2]
                    : 1.0F - positions[vertex][1] / top;
            float u = u0 + (u1 - u0) * horizontal;
            float v = v0 + (v1 - v0) * vertical;
            vertices[base + 4] = Float.floatToRawIntBits(u);
            vertices[base + 5] = Float.floatToRawIntBits(v);
        }
        return vertices;
    }

    private static void collectQuads(BakedModel model, MappingRegistry.BlockEntry entry, RenderType renderType,
                                     ModelData modelData,
                                     List<BakedBlockModel.Quad> output) {
        collectFace(model, entry, null, renderType, modelData, output);
        for (Direction face : FACES) {
            collectFace(model, entry, face, renderType, modelData, output);
        }
    }

    private static void collectFace(BakedModel model, MappingRegistry.BlockEntry entry, Direction face,
                                    RenderType renderType, ModelData modelData,
                                    List<BakedBlockModel.Quad> output) {
        List<BakedQuad> quads = model.getQuads(entry.state(), face, RandomSource.create(42L), modelData, renderType);
        for (BakedQuad quad : quads) {
            String layer = renderType == null ? "default" : renderType.toString();
            BakedBlockModel.MaterialPass pass = BakedBlockModel.MaterialPass.classify(layer);
            BakedBlockModel.MaterialType materialType = BakedBlockModel.MaterialType.classify(layer);
            float[] coverage = textureCoverage(quad.getSprite(), quad.getVertices());
            output.add(new BakedBlockModel.Quad(quad.getVertices(), quad.getTintIndex(), face,
                    quad.getSprite().contents().name(), layer, quad.isShade(), quad.hasAmbientOcclusion(),
                    pass, pass == BakedBlockModel.MaterialPass.TRANSLUCENT || usesSelfLight(quad, face),
                    null, Float.NaN, materialType, coverage[0], coverage[1]));
        }
    }

    private static float[] textureCoverage(TextureAtlasSprite sprite, int[] vertices) {
        try {
            var image = sprite.contents().getOriginalImage();
            int stride = vertices.length / 4;
            float minU = 1, maxU = 0, minV = 1, maxV = 0;
            float uSpan = sprite.getU1() - sprite.getU0();
            float vSpan = sprite.getV1() - sprite.getV0();
            if (stride < 6 || uSpan == 0 || vSpan == 0) return new float[]{0, 0};
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * stride;
                float u = (Float.intBitsToFloat(vertices[base + 4]) - sprite.getU0()) / uSpan;
                float v = (Float.intBitsToFloat(vertices[base + 5]) - sprite.getV0()) / vSpan;
                minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
            return pixelCoverage(sprite.contents().width(), sprite.contents().height(),
                    (x, y) -> image.getPixelRGBA(x, y) >>> 24 & 0xFF,
                    minU, maxU, minV, maxV);
        } catch (RuntimeException exception) {
            return new float[]{0.0F, 0.0F};
        }
    }

    static float[] pixelCoverage(int width, int height, java.util.function.IntBinaryOperator alpha,
                                 float minU, float maxU, float minV, float maxV) {
        int x0 = Math.max(0, Math.min(width - 1, (int) Math.floor(Math.min(minU, maxU) * width)));
        int x1 = Math.max(0, Math.min(width - 1, (int) Math.ceil(Math.max(minU, maxU) * width) - 1));
        int y0 = Math.max(0, Math.min(height - 1, (int) Math.floor(Math.min(minV, maxV) * height)));
        int y1 = Math.max(0, Math.min(height - 1, (int) Math.ceil(Math.max(minV, maxV) * height) - 1));
        if (width <= 0 || height <= 0 || x1 < x0 || y1 < y0) return new float[]{0, 0};
        int pixels = 0, written = 0, opaque = 0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int value = alpha.applyAsInt(x, y);
            pixels++;
            if (value != 0) written++;
            if (value == 255) opaque++;
        }
        return new float[]{written / (float) pixels, opaque / (float) pixels};
    }

    private void rebuildBiomes(MappingRegistry current) {
        this.biomes.clear();
        if (current == null || Minecraft.getInstance().level == null) return;
        for (MappingRegistry.BiomeEntry entry : current.biomeEntries()) {
            resolveBiome(entry);
        }
    }

    private void resolveBiome(MappingRegistry.BiomeEntry entry) {
        if (Minecraft.getInstance().level == null) return;
        Biome biome = Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.BIOME)
                .get(entry.location());
        if (biome != null) this.biomes.put(entry.id(), biome);
    }

    private int computeTintColor(TintKey key) {
        MappingRegistry current = this.mapping;
        Biome biome = this.biomes.get(key.biomeId());
        if (current == null) return 0xFFFFFFFF;
        BlockState state = current.blockState(key.blockId());
        try {
            if (key.source() == BakedBlockModel.TintSource.FLUID) {
                FluidState fluid = state.getFluidState();
                return IClientFluidTypeExtensions.of(fluid).getTintColor(fluid,
                        new SingleBiomeView(state, biome), BlockPos.ZERO);
            }
            if (biome == null) return 0xFFFFFFFF;
            BlockColors colors = Minecraft.getInstance().getBlockColors();
            int color = colors.getColor(state, new SingleBiomeView(state, biome), BlockPos.ZERO, key.tintIndex());
            return color == -1 ? 0xFFFFFFFF : 0xFF000000 | color & 0xFFFFFF;
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to resolve Voxy tint for block {} biome {} tint {}",
                    key.blockId(), key.biomeId(), key.tintIndex(), exception);
            return 0xFFFFFFFF;
        }
    }

    private static boolean usesSelfLight(BakedQuad quad, Direction face) {
        if (face == null) return true;
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        int coordinate = face.getAxis() == Direction.Axis.X ? 0 : face.getAxis() == Direction.Axis.Y ? 1 : 2;
        float boundary = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        for (int vertex = 0; vertex < 4; vertex++) {
            float value = Float.intBitsToFloat(vertices[vertex * stride + coordinate]);
            if (Math.abs(value - boundary) > 0.01F) return true;
        }
        return false;
    }

    private record TintKey(int blockId, int biomeId, int tintIndex, BakedBlockModel.TintSource source) {
    }

    public record CatalogModelSnapshot(long attachmentEpoch, long catalogGeneration,
                                       long factoryGeneration,
                                       java.util.Map<Integer, ModelFactory.ModelEntry> entries) {
    }

    private record SingleBiomeView(BlockState state, Biome biome) implements BlockAndTintGetter {
        @Override public float getShade(Direction direction, boolean shaded) { return 1.0F; }
        @Override public int getBrightness(LightLayer layer, BlockPos position) { return 15; }
        @Override public LevelLightEngine getLightEngine() { return null; }
        @Override public int getBlockTint(BlockPos position, ColorResolver resolver) {
            return this.biome == null ? 0xFFFFFF : resolver.getColor(this.biome, 0, 0);
        }
        @Override public BlockEntity getBlockEntity(BlockPos position) { return null; }
        @Override public BlockState getBlockState(BlockPos position) { return this.state; }
        @Override public FluidState getFluidState(BlockPos position) {
            return this.state == null ? net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState()
                    : this.state.getFluidState();
        }
        @Override public int getHeight() { return 1; }
        @Override public int getMinBuildHeight() { return 0; }
    }
}
