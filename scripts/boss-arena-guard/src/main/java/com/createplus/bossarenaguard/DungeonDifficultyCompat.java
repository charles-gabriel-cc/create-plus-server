package com.createplus.bossarenaguard;

import net.dungeon_difficulty.logic.EntityDifficultyScalable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

public final class DungeonDifficultyCompat {
    private static final String MOD_ID = "dungeon_difficulty";

    private DungeonDifficultyCompat() {
    }

    public static void clearMobScaling(LivingEntity boss) {
        if (!ModList.get().isLoaded(MOD_ID) || !(boss instanceof EntityDifficultyScalable difficulty)
                || (difficulty.getEvaluatedDimension() == null && !difficulty.isAlreadyScaled())) {
            return;
        }

        float oldMaximumHealth = boss.getMaxHealth();
        var attributes = boss.getAttributes();
        BuiltInRegistries.ATTRIBUTE.holders()
                .filter(attributes::hasAttribute)
                .map(attributes::getInstance)
                .forEach(attribute -> attribute.getModifiers().stream()
                        .filter(modifier -> MOD_ID.equals(modifier.id().getNamespace()))
                        .toList().forEach(attribute::removeModifier));

        float newMaximumHealth = boss.getMaxHealth();
        if (newMaximumHealth != oldMaximumHealth && oldMaximumHealth > 0) {
            boss.setHealth(boss.getHealth() / oldMaximumHealth * newMaximumHealth);
        }
        difficulty.markAlreadyScaled(0);
        difficulty.markEvaluated(null);
        difficulty.setScalingLocationData(null);
    }
}
