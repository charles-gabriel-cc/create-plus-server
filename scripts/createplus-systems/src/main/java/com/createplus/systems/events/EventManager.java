package com.createplus.systems.events;

import com.createplus.systems.CreatePlusSystemsMod;
import com.createplus.systems.core.DatabaseManager;
import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.core.NumismaticsBridge;
import com.createplus.systems.economy.EconomySchema;
import com.createplus.systems.economy.EconomyService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fixed-schedule server events manager (America/Sao_Paulo):
 * WORLD_BOSS, SUPPLY_DROP, DUNGEON_HOUR, COMPETITION_FISHING, COMPETITION_MINING, COMPETITION_KILLS.
 */
public final class EventManager {
    private final DatabaseManager db;
    private final EconomyService economy;
    private final Random random = new Random();

    private volatile List<ScheduledEvent> schedule = List.of();
    private ActiveEvent active;
    private int tickCounter = 0;
    private String lastTriggeredKey = "";
    private String lastWarn5Key = "";
    private String lastWarn1Key = "";

    public EventManager(DatabaseManager db, EconomyService economy) {
        this.db = db;
        this.economy = economy;
    }

    public CompletableFuture<Void> initialize() {
        if (!db.isConfigured()) {
            return CompletableFuture.completedFuture(null);
        }
        return db.runAsync(conn -> {
            EventSchema.ensureTables(conn);
            loadScheduleInternal(conn);
        });
    }

    public CompletableFuture<Integer> reloadSchedule() {
        if (!db.isConfigured()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.supplyAsync(this::loadScheduleInternal);
    }

    private int loadScheduleInternal(java.sql.Connection conn) throws java.sql.SQLException {
        List<ScheduledEvent> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, event_type, display_name, hour_brt, minute_brt, duration_min, min_players, reward_spurs
                FROM cp_event_schedule
                WHERE active = 1
                ORDER BY hour_brt ASC, minute_brt ASC
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new ScheduledEvent(
                        rs.getInt("id"),
                        rs.getString("event_type"),
                        rs.getString("display_name"),
                        rs.getInt("hour_brt"),
                        rs.getInt("minute_brt"),
                        rs.getInt("duration_min"),
                        rs.getInt("min_players"),
                        rs.getInt("reward_spurs")
                ));
            }
        }
        this.schedule = List.copyOf(list);
        CreatePlusSystemsMod.LOGGER.info("Events: loaded {} scheduled events (America/Sao_Paulo).", list.size());
        return list.size();
    }

    public List<ScheduledEvent> schedule() {
        return schedule;
    }

    public ActiveEvent activeEvent() {
        return active;
    }

    public double getDungeonRewardMultiplier() {
        if (active != null && "DUNGEON_HOUR".equals(active.type)) {
            return 1.5;
        }
        return 1.0;
    }

    public void tick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 20 != 0) return; // 1Hz check

        checkSchedule(server);
        if (active != null) {
            tickActiveEvent(server);
        }
    }

    private void checkSchedule(MinecraftServer server) {
        if (schedule.isEmpty()) return;
        ZonedDateTime now = ZonedDateTime.now(EconomySchema.SERVER_ZONE);
        int curMinOfDay = now.getHour() * 60 + now.getMinute();
        String dayPrefix = now.toLocalDate().toString() + "#";

        for (ScheduledEvent ev : schedule) {
            int targetMin = ev.hourBrt() * 60 + ev.minuteBrt();
            int diff = targetMin - curMinOfDay;
            String key = dayPrefix + ev.id();

            if (diff == 5 && !key.equals(lastWarn5Key)) {
                lastWarn5Key = key;
                broadcast(server, "[Evento] " + ev.displayName() + " comeca em 5 minutos!", ChatFormatting.GOLD);
            } else if (diff == 1 && !key.equals(lastWarn1Key)) {
                lastWarn1Key = key;
                broadcast(server, "[Evento] " + ev.displayName() + " comeca em 1 minuto!", ChatFormatting.YELLOW);
            } else if (diff == 0 && !key.equals(lastTriggeredKey)) {
                lastTriggeredKey = key;
                if (active == null && server.getPlayerCount() >= ev.minPlayers()) {
                    startEvent(server, ev.eventType(), ev.displayName(), ev.durationMin(), ev.rewardSpurs());
                }
            }
        }
    }

    public boolean startEvent(MinecraftServer server, String rawType, String displayName, int durationMin, int rewardSpurs) {
        if (active != null) {
            return false;
        }
        String type = rawType.toUpperCase();
        String title = (displayName == null || displayName.isBlank()) ? defaultNameFor(type) : displayName;
        int durSec = Math.max(30, durationMin * 60);

        ServerBossEvent bar = new ServerBossEvent(
                ItemCodecUtil.text("[EVENTO] " + title, ChatFormatting.GOLD, ChatFormatting.BOLD),
                BossEvent.BossBarColor.YELLOW,
                BossEvent.BossBarOverlay.PROGRESS
        );
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            bar.addPlayer(p);
        }

        ActiveEvent ev = new ActiveEvent(type, title, durSec, rewardSpurs, bar);
        this.active = ev;

        switch (type) {
            case "DUNGEON_HOUR" -> {
                bar.setColor(BossEvent.BossBarColor.PURPLE);
                broadcast(server, "=== HORA DA DUNGEON ATIVADA! Todas as Dungeons Instanciadas dao 1.5x moedas por " + durationMin + " min! ===", ChatFormatting.LIGHT_PURPLE);
            }
            case "WORLD_BOSS" -> spawnWorldBoss(server, ev);
            case "SUPPLY_DROP" -> spawnSupplyDrop(server, ev);
            case "COMPETITION_FISHING", "COMPETITION_MINING", "COMPETITION_KILLS" -> {
                bar.setColor(BossEvent.BossBarColor.BLUE);
                broadcast(server, "=== EVENTO INICIADO: " + title + " (" + durationMin + " min)! Top 3 ganham moedas do Numismatics! ===", ChatFormatting.AQUA);
            }
            default -> {
                bar.removeAllPlayers();
                this.active = null;
                return false;
            }
        }
        return true;
    }

    public boolean stopActiveEvent(MinecraftServer server, String reason) {
        if (active == null) return false;
        ActiveEvent ev = active;
        active = null;
        ev.bossBar.removeAllPlayers();
        if (ev.bossUuid != null) {
            Entity e = server.overworld().getEntity(ev.bossUuid);
            if (e != null) e.discard();
        }
        for (UUID gid : ev.guardianUuids) {
            Entity e = server.overworld().getEntity(gid);
            if (e != null) e.discard();
        }
        broadcast(server, "[Evento] " + ev.displayName + " encerrado: " + reason, ChatFormatting.YELLOW);
        recordHistory(ev.type, ev.displayName, reason);
        return true;
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (active != null) {
            active.bossBar.addPlayer(player);
        }
    }

    public void onEntityDamaged(LivingEntity target, ServerPlayer attacker, float damage) {
        if (active == null || !"WORLD_BOSS".equals(active.type) || active.bossUuid == null) return;
        if (!target.getUUID().equals(active.bossUuid)) return;
        active.scores.merge(attacker.getUUID(), (double) damage, Double::sum);
        active.playerNames.put(attacker.getUUID(), attacker.getGameProfile().getName());
    }

    public void onCompetitionAction(ServerPlayer player, String kind, double points) {
        if (active == null) return;
        if (active.type.equals("COMPETITION_" + kind)) {
            active.scores.merge(player.getUUID(), points, Double::sum);
            active.playerNames.put(player.getUUID(), player.getGameProfile().getName());
        }
    }

    private void tickActiveEvent(MinecraftServer server) {
        ActiveEvent ev = active;
        ev.remainingSec--;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!ev.bossBar.getPlayers().contains(p)) {
                ev.bossBar.addPlayer(p);
            }
        }

        if ("WORLD_BOSS".equals(ev.type)) {
            Entity boss = ev.bossUuid == null ? null : server.overworld().getEntity(ev.bossUuid);
            if (boss == null || !boss.isAlive()) {
                finishWorldBoss(server, ev);
                return;
            }
            if (boss instanceof LivingEntity living) {
                ev.bossBar.setProgress(Math.max(0f, Math.min(1f, living.getHealth() / Math.max(1f, living.getMaxHealth()))));
                ev.bossBar.setName(ItemCodecUtil.text(
                        "[BOSS MUNDIAL] " + (int) living.getHealth() + "/" + (int) living.getMaxHealth() + " HP | " + formatMinSec(ev.remainingSec),
                        ChatFormatting.RED, ChatFormatting.BOLD
                ));
            }
        } else if ("SUPPLY_DROP".equals(ev.type)) {
            ServerLevel ow = server.overworld();
            if (ev.dropPos != null) {
                ow.sendParticles(ParticleTypes.END_ROD, ev.dropPos.getX() + 0.5, ev.dropPos.getY() + 2.0, ev.dropPos.getZ() + 0.5, 10, 0.3, 3.0, 0.3, 0.02);
            }
            ev.guardianUuids.removeIf(id -> {
                Entity e = ow.getEntity(id);
                return e == null || !e.isAlive();
            });
            if (!ev.dropUnlocked && ev.guardianUuids.isEmpty()) {
                ev.dropUnlocked = true;
                unlockSupplyChest(server, ev);
                stopActiveEvent(server, "Guardioes derrotados! O bau do Supply Drop foi liberado!");
                return;
            }
            ev.bossBar.setProgress(Math.max(0f, ev.remainingSec / (float) ev.totalSec));
            ev.bossBar.setName(ItemCodecUtil.text(
                    "[SUPPLY DROP] Guardioes: " + ev.guardianUuids.size() + " | " + formatMinSec(ev.remainingSec),
                    ChatFormatting.GOLD, ChatFormatting.BOLD
            ));
        } else {
            ev.bossBar.setProgress(Math.max(0f, ev.remainingSec / (float) ev.totalSec));
            ev.bossBar.setName(ItemCodecUtil.text(
                    "[EVENTO] " + ev.displayName + " — " + formatMinSec(ev.remainingSec),
                    ChatFormatting.AQUA, ChatFormatting.BOLD
            ));
        }

        if (ev.remainingSec <= 0) {
            if (ev.type.startsWith("COMPETITION_")) {
                finishCompetition(server, ev);
            } else {
                stopActiveEvent(server, "Tempo encerrado.");
            }
        }
    }

    private void spawnWorldBoss(MinecraftServer server, ActiveEvent ev) {
        ServerLevel ow = server.overworld();
        BlockPos anchor = pickAnchor(server, ow);
        int x = anchor.getX() + random.nextInt(80) - 40;
        int z = anchor.getZ() + random.nextInt(80) - 40;
        int y = ow.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

        Ravager boss = EntityType.RAVAGER.create(ow);
        if (boss != null) {
            boss.moveTo(x + 0.5, y + 1.0, z + 0.5, 0f, 0f);
            boss.setCustomName(ItemCodecUtil.text("Colosso Mundial", ChatFormatting.DARK_RED, ChatFormatting.BOLD));
            boss.setCustomNameVisible(true);
            boss.setPersistenceRequired();
            AttributeInstance hp = boss.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(350.0);
                boss.setHealth(350.0f);
            }
            ow.addFreshEntity(boss);
            ev.bossUuid = boss.getUUID();
        }
        ev.bossBar.setColor(BossEvent.BossBarColor.RED);
        broadcast(server, "=== BOSS MUNDIAL apareceu em X=" + x + ", Y=" + y + ", Z=" + z + " (Overworld)! Todos que causarem dano ganham premio! ===", ChatFormatting.RED);
    }

    private void finishWorldBoss(MinecraftServer server, ActiveEvent ev) {
        active = null;
        ev.bossBar.removeAllPlayers();
        List<Map.Entry<UUID, Double>> ranking = ev.scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .toList();

        List<String> summary = new ArrayList<>();
        for (int i = 0; i < ranking.size(); i++) {
            UUID pid = ranking.get(i).getKey();
            int bonus = (i == 0) ? ev.rewardSpurs : (i == 1 ? (int) (ev.rewardSpurs * 0.7) : (i == 2 ? (int) (ev.rewardSpurs * 0.5) : (int) (ev.rewardSpurs * 0.35)));
            NumismaticsBridge.deposit(pid, bonus);
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) {
                p.sendSystemMessage(ItemCodecUtil.text(
                        "Boss Mundial derrotado (#" + (i + 1) + " em dano)! Voce recebeu " + NumismaticsBridge.formatSpurs(bonus) + "!",
                        ChatFormatting.GREEN, ChatFormatting.BOLD
                ));
            }
            if (i < 3) {
                summary.add("#" + (i + 1) + " " + ev.playerNames.getOrDefault(pid, pid.toString().substring(0, 6)));
            }
            if (economy != null) {
                economy.logAsync(pid, ev.playerNames.getOrDefault(pid, "unknown"), "EVENT_WORLD_BOSS", bonus, null, null, "rank=" + (i + 1));
            }
        }
        String sumStr = summary.isEmpty() ? "Sem participantes" : String.join(", ", summary);
        broadcast(server, "=== BOSS MUNDIAL DERROTADO! Top dano: " + sumStr + " ===", ChatFormatting.GREEN);
        recordHistory(ev.type, ev.displayName, "Derrotado: " + sumStr);
    }

    private void spawnSupplyDrop(MinecraftServer server, ActiveEvent ev) {
        ServerLevel ow = server.overworld();
        BlockPos anchor = pickAnchor(server, ow);
        int x = anchor.getX() + random.nextInt(120) - 60;
        int z = anchor.getZ() + random.nextInt(120) - 60;
        int y = ow.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        ev.dropPos = new BlockPos(x, y, z);

        for (int i = 0; i < 4; i++) {
            WitherSkeleton g = EntityType.WITHER_SKELETON.create(ow);
            if (g != null) {
                g.moveTo(x + random.nextInt(7) - 3 + 0.5, y + 1.0, z + random.nextInt(7) - 3 + 0.5, 0f, 0f);
                g.setCustomName(ItemCodecUtil.text("Guardiao do Supply Drop", ChatFormatting.GOLD));
                g.setPersistenceRequired();
                ow.addFreshEntity(g);
                ev.guardianUuids.add(g.getUUID());
            }
        }
        int approxX = (x / 20) * 20;
        int approxZ = (z / 20) * 20;
        broadcast(server, "=== SUPPLY DROP caiu perto de X~" + approxX + ", Z~" + approxZ + " (Overworld)! Derrote os 4 guardioes para liberar o bau! ===", ChatFormatting.GOLD);
    }

    private void unlockSupplyChest(MinecraftServer server, ActiveEvent ev) {
        if (ev.dropPos == null) return;
        ServerLevel ow = server.overworld();
        ow.setBlock(ev.dropPos, Blocks.CHEST.defaultBlockState(), 3);
        BlockEntity be = ow.getBlockEntity(ev.dropPos);
        if (be instanceof ChestBlockEntity chest) {
            chest.setItem(11, new ItemStack(Items.DIAMOND, 4));
            chest.setItem(13, new ItemStack(Items.TRIAL_KEY, 1));
            chest.setItem(15, new ItemStack(Items.GOLDEN_APPLE, 3));
            chest.setItem(22, new ItemStack(Items.EMERALD, 12));
        }
    }

    private void finishCompetition(MinecraftServer server, ActiveEvent ev) {
        active = null;
        ev.bossBar.removeAllPlayers();
        List<Map.Entry<UUID, Double>> ranking = ev.scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .toList();

        List<String> top = new ArrayList<>();
        double[] mults = {1.0, 0.6, 0.35};
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            UUID pid = ranking.get(i).getKey();
            int prize = Math.max(16, (int) Math.round(ev.rewardSpurs * mults[i]));
            NumismaticsBridge.deposit(pid, prize);
            String pname = ev.playerNames.getOrDefault(pid, "Player");
            top.add("#" + (i + 1) + " " + pname + " (" + ranking.get(i).getValue().intValue() + " pts)");
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) {
                p.sendSystemMessage(ItemCodecUtil.text(
                        "Parabens! #" + (i + 1) + " em " + ev.displayName + " — recebeu " + NumismaticsBridge.formatSpurs(prize) + "!",
                        ChatFormatting.GREEN, ChatFormatting.BOLD
                ));
            }
            if (economy != null) {
                economy.logAsync(pid, pname, "EVENT_" + ev.type, prize, null, null, "rank=" + (i + 1));
            }
        }
        String sumStr = top.isEmpty() ? "Ninguem pontuou" : String.join(" | ", top);
        broadcast(server, "=== FIM DO EVENTO: " + ev.displayName + " -> " + sumStr + " ===", ChatFormatting.AQUA);
        recordHistory(ev.type, ev.displayName, sumStr);
    }

    private BlockPos pickAnchor(MinecraftServer server, ServerLevel ow) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            ServerPlayer picked = players.get(random.nextInt(players.size()));
            if (picked.serverLevel() == ow) {
                return picked.blockPosition();
            }
        }
        return ow.getSharedSpawnPos();
    }

    private void recordHistory(String type, String name, String summary) {
        if (!db.isConfigured()) return;
        db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO cp_event_history (event_type, display_name, summary) VALUES (?, ?, ?)")) {
                ps.setString(1, type);
                ps.setString(2, name);
                ps.setString(3, summary.length() > 250 ? summary.substring(0, 250) : summary);
                ps.executeUpdate();
            }
        });
    }

    private static void broadcast(MinecraftServer server, String msg, ChatFormatting color) {
        server.getPlayerList().broadcastSystemMessage(ItemCodecUtil.text(msg, color, ChatFormatting.BOLD), false);
    }

    private static String defaultNameFor(String type) {
        return switch (type) {
            case "WORLD_BOSS" -> "Boss Mundial";
            case "SUPPLY_DROP" -> "Supply Drop";
            case "DUNGEON_HOUR" -> "Hora da Dungeon (1.5x)";
            case "COMPETITION_FISHING" -> "Torneio de Pesca";
            case "COMPETITION_MINING" -> "Torneio de Mineracao";
            case "COMPETITION_KILLS" -> "Torneio de Abates";
            default -> type;
        };
    }

    public static String formatMinSec(int sec) {
        int m = Math.max(0, sec) / 60;
        int s = Math.max(0, sec) % 60;
        return String.format("%02d:%02d", m, s);
    }

    public record ScheduledEvent(
            int id, String eventType, String displayName, int hourBrt, int minuteBrt,
            int durationMin, int minPlayers, int rewardSpurs
    ) {
    }

    public static final class ActiveEvent {
        public final String type;
        public final String displayName;
        public final int totalSec;
        public final int rewardSpurs;
        public final ServerBossEvent bossBar;
        public final Map<UUID, Double> scores = new HashMap<>();
        public final Map<UUID, String> playerNames = new HashMap<>();
        public final Set<UUID> guardianUuids = new HashSet<>();
        public int remainingSec;
        public UUID bossUuid;
        public BlockPos dropPos;
        public boolean dropUnlocked;

        ActiveEvent(String type, String displayName, int totalSec, int rewardSpurs, ServerBossEvent bossBar) {
            this.type = type;
            this.displayName = displayName;
            this.totalSec = totalSec;
            this.remainingSec = totalSec;
            this.rewardSpurs = rewardSpurs;
            this.bossBar = bossBar;
        }
    }
}
