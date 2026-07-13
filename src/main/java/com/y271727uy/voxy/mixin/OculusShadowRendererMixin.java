package com.y271727uy.voxy.mixin;

import com.y271727uy.voxy.client.render.OculusShaderBridge;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShadowRenderer.class, remap = false)
abstract class OculusShadowRendererMixin {
    @Inject(method = "renderShadows", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/mixin/LevelRendererAccessor;invokeRenderChunkLayer("
                    + "Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "DDDLorg/joml/Matrix4f;)V",
            ordinal = 2, shift = At.Shift.AFTER), remap = false)
    private void voxy$renderShadowTerrain(LevelRendererAccessor levelRenderer, Camera camera,
                                           CallbackInfo callbackInfo) {
        OculusShaderBridge.renderShadowTerrain(camera);
    }
}
