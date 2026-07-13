package com.y271727uy.voxy;

import com.mojang.logging.LogUtils;
import com.y271727uy.voxy.client.VoxyClientLifecycle;
import com.y271727uy.voxy.client.VoxyClientCommands;
import com.y271727uy.voxy.client.config.VoxyVideoSettingsPage;
import com.y271727uy.voxy.client.model.VoxyModelCatalog;
import com.y271727uy.voxy.client.render.EmbeddiumRenderBridge;
import com.y271727uy.voxy.client.render.OculusShaderBridge;
import com.y271727uy.voxy.config.VoxyClientConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

@Mod(Voxy.MOD_ID)
public final class Voxy {
    public static final String MOD_ID = "voxy";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Voxy() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, VoxyClientConfig.SPEC);
        MinecraftForge.EVENT_BUS.addListener(Voxy::onLevelLoad);
        MinecraftForge.EVENT_BUS.addListener(Voxy::onLevelUnload);
        MinecraftForge.EVENT_BUS.addListener(Voxy::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(OculusShaderBridge::onRenderLevel);
        MinecraftForge.EVENT_BUS.addListener(EmbeddiumRenderBridge::onRenderLevel);
        MinecraftForge.EVENT_BUS.addListener(Voxy::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(VoxyClientCommands::onRegisterCommands);
        LOGGER.info("Initializing Voxy");
    }

    private static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level) {
            VoxyClientLifecycle.onLevelStarted(level);
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            VoxyClientLifecycle.onLevelClosed();
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getChunk() instanceof LevelChunk chunk && chunk.getLevel() instanceof ClientLevel) {
            VoxyClientLifecycle.onChunkLoaded(chunk);
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            VoxyClientLifecycle.onClientTick();
        }
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            VoxyVideoSettingsPage.register();
            LOGGER.info("Voxy client bootstrap complete");
        }

        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(VoxyModelCatalog.INSTANCE);
        }
    }
}
