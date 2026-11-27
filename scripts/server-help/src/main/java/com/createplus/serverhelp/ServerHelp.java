package com.createplus.serverhelp;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@Mod(ServerHelp.MOD_ID)
public final class ServerHelp {
    public static final String MOD_ID = "createplus_server_help";

    private static volatile boolean tpaReflectionInit = false;
    private static Method tpaRequestsMethod;
    private static Method tpaRequestSourceMethod;
    private static Method tpaRequestHereMethod;
    private static Method playerDataUuidMethod;

    public ServerHelp() {
        NeoForge.EVENT_BUS.addListener(ServerHelp::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ServerHelp::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ServerHelp::onCommand);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("comandos").executes(ctx -> sendHelp(ctx.getSource()))
        );
        event.getDispatcher().register(
            Commands.literal("ajuda").executes(ctx -> sendHelp(ctx.getSource()))
        );
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MutableComponent msg = Component.empty();
        msg.append(Component.literal("══════════ ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("Bem-vindo ao Certamente é um servidor!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(" ══════════\n").withStyle(ChatFormatting.DARK_GRAY));

        msg.append(Component.literal("Comandos úteis: ").withStyle(ChatFormatting.GRAY))
            .append(quickCmd("/tpa", "/tpa "))
            .append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
            .append(quickCmd("/tpahere", "/tpahere "))
            .append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
            .append(quickCmd("/sethome", "/sethome "))
            .append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
            .append(quickCmd("/home", "/home"))
            .append(Component.literal(" (máx. 2)").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n"));

        msg.append(Component.literal("Use ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("/comandos").withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/comandos"))
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Clique para ver todos os comandos").withStyle(ChatFormatting.YELLOW)
                ))))
            .append(Component.literal(" ou ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("/ajuda").withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ajuda"))
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Clique para ver todos os comandos").withStyle(ChatFormatting.YELLOW)
                ))))
            .append(Component.literal(" para ver a lista completa.").withStyle(ChatFormatting.GRAY));

        player.displayClientMessage(msg, false);
    }

    private static void onCommand(CommandEvent event) {
        if (event.isCanceled()) {
            return;
        }
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        List<ParsedCommandNode<CommandSourceStack>> nodes = parse.getContext().getNodes();
        if (nodes.isEmpty()) {
            return;
        }
        String root = nodes.get(0).getNode().getName();
        if (!"tpaccept".equals(root) && !"ftbessentials:tpaccept".equals(root)) {
            return;
        }
        if (!(parse.getContext().getSource().getEntity() instanceof ServerPlayer accepter)) {
            return;
        }
        ParsedArgument<CommandSourceStack, ?> idArg = parse.getContext().getArguments().get("id");
        if (idArg == null || idArg.getResult() == null) {
            return;
        }
        UUID requestId;
        try {
            requestId = UUID.fromString(idArg.getResult().toString());
        } catch (IllegalArgumentException ignored) {
            return;
        }

        if (!ensureTpaReflection()) {
            return;
        }

        try {
            Map<?, ?> requests = (Map<?, ?>) tpaRequestsMethod.invoke(null);
            if (requests == null) {
                return;
            }
            Object request = requests.get(requestId);
            if (request == null) {
                return;
            }
            Object sourceData = tpaRequestSourceMethod.invoke(request);
            UUID sourceUuid = (UUID) playerDataUuidMethod.invoke(sourceData);
            boolean isHere = (boolean) tpaRequestHereMethod.invoke(request);

            MinecraftServer server = accepter.server;
            ServerPlayer requester = server.getPlayerList().getPlayer(sourceUuid);
            if (requester == null) {
                return;
            }
            String requesterName = requester.getScoreboardName();
            String accepterName = accepter.getScoreboardName();

            server.tell(new TickTask(server.getTickCount(), () -> {
                if (requests.containsKey(requestId)) {
                    return;
                }
                if (!accepter.hasDisconnected()) {
                    MutableComponent toAccepter = Component.literal("✔ ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                        .append(Component.literal("Pedido aceito! ").withStyle(ChatFormatting.GREEN));
                    if (isHere) {
                        toAccepter.append(Component.literal("Teleportando você até ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(requesterName).withStyle(ChatFormatting.AQUA))
                            .append(Component.literal("...").withStyle(ChatFormatting.GRAY));
                    } else {
                        toAccepter.append(Component.literal(requesterName).withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(" está se teleportando até você...").withStyle(ChatFormatting.GRAY));
                    }
                    accepter.displayClientMessage(toAccepter, false);
                }

                ServerPlayer onlineRequester = server.getPlayerList().getPlayer(sourceUuid);
                if (onlineRequester != null && !onlineRequester.hasDisconnected()) {
                    MutableComponent toRequester = Component.literal("✔ ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                        .append(Component.literal(accepterName).withStyle(ChatFormatting.AQUA));
                    if (isHere) {
                        toRequester.append(Component.literal(" aceitou seu convite e está vindo até você!").withStyle(ChatFormatting.GREEN));
                    } else {
                        toRequester.append(Component.literal(" aceitou seu pedido de teleporte!").withStyle(ChatFormatting.GREEN));
                    }
                    onlineRequester.displayClientMessage(toRequester, false);
                }
            }));
        } catch (ReflectiveOperationException ignored) {
            // FTB Essentials unavailable or changed API; skip silently
        }
    }

    private static boolean ensureTpaReflection() {
        if (tpaReflectionInit) {
            return tpaRequestsMethod != null;
        }
        tpaReflectionInit = true;
        try {
            Class<?> tpaCommandClass = Class.forName("dev.ftb.mods.ftbessentials.commands.impl.teleporting.TPACommand");
            Class<?> tpaRequestClass = Class.forName("dev.ftb.mods.ftbessentials.api.records.TPARequest");
            Class<?> playerDataClass = Class.forName("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
            tpaRequestsMethod = tpaCommandClass.getMethod("requests");
            tpaRequestSourceMethod = tpaRequestClass.getMethod("source");
            tpaRequestHereMethod = tpaRequestClass.getMethod("here");
            playerDataUuidMethod = playerDataClass.getMethod("getUuid");
            return true;
        } catch (ReflectiveOperationException ignored) {
            tpaRequestsMethod = null;
            return false;
        }
    }

    private static int sendHelp(CommandSourceStack source) {
        MutableComponent msg = Component.empty();

        msg.append(Component.literal("══════════ ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("Certamente é um servidor • Comandos").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(" ══════════\n").withStyle(ChatFormatting.DARK_GRAY));

        msg.append(section("Teleporte"));
        msg.append(cmd("/tpa <jogador>", "/tpa ", "Pede para ir até um jogador", "warmup 4s"));
        msg.append(cmd("/tpahere <jogador>", "/tpahere ", "Convida um jogador até você", "warmup 4s"));
        msg.append(cmd("/tpaccept", "/tpaccept", "Aceita um pedido de teleporte", null));
        msg.append(cmd("/tpdeny", "/tpdeny", "Recusa um pedido de teleporte", null));
        msg.append(cmd("/sethome [nome]", "/sethome ", "Marca sua home atual", "máx. 2 homes"));
        msg.append(cmd("/home [nome]", "/home", "Vai para sua home", "warmup 5s"));
        msg.append(cmd("/delhome [nome]", "/delhome ", "Apaga uma home", null));
        msg.append(cmd("/warp <nome>", "/warp ", "Vai para um warp público", "warmup 5s"));

        msg.append(Component.literal("\nDica: clique em qualquer comando para preencher no chat.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));

        source.sendSuccess(() -> msg, false);
        return 1;
    }

    private static MutableComponent quickCmd(String label, String suggest) {
        return Component.literal(label).withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggest))
            .withHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                Component.literal("Clique para usar " + label).withStyle(ChatFormatting.YELLOW)
            )));
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
