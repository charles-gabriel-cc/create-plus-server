package com.createplus.systems.economy;

import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.core.NumismaticsBridge;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player & Admin commands for the Economy module:
 * /saldo, /loja, /vender, /ah [vender|caixa|meus], /eco admin ...
 */
public final class EconomyCommands {
    private EconomyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, EconomyService eco) {
        // /saldo
        dispatcher.register(Commands.literal("saldo")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    int balance = NumismaticsBridge.getBalance(player.getUUID());
                    player.sendSystemMessage(ItemCodecUtil.text(
                            "Saldo bancario (Numismatics): " + NumismaticsBridge.formatSpurs(balance),
                            ChatFormatting.GOLD
                    ));
                    return 1;
                }));

        // /loja
        dispatcher.register(Commands.literal("loja")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!ensureReady(player, eco)) return 0;
                    EconomyGuis.openShopCategories(player, eco);
                    return 1;
                }));

        // /vender
        dispatcher.register(Commands.literal("vender")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!ensureReady(player, eco)) return 0;
                    EconomyGuis.openSellBox(player, eco);
                    return 1;
                })
                .then(Commands.literal("contratos")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!ensureReady(player, eco)) return 0;
                            EconomyGuis.openContractsGui(player, eco);
                            return 1;
                        })));

        // /ah
        dispatcher.register(Commands.literal("ah")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!ensureReady(player, eco)) return 0;
                    EconomyGuis.openAuctionHouse(player, eco, 0);
                    return 1;
                })
                .then(Commands.literal("vender")
                        .then(Commands.argument("preco_spurs", IntegerArgumentType.integer(EconomyService.AH_MIN_PRICE, EconomyService.AH_MAX_PRICE))
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (!ensureReady(player, eco)) return 0;
                                    int price = IntegerArgumentType.getInteger(ctx, "preco_spurs");
                                    eco.createListingFromHand(player, price);
                                    return 1;
                                })))
                .then(Commands.literal("caixa")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!ensureReady(player, eco)) return 0;
                            EconomyGuis.openMailbox(player, eco);
                            return 1;
                        }))
                .then(Commands.literal("meus")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!ensureReady(player, eco)) return 0;
                            EconomyGuis.openMyListings(player, eco);
                            return 1;
                        })));

        // /eco admin ...
        dispatcher.register(Commands.literal("eco")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("admin")
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    MinecraftServer server = src.getServer();
                                    eco.reloadShop().whenComplete((count, err) -> server.execute(() -> {
                                        if (err != null) {
                                            src.sendFailure(ItemCodecUtil.text("Falha ao recarregar loja: " + err.getMessage(), ChatFormatting.RED));
                                        } else {
                                            src.sendSuccess(() -> ItemCodecUtil.text("Loja recarregada do MariaDB (" + count + " itens ativos).", ChatFormatting.GREEN), true);
                                        }
                                    }));
                                    return 1;
                                }))
                        .then(Commands.literal("dar")
                                .then(Commands.argument("jogador", EntityArgument.player())
                                        .then(Commands.argument("spurs", IntegerArgumentType.integer(1, 10_000_000))
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "jogador");
                                                    int spurs = IntegerArgumentType.getInteger(ctx, "spurs");
                                                    NumismaticsBridge.deposit(target.getUUID(), spurs);
                                                    eco.logAsync(target.getUUID(), target.getGameProfile().getName(), "ADMIN_GIVE", spurs,
                                                            null, null, "by=" + ctx.getSource().getTextName());
                                                    ctx.getSource().sendSuccess(() -> ItemCodecUtil.text(
                                                            "Creditado " + NumismaticsBridge.formatSpurs(spurs) + " para " + target.getGameProfile().getName(),
                                                            ChatFormatting.GREEN
                                                    ), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("tirar")
                                .then(Commands.argument("jogador", EntityArgument.player())
                                        .then(Commands.argument("spurs", IntegerArgumentType.integer(1, 10_000_000))
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "jogador");
                                                    int spurs = IntegerArgumentType.getInteger(ctx, "spurs");
                                                    if (!NumismaticsBridge.deduct(target.getUUID(), spurs)) {
                                                        ctx.getSource().sendFailure(ItemCodecUtil.text("Jogador nao tem saldo suficiente.", ChatFormatting.RED));
                                                        return 0;
                                                    }
                                                    eco.logAsync(target.getUUID(), target.getGameProfile().getName(), "ADMIN_TAKE", -spurs,
                                                            null, null, "by=" + ctx.getSource().getTextName());
                                                    ctx.getSource().sendSuccess(() -> ItemCodecUtil.text(
                                                            "Debitado " + NumismaticsBridge.formatSpurs(spurs) + " de " + target.getGameProfile().getName(),
                                                            ChatFormatting.YELLOW
                                                    ), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("pausar_ah")
                                .then(Commands.argument("pausado", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            boolean paused = BoolArgumentType.getBool(ctx, "pausado");
                                            eco.setAhPaused(paused);
                                            ctx.getSource().sendSuccess(() -> ItemCodecUtil.text(
                                                    "Mercado /ah " + (paused ? "PAUSADO" : "REATIVADO") + ".",
                                                    paused ? ChatFormatting.RED : ChatFormatting.GREEN
                                            ), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("contratos_reset")
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    MinecraftServer server = src.getServer();
                                    eco.resetTodayContracts().whenComplete((v, err) -> server.execute(() -> {
                                        if (err != null) {
                                            src.sendFailure(ItemCodecUtil.text("Erro ao resetar contratos: " + err.getMessage(), ChatFormatting.RED));
                                        } else {
                                            src.sendSuccess(() -> ItemCodecUtil.text("Contratos diarios regenerados!", ChatFormatting.GREEN), true);
                                        }
                                    }));
                                    return 1;
                                }))
                        .then(Commands.literal("log")
                                .executes(ctx -> showLogs(ctx.getSource(), eco, 10))
                                .then(Commands.argument("limite", IntegerArgumentType.integer(1, 30))
                                        .executes(ctx -> showLogs(ctx.getSource(), eco, IntegerArgumentType.getInteger(ctx, "limite")))))));
    }

    private static int showLogs(CommandSourceStack src, EconomyService eco, int limit) {
        MinecraftServer server = src.getServer();
        eco.getRecentLogs(limit).whenComplete((lines, err) -> server.execute(() -> {
            if (err != null || lines == null) {
                src.sendFailure(ItemCodecUtil.text("Erro ao consultar cp_economy_log.", ChatFormatting.RED));
                return;
            }
            src.sendSuccess(() -> ItemCodecUtil.text("=== Ultimas " + lines.size() + " transacoes ===", ChatFormatting.GOLD), false);
            for (String line : lines) {
                src.sendSuccess(() -> ItemCodecUtil.text(line, ChatFormatting.GRAY), false);
            }
        }));
        return 1;
    }

    private static boolean ensureReady(ServerPlayer player, EconomyService eco) {
        if (!eco.isReady()) {
            player.sendSystemMessage(ItemCodecUtil.text(
                    "Sistema de economia indisponivel (MariaDB nao configurado).",
                    ChatFormatting.RED
            ));
            return false;
        }
        return true;
    }
}
