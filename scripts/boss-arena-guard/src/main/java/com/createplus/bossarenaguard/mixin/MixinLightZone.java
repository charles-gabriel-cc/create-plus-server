package com.createplus.bossarenaguard.mixin;

import com.createplus.bossarenaguard.BossRegistryHelper;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "io.github.vidarrcrib.createlanterns.ring.LightZone", remap = false)
public abstract class MixinLightZone {

    @Inject(method = "lambda$within$0(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void bossarenaguard$excludeBossFromLightZone(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (BossRegistryHelper.unwrapBoss(entity) != null) {
            cir.setReturnValue(false);
        }
    }
}
