package com.createplus.carryonfix;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import tschipp.carryon.common.carry.CarryOnData;
import tschipp.carryon.common.carry.CarryOnDataManager;

/**
 * Fixes two Carry On issues:
 * 1. Server-side: When a carried player dismounts via Shift (or teleports/leaves) without Carry On clearing
 *    the carrier's CarryType.PLAYER state, CarryOnCommon.onCarryTick keeps re-applying Slowness every tick
 *    indefinitely. We detect stale CarryType.PLAYER state (no player passenger for 4 ticks) and clear it.
 * 2. Client-side (CarryRenderHelperMixin): Caches the rendered carried entity instead of recreating it from
 *    NBT every frame and stabilizes its transform/rotation fields so mobs do not flicker.
 */
@Mod(CarryOnFix.MOD_ID)
public final class CarryOnFix {
    public static final String MOD_ID = "createplus_carryon_fix";
    private static final int DISMOUNT_GRACE_TICKS = 4;
    private static final Map<UUID, Integer> MISSING_PASSENGER_TICKS = new HashMap<>();

    public CarryOnFix() {
        NeoForge.EVENT_BUS.addListener(CarryOnFix::onPlayerTickPost);
        NeoForge.EVENT_BUS.addListener(CarryOnFix::onPlayerLoggedOut);
    }

    private static void onPlayerTickPost(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CarryOnData carry = CarryOnDataManager.getCarryData(player);
        UUID id = player.getUUID();
        if (!carry.isCarrying(CarryOnData.CarryType.PLAYER)) {
            MISSING_PASSENGER_TICKS.remove(id);
            return;
        }

        boolean hasPlayerPassenger = false;
        for (Entity passenger : player.getPassengers()) {
            if (passenger instanceof Player) {
                hasPlayerPassenger = true;
                break;
            }
        }

        if (hasPlayerPassenger) {
            MISSING_PASSENGER_TICKS.remove(id);
            return;
        }

        int missingTicks = MISSING_PASSENGER_TICKS.getOrDefault(id, 0) + 1;
        if (missingTicks < DISMOUNT_GRACE_TICKS) {
            MISSING_PASSENGER_TICKS.put(id, missingTicks);
            return;
        }

        MISSING_PASSENGER_TICKS.remove(id);
        carry.clear();
        CarryOnDataManager.setCarryData(player, carry);

        MobEffectInstance slowness = player.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        if (slowness != null && slowness.getDuration() <= 5) {
            player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        MISSING_PASSENGER_TICKS.remove(event.getEntity().getUUID());
    }
}
