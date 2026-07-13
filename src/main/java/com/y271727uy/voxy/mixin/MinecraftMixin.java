package com.y271727uy.voxy.mixin;

import com.y271727uy.voxy.client.VoxyClientLifecycle;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "clearLevel()V", at = @At("TAIL"))
    private void voxy$onLevelClosed(CallbackInfo callbackInfo) {
        VoxyClientLifecycle.onLevelClosed();
    }
}
