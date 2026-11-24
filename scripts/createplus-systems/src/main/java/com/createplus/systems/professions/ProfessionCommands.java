package com.createplus.systems.professions;

import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.professions.ProfessionManager.RankEntry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commands: /profissoes, /profissoes top <id>, /profissoes admin dar_xp | set_tier | gating | reload
 */
public final class ProfessionCommands {
    private ProfessionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ProfessionManager manager) {
        dispatcher.register(Commands.literal("profissoes")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ProfessionGuis.openProfessionsMenu(player, manager);
                    return 1;
                })
                .then(Commands.literal("top")
                        .then(Commands.argument("profissao", StringArgumentType.word())
                                .executes(ctx -> {
                                    ProfessionType type = ProfessionType.byId(StringArgumentType.getString(ctx, "profissao"));
                                    if (type == null) {
                                        ctx.getSource().sendFailure(ItemCodecUtil.text(
                                                "Profissao invalida. Use: engenheiro, ferreiro, taumaturgo, chef, naturalista.",
                                                ChatFormatting.RED
                                        ));
                                        return 0;
                                    }
                                    sendRanking(ctx.getSource(), manager, type);
                                    return 1;
                                })))
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    manager.reloadRules(ctx.getSource().getServer().getServerDirectory());
                                    ctx.getSource().sendSuccess(() -> ItemCodecUtil.text("Regras de profissoes recarregadas!", ChatFormatting.GREEN), true);
                                    return 1;
                                }))
                        .then(Commands.literal("gating")
                                .then(Commands.argument("ativo", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            boolean on = BoolArgumentType.getBool(ctx, "ativo");
                                            manager.setGatingEnforced(on);
                                            ctx.getSource().sendSuccess(() -> ItemCodecUtil.text(
                                                    "Trava de Tier por profissao: " + (on ? "ATIVADA" : "DESATIVADA"),
                                                    on ? ChatFormatting.GREEN : ChatFormatting.YELLOW
                                            ), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("dar_xp")
                                .then(Commands.argument("jogador", EntityArgument.player())
                                        .then(Commands.argument("profissao", StringArgumentType.word())
                                                .then(Commands.argument("xp", IntegerArgumentType.integer(1, 100000))
                                                        .executes(ctx -> {
                                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "jogador");
                                                            ProfessionType type = ProfessionType.byId(StringArgumentType.getString(ctx, "profissao"));
                                                            if (type == null) {
                                                                ctx.getSource().sendFailure(ItemCodecUtil.text("Profissao invalida.", ChatFormatting.RED));
                                                                return 0;
                                                            }
                                                            int xp = IntegerArgumentType.getInteger(ctx, "xp");
                                                            manager.awardXp(target, type, xp, null, 1);
                                                            ctx.getSource().sendSuccess(() -> ItemCodecUtil.text(
                                                                    "Entregue +" + xp + " XP em " + type.displayName + " para " + target.getGameProfile().getName(),
                                                                    ChatFormatting.GREEN
                                                            ), true);
                                                            return 1;
                                                        })))))
                        .then(Commands.literal("set_tier")
                                .then(Commands.argument("jogador", EntityArgument.player())
                                        .then(Commands.argument("profissao", StringArgumentType.word())
                                                .then(Commands.argument("tier", IntegerArgumentType.integer(1, 4))
                                                        .executes(ctx -> {
                                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "jogador");
                                                            ProfessionType type = ProfessionType.byId(StringArgumentType.getString(ctx, "profissao"));
                                                            if (type == null) {
                                                                ctx.getSource().sendFailure(ItemCodecUtil.text("Profissao invalida.", ChatFormatting.RED));
                                                                return 0;
                                                            }
                                                            int tier = IntegerArgumentType.getInteger(ctx, "tier");
                                                            manager.setTierAdmin(target, type, tier);
                                                            ctx.getSource().sendSuccess(() -> ItemCodecUtil.text(
                                                                    target.getGameProfile().getName() + " agora e " + type.displayName + " Tier " + tier,
                                                                    ChatFormatting.GREEN
                                                            ), true);
                                                            return 1;
                                                        })))))));
    }

    public static void sendRanking(CommandSourceStack src, ProfessionManager manager, ProfessionType type) {
        MinecraftServer server = src.getServer();
        manager.getRanking(type).whenComplete((list, err) -> server.execute(() -> {
            if (err != null || list == null) {
                src.sendFailure(ItemCodecUtil.text("Erro ao consultar ranking no MariaDB.", ChatFormatting.RED));
                return;
            }
            src.sendSuccess(() -> ItemCodecUtil.text("=== Top " + type.displayName + " ===", ChatFormatting.GOLD, ChatFormatting.BOLD), false);
            if (list.isEmpty()) {
                src.sendSuccess(() -> ItemCodecUtil.text("  Nenhum registro ainda.", ChatFormatting.GRAY), false);
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                RankEntry r = list.get(i);
                int rank = i + 1;
                src.sendSuccess(() -> ItemCodecUtil.text(
                        "  #" + rank + " " + r.playerName() + " — Nv. " + r.level() + " (" + ProfessionType.tierLabel(r.tier()) + ")",
                        ChatFormatting.YELLOW
                ), false);
            }
        }));
    }
}
