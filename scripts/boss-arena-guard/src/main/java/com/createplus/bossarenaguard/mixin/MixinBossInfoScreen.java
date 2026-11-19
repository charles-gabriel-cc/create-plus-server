package com.createplus.bossarenaguard.mixin;

import com.yori3o.boss_checklist.common.client.boss.BossEntry;
import com.yori3o.boss_checklist.common.client.data.BossNameCache;
import com.yori3o.boss_checklist.common.client.gui.BossInfoScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = BossInfoScreen.class, remap = false)
public abstract class MixinBossInfoScreen {

    @Shadow(remap = false)
    @Final
    private BossEntry boss;

    @Shadow(remap = false)
    private String bossName;

    @Shadow(remap = false)
    private void updateBossNameOffset() {
    }

    @Inject(method = "applyProgressionMode()V", at = @At("RETURN"), remap = false, require = 0)
    private void bossarenaguard$keepRevealedNextBossName(CallbackInfo ci) {
        if (this.boss != null && "???".equals(this.bossName)) {
            String cachedName = BossNameCache.getName(this.boss.id()).getString();
            if (cachedName != null && !"???".equals(cachedName)) {
                this.bossName = cachedName;
                this.updateBossNameOffset();
            }
        }
    }
}
