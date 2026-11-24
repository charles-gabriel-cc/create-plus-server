package com.createplus.systems.events;

import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.events.EventManager.ActiveEvent;
import com.createplus.systems.events.EventManager.ScheduledEvent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;

/**
 * Commands: /eventos, /evento iniciar <tipo> [duracao_min], /evento parar, /evento reload
 */
public final class EventCommands {
    private EventCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, EventManager manager) {
        dispatcher.register(Commands.literal("eventos")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ActiveEvent active = manager.activeEvent();
                    if (active != null) {
                        src.sendSuccess(() -> ItemCodecUtil.text(
                                "Evento ATIVO agora: " + active.displayName + " (restam " + EventManager.formatMinSec(active.remainingSec) + ")",
                                ChatFormatting.GREEN, ChatFormatting.BOLD
                        ), false);
                    } else {
                        src.sendSuccess(() -> ItemCodecUtil.text("Nenhum evento ativo no momento.", ChatFormatting.YELLOW), false);
                    }
                    src.sendSuccess(() -> ItemCodecUtil.text("=== Agenda Diaria (Horario de Brasilia) ===", ChatFormatting.GOLD, ChatFormatting.BOLD), false);
                    for (ScheduledEvent ev : manager.schedule()) {
                        String time = String.format("%02d:%02d", ev.hourBrt(), ev.minuteBrt());
                        src.sendSuccess(() -> ItemCodecUtil.text(
                                "  " + time + " — " + ev.displayName() + " (" + ev.durationMin() + " min)",
                                ChatFormatting.GRAY
                        ), false);
                    }
                    return 1;
                }));

        dispatcher.register(Commands.literal("evento")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("iniciar")
                        .then(Commands.argument("tipo", StringArgumentType.word())
                                .executes(ctx -> start(ctx.getSource(), manager, StringArgumentType.getString(ctx, "tipo"), 15))
                                .then(Commands.argument("duracao_min", IntegerArgumentType.integer(1, 120))
                                        .executes(ctx -> start(ctx.getSource(), manager,
                                                StringArgumentType.getString(ctx, "tipo"),
                                                IntegerArgumentType.getInteger(ctx, "duracao_min"))))))
                .then(Commands.literal("parar")
                        .executes(ctx -> {
                            if (manager.stopActiveEvent(ctx.getSource().getServer(), "Interrompido pela staff.")) {
                                ctx.getSource().sendSuccess(() -> ItemCodecUtil.text("Evento encerrado.", ChatFormatting.YELLOW), true);
                                return 1;
                            }
                            ctx.getSource().sendFailure(ItemCodecUtil.text("Nao ha evento ativo.", ChatFormatting.RED));
                            return 0;
                        }))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            MinecraftServer server = src.getServer();
                            manager.reloadSchedule().whenComplete((count, err) -> server.execute(() -> {
                                if (err != null) {
                                    src.sendFailure(ItemCodecUtil.text("Erro ao recarregar agenda: " + err.getMessage(), ChatFormatting.RED));
                                } else {
                                    src.sendSuccess(() -> ItemCodecUtil.text("Agenda recarregada (" + count + " eventos).", ChatFormatting.GREEN), true);
                                }
                            }));
                            return 1;
                        })));
    }

    private static int start(CommandSourceStack src, EventManager manager, String type, int durMin) {
        if (manager.startEvent(src.getServer(), type, null, durMin, 512)) {
            src.sendSuccess(() -> ItemCodecUtil.text("Evento " + type.toUpperCase() + " iniciado!", ChatFormatting.GREEN), true);
            return 1;
        }
        src.sendFailure(ItemCodecUtil.text(
                "Nao foi possivel iniciar. Use: WORLD_BOSS, SUPPLY_DROP, DUNGEON_HOUR, COMPETITION_FISHING, COMPETITION_MINING ou COMPETITION_KILLS.",
                ChatFormatting.RED
        ));
        return 0;
    }
}
