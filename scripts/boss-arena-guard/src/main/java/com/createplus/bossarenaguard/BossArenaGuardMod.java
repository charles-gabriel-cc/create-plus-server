package com.createplus.bossarenaguard;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(BossArenaGuardMod.MOD_ID)
public class BossArenaGuardMod {
    public static final String MOD_ID = "bossarenaguard";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, ArenaSession> ARENAS_BY_BOSS = new ConcurrentHashMap<>();
    private long serverTickCounter = 0L;
    private ArenaProfileDatabase database;
    private long appliedDatabaseRevision;

    public BossArenaGuardMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onProjectileImpact);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onLivingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(this::onLivingDamagePost);
        NeoForge.EVENT_BUS.addListener(this::onLivingChangeTarget);
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(this::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onCanPlayerSleep);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onPlayerSetSpawn);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onPlayerRespawnPosition);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, this::onEntityTeleport);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onEntityMount);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onEntityTravelToDimension);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        LOGGER.info("Create+ Boss Arena & Lantern Guard initialized.");
    }

    private void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Projectile proj) {
            for (ArenaSession session : ARENAS_BY_BOSS.values()) {
                if (!session.isFinished() && session.getLevel() == level) {
                    session.classifyProjectileOnSpawn(proj);
                }
            }
        }
    }

    private void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile proj = event.getProjectile();
        if (!(proj.level() instanceof ServerLevel level)) {
            return;
        }
        HitResult hit = event.getRayTraceResult();
        if (hit instanceof EntityHitResult ehr) {
            LivingEntity hitBoss = BossRegistryHelper.unwrapBoss(ehr.getEntity());
            if (hitBoss != null && hitBoss.isAlive() && !hitBoss.isRemoved()) {
                getOrCreateSession(level, hitBoss);
            }
        }

        for (ArenaSession session : ARENAS_BY_BOSS.values()) {
            if (session.isFinished() || session.getLevel() != level) {
                continue;
            }
            if (!session.isOutsiderProjectile(proj)) {
                continue;
            }
            boolean hitsArena = session.isInsideInterior(hit.getLocation(), -0.75);
            if (!hitsArena && hit instanceof EntityHitResult ehr2) {
                Entity target = ehr2.getEntity();
                LivingEntity targetBoss = BossRegistryHelper.unwrapBoss(target);
                hitsArena = (targetBoss != null && targetBoss.getUUID().equals(session.getBossUuid()))
                        || session.isInsideInterior(target.position(), -0.75);
            }
            if (hitsArena) {
                event.setCanceled(true);
                session.deflectOrDestroyOutsiderProjectile(proj);
                return;
            }
        }
    }

    private void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        if (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return;
        }

        LivingEntity victimBoss = BossRegistryHelper.unwrapBoss(event.getEntity());
        if (victimBoss != null) {
            if (isCreateLanternsDamage(source)) {
                event.setCanceled(true);
                if (victimBoss.level() instanceof ServerLevel sl) {
                    sl.sendParticles(
                            ParticleTypes.ELECTRIC_SPARK,
                            victimBoss.getX(),
                            victimBoss.getY() + victimBoss.getBbHeight() * 0.5,
                            victimBoss.getZ(),
                            10, 0.5, 0.5, 0.5, 0.08
                    );
                    sl.sendParticles(
                            ParticleTypes.CRIT,
                            victimBoss.getX(),
                            victimBoss.getY() + victimBoss.getBbHeight() * 0.5,
                            victimBoss.getZ(),
                            6, 0.4, 0.4, 0.4, 0.05
                    );
                    sl.playSound(
                            null,
                            victimBoss.getX(),
                            victimBoss.getY(),
                            victimBoss.getZ(),
                            SoundEvents.SHIELD_BLOCK,
                            SoundSource.HOSTILE,
                            0.9f,
                            1.35f
                    );
                }
                if (source.getEntity() instanceof ServerPlayer attacker) {
                    attacker.displayClientMessage(
                            Component.literal("§c✦ Este boss é imune aos poderes dos Anéis de Energia!"),
                            true
                    );
                }
                return;
            }

            // Block any damage to the boss originating from outside the circular arena barrier
            if (victimBoss.level() instanceof ServerLevel sl) {
                Entity attacker = source.getEntity();
                Entity direct = source.getDirectEntity();
                ArenaSession active = (attacker != null || direct != null)
                        ? getOrCreateSession(sl, victimBoss)
                        : ARENAS_BY_BOSS.get(victimBoss.getUUID());
                if (active != null && !active.isFinished() && active.isDamageFromOutside(source)) {
                    event.setCanceled(true);
                    if (direct instanceof Projectile proj) {
                        active.deflectOrDestroyOutsiderProjectile(proj);
                    } else {
                        active.triggerOutsideAttackBlockedFeedback(
                                attacker,
                                victimBoss.position(),
                                "§c⛨ O boss não pode receber dano de fora da arena! Entre na barreira para lutar."
                        );
                    }
                    return;
                }
            }
            return;
        }

        if (event.getEntity() instanceof ServerPlayer victimPlayer) {
            for (ArenaSession session : ARENAS_BY_BOSS.values()) {
                if (session.isSealed()
                        && session.getLevel() == victimPlayer.serverLevel()
                        && session.isActiveParticipant(victimPlayer.getUUID())) {
                    if (session.isDamageFromOutside(source)) {
                        event.setCanceled(true);
                        if (source.getDirectEntity() instanceof Projectile proj) {
                            session.deflectOrDestroyOutsiderProjectile(proj);
                        } else {
                            session.triggerOutsideAttackBlockedFeedback(
                                    source.getEntity(),
                                    victimPlayer.position(),
                                    "§c✖ A barreira protege os combatentes lá dentro contra ataques vindos de fora!"
                            );
                        }
                        return;
                    }
                }
            }
        }
    }

    private void onLivingDamagePost(LivingDamageEvent.Post event) {
        LivingEntity victimBoss = BossRegistryHelper.unwrapBoss(event.getEntity());
        if (victimBoss != null && event.getSource().getEntity() instanceof ServerPlayer player) {
            ArenaSession active = ARENAS_BY_BOSS.get(victimBoss.getUUID());
            if (active != null && active.isSealed()) {
                active.recordParticipantDamage(player.getUUID(), event.getNewDamage());
            }
            if (ArenaSession.isCombatParticipantEligible(player)) {
                handleCombatEngage(victimBoss, player);
            }
            return;
        }

        if (event.getEntity() instanceof ServerPlayer victimPlayer && ArenaSession.isCombatParticipantEligible(victimPlayer)) {
            LivingEntity attackerBoss = BossRegistryHelper.unwrapBoss(event.getSource().getEntity());
            if (attackerBoss != null) {
                handleCombatEngage(attackerBoss, victimPlayer);
            }
        }
    }

    private void onLivingChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity boss = BossRegistryHelper.unwrapBoss(event.getEntity());
        if (boss == null) {
            return;
        }
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
        ArenaSession existing = ARENAS_BY_BOSS.get(boss.getUUID());
        if (existing != null && existing.isSealed()) {
            if (newTarget != null) {
                if (!existing.isInsideInterior(newTarget.position(), 0.0)
                        || (newTarget instanceof ServerPlayer sp && !existing.isActiveParticipant(sp.getUUID()))) {
                    event.setCanceled(true);
                    return;
                }
            }
        }
        if (newTarget instanceof ServerPlayer player
                && ArenaSession.isCombatParticipantEligible(player)
                && boss.level() instanceof ServerLevel level) {
            if (existing != null) {
                if (existing.isStandby()
                        && existing.horizontalDist(player.position()) <= existing.getRadius() * 0.92
                        && player.getY() >= existing.getMinY()
                        && player.getY() <= existing.getMaxY()) {
                    existing.triggerCombatCountdown();
                }
                return;
            }
            BossRegistryHelper.ArenaGeometry geometry = BossRegistryHelper.computeArenaGeometry(level, boss);
            double dx = player.getX() - geometry.center().x;
            double dz = player.getZ() - geometry.center().z;
            double hDist = Math.sqrt(dx * dx + dz * dz);
            if (hDist <= geometry.radius() * 0.92
                    && player.getY() >= geometry.minY()
                    && player.getY() <= geometry.maxY()) {
                ArenaSession session = getOrCreateSession(level, boss, geometry);
                if (session != null) {
                    session.triggerCombatCountdown();
                }
            }
        }
    }

    private void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            for (ArenaSession session : ARENAS_BY_BOSS.values()) {
                if (session.isSealed() && session.isActiveParticipant(player.getUUID())) {
                    session.handleParticipantDeath(player);
                }
            }
            return;
        }

        LivingEntity boss = BossRegistryHelper.unwrapBoss(event.getEntity());
        if (boss != null) {
            ArenaSession session = ARENAS_BY_BOSS.remove(boss.getUUID());
            if (session != null && session.isSealed()) {
                session.endVictory(boss);
            }
        }
    }

    private void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ArenaSession.isCombatParticipantEligible(player)) {
            return;
        }

        for (ArenaSession session : ARENAS_BY_BOSS.values()) {
            if (!session.isSealed() || session.getLevel() != player.serverLevel()) {
                continue;
            }
            if (session.isFallenParticipant(player.getUUID()) && !session.isDeserter(player.getUUID())) {
                if (session.isInsideInterior(player.position(), 3.5)) {
                    session.readmitRevivedParticipant(player);
                } else {
                    player.displayClientMessage(
                            Component.literal("§e⛨ A luta contra o boss ainda está acontecendo! Só será possível entrar ao término ou se for revivido lá dentro."),
                            false
                    );
                }
            }
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            handlePlayerExitOrDisconnect(player);
            for (ArenaSession session : ARENAS_BY_BOSS.values()) {
                session.clearClientBarriers(player);
            }
        }
    }

    private void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            handlePlayerExitOrDisconnect(player);
            for (ArenaSession session : ARENAS_BY_BOSS.values()) {
                session.clearClientBarriers(player);
            }
        }
    }

    private void handlePlayerExitOrDisconnect(ServerPlayer player) {
        for (ArenaSession session : ARENAS_BY_BOSS.values()) {
            if (session.isSealed() && session.isActiveParticipant(player.getUUID())) {
                session.handleParticipantLeft(player);
            }
        }
    }

    private void onCanPlayerSleep(CanPlayerSleepEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = player.serverLevel();
            BlockPos pos = event.getPos();
            if (isInsideAnyArena(level, pos) || isInsideAnyArena(level, player.position())) {
                event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
                player.displayClientMessage(
                        Component.literal("\u00a7c\u26a0 N\u00e3o \u00e9 permitido dormir dentro de uma arena de boss!"),
                        true
                );
            }
        }
    }

    private void onPlayerSetSpawn(PlayerSetSpawnEvent event) {
        if (event.isCanceled() || event.getNewSpawn() == null) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel targetLevel = player.server.getLevel(event.getSpawnLevel());
            if (targetLevel != null && isInsideAnyArena(targetLevel, event.getNewSpawn())) {
                event.setCanceled(true);
                player.displayClientMessage(
                        Component.literal("\u00a7c\u26a0 N\u00e3o \u00e9 permitido definir ponto de renascimento dentro de uma arena de boss!"),
                        true
                );
            }
        }
    }

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockPos clickedPos = event.getPos();
            if (isInsideAnyArena(level, clickedPos) || isInsideAnyArena(level, event.getEntity().position())) {
                BlockState state = level.getBlockState(clickedPos);
                if (state.getBlock() instanceof BedBlock || state.getBlock() instanceof RespawnAnchorBlock || state.is(BlockTags.BEDS)) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    if (event.getEntity() instanceof ServerPlayer player) {
                        player.displayClientMessage(
                                Component.literal("\u00a7c\u26a0 N\u00e3o \u00e9 permitido usar camas ou definir spawn dentro de uma arena de boss!"),
                                true
                        );
                    }
                }
            }
        }
    }

    private void onPlayerRespawnPosition(PlayerRespawnPositionEvent event) {
        DimensionTransition dt = event.getDimensionTransition();
        if (dt == null) {
            return;
        }
        ServerLevel level = dt.newLevel();
        Vec3 spawnPos = dt.pos();
        for (ArenaSession session : ARENAS_BY_BOSS.values()) {
            if (!session.isFinished() && session.getLevel() == level && session.isInsideInterior(spawnPos, 0.0)) {
                Vec3 center = session.getCenter();
                double dx = spawnPos.x - center.x;
                double dz = spawnPos.z - center.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                double safeDist = session.getRadius() + 3.0;
                double factor = dist > 0.001 ? safeDist / dist : 1.0;
                double safeX = dist > 0.001 ? center.x + dx * factor : center.x + safeDist;
                double safeZ = dist > 0.001 ? center.z + dz * factor : center.z;
                Vec3 newPos = new Vec3(safeX, spawnPos.y, safeZ);
                event.setDimensionTransition(new DimensionTransition(
                        level,
                        newPos,
                        dt.speed(),
                        dt.yRot(),
                        dt.xRot(),
                        dt.missingRespawnBlock(),
                        dt.postDimensionTransition()
                ));
                break;
            }
        }
    }

    public static boolean isInsideAnyArena(ServerLevel level, BlockPos pos) {
        return isInsideAnyArena(level, Vec3.atCenterOf(pos));
    }

    public static boolean isInsideArenaSession(ServerLevel level, Vec3 pos) {
        for (ArenaSession session : ARENAS_BY_BOSS.values()) {
            if (!session.isFinished() && session.getLevel() == level && session.isInsideInterior(pos, 0.0)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isInsideAnyArena(ServerLevel level, Vec3 pos) {
        if (isInsideArenaSession(level, pos)) {
            return true;
        }
        List<LivingEntity> nearbyBosses = level.getEntitiesOfClass(
                LivingEntity.class,
                new AABB(pos.x - 64.0, pos.y - 32.0, pos.z - 64.0, pos.x + 64.0, pos.y + 32.0, pos.z + 64.0),
                BossRegistryHelper::isBoss
        );
        for (LivingEntity boss : nearbyBosses) {
            LivingEntity primary = BossRegistryHelper.unwrapBoss(boss);
            if (primary != null && primary.isAlive() && !primary.isRemoved() && !primary.isDeadOrDying()) {
                BossRegistryHelper.ArenaGeometry geom = BossRegistryHelper.computeArenaGeometry(level, primary);
                double dx = pos.x - geom.center().x;
                double dz = pos.z - geom.center().z;
                double hDist = Math.sqrt(dx * dx + dz * dz);
                if (hDist <= geom.radius() && pos.y >= geom.minY() && pos.y <= geom.maxY()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void onEntityTeleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (entity == null || entity.level().isClientSide()) {
            return;
        }

        // 1. Check if a boss is attempting to teleport outside its arena
        LivingEntity boss = BossRegistryHelper.unwrapBoss(entity);
        if (boss != null) {
            ArenaSession session = ARENAS_BY_BOSS.get(boss.getUUID());
            if (session != null && !session.isFinished()) {
                Vec3 to = event.getTarget();
                if (!session.isInsideInterior(to, 1.5)) {
                    event.setCanceled(true);
                    return;
                }
            }
        }

        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        if (player.isCreative() || player.isSpectator()) {
            return;
        }

        ServerLevel level = player.serverLevel();
        Vec3 to = event.getTarget();
        Vec3 from = event.getPrev();
        boolean isItemEscape = (event instanceof EntityTeleportEvent.EnderPearl)
                || (event instanceof EntityTeleportEvent.ChorusFruit);

        for (ArenaSession session : ARENAS_BY_BOSS.values()) {
            if (!session.isSealed() || session.getLevel() != level) {
                continue;
            }

            if (session.isActiveParticipant(player.getUUID())) {
                if (!session.isInsideInterior(to, 1.0)) {
                    event.setCanceled(true);
                    player.displayClientMessage(Component.literal("§6⛨ Teleporte bloqueado pela barreira da arena!"), true);
                    level.playSound(null, from.x, from.y, from.z, SoundEvents.SHIELD_BLOCK, SoundSource.BLOCKS, 1.0f, 0.8f);
                    return;
                }
            } else {
                boolean targetInsideArenaBand = session.horizontalDist(to) <= session.getRadius() + 1.0
                        && to.y >= session.getMinY() - 2.0
                        && to.y <= session.getMaxY() + 2.0;

                if (targetInsideArenaBand) {
                    if (!isItemEscape
                            && session.isFallenParticipant(player.getUUID())
                            && !session.isDeserter(player.getUUID())
                            && session.isInsideInterior(to, 2.5)
                            && session.isNearLivingActiveAlly(to, 4.5)) {
                        session.readmitRevivedParticipant(player);
                    } else {
                        event.setCanceled(true);
                        player.displayClientMessage(Component.literal("§c✖ A barreira da arena bloqueou seu teleporte!"), true);
                        level.playSound(null, from.x, from.y, from.z, SoundEvents.SHIELD_BLOCK, SoundSource.BLOCKS, 1.0f, 0.7f);
                        return;
                    }
                }
            }
        }
    }

    private void onEntityMount(EntityMountEvent event) {
        if (!event.isMounting()) {
            return;
        }
        // Bosses cannot mount vehicles (boats, minecarts) or be mounted
        LivingEntity bossMounting = BossRegistryHelper.unwrapBoss(event.getEntityMounting());
        LivingEntity bossMounted = BossRegistryHelper.unwrapBoss(event.getEntityBeingMounted());
        if (bossMounting != null || bossMounted != null) {
            event.setCanceled(true);
            return;
        }

        if (!(event.getEntityMounting() instanceof ServerPlayer player)) {
            return;
        }
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        Entity mount = event.getEntityBeingMounted();
        if (mount == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        for (ArenaSession session : ARENAS_BY_BOSS.values()) {
            if (!session.isSealed() || session.getLevel() != level) {
                continue;
            }
            if (session.isActiveParticipant(player.getUUID())) {
                if (!session.isInsideInterior(mount.position(), 1.0)) {
                    event.setCanceled(true);
                    player.displayClientMessage(Component.literal("§6⛨ Você não pode montar em algo fora da arena!"), true);
                    return;
                }
            } else {
                if (session.horizontalDist(mount.position()) <= session.getRadius() + 1.15
                        && mount.getY() >= session.getMinY() - 2.0
                        && mount.getY() <= session.getMaxY() + 2.0) {
                    event.setCanceled(true);
                    player.displayClientMessage(Component.literal("§c✖ Você não pode montar em algo dentro da arena selada!"), true);
                    return;
                }
            }
        }
    }

    private void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        LivingEntity boss = BossRegistryHelper.unwrapBoss(event.getEntity());
        if (boss != null) {
            ArenaSession session = ARENAS_BY_BOSS.get(boss.getUUID());
            if (session != null && !session.isFinished()) {
                event.setCanceled(true);
            }
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bossarena")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("inspect").then(Commands.argument("boss", EntityArgument.entity())
                        .executes(context -> {
                            LivingEntity boss = BossRegistryHelper.unwrapBoss(EntityArgument.getEntity(context, "boss"));
                            if (boss == null || !(boss.level() instanceof ServerLevel level)) {
                                context.getSource().sendFailure(Component.literal("Entity has no arena profile."));
                                return 0;
                            }
                            var geometry = BossRegistryHelper.computeArenaGeometry(level, boss);
                            var id = BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType());
                            context.getSource().sendSuccess(() -> Component.literal("Next arena for " + id
                                    + ": radius=" + geometry.radius() + ", minY=" + geometry.minY()
                                    + ", maxY=" + geometry.maxY()), false);
                            return 1;
                        }))));
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        BossChecklistCatalog.reload();
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (event.getServer().isDedicatedServer()) {
            database = new ArenaProfileDatabase(event.getServer().getServerDirectory().resolve("boss-arena-db.json"));
            appliedDatabaseRevision = 0;
            BossRegistryHelper.setDatabase(database);
            database.start();
        }
    }

    private void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null && BossChecklistCatalog.reload()) {
            clearStandbyArenas();
        }
    }

    private static void clearStandbyArenas() {
        ARENAS_BY_BOSS.values().removeIf(session -> {
            if (session.isStandby()) {
                session.clearAllClientBarriers();
                return true;
            }
            return false;
        });
    }

    private void onServerTickPost(ServerTickEvent.Post event) {
        if (database != null) {
            long revision = database.revision();
            if (revision != appliedDatabaseRevision) {
                appliedDatabaseRevision = revision;
                clearStandbyArenas();
            }
        }
        if ((++serverTickCounter % 10L) == 0L) {
            scanNearbyBosses(event);
        }
        if (ARENAS_BY_BOSS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, ArenaSession>> it = ARENAS_BY_BOSS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ArenaSession> entry = it.next();
            ArenaSession session = entry.getValue();
            session.tick();
            if (session.isFinished()) {
                it.remove();
            }
        }
    }

    private void scanNearbyBosses(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.isSpectator()) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            List<LivingEntity> nearbyBosses = level.getEntitiesOfClass(
                    LivingEntity.class,
                    player.getBoundingBox().inflate(96.0, 48.0, 96.0),
                    BossRegistryHelper::isBoss
            );
            for (LivingEntity boss : nearbyBosses) {
                LivingEntity primaryBoss = BossRegistryHelper.unwrapBoss(boss);
                if (primaryBoss == null || ARENAS_BY_BOSS.containsKey(primaryBoss.getUUID())) {
                    continue;
                }
                if (!primaryBoss.isAlive() || primaryBoss.isRemoved() || primaryBoss.isDeadOrDying()) {
                    continue;
                }
                BossRegistryHelper.ArenaGeometry geometry = BossRegistryHelper.computeArenaGeometry(level, primaryBoss);
                double dx = player.getX() - geometry.center().x;
                double dz = player.getZ() - geometry.center().z;
                double hDist = Math.sqrt(dx * dx + dz * dz);
                if (hDist <= geometry.radius() + 24.0
                        && player.getY() >= geometry.minY() - 6.0 && player.getY() <= geometry.maxY() + 6.0) {
                    getOrCreateSession(level, primaryBoss, geometry);
                }
            }
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        BossChecklistCatalog.clear();
        if (database != null) {
            BossRegistryHelper.setDatabase(null);
            database.close();
            database = null;
        }
        for (ArenaSession session : ARENAS_BY_BOSS.values()) {
            session.clearAllClientBarriers();
        }
        ARENAS_BY_BOSS.clear();
    }

    private void handleCombatEngage(LivingEntity boss, ServerPlayer triggeringPlayer) {
        if (!(boss.level() instanceof ServerLevel level)) {
            return;
        }
        if (!boss.isAlive() || boss.isRemoved() || boss.isDeadOrDying()) {
            return;
        }
        ArenaSession session = getOrCreateSession(level, boss);
        if (session == null || session.isSealed()) {
            return;
        }

        boolean anyEligibleInside = false;
        for (ServerPlayer player : level.players()) {
            if (ArenaSession.isCombatParticipantEligible(player) && session.isInsideInterior(player.position(), 0.0)) {
                anyEligibleInside = true;
                break;
            }
        }

        if (anyEligibleInside) {
            session.triggerCombatCountdown();
        } else if (triggeringPlayer != null) {
            triggeringPlayer.displayClientMessage(
                    Component.literal("§e⚔ Entre na área da barreira do boss para iniciar o combate!"),
                    true
            );
        }
    }

    private ArenaSession getOrCreateSession(ServerLevel level, LivingEntity boss) {
        ArenaSession existing = ARENAS_BY_BOSS.get(boss.getUUID());
        if (existing != null) {
            if (!existing.isFinished()) {
                return existing;
            }
            ARENAS_BY_BOSS.remove(boss.getUUID(), existing);
        }
        BossRegistryHelper.ArenaGeometry geometry = BossRegistryHelper.computeArenaGeometry(level, boss);
        return getOrCreateSession(level, boss, geometry);
    }

    private ArenaSession getOrCreateSession(
            ServerLevel level,
            LivingEntity boss,
            BossRegistryHelper.ArenaGeometry geometry
    ) {
        ArenaSession existing = ARENAS_BY_BOSS.get(boss.getUUID());
        if (existing != null) {
            if (!existing.isFinished()) {
                return existing;
            }
            ARENAS_BY_BOSS.remove(boss.getUUID(), existing);
        }
        ResourceLocation bossId = BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType());
        BossRegistryHelper.BossPalette palette = BossRegistryHelper.getPalette(boss);
        DungeonDifficultyCompat.clearMobScaling(boss);
        ArenaSession created = new ArenaSession(level, boss, bossId, geometry, palette);
        ArenaSession raced = ARENAS_BY_BOSS.putIfAbsent(boss.getUUID(), created);
        return raced != null ? raced : created;
    }

    private static boolean isCreateLanternsDamage(DamageSource source) {
        if (source == null) {
            return false;
        }
        Optional<ResourceKey<DamageType>> keyOpt = source.typeHolder().unwrapKey();
        if (keyOpt.isPresent()) {
            return "createlanterns".equals(keyOpt.get().location().getNamespace());
        }
        return false;
    }
}
