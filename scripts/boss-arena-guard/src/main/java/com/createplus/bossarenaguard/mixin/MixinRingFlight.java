package com.createplus.bossarenaguard.mixin;

import com.createplus.bossarenaguard.BossArenaGuardMod;
import io.github.vidarrcrib.createlanterns.ring.RingState;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "io.github.vidarrcrib.createlanterns.ring.RingFlight", remap = false)
public abstract class MixinRingFlight {

    @Inject(method = "canFly", at = @At("HEAD"), cancellable = true)
    private static void bossarenaguard$blockRingFlight(ServerPlayer player, RingState ring, CallbackInfoReturnable<Boolean> cir) {
        if (BossArenaGuardMod.isInsideArenaSession(player.serverLevel(), player.position())) {
            cir.setReturnValue(false);
        }
    }
}
