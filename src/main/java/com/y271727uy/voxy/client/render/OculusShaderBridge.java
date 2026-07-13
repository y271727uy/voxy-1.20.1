package com.y271727uy.voxy.client.render;

import com.mojang.logging.LogUtils;
import com.y271727uy.voxy.config.VoxyClientConfig;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.SodiumTerrainPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Camera;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import com.y271727uy.voxy.client.render.mesh.GpuSectionRenderer;
import com.y271727uy.voxy.client.core.rendering.pipeline.RenderPipelineFactory;
import org.slf4j.Logger;

public final class OculusShaderBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ClientLevel level;
    private static boolean reported;
    private static volatile ShaderState lastState;

    private OculusShaderBridge() {
    }

    public static void attach(ClientLevel clientLevel) {
        level = clientLevel;
        reported = false;
        lastState = null;
    }

    public static void detach() {
        level = null;
        reported = false;
        lastState = null;
    }

    public static ShaderState lastState() {
        return lastState;
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (level == null) return;
        boolean solid = event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS;
        boolean translucent = event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        if (!solid && !translucent) return;
        IrisApi api = IrisApi.getInstance();
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        SodiumTerrainPipeline terrainPipeline = pipeline == null ? null : pipeline.getSodiumTerrainPipeline();
        RenderPipelineFactory.Mode mode = RenderPipelineFactory.selectMode(
                api.isShaderPackInUse(), terrainPipeline != null);
        if (translucent) {
            if (VoxyClientConfig.RENDERING_ENABLED.get()
                    && mode == RenderPipelineFactory.Mode.IRIS) {
                SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
                if (renderer != null) GpuSectionRenderer.renderOculusTranslucent(event, renderer, terrainPipeline);
            }
            return;
        }
        ShaderState state = new ShaderState(api.isShaderPackInUse(), api.isRenderingShadowPass(),
                pipeline == null ? WorldRenderingPhase.NONE : pipeline.getPhase(),
                terrainPipeline != null, terrainPipeline != null && terrainPipeline.hasShadowPass());
        lastState = state;
        if (!reported) {
            reported = true;
            LOGGER.info("Voxy Oculus bridge active: shaders={}, phase={}, terrainPipeline={}, shadowProgram={}",
                    state.shaderPackInUse(), state.phase(), state.terrainPipelineAvailable(), state.shadowProgramAvailable());
        }
        if (!VoxyClientConfig.RENDERING_ENABLED.get()) {
            return;
        }
        if (mode == RenderPipelineFactory.Mode.IRIS) {
            SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
            if (renderer != null) {
                GpuSectionRenderer.renderOculus(event, renderer, pipeline, terrainPipeline, state.shadowPass());
            }
        }
    }

    public static void renderShadowTerrain(Camera camera) {
        if (level == null || !VoxyClientConfig.RENDERING_ENABLED.get()) return;
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        SodiumTerrainPipeline terrainPipeline = pipeline == null ? null : pipeline.getSodiumTerrainPipeline();
        if (terrainPipeline != null && terrainPipeline.hasShadowPass()) {
            GpuSectionRenderer.renderOculusShadow(camera, terrainPipeline);
        }
    }

    public record ShaderState(boolean shaderPackInUse,
                              boolean shadowPass,
                              WorldRenderingPhase phase,
                              boolean terrainPipelineAvailable,
                              boolean shadowProgramAvailable) {
    }
}
