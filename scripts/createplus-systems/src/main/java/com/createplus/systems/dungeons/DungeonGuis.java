package com.createplus.systems.dungeons;

import com.createplus.systems.core.ChestGui;
import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.core.NumismaticsBridge;
import com.createplus.systems.dungeons.DungeonManager.RecordView;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server-side Chest GUI for /dungeon.
 */
public final class DungeonGuis {
    private DungeonGuis() {
    }

    public static void openDungeonMenu(ServerPlayer player, DungeonManager manager) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        manager.getAllRecords().whenComplete((records, err) -> server.execute(() -> {
            Map<String, RecordView> recMap = records == null ? Map.of() : records;
            ChestGui gui = ChestGui.menu(ItemCodecUtil.text("Dungeons Instanciadas (/dungeon)", ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));

            int[] slots = {11, 13, 15, 29, 31, 33};
            int idx = 0;
            for (DungeonDefinition def : manager.definitions().values()) {
                if (idx >= slots.length) break;
                Item iconItem = switch (def.tier()) {
                    case 1 -> Items.IRON_SWORD;
                    case 2 -> Items.DIAMOND_SWORD;
                    default -> Items.NETHERITE_SWORD;
                };
                List<net.minecraft.network.chat.Component> lore = new ArrayList<>();
                lore.add(ItemCodecUtil.text(def.description(), ChatFormatting.GRAY));
                lore.add(ItemCodecUtil.text("Tier: " + def.tier() + " | Party max: " + def.maxPlayers() + " jogadores", ChatFormatting.AQUA));
                lore.add(ItemCodecUtil.text(
                        "Chave: " + (def.keyItemId() == null || def.keyItemId().isBlank() ? "Gratuita (Piloto)" : def.keyCount() + "x " + def.keyItemId()),
                        ChatFormatting.YELLOW
                ));
                lore.add(ItemCodecUtil.text("Recompensa base: " + NumismaticsBridge.formatSpurs(def.rewardSpurs()), ChatFormatting.GOLD));
                if (def.environmentalHazard()) {
                    lore.add(ItemCodecUtil.text("! Ambiente hostil: traga banquetes/pocoes do Chef!", ChatFormatting.RED));
                }
                RecordView rec = recMap.get(def.id());
                if (rec != null) {
                    lore.add(ItemCodecUtil.text("Recorde: " + DungeonManager.formatDuration(rec.bestTimeSec()) + " (" + rec.holders() + ")", ChatFormatting.LIGHT_PURPLE));
                }
                lore.add(ItemCodecUtil.text("Clique para iniciar instancia!", ChatFormatting.GREEN, ChatFormatting.BOLD));

                ItemStack icon = ItemCodecUtil.icon(iconItem, ItemCodecUtil.text(def.displayName(), ChatFormatting.YELLOW, ChatFormatting.BOLD), lore);
                gui.slot(slots[idx++], icon, p -> {
                    p.closeContainer();
                    manager.startRun(p, def.id());
                });
            }

            if (manager.getPlayerInstance(player.getUUID()) != null) {
                gui.slot(49, ItemCodecUtil.icon(
                        Items.RED_BED,
                        ItemCodecUtil.text("Sair da Dungeon Atual", ChatFormatting.RED, ChatFormatting.BOLD),
                        List.of(ItemCodecUtil.text("Retorna imediatamente ao ponto de origem", ChatFormatting.GRAY))
                ), p -> {
                    p.closeContainer();
                    manager.leaveRun(p);
                });
            }
            gui.fillBottomBar();
            gui.open(player);
        }));
    }
}
