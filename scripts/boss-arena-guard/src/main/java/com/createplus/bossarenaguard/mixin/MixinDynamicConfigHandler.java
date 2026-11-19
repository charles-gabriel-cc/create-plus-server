package com.createplus.bossarenaguard.mixin;

import com.yori3o.boss_checklist.common.config.ClientConfig;
import com.yori3o.boss_checklist.common.config.DynamicConfigHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = DynamicConfigHandler.class, remap = false)
public abstract class MixinDynamicConfigHandler {

    private static void bossarenaguard$enforceLockedSettings(ClientConfig.Values cfg) {
        if (cfg == null) {
            return;
        }
        cfg.progressionMode = true;
        cfg.progressionModePlus = false;
        cfg.searchBarEnabled = true;
        cfg.openButtonEnabled = true;
        cfg.showEditorButton = true;
        cfg.ignoreUnparticipatedDefeats = true;
    }

    @Inject(
            method = "client()Lcom/yori3o/boss_checklist/common/config/ClientConfig$Values;",
            at = @At("RETURN"),
            remap = false,
            require = 0
    )
    private static void bossarenaguard$onGetClient(CallbackInfoReturnable<ClientConfig.Values> cir) {
        bossarenaguard$enforceLockedSettings(cir.getReturnValue());
    }

    @Inject(method = "loadClient()V", at = @At("RETURN"), remap = false, require = 0)
    private static void bossarenaguard$onLoadClient(CallbackInfo ci) {
        if (DynamicConfigHandler.cc != null) {
            ClientConfig.Values cfg = DynamicConfigHandler.cc.get();
            bossarenaguard$enforceLockedSettings(cfg);
            DynamicConfigHandler.cc.save();
        }
    }
}
