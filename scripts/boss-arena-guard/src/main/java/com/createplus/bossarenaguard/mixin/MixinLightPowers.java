package com.createplus.bossarenaguard.mixin;

import com.createplus.bossarenaguard.BossRegistryHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "io.github.vidarrcrib.createlanterns.ring.LightPowers", remap = false)
public abstract class MixinLightPowers {

    @Inject(method = "looked(Lnet/minecraft/server/level/ServerPlayer;D)Lnet/minecraft/world/entity/LivingEntity;", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void bossarenaguard$hideBossFromLookedTarget(ServerPlayer player, double range, CallbackInfoReturnable<LivingEntity> cir) {
        LivingEntity ret = cir.getReturnValue();
        if (ret != null && BossRegistryHelper.unwrapBoss(ret) != null) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "canFrighten(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/server/level/ServerPlayer;)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void bossarenaguard$blockFearOnBoss(Mob mob, ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (BossRegistryHelper.unwrapBoss(mob) != null) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "pause(Lnet/minecraft/world/entity/Mob;IILnet/minecraft/server/level/ServerPlayer;)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void bossarenaguard$blockPauseOnBoss(Mob mob, int ticks, int color, ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (BossRegistryHelper.unwrapBoss(mob) != null) {
            cir.setReturnValue(false);
        }
    }
}
