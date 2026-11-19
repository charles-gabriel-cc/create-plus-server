package com.createplus.bossarenaguard;

import io.github.vidarrcrib.createlanterns.ring.RingFlight;
import io.github.vidarrcrib.createlanterns.ring.RingService;
import io.github.vidarrcrib.createlanterns.ring.RingState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class LanternFlightCompat {
    private LanternFlightCompat() {
    }

    public static boolean stopFlightIfActive(ServerPlayer player) {
        try {
            RingState state = RingService.worn(player);
            if (state != null && state.flying()) {
                RingFlight.stop(player, state);
                RingFlight.land(player);
                player.displayClientMessage(
                        Component.literal("\u00a7c\u26a1 O voo da lanterna \u00e9 anulado pelo campo de for\u00e7a da arena!"),
                        true
                );
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
