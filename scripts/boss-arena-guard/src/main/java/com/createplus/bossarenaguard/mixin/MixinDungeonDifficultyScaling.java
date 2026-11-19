package com.createplus.bossarenaguard.mixin;

import com.createplus.bossarenaguard.BossRegistryHelper;
import com.createplus.bossarenaguard.DungeonDifficultyCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.dungeon_difficulty.logic.EntityScaling", remap = false)
public abstract class MixinDungeonDifficultyScaling {
    @Inject(method = "scale(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void bossarenaguard$skipArenaBosses(Entity entity, ServerLevel level, CallbackInfo ci) {
        if (entity instanceof LivingEntity boss && BossRegistryHelper.isBoss(boss)) {
            DungeonDifficultyCompat.clearMobScaling(boss);
            ci.cancel();
        }
    }
}
