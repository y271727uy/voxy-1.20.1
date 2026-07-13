package com.y271727uy.voxy.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.y271727uy.voxy.config.VoxyClientConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void voxy$extendTerrainFog(Camera camera, FogRenderer.FogMode mode,
                                              float viewDistance, boolean thickFog,
                                              float partialTick, CallbackInfo callbackInfo) {
        if (!VoxyClientConfig.RENDERING_ENABLED.get() || thickFog) return;
        if (mode != FogRenderer.FogMode.FOG_TERRAIN) return;
        if (camera.getFluidInCamera() != FogType.NONE) return;
        float distance = (float) (VoxyClientConfig.SECTION_RENDER_DISTANCE.get() * 32.0 * 16.0);
        float fogEnd = Math.max(RenderSystem.getShaderFogEnd(), distance);
        float fogStart = Math.max(RenderSystem.getShaderFogStart(), fogEnd * 0.75F);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }
}
