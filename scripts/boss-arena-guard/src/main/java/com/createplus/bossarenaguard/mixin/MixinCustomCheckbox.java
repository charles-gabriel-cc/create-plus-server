package com.createplus.bossarenaguard.mixin;

import com.yori3o.boss_checklist.common.client.gui.ConfigScreen;
import com.yori3o.boss_checklist.common.client.gui.widget.CustomCheckbox;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = CustomCheckbox.class, remap = false)
public abstract class MixinCustomCheckbox {

    @Inject(method = "invertValue()V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void bossarenaguard$lockSettingsScreenCheckboxes(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen instanceof ConfigScreen) {
            ci.cancel();
        }
    }
}
