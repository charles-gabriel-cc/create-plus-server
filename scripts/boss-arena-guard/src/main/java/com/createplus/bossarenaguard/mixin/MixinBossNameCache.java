package com.createplus.bossarenaguard.mixin;

import com.yori3o.boss_checklist.common.client.boss.BossEntry;
import com.yori3o.boss_checklist.common.client.boss.BossProgress;
import com.yori3o.boss_checklist.common.client.data.BossNameCache;
import com.yori3o.boss_checklist.common.client.data.BossService;
import com.yori3o.boss_checklist.common.config.ClientConfig;
import com.yori3o.boss_checklist.common.config.DynamicConfigHandler;
import com.yori3o.boss_checklist.common.util.TextUtil;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Pseudo
@Mixin(value = BossNameCache.class, remap = false)
public abstract class MixinBossNameCache {

    @Inject(method = "rebuild()V", at = @At("RETURN"), remap = false, require = 0)
    private static void bossarenaguard$revealNextBossInProgression(CallbackInfo ci) {
        ClientConfig.Values cfg = DynamicConfigHandler.client();
        if (cfg == null || !cfg.progressionMode) {
            return;
        }

        List<BossEntry> visible = new ArrayList<>();
        for (BossEntry entry : BossService.all()) {
            int type = entry.definition().type();
            if (type == 1 && !cfg.showBosses) {
                continue;
            }
            if (type == 2 && !cfg.showMinibosses) {
                continue;
            }
            visible.add(entry);
        }

        if (visible.isEmpty()) {
            return;
        }

        int firstUndefeatedIdx = -1;
        int lastDefeatedIdx = -1;
        for (int i = 0; i < visible.size(); i++) {
            BossProgress progress = visible.get(i).progress();
            boolean done = progress.isDefeated() || progress.isMarkedAsDefeatedOnClient();
            if (done) {
                lastDefeatedIdx = i;
            } else if (firstUndefeatedIdx < 0) {
                firstUndefeatedIdx = i;
            }
        }

        int nextAfterLastDefeatedIdx = -1;
        if (lastDefeatedIdx >= 0) {
            for (int j = lastDefeatedIdx + 1; j < visible.size(); j++) {
                BossProgress progress = visible.get(j).progress();
                if (!progress.isDefeated() && !progress.isMarkedAsDefeatedOnClient()) {
                    nextAfterLastDefeatedIdx = j;
                    break;
                }
            }
        }

        for (int i = 0; i < visible.size(); i++) {
            BossEntry entry = visible.get(i);
            BossProgress progress = entry.progress();
            boolean shouldReveal = progress.isMarkedAsDefeatedOnClient()
                    || i == firstUndefeatedIdx
                    || i == nextAfterLastDefeatedIdx;
            if (shouldReveal) {
                String id = entry.id();
                String key = "boss_checklist.boss." + id.replace(":", "_");
                String fullName = Component.translatable(key).getString();
                Component truncated = TextUtil.truncateText(fullName, 103);
                BossNameCache.CACHE.put(id, Component.literal(fullName));
                BossNameCache.CACHE_TRUNCATED.put(id, Component.literal(truncated.getString()));
            }
        }
    }
}
