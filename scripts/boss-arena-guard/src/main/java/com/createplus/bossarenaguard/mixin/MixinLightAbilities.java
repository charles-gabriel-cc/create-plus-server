package com.createplus.bossarenaguard.mixin;

import com.createplus.bossarenaguard.BossRegistryHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "io.github.vidarrcrib.createlanterns.ring.LightAbilities", remap = false)
public abstract class MixinLightAbilities {

    @Inject(method = "great(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void bossarenaguard$treatBossAsGreat(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (BossRegistryHelper.unwrapBoss(entity) != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "spares(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void bossarenaguard$spareBossFromAbilities(ServerPlayer caster, LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (BossRegistryHelper.unwrapBoss(target) != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "fling(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void bossarenaguard$cancelBossFling(LivingEntity target, Vec3 velocity, CallbackInfo ci) {
        if (BossRegistryHelper.unwrapBoss(target) != null) {
            ci.cancel();
        }
    }
}
