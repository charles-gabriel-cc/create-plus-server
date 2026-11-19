package com.createplus.bossarenaguard.mixin;

import com.yori3o.boss_checklist.common.client.boss.BossEntry;
import com.yori3o.boss_checklist.common.client.gui.BossChecklistScreen;
import com.yori3o.boss_checklist.common.config.DynamicConfigHandler;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = BossChecklistScreen.class, remap = false)
public abstract class MixinBossChecklistScreen {

    @Shadow(remap = false)
    public boolean invalidateNames;

    @Shadow(remap = false)
    private EditBox searchBox;

    @Shadow(remap = false)
    public abstract void reloadNamesMap();

    @Shadow(remap = false)
    private void updateSearch(String search) {
    }

    @Inject(
            method = "lambda$updatePage$6(Ljava/lang/String;Lcom/yori3o/boss_checklist/common/client/boss/BossEntry;Ljava/lang/Boolean;)V",
            at = @At("RETURN"),
            remap = false,
            require = 0
    )
    private void bossarenaguard$refreshProgressionOnCheckboxToggle(
            String id,
            BossEntry entry,
            Boolean selected,
            CallbackInfo ci
    ) {
        if (DynamicConfigHandler.client() != null && DynamicConfigHandler.client().progressionMode) {
            if (Boolean.TRUE.equals(selected) && entry != null && entry.progress() != null) {
                entry.progress().alreadyAnimated = false;
            }
            this.invalidateNames = true;
            this.reloadNamesMap();
            this.updateSearch(this.searchBox != null ? this.searchBox.getValue() : null);
        }
    }
}
