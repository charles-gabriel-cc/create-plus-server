package com.createplus.systems.dungeons;

import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.core.NumismaticsBridge;
import com.createplus.systems.dungeons.DungeonDefinition.BossDef;
import com.createplus.systems.dungeons.DungeonDefinition.RewardItem;
import com.createplus.systems.dungeons.DungeonDefinition.TemplateTile;
import com.createplus.systems.dungeons.DungeonDefinition.WaveDef;
import com.createplus.systems.dungeons.DungeonDefinition.WaveMob;
import com.createplus.systems.economy.EconomyService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * A single live instanced dungeon run inside an isolated slot of createplussystems:dungeons.
 */
public final class DungeonInstance {
    public static final int SLOT_SPACING = 512;
    public static final int BASE_Y = 64;

    public enum Phase { WAVE, BOSS, VICTORY, FINISHED }

    private final int slotIndex;
    private final BlockPos origin;
    private final DungeonDefinition def;
    private final ServerLevel level;
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> aliveMobs = new HashSet<>();
    private final ServerBossEvent bossBar;
    private final Random random = new Random();
    private final long startedAtMs;

    private final List<BlockPos> roomSpawnSpots = new ArrayList<>();
    private Phase phase = Phase.WAVE;
    private int waveIndex = 0;
    private int elapsedTicks = 0;
    private int exitCountdownTicks = 20 * 20; // 20s after victory
    private UUID bossUuid;
    private boolean victoryRecorded = false;

    public DungeonInstance(int slotIndex, DungeonDefinition def, ServerLevel level, List<ServerPlayer> players) {
        this.slotIndex = slotIndex;
        this.origin = new BlockPos(slotIndex * SLOT_SPACING, BASE_Y, 0);
        this.def = def;
        this.level = level;
        this.startedAtMs = System.currentTimeMillis();
        this.bossBar = new ServerBossEvent(
                ItemCodecUtil.text(def.displayName(), ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.PROGRESS
        );
        for (ServerPlayer p : players) {
            participants.add(p.getUUID());
            bossBar.addPlayer(p);
        }
    }

    public int slotIndex() { return slotIndex; }
    public BlockPos origin() { return origin; }
    public DungeonDefinition def() { return def; }
    public Set<UUID> participants() { return participants; }
    public Phase phase() { return phase; }
    public boolean isFinished() { return phase == Phase.FINISHED; }
    public int elapsedSeconds() { return Math.max(1, (int) ((System.currentTimeMillis() - startedAtMs) / 1000L)); }

    public void buildStructureAndStart() {
        // Always clear previous structure blocks in this slot so maps NEVER overlap!
        clearSlotArea();

        boolean pastedAny = false;
        if (def.templateTiles() != null && !def.templateTiles().isEmpty()) {
            for (TemplateTile tile : def.templateTiles()) {
                ResourceLocation loc = ResourceLocation.tryParse(tile.structureId());
                if (loc == null) continue;
                Optional<StructureTemplate> tpl = level.getStructureManager().get(loc);
                if (tpl.isPresent()) {
                    BlockPos pos = origin.offset(tile.offsetX(), tile.offsetY(), tile.offsetZ());
                    StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
                    tpl.get().placeInWorld(level, pos, pos, settings, level.random, 2);
                    pastedAny = true;
                }
            }
        }
        if (!pastedAny) {
            buildFallbackArena();
        }
        scanRoomSpawnSpots();
        spawnCurrentWave();
    }

    private void clearSlotArea() {
        int r = Math.max(28, def.arenaRadius() + 4);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 52; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.getBlockState(cursor).isAir()) {
                        level.setBlock(cursor, air, 2);
                    }
                }
            }
        }
    }

    /**
     * Scans all interior floors/rooms/corridors of the pasted dungeon so mobs spawn
     * naturally scattered across rooms and hallways instead of clumped in one spot.
     */
    private void scanRoomSpawnSpots() {
        roomSpawnSpots.clear();
        int r = Math.max(16, def.arenaRadius() - 2);
        double entryX = origin.getX() + 0.5 + (def.spawnOffset() != null ? def.spawnOffset().x() : 0);
        double entryZ = origin.getZ() + 0.5 + (def.spawnOffset() != null ? def.spawnOffset().z() : -8);

        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos head = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                int wx = origin.getX() + dx;
                int wz = origin.getZ() + dz;
                double distSqEntry = (wx - entryX) * (wx - entryX) + (wz - entryZ) * (wz - entryZ);
                if (distSqEntry < 49.0) continue; // Keep 7-block safe zone around entrance

                for (int dy = 1; dy <= 44; dy++) {
                    int wy = origin.getY() + dy;
                    below.set(wx, wy - 1, wz);
                    feet.set(wx, wy, wz);
                    head.set(wx, wy + 1, wz);
                    BlockState bsBelow = level.getBlockState(below);
                    if (!bsBelow.isAir() && bsBelow.blocksMotion()
                            && !bsBelow.is(Blocks.LAVA) && !bsBelow.is(Blocks.MAGMA_BLOCK)
                            && level.getBlockState(feet).isAir()
                            && level.getBlockState(head).isAir()) {
                        roomSpawnSpots.add(new BlockPos(wx, wy, wz));
                        dy += 3; // Skip upward to next floor/room
                    }
                }
            }
        }
    }

    /**
     * Clean stone/deepslate enclosed arena used automatically when no .nbt map is configured yet.
     */
    private void buildFallbackArena() {
        int r = Math.max(12, Math.min(32, def.arenaRadius()));
        int height = 12;
        BlockState floor = def.tier() >= 2 ? Blocks.POLISHED_DEEPSLATE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
        BlockState accent = def.tier() >= 3 ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        BlockState wall = def.tier() >= 2 ? Blocks.DEEPSLATE_BRICKS.defaultBlockState() : Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        BlockState light = Blocks.SEA_LANTERN.defaultBlockState();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean border = Math.abs(dx) == r || Math.abs(dz) == r;
                boolean lanternSpot = (dx % 6 == 0 && dz % 6 == 0);
                cursor.set(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
                level.setBlock(cursor, lanternSpot ? light : ((dx + dz) % 4 == 0 ? accent : floor), 2);

                for (int dy = 1; dy <= height; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (border || dy == height) {
                        level.setBlock(cursor, dy == height ? Blocks.BARRIER.defaultBlockState() : wall, 2);
                    } else {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    public void teleportPlayerIn(ServerPlayer player) {
        double sx = origin.getX() + 0.5 + (def.spawnOffset() != null ? def.spawnOffset().x() : 0);
        double sz = origin.getZ() + 0.5 + (def.spawnOffset() != null ? def.spawnOffset().z() : -8);
        int baseHint = origin.getY() + (def.spawnOffset() != null ? (int) def.spawnOffset().y() : 1);
        double sy = findSafeFloorY((int) Math.floor(sx), baseHint, (int) Math.floor(sz));
        player.teleportTo(level, sx, sy, sz, 0.0f, 0.0f);
        player.sendSystemMessage(ItemCodecUtil.text(
                "Entrou na dungeon: " + def.displayName() + " (Tempo limite: " + (def.timeLimitSec() / 60) + " min). Use /dungeon sair para abandonar.",
                ChatFormatting.LIGHT_PURPLE
        ));
    }

    private double findSafeFloorY(int x, int startY, int z) {
        for (int y = Math.max(origin.getY() + 1, startY - 4); y <= origin.getY() + 44; y++) {
            BlockPos below = new BlockPos(x, y - 1, z);
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos head = new BlockPos(x, y + 1, z);
            if (!level.getBlockState(below).isAir()
                    && level.getBlockState(below).blocksMotion()
                    && level.getBlockState(feet).isAir()
                    && level.getBlockState(head).isAir()) {
                return y;
            }
        }
        // Guarantee a safety pad if the spot is empty void
        BlockPos pad = new BlockPos(x, Math.max(origin.getY(), startY - 1), z);
        if (level.getBlockState(pad).isAir()) {
            level.setBlock(pad, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        return pad.getY() + 1.0;
    }

    public boolean containsPosition(BlockPos pos) {
        int r = Math.max(24, def.arenaRadius() + 12);
        return Math.abs(pos.getX() - origin.getX()) <= r && Math.abs(pos.getZ() - origin.getZ()) <= r;
    }

    public void tick(MinecraftServer server, DungeonManager manager, double rewardMultiplier) {
        if (phase == Phase.FINISHED) return;
        elapsedTicks++;

        // Remove offline / left-dimension players from active set check
        List<ServerPlayer> activePlayers = getOnlineParticipantsInDim(server);
        if (activePlayers.isEmpty()) {
            abort(server, manager, "Todos os jogadores sairam da dungeon.");
            return;
        }

        // Enforce arena boundary & environmental hazard every second
        if (elapsedTicks % 20 == 0) {
            int maxDist = Math.max(16, def.arenaRadius() + 4);
            for (ServerPlayer p : activePlayers) {
                if (Math.abs(p.getX() - origin.getX()) > maxDist
                        || Math.abs(p.getZ() - origin.getZ()) > maxDist
                        || p.getY() < origin.getY() - 5) {
                    teleportPlayerIn(p);
                }
                // T2/T3 environmental pressure: Farmer's Delight Comfort/Nourishment protects against it
                if (def.environmentalHazard() && (elapsedTicks % 160 == 0) && phase != Phase.VICTORY) {
                    if (!hasChefProtection(p)) {
                        p.addEffect(new MobEffectInstance(MobEffects.HUNGER, 100, 1, false, true));
                        p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0, false, true));
                        if (elapsedTicks % 320 == 0) {
                            p.displayClientMessage(ItemCodecUtil.text(
                                    "O ambiente da dungeon esta drenando sua energia! Traga banquetes/pocoes do Chef.",
                                    ChatFormatting.RED
                            ), true);
                        }
                    }
                }
            }
        }

        // Time limit check
        if (phase != Phase.VICTORY && elapsedSeconds() >= def.timeLimitSec()) {
            abort(server, manager, "Tempo limite esgotado!");
            return;
        }

        // Clean dead mobs from tracking
        if (elapsedTicks % 10 == 0) {
            Iterator<UUID> it = aliveMobs.iterator();
            while (it.hasNext()) {
                Entity e = level.getEntity(it.next());
                if (e == null || !e.isAlive()) {
                    it.remove();
                }
            }
        }

        if (phase == Phase.WAVE) {
            int totalWaves = def.waves() == null ? 0 : def.waves().size();
            if (aliveMobs.isEmpty()) {
                waveIndex++;
                if (waveIndex < totalWaves) {
                    spawnCurrentWave();
                } else {
                    spawnBoss();
                }
            } else {
                // Highlight remaining mobs with Glowing when <= 3 left so players never get stuck searching rooms
                if (aliveMobs.size() <= 3 && elapsedTicks % 40 == 0) {
                    for (UUID mid : aliveMobs) {
                        Entity e = level.getEntity(mid);
                        if (e instanceof LivingEntity living) {
                            living.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
                        }
                    }
                }
                String title = def.displayName() + " — Onda " + (waveIndex + 1) + "/" + Math.max(1, totalWaves)
                        + " (Restam " + aliveMobs.size() + " inimigos nas salas)";
                bossBar.setName(ItemCodecUtil.text(title, ChatFormatting.YELLOW, ChatFormatting.BOLD));
                bossBar.setProgress(Math.min(1.0f, Math.max(0.05f, aliveMobs.size() / 10.0f)));
            }
        } else if (phase == Phase.BOSS) {
            Entity boss = bossUuid == null ? null : level.getEntity(bossUuid);
            if (boss == null || !boss.isAlive()) {
                onVictory(server, manager, activePlayers, rewardMultiplier);
            } else if (boss instanceof LivingEntity living) {
                float pct = Math.max(0f, Math.min(1f, living.getHealth() / Math.max(1f, living.getMaxHealth())));
                bossBar.setColor(BossEvent.BossBarColor.RED);
                bossBar.setName(ItemCodecUtil.text(
                        "BOSS: " + def.boss().displayName() + " (" + (int) living.getHealth() + "/" + (int) living.getMaxHealth() + " HP)",
                        ChatFormatting.RED, ChatFormatting.BOLD
                ));
                bossBar.setProgress(pct);
            }
        } else if (phase == Phase.VICTORY) {
            exitCountdownTicks--;
            if (exitCountdownTicks % 20 == 0) {
                int sec = Math.max(0, exitCountdownTicks / 20);
                bossBar.setColor(BossEvent.BossBarColor.GREEN);
                bossBar.setName(ItemCodecUtil.text("VITORIA! Retornando em " + sec + "s...", ChatFormatting.GREEN, ChatFormatting.BOLD));
                bossBar.setProgress(sec / 20.0f);
            }
            if (exitCountdownTicks <= 0) {
                finishAndEvict(server, manager);
            }
        }
    }

    private void spawnCurrentWave() {
        List<WaveDef> waves = def.waves();
        if (waves == null || waveIndex >= waves.size()) {
            spawnBoss();
            return;
        }
        WaveDef wave = waves.get(waveIndex);
        int partySize = Math.max(1, participants.size());
        double partyScale = 1.0 + (partySize - 1) * 0.25;
        int r = Math.max(6, def.arenaRadius() - 4);

        broadcast(ItemCodecUtil.text(">> " + wave.title(), ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (WaveMob wm : wave.mobs()) {
            int count = Math.max(1, (int) Math.round(wm.count() * (1.0 + (partySize - 1) * 0.35)));
            for (int i = 0; i < count; i++) {
                double x, y, z;
                if (!roomSpawnSpots.isEmpty()) {
                    BlockPos spot = roomSpawnSpots.get(random.nextInt(roomSpawnSpots.size()));
                    x = spot.getX() + 0.5;
                    y = spot.getY();
                    z = spot.getZ() + 0.5;
                } else {
                    double angle = random.nextDouble() * Math.PI * 2;
                    double dist = 4 + random.nextDouble() * Math.max(2, r - 4);
                    x = origin.getX() + 0.5 + Math.cos(angle) * dist;
                    z = origin.getZ() + 0.5 + Math.sin(angle) * dist;
                    y = findSafeFloorY((int) Math.floor(x), origin.getY() + 2, (int) Math.floor(z));
                }
                spawnEntity(wm.entityId(), x, y, z, wm.hpMultiplier() * partyScale, null);
            }
        }
    }

    private void spawnBoss() {
        phase = Phase.BOSS;
        BossDef bossDef = def.boss();
        if (bossDef == null || bossDef.entityId() == null || bossDef.entityId().isBlank()) {
            bossUuid = null;
            return;
        }
        int partySize = Math.max(1, participants.size());
        double scale = bossDef.hpMultiplier() * (1.0 + (partySize - 1) * 0.30);
        double bx = origin.getX() + 0.5 + (bossDef.spawnOffset() != null ? bossDef.spawnOffset().x() : 0);
        double bz = origin.getZ() + 0.5 + (bossDef.spawnOffset() != null ? bossDef.spawnOffset().z() : 8);
        int bHint = origin.getY() + (bossDef.spawnOffset() != null ? (int) bossDef.spawnOffset().y() : 2);
        double by = findSafeFloorY((int) Math.floor(bx), bHint, (int) Math.floor(bz));

        Entity spawned = spawnEntity(bossDef.entityId(), bx, by, bz, scale, bossDef.displayName());
        if (spawned != null) {
            bossUuid = spawned.getUUID();
        }
        broadcast(ItemCodecUtil.text("!! BOSS FINAL: " + bossDef.displayName() + " apareceu !!", ChatFormatting.RED, ChatFormatting.BOLD));
    }

    private Entity spawnEntity(String entityId, double x, double y, double z, double hpMult, String customName) {
        ResourceLocation loc = ResourceLocation.tryParse(entityId);
        if (loc == null) return null;
        Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(loc);
        if (typeOpt.isEmpty()) return null;
        Entity entity = typeOpt.get().create(level);
        if (entity == null) return null;
        entity.moveTo(x, y, z, random.nextFloat() * 360f, 0f);
        if (entity instanceof LivingEntity living) {
            AttributeInstance maxHp = living.getAttribute(Attributes.MAX_HEALTH);
            if (maxHp != null && hpMult > 0 && hpMult != 1.0) {
                maxHp.setBaseValue(Math.max(4.0, maxHp.getBaseValue() * hpMult));
                living.setHealth(living.getMaxHealth());
            }
            if (entity instanceof Mob mob) {
                mob.setPersistenceRequired();
            }
        }
        if (customName != null && !customName.isBlank()) {
            entity.setCustomName(ItemCodecUtil.text(customName, ChatFormatting.RED, ChatFormatting.BOLD));
            entity.setCustomNameVisible(true);
        }
        level.addFreshEntity(entity);
        aliveMobs.add(entity.getUUID());
        return entity;
    }

    private void onVictory(MinecraftServer server, DungeonManager manager, List<ServerPlayer> players, double rewardMultiplier) {
        if (victoryRecorded) return;
        victoryRecorded = true;
        phase = Phase.VICTORY;
        int duration = elapsedSeconds();
        int finalSpurs = (int) Math.round(def.rewardSpurs() * Math.max(1.0, rewardMultiplier));

        List<String> names = new ArrayList<>();
        for (ServerPlayer p : players) {
            names.add(p.getGameProfile().getName());
            if (finalSpurs > 0) {
                NumismaticsBridge.deposit(p.getUUID(), finalSpurs);
            }
            List<String> drops = new ArrayList<>();
            if (def.rewardItems() != null) {
                for (RewardItem ri : def.rewardItems()) {
                    if (random.nextDouble() <= ri.chance()) {
                        ItemStack stack = ItemCodecUtil.fromIdOrSnbt(ri.itemId(), null, ri.count(), p.registryAccess());
                        if (!stack.isEmpty()) {
                            EconomyService.giveItemOrDrop(p, stack, stack.getCount());
                            drops.add(stack.getCount() + "x " + stack.getHoverName().getString());
                        }
                    }
                }
            }
            p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0f, 1.0f);
            p.sendSystemMessage(ItemCodecUtil.text(
                    "VITORIA em " + def.displayName() + " (" + duration + "s)! Recebeu "
                            + NumismaticsBridge.formatSpurs(finalSpurs)
                            + (rewardMultiplier > 1.0 ? " [Bonus Hora da Dungeon!]" : "")
                            + (drops.isEmpty() ? "" : " + " + String.join(", ", drops)),
                    ChatFormatting.GREEN, ChatFormatting.BOLD
            ));
        }

        manager.recordRunCompletion(def, players, names, duration, true);
    }

    public void abort(MinecraftServer server, DungeonManager manager, String reason) {
        broadcast(ItemCodecUtil.text("Dungeon encerrada: " + reason, ChatFormatting.RED));
        List<ServerPlayer> online = getOnlineParticipantsInDim(server);
        List<String> names = online.stream().map(p -> p.getGameProfile().getName()).toList();
        if (!victoryRecorded) {
            manager.recordRunCompletion(def, online, names, elapsedSeconds(), false);
        }
        finishAndEvict(server, manager);
    }

    public void finishAndEvict(MinecraftServer server, DungeonManager manager) {
        phase = Phase.FINISHED;
        bossBar.removeAllPlayers();
        for (UUID mobId : aliveMobs) {
            Entity e = level.getEntity(mobId);
            if (e != null) e.discard();
        }
        aliveMobs.clear();
        for (UUID pid : new ArrayList<>(participants)) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) {
                manager.returnPlayerToSavedPosition(p);
            }
        }
        participants.clear();
        clearSlotArea();
    }

    public void removePlayer(ServerPlayer player) {
        participants.remove(player.getUUID());
        bossBar.removePlayer(player);
    }

    private List<ServerPlayer> getOnlineParticipantsInDim(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID pid : participants) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null && p.isAlive() && p.serverLevel() == level) {
                out.add(p);
            }
        }
        return out;
    }

    private void broadcast(Component msg) {
        for (ServerPlayer p : bossBar.getPlayers()) {
            p.sendSystemMessage(msg);
        }
    }

    private static boolean hasChefProtection(ServerPlayer player) {
        for (MobEffectInstance inst : player.getActiveEffects()) {
            String key = inst.getEffect().unwrapKey().map(k -> k.location().toString()).orElse("");
            if (key.contains("comfort") || key.contains("nourishment") || key.contains("fire_resistance") || key.contains("regeneration")) {
                return true;
            }
        }
        return false;
    }
}
