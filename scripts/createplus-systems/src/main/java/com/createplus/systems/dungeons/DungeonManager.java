package com.createplus.systems.dungeons;

import com.createplus.systems.CreatePlusSystemsMod;
import com.createplus.systems.core.DatabaseManager;
import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.economy.EconomyService;
import com.createplus.systems.events.EventManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active dungeon instances, slot allocation, cooldowns, records, and crash-safe return positions.
 */
public final class DungeonManager {
    public static final ResourceKey<Level> DUNGEON_DIM = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(CreatePlusSystemsMod.MOD_ID, "dungeons")
    );

    private final DatabaseManager db;
    private final EconomyService economy;
    private EventManager eventManager;

    private volatile Map<String, DungeonDefinition> definitions = Map.of();
    private final Map<Integer, DungeonInstance> activeBySlot = new ConcurrentHashMap<>();
    private final Map<UUID, DungeonInstance> activeByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, SavedReturnPos> returnPosCache = new ConcurrentHashMap<>();

    public DungeonManager(DatabaseManager db, EconomyService economy) {
        this.db = db;
        this.economy = economy;
    }

    public void setEventManager(EventManager eventManager) {
        this.eventManager = eventManager;
    }

    public void reloadDefinitions(Path serverRoot) {
        this.definitions = DungeonDefinition.loadAll(serverRoot.resolve("config"));
        CreatePlusSystemsMod.LOGGER.info("Dungeons: loaded {} dungeon definitions.", definitions.size());
    }

    public CompletableFuture<Void> initialize(Path serverRoot) {
        reloadDefinitions(serverRoot);
        if (!db.isConfigured()) {
            return CompletableFuture.completedFuture(null);
        }
        return db.runAsync(DungeonSchema::ensureTables);
    }

    public Map<String, DungeonDefinition> definitions() {
        return definitions;
    }

    public boolean isInDungeonDimension(Level level) {
        return level != null && level.dimension().equals(DUNGEON_DIM);
    }

    public DungeonInstance getPlayerInstance(UUID playerUuid) {
        return activeByPlayer.get(playerUuid);
    }

    public void tick(MinecraftServer server) {
        double rewardMult = (eventManager != null) ? eventManager.getDungeonRewardMultiplier() : 1.0;
        List<Integer> finishedSlots = new ArrayList<>();
        for (DungeonInstance instance : activeBySlot.values()) {
            instance.tick(server, this, rewardMult);
            if (instance.isFinished()) {
                finishedSlots.add(instance.slotIndex());
            }
        }
        for (int slot : finishedSlots) {
            DungeonInstance removed = activeBySlot.remove(slot);
            if (removed != null) {
                activeByPlayer.entrySet().removeIf(e -> e.getValue() == removed);
            }
        }
    }

    public void shutdownAll(MinecraftServer server) {
        for (DungeonInstance instance : new ArrayList<>(activeBySlot.values())) {
            instance.finishAndEvict(server, this);
        }
        activeBySlot.clear();
        activeByPlayer.clear();
    }

    /**
     * Starts an instanced dungeon run for the player (and nearby teammates within 16 blocks if any).
     */
    public void startRun(ServerPlayer leader, String dungeonId) {
        DungeonDefinition def = definitions.get(dungeonId);
        if (def == null) {
            leader.sendSystemMessage(ItemCodecUtil.text("Dungeon nao encontrada: " + dungeonId, ChatFormatting.RED));
            return;
        }
        if (activeByPlayer.containsKey(leader.getUUID())) {
            leader.sendSystemMessage(ItemCodecUtil.text("Voce ja esta em uma dungeon ativa! Use /dungeon sair primeiro.", ChatFormatting.RED));
            return;
        }
        MinecraftServer server = leader.getServer();
        if (server == null) return;
        ServerLevel dungeonLevel = server.getLevel(DUNGEON_DIM);
        if (dungeonLevel == null) {
            leader.sendSystemMessage(ItemCodecUtil.text(
                    "Dimensao createplussystems:dungeons nao carregada. Reinicie o mundo apos instalar o mod.",
                    ChatFormatting.RED
            ));
            return;
        }

        // Gather nearby party members (same scoreboard/FTB team or solo)
        List<ServerPlayer> party = new ArrayList<>();
        party.add(leader);
        for (ServerPlayer other : leader.serverLevel().players()) {
            if (other == leader || !other.isAlive() || activeByPlayer.containsKey(other.getUUID())) continue;
            if (party.size() >= def.maxPlayers()) break;
            boolean sameTeam = leader.getTeam() != null && leader.getTeam() == other.getTeam();
            if (sameTeam && other.distanceToSqr(leader) <= 256.0) {
                party.add(other);
            }
        }

        // Check cooldown asynchronously if DB is configured
        if (db.isConfigured()) {
            checkCooldown(leader.getUUID(), dungeonId).whenComplete((remainingSec, err) -> server.execute(() -> {
                if (remainingSec != null && remainingSec > 0) {
                    leader.sendSystemMessage(ItemCodecUtil.text(
                            "Dungeon em cooldown! Aguarde " + formatDuration(remainingSec) + ".",
                            ChatFormatting.YELLOW
                    ));
                    return;
                }
                consumeKeyAndLaunch(leader, party, def, dungeonLevel);
            }));
        } else {
            consumeKeyAndLaunch(leader, party, def, dungeonLevel);
        }
    }

    private void consumeKeyAndLaunch(ServerPlayer leader, List<ServerPlayer> party, DungeonDefinition def, ServerLevel dungeonLevel) {
        if (def.keyItemId() != null && !def.keyItemId().isBlank() && def.keyCount() > 0) {
            if (!consumeKey(leader, def.keyItemId(), def.keyCount())) {
                leader.sendSystemMessage(ItemCodecUtil.text(
                        "Voce precisa de " + def.keyCount() + "x " + def.keyItemId() + " para abrir esta dungeon!",
                        ChatFormatting.RED
                ));
                return;
            }
        }

        int slot = allocateFreeSlot();
        DungeonInstance instance = new DungeonInstance(slot, def, dungeonLevel, party);
        activeBySlot.put(slot, instance);

        for (ServerPlayer p : party) {
            saveReturnPosition(p);
            activeByPlayer.put(p.getUUID(), instance);
        }

        instance.buildStructureAndStart();
        for (ServerPlayer p : party) {
            instance.teleportPlayerIn(p);
        }
    }

    public void leaveRun(ServerPlayer player) {
        DungeonInstance inst = activeByPlayer.remove(player.getUUID());
        if (inst != null) {
            inst.removePlayer(player);
        }
        returnPlayerToSavedPosition(player);
        player.sendSystemMessage(ItemCodecUtil.text("Voce saiu da dungeon.", ChatFormatting.YELLOW));
    }

    public void onPlayerLogin(ServerPlayer player) {
        if (!isInDungeonDimension(player.serverLevel())) {
            return;
        }
        // Player logged back into dungeon dimension after a crash/restart -> return safely!
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (db.isConfigured()) {
            db.supplyAsync(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT dimension, x, y, z, yaw, pitch FROM cp_dungeon_return_pos WHERE player_uuid = ?")) {
                    ps.setString(1, player.getUUID().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new SavedReturnPos(
                                    rs.getString("dimension"),
                                    rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                                    rs.getFloat("yaw"), rs.getFloat("pitch")
                            );
                        }
                    }
                }
                return null;
            }).whenComplete((saved, err) -> server.execute(() -> {
                if (saved != null) {
                    returnPosCache.put(player.getUUID(), saved);
                }
                returnPlayerToSavedPosition(player);
            }));
        } else {
            returnPlayerToSavedPosition(player);
        }
    }

    private void saveReturnPosition(ServerPlayer player) {
        SavedReturnPos pos = new SavedReturnPos(
                player.serverLevel().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()
        );
        returnPosCache.put(player.getUUID(), pos);
        if (!db.isConfigured()) return;
        UUID uuid = player.getUUID();
        db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO cp_dungeon_return_pos (player_uuid, dimension, x, y, z, yaw, pitch)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE dimension=VALUES(dimension), x=VALUES(x), y=VALUES(y), z=VALUES(z), yaw=VALUES(yaw), pitch=VALUES(pitch), saved_at=NOW()
                    """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, pos.dimension());
                ps.setDouble(3, pos.x());
                ps.setDouble(4, pos.y());
                ps.setDouble(5, pos.z());
                ps.setFloat(6, pos.yaw());
                ps.setFloat(7, pos.pitch());
                ps.executeUpdate();
            }
        });
    }

    public void returnPlayerToSavedPosition(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SavedReturnPos pos = returnPosCache.remove(player.getUUID());
        ServerLevel target = server.overworld();
        double x = target.getSharedSpawnPos().getX() + 0.5;
        double y = target.getSharedSpawnPos().getY() + 1.0;
        double z = target.getSharedSpawnPos().getZ() + 0.5;
        float yaw = 0f, pitch = 0f;

        if (pos != null) {
            ResourceLocation dimLoc = ResourceLocation.tryParse(pos.dimension());
            if (dimLoc != null) {
                ServerLevel found = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimLoc));
                if (found != null && !found.dimension().equals(DUNGEON_DIM)) {
                    target = found;
                    x = pos.x();
                    y = pos.y();
                    z = pos.z();
                    yaw = pos.yaw();
                    pitch = pos.pitch();
                }
            }
        }
        player.teleportTo(target, x, y, z, yaw, pitch);
        if (db.isConfigured()) {
            UUID uuid = player.getUUID();
            db.runAsync(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM cp_dungeon_return_pos WHERE player_uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
            });
        }
    }

    public void recordRunCompletion(DungeonDefinition def, List<ServerPlayer> players, List<String> names, int durationSec, boolean victory) {
        if (!db.isConfigured()) return;
        String joined = String.join(", ", names);
        List<UUID> uuids = players.stream().map(ServerPlayer::getUUID).toList();
        db.runAsync(conn -> {
            try (PreparedStatement ins = conn.prepareStatement("""
                    INSERT INTO cp_dungeon_runs (dungeon_id, party_members, party_size, duration_sec, victory)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                ins.setString(1, def.id());
                ins.setString(2, joined);
                ins.setInt(3, Math.max(1, names.size()));
                ins.setInt(4, durationSec);
                ins.setInt(5, victory ? 1 : 0);
                ins.executeUpdate();
            }
            if (victory) {
                Timestamp avail = Timestamp.from(Instant.now().plusSeconds(Math.max(10, def.cooldownSec())));
                try (PreparedStatement cd = conn.prepareStatement("""
                        INSERT INTO cp_dungeon_cooldowns (player_uuid, dungeon_id, available_at)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE available_at = VALUES(available_at)
                        """)) {
                    for (UUID u : uuids) {
                        cd.setString(1, u.toString());
                        cd.setString(2, def.id());
                        cd.setTimestamp(3, avail);
                        cd.addBatch();
                    }
                    cd.executeBatch();
                }
                try (PreparedStatement rec = conn.prepareStatement("""
                        INSERT INTO cp_dungeon_records (dungeon_id, best_time_sec, holders)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            holders = IF(VALUES(best_time_sec) < best_time_sec, VALUES(holders), holders),
                            achieved_at = IF(VALUES(best_time_sec) < best_time_sec, NOW(), achieved_at),
                            best_time_sec = LEAST(best_time_sec, VALUES(best_time_sec))
                        """)) {
                    rec.setString(1, def.id());
                    rec.setInt(2, durationSec);
                    rec.setString(3, joined.length() > 250 ? joined.substring(0, 250) : joined);
                    rec.executeUpdate();
                }
            }
        });
        if (victory && economy != null) {
            for (ServerPlayer p : players) {
                economy.logAsync(p.getUUID(), p.getGameProfile().getName(), "DUNGEON_CLEAR", def.rewardSpurs(),
                        def.id(), 1, "duration=" + durationSec + "s");
            }
        }
    }

    public CompletableFuture<Long> checkCooldown(UUID playerUuid, String dungeonId) {
        return db.supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT available_at FROM cp_dungeon_cooldowns WHERE player_uuid = ? AND dungeon_id = ?")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, dungeonId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long diff = rs.getTimestamp("available_at").toInstant().getEpochSecond() - Instant.now().getEpochSecond();
                        return Math.max(0L, diff);
                    }
                }
            }
            return 0L;
        });
    }

    public CompletableFuture<Map<String, RecordView>> getAllRecords() {
        if (!db.isConfigured()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return db.supplyAsync(conn -> {
            Map<String, RecordView> map = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT dungeon_id, best_time_sec, holders FROM cp_dungeon_records");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("dungeon_id"), new RecordView(rs.getInt("best_time_sec"), rs.getString("holders")));
                }
            }
            return map;
        });
    }

    public CompletableFuture<Void> resetCooldowns(UUID playerUuid) {
        if (!db.isConfigured()) return CompletableFuture.completedFuture(null);
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM cp_dungeon_cooldowns WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
            }
        });
    }

    private int allocateFreeSlot() {
        int slot = 0;
        while (activeBySlot.containsKey(slot)) {
            slot++;
        }
        return slot;
    }

    private static boolean consumeKey(ServerPlayer player, String itemId, int needed) {
        var inv = player.getInventory();
        int available = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemCodecUtil.itemIdOf(s).equals(itemId)) {
                available += s.getCount();
            }
        }
        if (available < needed) return false;
        int left = needed;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemCodecUtil.itemIdOf(s).equals(itemId)) {
                int take = Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
            }
        }
        inv.setChanged();
        return true;
    }

    public static String formatDuration(long sec) {
        long m = sec / 60;
        long s = sec % 60;
        return m > 0 ? (m + "m " + s + "s") : (s + "s");
    }

    public record SavedReturnPos(String dimension, double x, double y, double z, float yaw, float pitch) {
    }

    public record RecordView(int bestTimeSec, String holders) {
    }
}
