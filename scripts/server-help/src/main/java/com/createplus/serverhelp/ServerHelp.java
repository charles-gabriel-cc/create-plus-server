package com.createplus.serverhelp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@Mod(ServerHelp.MOD_ID)
public final class ServerHelp {
    public static final String MOD_ID = "createplus_server_help";
    private static final String DIVIDER = "════════════════════════════";
    private static final String PT_BR_HANDLER = "createplus_ftb_pt_br";
    private static final Pattern FORMAT_PATTERN = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%])");
    private static final Map<String, String> PT_BR_MESSAGES = loadPtBrMessages();

    private static volatile boolean tpaReflectionInit = false;
    private static Method tpaRequestsMethod;
    private static Method tpaRequestSourceMethod;
    private static Method tpaRequestHereMethod;
    private static Method playerDataUuidMethod;

    private static volatile boolean channelReflectionInit = false;
    private static Field listenerConnectionField;
    private static Field connectionChannelField;

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
        installPtBrInterceptor(player);

        MutableComponent msg = Component.empty();
        msg.append(Component.literal(DIVIDER + "\n").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("  Bem-vindo ao ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal("Certamente é um servidor!\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal("  ").withStyle(ChatFormatting.GRAY))
            .append(quickCmd("/tpa", "/tpa "))
            .append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
            .append(quickCmd("/tpahere", "/tpahere "))
            .append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
            .append(quickCmd("/sethome", "/sethome "))
            .append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
            .append(quickCmd("/home", "/home"))
            .append(Component.literal(" (máx. 2)\n").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("  Use ").withStyle(ChatFormatting.GRAY))
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
            .append(Component.literal(" para ver detalhes.\n").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(DIVIDER).withStyle(ChatFormatting.DARK_GRAY));

        player.displayClientMessage(msg, false);
    }

    private static void installPtBrInterceptor(ServerPlayer player) {
        if (!ensureChannelReflection()) {
            return;
        }
        try {
            Connection connection = (Connection) listenerConnectionField.get(player.connection);
            if (connection == null) {
                return;
            }
            Channel channel = (Channel) connectionChannelField.get(connection);
            if (channel == null) {
                return;
            }
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(PT_BR_HANDLER) != null || pipeline.get("packet_handler") == null) {
                return;
            }
            pipeline.addBefore("packet_handler", PT_BR_HANDLER, new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    if (msg instanceof ClientboundSystemChatPacket packet) {
                        Component translated = translateFtbEssentials(packet.content());
                        if (translated != packet.content()) {
                            msg = new ClientboundSystemChatPacket(translated, packet.overlay());
                        }
                    }
                    super.write(ctx, msg, promise);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Do not block player login if pipeline differs
        }
    }

    private static boolean ensureChannelReflection() {
        if (channelReflectionInit) {
            return listenerConnectionField != null && connectionChannelField != null;
        }
        channelReflectionInit = true;
        try {
            Field connField = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
            connField.setAccessible(true);
            Field chanField = Connection.class.getDeclaredField("channel");
            chanField.setAccessible(true);
            listenerConnectionField = connField;
            connectionChannelField = chanField;
            return true;
        } catch (ReflectiveOperationException ignored) {
            listenerConnectionField = null;
            connectionChannelField = null;
            return false;
        }
    }

    private static Component translateFtbEssentials(Component component) {
        if (component == null) {
            return null;
        }
        boolean changed = false;

        Style style = component.getStyle();
        HoverEvent hover = style.getHoverEvent();
        if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
            Component hoverText = hover.getValue(HoverEvent.Action.SHOW_TEXT);
            Component translatedHover = translateFtbEssentials(hoverText);
            if (translatedHover != hoverText) {
                style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, translatedHover));
                changed = true;
            }
        }

        MutableComponent base = null;
        if (component.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            String template = key.startsWith("ftbessentials.") ? PT_BR_MESSAGES.get(key) : null;
            if (template != null) {
                base = formatTemplate(template, tc.getArgs()).withStyle(style);
                changed = true;
            } else {
                Object[] origArgs = tc.getArgs();
                Object[] newArgs = null;
                for (int i = 0; i < origArgs.length; i++) {
                    if (origArgs[i] instanceof Component argComp) {
                        Component translatedArg = translateFtbEssentials(argComp);
                        if (translatedArg != argComp) {
                            if (newArgs == null) {
                                newArgs = origArgs.clone();
                            }
                            newArgs[i] = translatedArg;
                        }
                    }
                }
                if (newArgs != null) {
                    base = Component.translatable(key, newArgs).withStyle(style);
                    changed = true;
                }
            }
        }

        List<Component> siblings = component.getSiblings();
        List<Component> translatedSiblings = null;
        for (int i = 0; i < siblings.size(); i++) {
            Component sib = siblings.get(i);
            Component translatedSib = translateFtbEssentials(sib);
            if (translatedSib != sib) {
                if (translatedSiblings == null) {
                    translatedSiblings = new java.util.ArrayList<>(siblings);
                }
                translatedSiblings.set(i, translatedSib);
                changed = true;
            }
        }

        if (!changed) {
            return component;
        }

        if (base == null) {
            base = component.plainCopy().withStyle(style);
        }
        List<Component> finalSiblings = translatedSiblings != null ? translatedSiblings : siblings;
        for (Component sib : finalSiblings) {
            base.append(sib);
        }
        return base;
    }

    private static MutableComponent formatTemplate(String template, Object[] args) {
        MutableComponent out = Component.empty();
        Matcher matcher = FORMAT_PATTERN.matcher(template);
        int lastEnd = 0;
        int nextArg = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                out.append(Component.literal(template.substring(lastEnd, matcher.start())));
            }
            String spec = matcher.group(2);
            if ("%".equals(spec)) {
                out.append(Component.literal("%"));
            } else {
                String indexStr = matcher.group(1);
                int idx = indexStr != null ? Integer.parseInt(indexStr) - 1 : nextArg++;
                if (idx >= 0 && idx < args.length) {
                    Object arg = args[idx];
                    if (arg instanceof Component argComp) {
                        out.append(translateFtbEssentials(argComp));
                    } else {
                        out.append(Component.literal(String.valueOf(arg)));
                    }
                } else {
                    out.append(Component.literal(matcher.group(0)));
                }
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < template.length()) {
            out.append(Component.literal(template.substring(lastEnd)));
        }
        return out;
    }

    private static Map<String, String> loadPtBrMessages() {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = ServerHelp.class.getClassLoader().getResourceAsStream("assets/ftbessentials/lang/pt_br.json")) {
            if (in != null) {
                JsonObject obj = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        map.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to built-in PT-BR map below
        }

        // Overrides em português natural para comandos ativos do servidor
        map.put("ftbessentials.unknown_dest", "Destino desconhecido!");
        map.put("ftbessentials.dimension_not_found", "Dimensão não encontrada!");
        map.put("ftbessentials.teleport_prevented", "Teleporte impedido!");
        map.put("ftbessentials.teleport.not_from_here", "Teleporte a partir desta dimensão não é permitido!");
        map.put("ftbessentials.teleport.not_to_here", "Teleporte para esta dimensão não é permitido!");
        map.put("ftbessentials.teleport.on_cooldown", "Aguarde para teleportar novamente! Cooldown: %s");
        map.put("ftbessentials.teleport.interrupted", "Teleporte interrompido!");
        map.put("ftbessentials.teleport.notify", "Teleportando em %ss");
        map.put("ftbessentials.home.set", "Home salva!");
        map.put("ftbessentials.home.too_many", "Você já atingiu o limite de homes!");
        map.put("ftbessentials.home.deleted", "Home apagada!");
        map.put("ftbessentials.home.not_found", "Home não encontrada!");
        map.put("ftbessentials.home.show_home", "%s: a %s de distância");
        map.put("ftbessentials.home.y_too_low", "Altura Y muito baixa! Homes devem ser salvas acima de Y=%s");
        map.put("ftbessentials.home.for_player", "Homes de %s");
        map.put("ftbessentials.none", "Nenhuma");
        map.put("ftbessentials.click_to_teleport", "Clique para teleportar");
        map.put("ftbessentials.unknown_player_id", "ID de jogador desconhecido: %s");
        map.put("ftbessentials.unknown_player", "Jogador desconhecido: %s");
        map.put("ftbessentials.tpa.already_sent", "Pedido de teleporte já enviado!");
        map.put("ftbessentials.tpa.notify", "Pedido de TPA! [ %s ➡ %s ]");
        map.put("ftbessentials.tpa.click_one", "Clique em uma opção: ");
        map.put("ftbessentials.tpa.accept", "Aceitar ✔");
        map.put("ftbessentials.tpa.accept.tooltip", "Clique para aceitar");
        map.put("ftbessentials.tpa.deny", "Recusar ❌");
        map.put("ftbessentials.tpa.deny.tooltip", "Clique para recusar");
        map.put("ftbessentials.tpa.request_sent", "Pedido de teleporte enviado!");
        map.put("ftbessentials.tpa.invalid_request", "Pedido inválido ou expirado!");
        map.put("ftbessentials.tpa.gone_offline", "O jogador saiu do servidor!");
        map.put("ftbessentials.tpa.denied", "Pedido de teleporte recusado!");
        map.put("ftbessentials.tpa.expired", "Pedido de teleporte expirado!");
        map.put("ftbessentials.warp.set", "Warp salvo!");
        map.put("ftbessentials.warp.deleted", "Warp apagado!");
        map.put("ftbessentials.warp.not_found", "Warp não encontrado!");
        return Map.copyOf(map);
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

        msg.append(Component.literal(DIVIDER + "\n").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("  Certamente é um servidor ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal("• Comandos\n").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(DIVIDER + "\n").withStyle(ChatFormatting.DARK_GRAY));

        msg.append(section("Teleporte"));
        msg.append(cmd("/tpa <jogador>", "/tpa ", "Ir até um jogador", "4s"));
        msg.append(cmd("/tpahere <jogador>", "/tpahere ", "Chamar até você", "4s"));
        msg.append(cmd("/sethome [nome]", "/sethome ", "Salvar home", "máx. 2"));
        msg.append(cmd("/home [nome]", "/home", "Ir para sua home", "5s"));
        msg.append(cmd("/delhome [nome]", "/delhome ", "Apagar uma home", null));
        msg.append(cmd("/listhomes", "/listhomes", "Ver suas homes", null));
        msg.append(cmd("/warp <nome>", "/warp ", "Ir para um warp", "5s"));

        msg.append(Component.literal("\n  Dica: clique em um comando para preencher.\n").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC))
            .append(Component.literal(DIVIDER).withStyle(ChatFormatting.DARK_GRAY));

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
