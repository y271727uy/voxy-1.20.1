package com.y271727uy.voxy.mixin;

import com.y271727uy.voxy.client.VoxyClientLifecycle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void voxy$onBlockChanged(BlockPos position, BlockState previous, BlockState updated,
                                     CallbackInfo callbackInfo) {
        if (previous != updated) {
            VoxyClientLifecycle.onBlockChanged((ClientLevel) (Object) this, position);
        }
    }
}
