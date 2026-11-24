package com.createplus.systems.professions;

import com.createplus.systems.core.ChestGui;
import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.professions.ProfessionManager.PlayerProfessionState;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Server-side Chest GUI for /profissoes.
 */
public final class ProfessionGuis {
    private ProfessionGuis() {
    }

    public static void openProfessionsMenu(ServerPlayer player, ProfessionManager manager) {
        ChestGui gui = ChestGui.menu(ItemCodecUtil.text("Profissoes por Maestria (/profissoes)", ChatFormatting.AQUA, ChatFormatting.BOLD));
        int[] slots = {11, 13, 15, 29, 33};
        ProfessionType[] all = ProfessionType.values();

        for (int i = 0; i < all.length && i < slots.length; i++) {
            ProfessionType type = all[i];
            PlayerProfessionState st = manager.getState(player.getUUID(), type);
            int nextReq = ProfessionType.xpRequiredForNextLevel(st.level);
            String xpLine = (st.level >= ProfessionType.MAX_LEVEL)
                    ? "MAXIMO (Mestre!)"
                    : (st.xp + " / " + nextReq + " XP");

            ItemStack icon = ItemCodecUtil.icon(
                    type.icon,
                    ItemCodecUtil.text(type.displayName, ChatFormatting.YELLOW, ChatFormatting.BOLD),
                    List.of(
                            ItemCodecUtil.text(type.description, ChatFormatting.GRAY),
                            ItemCodecUtil.text("--------------------------", ChatFormatting.DARK_GRAY),
                            ItemCodecUtil.text("Nivel: " + st.level + " / " + ProfessionType.MAX_LEVEL, ChatFormatting.GREEN),
                            ItemCodecUtil.text("Patente: " + ProfessionType.tierLabel(st.tier), ChatFormatting.GOLD),
                            ItemCodecUtil.text("Progresso: " + xpLine, ChatFormatting.AQUA),
                            ItemCodecUtil.text("Clique para ver o ranking /profissoes top " + type.id, ChatFormatting.DARK_GRAY)
                    )
            );
            gui.slot(slots[i], icon, p -> {
                p.closeContainer();
                ProfessionCommands.sendRanking(p.createCommandSourceStack(), manager, type);
            });
        }

        gui.slot(49, ItemCodecUtil.icon(
                Items.BOOK,
                ItemCodecUtil.text("Como evoluir?", ChatFormatting.GOLD, ChatFormatting.BOLD),
                List.of(
                        ItemCodecUtil.text("Voce pode masterizar TODAS as 5 profissoes!", ChatFormatting.GREEN),
                        ItemCodecUtil.text("Pratique cada oficio (novas receitas/itens dao bonus)", ChatFormatting.GRAY),
                        ItemCodecUtil.text("Trava de Tier ativa: " + (manager.isGatingEnforced() ? "SIM" : "NAO"), ChatFormatting.YELLOW)
                )
        ));
        gui.fillBottomBar();
        gui.open(player);
    }
}
