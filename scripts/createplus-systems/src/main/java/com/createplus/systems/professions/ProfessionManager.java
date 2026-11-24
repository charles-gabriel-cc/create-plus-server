package com.createplus.systems.professions;

import com.createplus.systems.CreatePlusSystemsMod;
import com.createplus.systems.core.DatabaseManager;
import com.createplus.systems.core.ItemCodecUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Albion-style Mastery Professions:
 * - Open mastery (players can level all 5 professions in parallel via RP practice)
 * - Variety/discovery XP bonus + anti-AFK cooldown
 * - Data-driven tool/item Tier gating (config/createplus/profession_rules.json)
 */
public final class ProfessionManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long COMBAT_XP_COOLDOWN_MS = 2500L;

    private final DatabaseManager db;
    private final Map<UUID, Map<ProfessionType, PlayerProfessionState>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> discoveries = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombatXpMs = new ConcurrentHashMap<>();
    private volatile RulesConfig rules = RulesConfig.defaults();

    public ProfessionManager(DatabaseManager db) {
        this.db = db;
    }

    public CompletableFuture<Void> initialize(Path serverRoot) {
        reloadRules(serverRoot);
        if (!db.isConfigured()) {
            return CompletableFuture.completedFuture(null);
        }
        return db.runAsync(ProfessionSchema::ensureTables);
    }

    public void reloadRules(Path serverRoot) {
        Path file = serverRoot.resolve("config").resolve("createplus").resolve("profession_rules.json");
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                Files.writeString(file, GSON.toJson(RulesConfig.defaults()));
            }
            try (var reader = Files.newBufferedReader(file)) {
                RulesConfig loaded = GSON.fromJson(reader, RulesConfig.class);
                if (loaded != null && loaded.gating != null) {
                    this.rules = loaded;
                }
            }
            CreatePlusSystemsMod.LOGGER.info("Professions: loaded {} gating rules (enforceGating={}).",
                    rules.gating.size(), rules.enforceGating);
        } catch (IOException | RuntimeException ex) {
            CreatePlusSystemsMod.LOGGER.error("Failed to load profession_rules.json: {}", ex.getMessage());
        }
    }

    public boolean isGatingEnforced() {
        return rules.enforceGating;
    }

    public void setGatingEnforced(boolean enforce) {
        this.rules = new RulesConfig(enforce, rules.gating);
    }

    public void onPlayerLogin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        ensureMemoryState(uuid);
        if (!db.isConfigured()) return;

        MinecraftServer server = player.getServer();
        db.supplyAsync(conn -> {
            Map<ProfessionType, PlayerProfessionState> loaded = new EnumMap<>(ProfessionType.class);
            for (ProfessionType t : ProfessionType.values()) {
                loaded.put(t, new PlayerProfessionState(1, 0, 1));
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT profession_id, level, xp, tier FROM cp_player_professions WHERE player_uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ProfessionType t = ProfessionType.byId(rs.getString("profession_id"));
                        if (t != null) {
                            loaded.put(t, new PlayerProfessionState(
                                    rs.getInt("level"), rs.getInt("xp"), rs.getInt("tier")
                            ));
                        }
                    }
                }
            }
            Set<String> disc = new HashSet<>();
            try (PreparedStatement ds = conn.prepareStatement(
                    "SELECT profession_id, discovery_key FROM cp_player_discoveries WHERE player_uuid = ?")) {
                ds.setString(1, uuid.toString());
                try (ResultSet rs = ds.executeQuery()) {
                    while (rs.next()) {
                        disc.add(rs.getString("profession_id") + "|" + rs.getString("discovery_key"));
                    }
                }
            }
            return new LoadedProfile(loaded, disc);
        }).whenComplete((profile, err) -> {
            if (server == null || err != null || profile == null) return;
            server.execute(() -> {
                cache.put(uuid, profile.states());
                discoveries.put(uuid, ConcurrentHashMap.newKeySet());
                discoveries.get(uuid).addAll(profile.discoveries());
            });
        });
    }

    public void onPlayerLogout(UUID uuid) {
        cache.remove(uuid);
        discoveries.remove(uuid);
        lastCombatXpMs.remove(uuid);
    }

    public PlayerProfessionState getState(UUID uuid, ProfessionType type) {
        return ensureMemoryState(uuid).get(type);
    }

    /**
     * Checks whether a player has the required Profession Tier for the given scope ("WORKSTATION" or "TOOL").
     * Products bought on /ah remain freely usable by anyone unless marked as a profession-exclusive TOOL.
     */
    public boolean checkAccess(ServerPlayer player, String itemOrBlockId, String scope) {
        if (!rules.enforceGating || player.isCreative() || itemOrBlockId == null || itemOrBlockId.isBlank()) {
            return true;
        }
        for (GatingRule rule : rules.gating) {
            String ruleScope = (rule.scope() == null || rule.scope().isBlank()) ? "WORKSTATION" : rule.scope();
            if (!ruleScope.equalsIgnoreCase(scope) && !"BOTH".equalsIgnoreCase(ruleScope)) {
                continue;
            }
            if (matchesRule(itemOrBlockId, rule.matchPrefix())) {
                ProfessionType prof = ProfessionType.byId(rule.profession());
                if (prof == null) continue;
                PlayerProfessionState st = getState(player.getUUID(), prof);
                if (st.tier < rule.requiredTier()) {
                    String verb = "WORKSTATION".equalsIgnoreCase(scope) ? "operar/montar esta bancada ou maquina" : "usar esta ferramenta de oficio";
                    player.displayClientMessage(ItemCodecUtil.text(
                            "Requer " + prof.displayName + " " + ProfessionType.tierLabel(rule.requiredTier())
                                    + " (seu: Tier " + st.tier + ") para " + verb + "!",
                            ChatFormatting.RED
                    ), true);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Awards RP XP with optional first-time discovery multiplier (encourages variety over spam).
     */
    public void awardXp(ServerPlayer player, ProfessionType type, int baseXp, String discoveryKey, int discoveryMult) {
        if (baseXp <= 0) return;
        UUID uuid = player.getUUID();
        int finalXp = baseXp;
        boolean newDiscovery = false;

        if (discoveryKey != null && !discoveryKey.isBlank()) {
            Set<String> set = discoveries.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
            String fullKey = type.id + "|" + discoveryKey;
            if (set.add(fullKey)) {
                finalXp = baseXp * Math.max(1, discoveryMult);
                newDiscovery = true;
                persistDiscovery(uuid, type.id, discoveryKey);
            }
        }

        PlayerProfessionState state = getState(uuid, type);
        if (state.level >= ProfessionType.MAX_LEVEL) {
            return;
        }

        int oldTier = state.tier;
        state.xp += finalXp;
        boolean leveledUp = false;
        while (state.level < ProfessionType.MAX_LEVEL) {
            int req = ProfessionType.xpRequiredForNextLevel(state.level);
            if (state.xp < req) break;
            state.xp -= req;
            state.level++;
            state.tier = ProfessionType.tierForLevel(state.level);
            leveledUp = true;
        }
        if (state.level >= ProfessionType.MAX_LEVEL) {
            state.xp = 0;
        }

        if (leveledUp) {
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
            if (state.tier > oldTier) {
                player.sendSystemMessage(ItemCodecUtil.text(
                        "=== PROMOCAO! " + type.displayName + " alcancou " + ProfessionType.tierLabel(state.tier) + " (Nv. " + state.level + ")! ===",
                        ChatFormatting.GOLD, ChatFormatting.BOLD
                ));
            } else {
                player.displayClientMessage(ItemCodecUtil.text(
                        type.displayName + " subiu para o Nivel " + state.level + "!",
                        ChatFormatting.GREEN
                ), true);
            }
        } else if (newDiscovery) {
            player.displayClientMessage(ItemCodecUtil.text(
                    "+" + finalXp + " XP " + type.displayName + " (Nova descoberta!)",
                    ChatFormatting.AQUA
            ), true);
        }

        persistState(uuid, player.getGameProfile().getName(), type, state);
    }

    public void setTierAdmin(ServerPlayer player, ProfessionType type, int tier) {
        int clamped = Math.max(1, Math.min(4, tier));
        int level = switch (clamped) {
            case 4 -> 30;
            case 3 -> 20;
            case 2 -> 10;
            default -> 1;
        };
        PlayerProfessionState st = getState(player.getUUID(), type);
        st.level = level;
        st.xp = 0;
        st.tier = clamped;
        persistState(player.getUUID(), player.getGameProfile().getName(), type, st);
    }

    // ==================== RP EVENT HOOKS ====================

    public void onItemCrafted(ServerPlayer player, ItemStack crafted) {
        if (crafted == null || crafted.isEmpty()) return;
        String id = ItemCodecUtil.itemIdOf(crafted);
        String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";

        if (ns.startsWith("create") || ns.equals("powergrid") || ns.equals("railways") || ns.equals("alloyed") || ns.equals("cosmonautics")) {
            awardXp(player, ProfessionType.ENGENHEIRO, 8, id, 4);
        } else if (ns.equals("thaumaturge")) {
            awardXp(player, ProfessionType.TAUMATURGO, 12, id, 4);
        } else if (ns.equals("farmersdelight") || ns.equals("mynethersdelight") || ns.equals("ends_delight")
                || ns.equals("minersdelight") || ns.equals("handson_delight")) {
            awardXp(player, ProfessionType.CHEF, 10, id, 4);
        } else if (ns.equals("cataclysm") || ns.equals("deeperdarker") || ns.equals("simplyswords")
                || id.endsWith("_sword") || id.endsWith("_chestplate") || id.endsWith("_helmet")
                || id.endsWith("_leggings") || id.endsWith("_boots") || id.endsWith("_shield")) {
            awardXp(player, ProfessionType.FERREIRO, 10, id, 3);
        } else if (ns.equals("hybrid_aquatic") || ns.equals("alexsmobs")) {
            awardXp(player, ProfessionType.NATURALISTA, 12, id, 4);
        }
    }

    public void onFishCaught(ServerPlayer player, List<ItemStack> drops) {
        for (ItemStack s : drops) {
            if (s.isEmpty()) continue;
            String id = ItemCodecUtil.itemIdOf(s);
            int base = id.startsWith("hybrid_aquatic:") ? 18 : 8;
            awardXp(player, ProfessionType.NATURALISTA, base, id, 4);
        }
    }

    public void onAdvancement(ServerPlayer player, String advancementId) {
        if (advancementId == null) return;
        if (advancementId.startsWith("thaumaturge:")) {
            awardXp(player, ProfessionType.TAUMATURGO, 45, advancementId, 1);
        } else if (advancementId.startsWith("create:") || advancementId.startsWith("powergrid:") || advancementId.startsWith("railways:")) {
            awardXp(player, ProfessionType.ENGENHEIRO, 35, advancementId, 1);
        } else if (advancementId.startsWith("farmersdelight:")) {
            awardXp(player, ProfessionType.CHEF, 35, advancementId, 1);
        } else if (advancementId.startsWith("alexsmobs:") || advancementId.startsWith("hybrid_aquatic:")) {
            awardXp(player, ProfessionType.NATURALISTA, 40, advancementId, 1);
        } else if (advancementId.startsWith("cataclysm:") || advancementId.startsWith("deeperdarker:")) {
            awardXp(player, ProfessionType.FERREIRO, 45, advancementId, 1);
        }
    }

    public void onMobKilled(ServerPlayer player, String entityId) {
        long now = System.currentTimeMillis();
        long prev = lastCombatXpMs.getOrDefault(player.getUUID(), 0L);
        if (now - prev < COMBAT_XP_COOLDOWN_MS) {
            return; // Anti-AFK mob farm throttle
        }
        lastCombatXpMs.put(player.getUUID(), now);

        if (entityId.startsWith("alexsmobs:") || entityId.startsWith("hybrid_aquatic:")) {
            awardXp(player, ProfessionType.NATURALISTA, 10, entityId, 3);
        } else {
            awardXp(player, ProfessionType.FERREIRO, 6, null, 1);
        }
    }

    public CompletableFuture<List<RankEntry>> getRanking(ProfessionType type) {
        if (!db.isConfigured()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return db.supplyAsync(conn -> {
            List<RankEntry> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT player_name, level, tier, xp
                    FROM cp_player_professions
                    WHERE profession_id = ?
                    ORDER BY level DESC, xp DESC
                    LIMIT 10
                    """)) {
                ps.setString(1, type.id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new RankEntry(rs.getString("player_name"), rs.getInt("level"), rs.getInt("tier"), rs.getInt("xp")));
                    }
                }
            }
            return out;
        });
    }

    private Map<ProfessionType, PlayerProfessionState> ensureMemoryState(UUID uuid) {
        return cache.computeIfAbsent(uuid, k -> {
            Map<ProfessionType, PlayerProfessionState> map = new EnumMap<>(ProfessionType.class);
            for (ProfessionType t : ProfessionType.values()) {
                map.put(t, new PlayerProfessionState(1, 0, 1));
            }
            return map;
        });
    }

    private void persistState(UUID uuid, String name, ProfessionType type, PlayerProfessionState state) {
        if (!db.isConfigured()) return;
        int lvl = state.level, xp = state.xp, tier = state.tier;
        db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO cp_player_professions (player_uuid, player_name, profession_id, level, xp, tier, mastered_at)
                    VALUES (?, ?, ?, ?, ?, ?, IF(? >= 4, NOW(), NULL))
                    ON DUPLICATE KEY UPDATE
                        player_name = VALUES(player_name),
                        level = VALUES(level),
                        xp = VALUES(xp),
                        tier = VALUES(tier),
                        mastered_at = COALESCE(mastered_at, IF(VALUES(tier) >= 4, NOW(), NULL))
                    """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, type.id);
                ps.setInt(4, lvl);
                ps.setInt(5, xp);
                ps.setInt(6, tier);
                ps.setInt(7, tier);
                ps.executeUpdate();
            }
        });
    }

    private void persistDiscovery(UUID uuid, String profId, String key) {
        if (!db.isConfigured()) return;
        db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT IGNORE INTO cp_player_discoveries (player_uuid, profession_id, discovery_key)
                    VALUES (?, ?, ?)
                    """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, profId);
                ps.setString(3, key.length() > 155 ? key.substring(0, 155) : key);
                ps.executeUpdate();
            }
        });
    }

    private static boolean matchesRule(String id, String pattern) {
        if (pattern.endsWith("*")) {
            return id.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return id.equals(pattern);
    }

    public static final class PlayerProfessionState {
        public int level;
        public int xp;
        public int tier;

        public PlayerProfessionState(int level, int xp, int tier) {
            this.level = level;
            this.xp = xp;
            this.tier = tier;
        }
    }

    private record LoadedProfile(Map<ProfessionType, PlayerProfessionState> states, Set<String> discoveries) {
    }

    public record RankEntry(String playerName, int level, int tier, int xp) {
    }

    public record GatingRule(String matchPrefix, String profession, int requiredTier, String scope) {
    }

    public record RulesConfig(boolean enforceGating, List<GatingRule> gating) {
        static RulesConfig defaults() {
            return new RulesConfig(true, List.of(
                    // ENGENHEIRO — bancadas/maquinas industriais (produtos prontos livres no /ah!)
                    new GatingRule("create_enchantment_industry:*", "engenheiro", 2, "WORKSTATION"),
                    new GatingRule("railways:*", "engenheiro", 2, "WORKSTATION"),
                    new GatingRule("powergrid:*", "engenheiro", 3, "WORKSTATION"),
                    new GatingRule("createdieselgenerators:*", "engenheiro", 3, "WORKSTATION"),
                    new GatingRule("cosmonautics:*", "engenheiro", 4, "WORKSTATION"),
                    // FERREIRO — Smithing Table (forja/upgrade de gear endgame; armas/armaduras prontas livres pra qualquer um usar!)
                    new GatingRule("minecraft:smithing_table", "ferreiro", 2, "WORKSTATION"),
                    // TAUMATURGO — bancadas arcanas & ferramentas de pesquisa/golem (amuletos prontos livres no /ah!)
                    new GatingRule("thaumaturge:alembic", "taumaturgo", 2, "WORKSTATION"),
                    new GatingRule("thaumaturge:advanced_alchemical_furnace", "taumaturgo", 2, "WORKSTATION"),
                    new GatingRule("thaumaturge:golem_builder", "taumaturgo", 2, "WORKSTATION"),
                    new GatingRule("thaumaturge:golem_bell", "taumaturgo", 2, "TOOL"),
                    new GatingRule("thaumaturge:infusion_matrix", "taumaturgo", 3, "WORKSTATION"),
                    // CHEF & ALQUIMISTA — destilacao de pocoes (banquetes/pocoes prontos livres pra todos consumirem!)
                    new GatingRule("minecraft:brewing_stand", "chef", 2, "WORKSTATION"),
                    // NATURALISTA — equipamentos de coleta marinha profunda (peixes/perolas/drops livres no /ah!)
                    new GatingRule("hybrid_aquatic:crab_pot", "naturalista", 2, "WORKSTATION"),
                    new GatingRule("hybrid_aquatic:fishing_net", "naturalista", 2, "TOOL"),
                    new GatingRule("hybrid_aquatic:diving_*", "naturalista", 3, "TOOL")
            ));
        }
    }
}
