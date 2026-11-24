package com.createplus.systems.dungeons;

import com.createplus.systems.core.ItemCodecUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commands: /dungeon, /dungeon entrar <id>, /dungeon sair, /dungeon admin reload | reset_cd <player>
 */
public final class DungeonCommands {
    private DungeonCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, DungeonManager manager) {
        dispatcher.register(Commands.literal("dungeon")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    DungeonGuis.openDungeonMenu(player, manager);
                    return 1;
                })
                .then(Commands.literal("entrar")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    manager.startRun(player, StringArgumentType.getString(ctx, "id"));
                                    return 1;
                                })))
                .then(Commands.literal("sair")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            manager.leaveRun(player);
                            return 1;
                        }))
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    manager.reloadDefinitions(ctx.getSource().getServer().getServerDirectory());
                                    ctx.getSource().sendSuccess(() -> ItemCodecUtil.text(
                                            "Dungeons recarregadas (" + manager.definitions().size() + " definicoes).",
                                            ChatFormatting.GREEN
                                    ), true);
                                    return 1;
                                }))
                        .then(Commands.literal("reset_cd")
                                .then(Commands.argument("jogador", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "jogador");
                                            manager.resetCooldowns(target.getUUID());
                                            ctx.getSource().sendSuccess(() -> ItemCodecUtil.text(
                                                    "Cooldowns de dungeon resetados para " + target.getGameProfile().getName() + ".",
                                                    ChatFormatting.GREEN
                                            ), true);
                                            return 1;
                                        })))));
    }
}
