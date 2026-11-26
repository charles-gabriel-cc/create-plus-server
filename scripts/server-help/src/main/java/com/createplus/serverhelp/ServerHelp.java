package com.createplus.serverhelp;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(ServerHelp.MOD_ID)
public final class ServerHelp {
    public static final String MOD_ID = "createplus_server_help";

    public ServerHelp() {
        NeoForge.EVENT_BUS.addListener(ServerHelp::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("comandos").executes(ctx -> sendHelp(ctx.getSource()))
        );
        event.getDispatcher().register(
            Commands.literal("ajuda").executes(ctx -> sendHelp(ctx.getSource()))
        );
    }

    private static int sendHelp(CommandSourceStack source) {
        MutableComponent msg = Component.empty();

        msg.append(Component.literal("══════════ ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("Create+ • Comandos do Servidor").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(" ══════════\n").withStyle(ChatFormatting.DARK_GRAY));

        msg.append(section("Teleporte"));
        msg.append(cmd("/tpa <jogador>", "/tpa ", "Pede para ir até um jogador", "warmup 4s • cooldown 45s"));
        msg.append(cmd("/tpahere <jogador>", "/tpahere ", "Convida um jogador até você", "warmup 4s • cooldown 45s"));
        msg.append(cmd("/tpaccept", "/tpaccept", "Aceita um pedido de teleporte", null));
        msg.append(cmd("/tpdeny", "/tpdeny", "Recusa um pedido de teleporte", null));
        msg.append(cmd("/sethome", "/sethome", "Marca sua home atual", "máx. 1 home"));
        msg.append(cmd("/home", "/home", "Vai para sua home", "warmup 5s • cooldown 4 min"));
        msg.append(cmd("/delhome", "/delhome", "Apaga sua home", null));
        msg.append(cmd("/spawn", "/spawn", "Volta para o spawn", "warmup 5s • cooldown 1m30s"));
        msg.append(cmd("/warp <nome>", "/warp ", "Vai para um warp público", "warmup 5s • cooldown 1 min"));

        msg.append(section("Terrenos e Party"));
        msg.append(cmd("/claim", "/claim", "Protege o chunk atual", null));
        msg.append(cmd("/unclaim", "/unclaim", "Remove a proteção do chunk atual", null));
        msg.append(cmd("/claims", "/claims", "Gerencia seus terrenos protegidos", null));
        msg.append(cmd("/party", "/party", "Cria ou gerencia sua party", null));
        msg.append(cmd("/pchat <msg>", "/pchat ", "Envia mensagem no chat da party", null));

        msg.append(Component.literal("\nDica: clique em qualquer comando para preencher no chat.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));

        source.sendSuccess(() -> msg, false);
        return 1;
    }

    private static MutableComponent section(String title) {
        return Component.literal("\n▸ " + title + "\n").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
    }

    private static MutableComponent cmd(String label, String suggest, String description, String note) {
        MutableComponent line = Component.literal("  ")
            .append(Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggest))
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Clique para usar " + label).withStyle(ChatFormatting.YELLOW)
                ))))
            .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(description).withStyle(ChatFormatting.WHITE));
        if (note != null) {
            line.append(Component.literal(" (" + note + ")").withStyle(ChatFormatting.GRAY));
        }
        line.append(Component.literal("\n"));
        return line;
    }
}
