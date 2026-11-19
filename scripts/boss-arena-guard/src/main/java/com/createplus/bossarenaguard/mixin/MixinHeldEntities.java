package com.createplus.bossarenaguard.mixin;

import com.createplus.bossarenaguard.BossRegistryHelper;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "io.github.vidarrcrib.createlanterns.ring.HeldEntities", remap = false)
public abstract class MixinHeldEntities {

    @Inject(method = "isTooMighty(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void bossarenaguard$blockBossHold(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (BossRegistryHelper.unwrapBoss(entity) != null) {
            cir.setReturnValue(true);
        }
    }
}
