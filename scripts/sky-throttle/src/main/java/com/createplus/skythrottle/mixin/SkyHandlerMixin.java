package com.createplus.skythrottle.mixin;

import com.createplus.skythrottle.SkyThrottle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.devce.rocketnautics.client.SkyHandler", remap = false)
public abstract class SkyHandlerMixin {
    @Shadow
    public static ResourceLocation STAR_PLASMA_TEXTURE_ID;
    @Shadow
    private static long lastPlasmaUpdateTick;

    @Inject(method = "ensureStarPlasmaTexture", at = @At("HEAD"), cancellable = true)
    private static void createplus$throttle(CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null
            && SkyThrottle.skipRedraw(STAR_PLASMA_TEXTURE_ID != null, level.getGameTime(), lastPlasmaUpdateTick)) {
            ci.cancel();
        }
    }
}
