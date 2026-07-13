package com.y271727uy.voxy.client.render;

import com.mojang.logging.LogUtils;
import com.y271727uy.voxy.config.VoxyClientConfig;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import com.y271727uy.voxy.client.render.mesh.GpuSectionRenderer;
import org.slf4j.Logger;

public final class EmbeddiumRenderBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ClientLevel level;
    private static boolean reportedReady;
    private static volatile FrameState lastFrame;

    private EmbeddiumRenderBridge() {
    }

    public static void attach(ClientLevel clientLevel) {
        level = clientLevel;
        reportedReady = false;
        lastFrame = null;
    }

    public static void detach() {
        level = null;
        lastFrame = null;
        reportedReady = false;
    }

    public static FrameState lastFrame() {
        return lastFrame;
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (level == null) {
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
            if (renderer != null && VoxyClientConfig.RENDERING_ENABLED.get()) {
                GpuSectionRenderer.renderTranslucent(event, renderer);
            }
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        int sectionX = ((int) Math.floor(camera.x)) >> 4;
        int sectionY = ((int) Math.floor(camera.y)) >> 4;
        int sectionZ = ((int) Math.floor(camera.z)) >> 4;
        FrameState state = new FrameState(renderer.getVisibleChunkCount(), renderer.isTerrainRenderComplete(),
                renderer.isSectionReady(sectionX, sectionY, sectionZ), event.getPartialTick(), camera);
        lastFrame = state;
        if (!reportedReady) {
            reportedReady = true;
            LOGGER.info("Voxy Embeddium bridge active: {} visible chunks, camera section ready={}",
                    state.visibleChunkCount(), state.cameraSectionReady());
        }
        if (!VoxyClientConfig.RENDERING_ENABLED.get()) {
            return;
        }
        GpuSectionRenderer.render(event, renderer);
    }

    public record FrameState(int visibleChunkCount,
                             boolean terrainRenderComplete,
                             boolean cameraSectionReady,
                             float partialTick,
                             Vec3 cameraPosition) {
    }
}
