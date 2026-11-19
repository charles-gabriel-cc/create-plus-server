package com.createplus.bossarenaguard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ArenaSession {
    private static final DustColorTransitionOptions GOLD_DUST = new DustColorTransitionOptions(
            new Vector3f(1.0f, 0.85f, 0.20f),
            new Vector3f(1.0f, 0.98f, 0.82f),
            1.7f
    );
    private static final DustColorTransitionOptions REJECT_DUST = new DustColorTransitionOptions(
            new Vector3f(1.0f, 0.16f, 0.16f),
            new Vector3f(1.0f, 0.55f, 0.20f),
            1.8f
    );

    public enum State {
        STANDBY,
        SEALED
    }

    private final UUID bossUuid;
    private final Component bossDisplayName;
    private final ResourceLocation bossTypeId;
    private final ServerLevel level;
    private final Vec3 center;
    private final double radius;
    private final double minY;
    private final double maxY;
    private final DustColorTransitionOptions mainDust;
    private final DustColorTransitionOptions secondaryDust;

    private final Set<UUID> activeParticipants = new HashSet<>();
    private final Set<UUID> fallenParticipants = new HashSet<>();
    private final Set<UUID> deserterParticipants = new HashSet<>();
    private final Map<UUID, Integer> exitAttemptTicks = new HashMap<>();
    private final Map<UUID, Float> damageByParticipant = new HashMap<>();
    private final Map<UUID, Long> feedbackCooldowns = new HashMap<>();
    private final Map<UUID, Set<BlockPos>> clientFakeBarriers = new HashMap<>();

    public boolean isDeserter(UUID playerId) {
        return deserterParticipants.contains(playerId);
    }

    public void recordParticipantDamage(UUID playerId, float amount) {
        if (amount > 0.0f && activeParticipants.contains(playerId)) {
            damageByParticipant.merge(playerId, amount, Float::sum);
        }
    }

    public static final double HP_SCALING_PER_EXTRA_PLAYER = 0.45;
    public static final double DAMAGE_SCALING_PER_EXTRA_PLAYER = 0.10;

    private double originalBaseMaxHealth = -1.0;
    private double originalBaseAttackDamage = -1.0;
    private boolean bossScaled = false;

    private State state = State.STANDBY;
    private int countdownTicks = -1;
    private boolean combatTriggered = false;
    private int farIdleTicks = 0;

    private long tickAge = 0L;
    private long missingBossTicks = 0L;
    private boolean finished = false;

    public ArenaSession(
            ServerLevel level,
            LivingEntity boss,
            ResourceLocation bossTypeId,
            BossRegistryHelper.ArenaGeometry geometry,
            BossRegistryHelper.BossPalette palette
    ) {
        this.level = level;
        this.bossUuid = boss.getUUID();
        this.bossDisplayName = boss.getDisplayName();
        this.bossTypeId = bossTypeId;
        this.center = geometry.center();
        this.radius = geometry.radius();
        this.minY = geometry.minY();
        this.maxY = geometry.maxY();
        this.mainDust = new DustColorTransitionOptions(palette.fromColor(), palette.toColor(), 1.65f);
        this.secondaryDust = new DustColorTransitionOptions(palette.toColor(), palette.fromColor(), 1.35f);
    }

    public UUID getBossUuid() {
        return bossUuid;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public Vec3 getCenter() {
        return center;
    }

    public double getRadius() {
        return radius;
    }

    public double getMinY() {
        return minY;
    }

    public double getMaxY() {
        return maxY;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isSealed() {
        return state == State.SEALED && !finished;
    }

    public boolean isStandby() {
        return state == State.STANDBY && !finished;
    }

    public void triggerCombatCountdown() {
        if (state == State.STANDBY && !finished) {
            this.combatTriggered = true;
            if (this.countdownTicks < 0) {
                this.countdownTicks = 60;
            }
        }
    }

    public boolean hasAnyActiveParticipants() {
        return !activeParticipants.isEmpty();
    }

    public boolean isActiveParticipant(UUID playerId) {
        return activeParticipants.contains(playerId);
    }

    public boolean isFallenParticipant(UUID playerId) {
        return fallenParticipants.contains(playerId);
    }

    public static boolean isCombatParticipantEligible(ServerPlayer player) {
        return player != null
                && !player.isRemoved()
                && player.isAlive()
                && !player.isDeadOrDying()
                && !player.isCreative()
                && !player.isSpectator();
    }

    public double horizontalDist(Vec3 pos) {
        double dx = pos.x - center.x;
        double dz = pos.z - center.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean isInsideInterior(Vec3 pos, double margin) {
        return horizontalDist(pos) <= Math.max(2.0, radius - margin)
                && pos.y >= minY
                && pos.y <= maxY;
    }

    public boolean isNearLivingActiveAlly(Vec3 pos, double maxDist) {
        double maxDistSq = maxDist * maxDist;
        for (UUID allyId : activeParticipants) {
            ServerPlayer ally = getServerPlayer(allyId);
            if (ally != null && ally.serverLevel() == level && isCombatParticipantEligible(ally)) {
                if (ally.position().distanceToSqr(pos) <= maxDistSq) {
                    return true;
                }
            }
        }
        return false;
    }

    public void playOpeningAnimation() {
        int steps = Math.min(64, Math.max(28, (int) Math.round(radius * 1.5)));
        List<ServerPlayer> viewers = getNearbyViewers();
        for (int i = 0; i < steps; i++) {
            double angle = (Math.PI * 2.0 * i) / steps;
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;
            sendParticleToViewers(viewers, mainDust, x, center.y + 0.5, z, 2, 0.15, 0.3, 0.15, 0.02);
            sendParticleToViewers(viewers, secondaryDust, x, center.y + 2.0, z, 2, 0.15, 0.3, 0.15, 0.02);
            sendParticleToViewers(viewers, ParticleTypes.END_ROD, x, center.y + 3.5, z, 1, 0.1, 0.6, 0.1, 0.04);
        }

        level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.2f, 0.85f);
        Component msg = Component.literal("§6⚔ §eArena selada contra §c")
                .append(bossDisplayName)
                .append(Component.literal(" §7(Raio: " + (int) Math.round(radius) + "m) §e— Derrote-o para sair!"));
        for (UUID id : activeParticipants) {
            ServerPlayer p = getServerPlayer(id);
            if (p != null) {
                p.displayClientMessage(msg, false);
            }
        }
    }

    public void tick() {
        if (finished) {
            return;
        }
        tickAge++;

        LivingEntity boss = resolveBossEntity();
        if (boss == null) {
            missingBossTicks++;
            if (missingBossTicks > 80L) {
                if (state == State.SEALED) {
                    endVictory(null);
                } else {
                    restoreBossAttributes(null);
                    finished = true;
                }
            }
            return;
        }
        missingBossTicks = 0L;

        if (!boss.isAlive() || boss.isRemoved() || boss.isDeadOrDying()) {
            if (state == State.SEALED) {
                endVictory(boss);
            } else {
                restoreBossAttributes(boss);
                finished = true;
            }
            return;
        }

        if (state == State.STANDBY) {
            tickStandby(boss);
            return;
        }

        cleanupInvalidActiveParticipants();
        if (activeParticipants.isEmpty()) {
            triggerWipe(boss);
            return;
        }

        keepBossInside(boss);
        enforcePlayerBoundaries();
        enforceProjectileBarrier();
        renderForcefield();
        for (ServerPlayer player : level.players()) {
            updateClientBarriers(player);
        }
    }

    private void tickStandby(LivingEntity boss) {
        clearAllClientBarriers();
        keepBossInside(boss);
        enforceProjectileBarrier();
        renderForcefield();

        boolean anyNearbyPlayer = false;
        boolean anyTriggeredInside = false;
        List<ServerPlayer> insidePerimeter = new ArrayList<>();

        for (ServerPlayer player : level.players()) {
            double hDist = horizontalDist(player.position());
            if (hDist <= radius + 48.0) {
                anyNearbyPlayer = true;
            }
            if (isCombatParticipantEligible(player)) {
                if (isInsideInterior(player.position(), 0.0)) {
                    insidePerimeter.add(player);
                    LanternFlightCompat.stopFlightIfActive(player);
                    if (player.isSleeping()) {
                        player.stopSleeping();
                        player.displayClientMessage(
                                Component.literal("\u00a7c\u26a0 Voc\u00ea foi acordado pelo despertar do boss!"),
                                true
                        );
                    }
                }
                if (isInsideInterior(player.position(), 1.5)) {
                    anyTriggeredInside = true;
                }
            }
        }

        if (!anyNearbyPlayer && countdownTicks < 0) {
            farIdleTicks++;
            if (farIdleTicks > 200) {
                restoreBossAttributes(boss);
                finished = true;
            }
            return;
        }
        farIdleTicks = 0;

        if (anyTriggeredInside && countdownTicks < 0) {
            countdownTicks = 60;
        }

        if (countdownTicks >= 0) {
            if (insidePerimeter.isEmpty()) {
                countdownTicks = -1;
                combatTriggered = false;
                return;
            }

            if (countdownTicks == 60 || countdownTicks == 40 || countdownTicks == 20) {
                int sec = Math.max(1, (countdownTicks + 19) / 20);
                float pitch = 1.0f + (3 - sec) * 0.2f;
                level.playSound(null, center.x, center.y, center.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.2f, pitch);
                Component countdownMsg = Component.literal("§6⏳ Selando a arena de §c")
                        .append(bossDisplayName)
                        .append(Component.literal(" §6em " + sec + "s... Entrem na arena!"));
                double notifyRadiusSq = (radius + 24.0) * (radius + 24.0);
                for (ServerPlayer player : level.players()) {
                    double dx = player.getX() - center.x;
                    double dz = player.getZ() - center.z;
                    if (dx * dx + dz * dz <= notifyRadiusSq) {
                        player.displayClientMessage(countdownMsg, true);
                    }
                }
            }

            countdownTicks--;
            if (countdownTicks <= 0) {
                for (ServerPlayer player : insidePerimeter) {
                    activeParticipants.add(player.getUUID());
                }
                if (!activeParticipants.isEmpty()) {
                    state = State.SEALED;
                    feedbackCooldowns.clear();
                    applyMultiplayerScaling(boss);
                    playOpeningAnimation();
                    BossArenaGuardMod.LOGGER.info(
                            "Sealed boss arena for {} (radius={}, center=({}, {}, {}), participants={})",
                            bossTypeId,
                            Math.round(radius),
                            Math.round(center.x),
                            Math.round(center.y),
                            Math.round(center.z),
                            activeParticipants.size()
                    );
                } else {
                    countdownTicks = -1;
                    combatTriggered = false;
                }
            }
        } else {
            long now = level.getGameTime();
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator()) {
                    continue;
                }
                Vec3 pos = player.position();
                double hd = horizontalDist(pos);
                if (hd >= radius - 1.5 && hd <= radius + 6.0 && pos.y >= minY - 3.0 && pos.y <= maxY + 3.0) {
                    Long last = feedbackCooldowns.get(player.getUUID());
                    if (last == null || now - last >= 30L) {
                        feedbackCooldowns.put(player.getUUID(), now);
                        player.displayClientMessage(
                                Component.literal("§e⛨ Fronteira da Arena: §6")
                                        .append(bossDisplayName)
                                        .append(Component.literal(" §7(Raio: " + (int) Math.round(radius) + "m) — Cruze a barreira para iniciar!")),
                                true
                        );
                    }
                }
            }
        }
    }

    private LivingEntity resolveBossEntity() {
        var entity = level.getEntity(bossUuid);
        if (entity instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    private void cleanupInvalidActiveParticipants() {
        List<UUID> toRemove = null;
        for (UUID id : activeParticipants) {
            ServerPlayer player = getServerPlayer(id);
            if (player == null || player.serverLevel() != level || player.isCreative() || player.isSpectator()) {
                if (toRemove == null) {
                    toRemove = new ArrayList<>();
                }
                toRemove.add(id);
            }
        }
        if (toRemove != null) {
            for (UUID id : toRemove) {
                activeParticipants.remove(id);
                fallenParticipants.add(id);
            }
        }
    }

    private void keepBossInside(LivingEntity boss) {
        if (boss.isPassenger()) {
            boss.stopRiding();
        }
        double allowedRadius = "minecraft:ender_dragon".equals(bossTypeId.toString()) ? radius - 1.0 : radius - 2.0;
        Vec3 pos = boss.position();
        double dx = pos.x - center.x;
        double dz = pos.z - center.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        boolean outHoriz = dist > allowedRadius;
        boolean outVert = pos.y > maxY - 1.0 || pos.y < minY - 2.0;

        if (outHoriz || outVert) {
            double newX = pos.x;
            double newZ = pos.z;
            double newY = Math.max(minY + 0.5, Math.min(maxY - 2.0, pos.y));
            if (outHoriz && dist > 0.001) {
                double factor = Math.max(2.0, allowedRadius - 1.5) / dist;
                newX = center.x + dx * factor;
                newZ = center.z + dz * factor;
            }
            boss.teleportTo(newX, newY, newZ);
            Vec3 inward = center.subtract(pos).normalize().scale(0.35);
            boss.setDeltaMovement(inward.x, 0.05, inward.z);
            boss.hurtMarked = true;
            if (boss instanceof Mob mob) {
                mob.getNavigation().stop();
                if (mob.getTarget() != null && !isInsideInterior(mob.getTarget().position(), 0.0)) {
                    mob.setTarget(null);
                }
            }
        }
    }

    private void enforcePlayerBoundaries() {
        for (ServerPlayer player : new ArrayList<>(level.players())) {
            if (!player.isAlive() || player.isDeadOrDying()) {
                continue;
            }
            if (player.isCreative() || player.isSpectator()) {
                continue;
            }

            UUID pid = player.getUUID();
            Vec3 pos = player.position();
            double dx = pos.x - center.x;
            double dz = pos.z - center.z;
            double hDist = Math.sqrt(dx * dx + dz * dz);

            if (activeParticipants.contains(pid)) {
                LanternFlightCompat.stopFlightIfActive(player);
                if (player.isSleeping()) {
                    player.stopSleeping();
                    player.displayClientMessage(
                            Component.literal("\u00a7c\u26a0 Voc\u00ea foi acordado pelo campo de for\u00e7a da arena!"),
                            true
                    );
                }
                boolean outsideHoriz = hDist > radius + 0.6;
                boolean outsideHigh = pos.y > maxY + 0.2;
                boolean outsideLow = pos.y < minY - 0.2;
                boolean atCeiling = pos.y >= maxY - 1.1;
                boolean atExitBorder = hDist >= radius - 0.85 || atCeiling;
                boolean tryingToExit = atExitBorder && isFacingOutward(player, dx, dz, hDist, atCeiling);

                if (tryingToExit) {
                    Vec3 validExit = findValidExitPos(player, pos, dx, dz, hDist);
                    if (validExit == null) {
                        exitAttemptTicks.remove(pid);
                        long now = System.currentTimeMillis();
                        if (now - feedbackCooldowns.getOrDefault(pid, 0L) > 2500L) {
                            feedbackCooldowns.put(pid, now);
                            player.displayClientMessage(
                                    Component.literal("§c⚠ Não há chão no mesmo nível ou espaço livre para sair aqui! Procure uma passagem desobstruída."),
                                    true
                            );
                        }
                    } else {
                        int ticks = exitAttemptTicks.getOrDefault(pid, 0) + 1;
                        exitAttemptTicks.put(pid, ticks);

                        if (ticks >= 25) {
                            handleParticipantVoluntaryExit(player, validExit);
                            if (finished) {
                                return;
                            }
                            continue;
                        }

                        int pct = Math.min(99, (ticks * 100) / 25);
                        String warnSuffix = activeParticipants.size() <= 1
                                ? "§c(Continue por 1.2s para sair — o boss irá resetar!)"
                                : "§c(Continue por 1.2s para desertar — você ficará trancado fora e o boss curará seu dano!)";
                        player.displayClientMessage(
                                Component.literal("§e⚠ Saindo da arena... §6" + pct + "% " + warnSuffix),
                                true
                        );
                        if (ticks % 5 == 0) {
                            float pitch = 0.8f + (ticks / 25.0f) * 0.7f;
                            level.playSound(null, pos.x, pos.y + 1.0, pos.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.0f, pitch);
                            spawnImpactRipple(pos, mainDust);
                        }
                    }
                } else if (exitAttemptTicks.containsKey(pid)) {
                    int nextTicks = exitAttemptTicks.get(pid) - 2;
                    if (nextTicks <= 0) {
                        exitAttemptTicks.remove(pid);
                    } else {
                        exitAttemptTicks.put(pid, nextTicks);
                    }
                }

                if (outsideHoriz || outsideHigh || outsideLow) {
                    pushPlayerInside(player, pos, dx, dz, hDist, outsideHoriz, outsideHigh, outsideLow, tryingToExit);
                }
            } else {
                boolean withinVerticalBand = pos.y >= minY - 3.0 && pos.y <= maxY + 3.0;
                if (!withinVerticalBand) {
                    continue;
                }

                if (pos.y >= minY && pos.y <= maxY && hDist <= radius) {
                    LanternFlightCompat.stopFlightIfActive(player);
                    if (player.isSleeping()) {
                        player.stopSleeping();
                    }
                }

                if (fallenParticipants.contains(pid)
                        && !deserterParticipants.contains(pid)
                        && hDist < radius - 3.5
                        && pos.y >= minY
                        && pos.y <= maxY
                        && isNearLivingActiveAlly(pos, 6.0)) {
                    readmitRevivedParticipant(player);
                    continue;
                }

                if (hDist < radius - 0.2) {
                    pushPlayerOutside(player, pos, dx, dz, hDist);
                }
            }
        }
    }

    private boolean isFacingOutward(ServerPlayer player, double dx, double dz, double hDist, boolean atCeiling) {
        Vec3 look = player.getLookAngle();
        if (atCeiling && look.y > 0.45) {
            return true;
        }
        if (hDist < 0.05) {
            return false;
        }
        double ux = dx / hDist;
        double uz = dz / hDist;
        double dot = look.x * ux + look.z * uz;
        return dot > 0.65;
    }

    private void pushPlayerInside(
            ServerPlayer player,
            Vec3 pos,
            double dx,
            double dz,
            double hDist,
            boolean outsideHoriz,
            boolean outsideHigh,
            boolean outsideLow,
            boolean tryingToExit
    ) {
        LanternFlightCompat.stopFlightIfActive(player);

        double safeX = pos.x;
        double safeZ = pos.z;
        double safeY = pos.y;

        if (outsideHoriz) {
            double invLen = hDist > 0.001 ? 1.0 / hDist : 0.0;
            double ux = invLen > 0 ? dx * invLen : 1.0;
            double uz = invLen > 0 ? dz * invLen : 0.0;
            double clampedR = Math.max(2.0, radius - 1.2);
            safeX = center.x + ux * clampedR;
            safeZ = center.z + uz * clampedR;
        }
        if (outsideHigh) {
            safeY = tryingToExit ? maxY - 0.5 : maxY - 1.0;
        } else if (outsideLow) {
            safeY = minY + 1.0;
        }

        Vec3 resolved = findSafePos(safeX, safeY, safeZ, true);
        if (player.isPassenger()) {
            player.stopRiding();
        }
        player.connection.teleport(resolved.x, resolved.y, resolved.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();

        if (!tryingToExit) {
            sendBoundaryFeedback(
                    player,
                    pos,
                    mainDust,
                    "§6⛨ Arena selada! Olhe para a saída com chão e avance por 1.2s se quiser abandonar a luta."
            );
        }
    }

    private Vec3 findValidExitPos(ServerPlayer player, Vec3 pos, double dx, double dz, double hDist) {
        double ux;
        double uz;
        if (hDist > 0.05) {
            ux = dx / hDist;
            uz = dz / hDist;
        } else {
            ux = 1.0;
            uz = 0.0;
        }

        double tx = -uz;
        double tz = ux;

        int playerBaseY = (int) Math.floor(player.getY() - 0.1);
        double targetDist = radius + 1.8;

        double[] lateralOffsets = {0.0, 0.7, -0.7, 1.4, -1.4};

        for (double lat : lateralOffsets) {
            double cx = center.x + ux * targetDist + tx * lat;
            double cz = center.z + uz * targetDist + tz * lat;

            int[] floorYOffsets = {0, -1, 1};
            for (int dy : floorYOffsets) {
                int testFloorY = playerBaseY + dy;
                BlockPos floorPos = BlockPos.containing(cx, testFloorY, cz);
                BlockPos feetPos = floorPos.above();
                BlockPos headPos = feetPos.above();

                BlockState floorState = level.getBlockState(floorPos);
                if (floorState.isAir() || floorState.getCollisionShape(level, floorPos).isEmpty()) {
                    continue;
                }
                if (floorState.is(Blocks.LAVA) || floorState.getFluidState().is(FluidTags.LAVA)
                        || floorState.is(Blocks.FIRE) || floorState.is(Blocks.SOUL_FIRE)
                        || floorState.is(Blocks.MAGMA_BLOCK) || floorState.is(Blocks.CAMPFIRE)
                        || floorState.is(Blocks.SOUL_CAMPFIRE) || floorState.is(Blocks.CACTUS)
                        || floorState.is(Blocks.SWEET_BERRY_BUSH)) {
                    continue;
                }

                BlockState feetState = level.getBlockState(feetPos);
                BlockState headState = level.getBlockState(headPos);

                if (!feetState.getCollisionShape(level, feetPos).isEmpty() || feetState.isSuffocating(level, feetPos)) {
                    continue;
                }
                if (!headState.getCollisionShape(level, headPos).isEmpty() || headState.isSuffocating(level, headPos)) {
                    continue;
                }

                if (feetState.getFluidState().is(FluidTags.LAVA) || feetState.is(Blocks.FIRE) || feetState.is(Blocks.SOUL_FIRE)) {
                    continue;
                }
                if (headState.getFluidState().is(FluidTags.LAVA) || headState.is(Blocks.FIRE) || headState.is(Blocks.SOUL_FIRE)) {
                    continue;
                }

                return new Vec3(floorPos.getX() + 0.5, floorPos.getY() + 1.0, floorPos.getZ() + 0.5);
            }
        }

        return null;
    }

    private Vec3 findAnySafePerimeterExit(ServerPlayer player) {
        int playerBaseY = (int) Math.floor(player.getY() - 0.1);
        double targetDist = radius + 1.8;

        for (int i = 0; i < 16; i++) {
            double angle = (i * 2.0 * Math.PI) / 16.0;
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);
            double cx = center.x + ux * targetDist;
            double cz = center.z + uz * targetDist;

            int[] floorYOffsets = {0, -1, 1, -2, 2};
            for (int dy : floorYOffsets) {
                int testFloorY = playerBaseY + dy;
                BlockPos floorPos = BlockPos.containing(cx, testFloorY, cz);
                BlockPos feetPos = floorPos.above();
                BlockPos headPos = feetPos.above();

                BlockState floorState = level.getBlockState(floorPos);
                if (floorState.isAir() || floorState.getCollisionShape(level, floorPos).isEmpty()) {
                    continue;
                }
                if (floorState.is(Blocks.LAVA) || floorState.getFluidState().is(FluidTags.LAVA)
                        || floorState.is(Blocks.FIRE) || floorState.is(Blocks.SOUL_FIRE)
                        || floorState.is(Blocks.MAGMA_BLOCK) || floorState.is(Blocks.CAMPFIRE)
                        || floorState.is(Blocks.SOUL_CAMPFIRE) || floorState.is(Blocks.CACTUS)
                        || floorState.is(Blocks.SWEET_BERRY_BUSH)) {
                    continue;
                }

                BlockState feetState = level.getBlockState(feetPos);
                BlockState headState = level.getBlockState(headPos);
                if (!feetState.getCollisionShape(level, feetPos).isEmpty() || feetState.isSuffocating(level, feetPos)) {
                    continue;
                }
                if (!headState.getCollisionShape(level, headPos).isEmpty() || headState.isSuffocating(level, headPos)) {
                    continue;
                }
                if (feetState.getFluidState().is(FluidTags.LAVA) || feetState.is(Blocks.FIRE) || feetState.is(Blocks.SOUL_FIRE)) {
                    continue;
                }
                if (headState.getFluidState().is(FluidTags.LAVA) || headState.is(Blocks.FIRE) || headState.is(Blocks.SOUL_FIRE)) {
                    continue;
                }

                return new Vec3(floorPos.getX() + 0.5, floorPos.getY() + 1.0, floorPos.getZ() + 0.5);
            }
        }
        return null;
    }

    private void handleParticipantVoluntaryExit(ServerPlayer player, Vec3 validExit) {
        UUID pid = player.getUUID();
        exitAttemptTicks.remove(pid);
        activeParticipants.remove(pid);
        fallenParticipants.add(pid);
        deserterParticipants.add(pid);
        clearClientBarriers(player);

        if (player.isPassenger()) {
            player.stopRiding();
        }
        player.connection.teleport(validExit.x, validExit.y, validExit.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();

        LivingEntity boss = resolveBossEntity();
        if (activeParticipants.isEmpty()) {
            triggerFullBossReset(
                    boss,
                    "§e⛨ " + player.getName().getString() + " abandonou a arena! A barreira foi desfeita e o boss resetou completamente."
            );
        } else {
            float dealt = damageByParticipant.getOrDefault(pid, 0.0f);
            if (boss != null && boss.isAlive() && !boss.isRemoved()) {
                float minHeal = boss.getMaxHealth() * 0.15f;
                float healAmount = Math.max(dealt, minHeal);
                boss.setHealth(Math.min(boss.getMaxHealth(), boss.getHealth() + healAmount));
                if (boss instanceof Mob mob && mob.getTarget() != null && mob.getTarget().getUUID().equals(pid)) {
                    mob.setTarget(null);
                }
            }
            level.playSound(null, validExit.x, validExit.y, validExit.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.4f, 0.9f);
            Component desertMsg = Component.literal(
                    "§e⛨ " + player.getName().getString() + " desertou da arena! Ele ficou trancado para fora e o boss regenerou o dano recebido dele."
            );
            notifyAllRelatedPlayers(desertMsg);
        }
    }

    private void pushPlayerOutside(ServerPlayer player, Vec3 pos, double dx, double dz, double hDist) {
        LanternFlightCompat.stopFlightIfActive(player);

        Vec3 validExit = findValidExitPos(player, pos, dx, dz, hDist);
        if (validExit == null) {
            validExit = findAnySafePerimeterExit(player);
        }

        if (validExit != null) {
            if (player.isPassenger()) {
                player.stopRiding();
            }
            player.connection.teleport(validExit.x, validExit.y, validExit.z, player.getYRot(), player.getXRot());
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
        }

        String msg = fallenParticipants.contains(player.getUUID())
                ? "§c✖ Você caiu na batalha! Aguarde o fim da luta ou seja revivido lá dentro."
                : "§c✖ Há uma luta de boss em andamento! Aguarde a arena ser liberada.";
        sendBoundaryFeedback(player, pos, REJECT_DUST, msg);
    }

    public void updateClientBarriers(ServerPlayer player) {
        if (state != State.SEALED || finished) {
            clearClientBarriers(player);
            return;
        }

        Vec3 pPos = player.position();
        double hDist = horizontalDist(pPos);

        if (hDist < radius - 8.0 || hDist > radius + 8.0) {
            clearClientBarriers(player);
            return;
        }

        UUID pid = player.getUUID();
        Set<BlockPos> currentlySent = clientFakeBarriers.computeIfAbsent(pid, k -> new HashSet<>());
        Set<BlockPos> newNeeded = new HashSet<>();

        int px = player.getBlockX();
        int py = player.getBlockY();
        int pz = player.getBlockZ();

        int minYCheck = Math.max((int) minY, py - 2);
        int maxYCheck = Math.min((int) maxY, py + 4);

        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int bx = px + dx;
                int bz = pz + dz;
                double dist = Math.sqrt((bx + 0.5 - center.x) * (bx + 0.5 - center.x) + (bz + 0.5 - center.z) * (bz + 0.5 - center.z));
                if (dist >= radius - 0.72 && dist <= radius + 1.25) {
                    for (int by = minYCheck; by <= maxYCheck; by++) {
                        BlockPos pos = new BlockPos(bx, by, bz);
                        BlockState realState = level.getBlockState(pos);
                        if (!realState.isCollisionShapeFullBlock(level, pos)) {
                            newNeeded.add(pos);
                        }
                    }
                }
            }
        }

        BlockState barrierState = Blocks.BARRIER.defaultBlockState();
        for (BlockPos pos : newNeeded) {
            if (!currentlySent.contains(pos)) {
                player.connection.send(new ClientboundBlockUpdatePacket(pos, barrierState));
            }
        }

        for (BlockPos pos : currentlySent) {
            if (!newNeeded.contains(pos)) {
                player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
            }
        }

        clientFakeBarriers.put(pid, newNeeded);
    }

    public void clearClientBarriers(ServerPlayer player) {
        if (player == null) return;
        Set<BlockPos> sent = clientFakeBarriers.remove(player.getUUID());
        if (sent != null && !sent.isEmpty() && player.connection != null) {
            for (BlockPos pos : sent) {
                player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
            }
        }
    }

    public void clearAllClientBarriers() {
        if (clientFakeBarriers.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Set<BlockPos>> entry : clientFakeBarriers.entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.connection != null) {
                for (BlockPos pos : entry.getValue()) {
                    player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
                }
            }
        }
        clientFakeBarriers.clear();
    }

    public void readmitRevivedParticipant(ServerPlayer player) {
        UUID pid = player.getUUID();
        fallenParticipants.remove(pid);
        activeParticipants.add(pid);

        Vec3 pos = player.position();
        List<ServerPlayer> viewers = getNearbyViewers();
        for (int i = 0; i < 24; i++) {
            double angle = (Math.PI * 2.0 * i) / 12.0;
            double r = 0.9;
            double yOff = (i / 24.0) * 2.2;
            sendParticleToViewers(
                    viewers,
                    GOLD_DUST,
                    pos.x + Math.cos(angle) * r,
                    pos.y + yOff,
                    pos.z + Math.sin(angle) * r,
                    1, 0, 0, 0, 0.01
            );
        }
        sendParticleToViewers(viewers, ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y + 1.0, pos.z, 18, 0.4, 0.6, 0.4, 0.15);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.8f, 1.15f);

        player.displayClientMessage(Component.literal("§6✨ Você foi revivido dentro da arena e retornou ao combate!"), false);
        Component allyMsg = Component.literal("§e✨ " + player.getGameProfile().getName() + " retornou à batalha dentro da arena!");
        for (UUID allyId : activeParticipants) {
            if (!allyId.equals(pid)) {
                ServerPlayer ally = getServerPlayer(allyId);
                if (ally != null) {
                    ally.displayClientMessage(allyMsg, true);
                }
            }
        }
    }

    public void handleParticipantDeath(ServerPlayer player) {
        UUID pid = player.getUUID();
        if (!activeParticipants.remove(pid)) {
            return;
        }
        fallenParticipants.add(pid);

        if (activeParticipants.isEmpty()) {
            LivingEntity boss = resolveBossEntity();
            triggerWipe(boss);
        } else {
            player.displayClientMessage(
                    Component.literal("§c☠ Você morreu na arena! Aguarde seus aliados terminarem ou te reviverem lá dentro."),
                    false
            );
        }
    }

    public void handleParticipantLeft(ServerPlayer player) {
        UUID pid = player.getUUID();
        if (activeParticipants.remove(pid)) {
            fallenParticipants.add(pid);
            if (activeParticipants.isEmpty()) {
                LivingEntity boss = resolveBossEntity();
                triggerWipe(boss);
            }
        }
    }

    private void triggerWipe(LivingEntity boss) {
        triggerFullBossReset(
                boss,
                "§c☠ Todos os combatentes na arena caíram! A barreira caiu e o boss recuperou sua vida."
        );
    }

    private void triggerFullBossReset(LivingEntity boss, String message) {
        if (finished) {
            return;
        }
        finished = true;
        clearAllClientBarriers();
        restoreBossAttributes(boss);

        if (boss != null && boss.isAlive() && !boss.isRemoved()) {
            boss.setHealth(boss.getMaxHealth());
            boss.removeAllEffects();
            if (boss instanceof Mob mob) {
                mob.setTarget(null);
            }
            Vec3 safeCenter = findSafePos(center.x, center.y, center.z, false);
            boss.teleportTo(safeCenter.x, safeCenter.y, safeCenter.z);
            boss.setDeltaMovement(Vec3.ZERO);
            boss.hurtMarked = true;
        }

        playDissolveAnimation(false);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0f, 0.7f);

        notifyAllRelatedPlayers(Component.literal(message));
    }

    public void endVictory(LivingEntity boss) {
        if (finished) {
            return;
        }
        finished = true;
        clearAllClientBarriers();
        restoreBossAttributes(boss);

        playDissolveAnimation(true);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 1.5f, 1.25f);

        Component msg = Component.literal("§a⚔ Vitória contra ")
                .append(bossDisplayName)
                .append(Component.literal("§a! A barreira da arena foi liberada."));
        notifyAllRelatedPlayers(msg);
    }

    public void applyMultiplayerScaling(LivingEntity boss) {
        if (boss == null || !boss.isAlive() || boss.isRemoved()) {
            return;
        }
        int count = activeParticipants.size();
        if (count <= 1) {
            return;
        }

        AttributeInstance healthAttr = boss.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null) {
            originalBaseMaxHealth = healthAttr.getBaseValue();
            double hpMultiplier = 1.0 + (count - 1) * HP_SCALING_PER_EXTRA_PLAYER;
            double newMaxHealth = Math.round(originalBaseMaxHealth * hpMultiplier * 10.0) / 10.0;
            healthAttr.setBaseValue(newMaxHealth);
            boss.setHealth((float) newMaxHealth);
            bossScaled = true;
        }

        AttributeInstance attackAttr = boss.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackAttr != null) {
            originalBaseAttackDamage = attackAttr.getBaseValue();
            double dmgMultiplier = 1.0 + (count - 1) * DAMAGE_SCALING_PER_EXTRA_PLAYER;
            double newDmg = Math.round(originalBaseAttackDamage * dmgMultiplier * 10.0) / 10.0;
            attackAttr.setBaseValue(newDmg);
            bossScaled = true;
        }

        BossArenaGuardMod.LOGGER.info(
                "Scaled boss {} for {} players: HP {} -> {}, Dmg {} -> {}",
                bossTypeId,
                count,
                originalBaseMaxHealth,
                healthAttr != null ? healthAttr.getBaseValue() : "N/A",
                originalBaseAttackDamage,
                attackAttr != null ? attackAttr.getBaseValue() : "N/A"
        );
    }

    public void restoreBossAttributes(LivingEntity boss) {
        if (!bossScaled) {
            return;
        }
        if (boss == null) {
            boss = resolveBossEntity();
        }
        if (boss != null && boss.isAlive() && !boss.isRemoved()) {
            if (originalBaseMaxHealth > 0.0) {
                AttributeInstance healthAttr = boss.getAttribute(Attributes.MAX_HEALTH);
                if (healthAttr != null) {
                    healthAttr.setBaseValue(originalBaseMaxHealth);
                }
            }
            if (originalBaseAttackDamage > 0.0) {
                AttributeInstance attackAttr = boss.getAttribute(Attributes.ATTACK_DAMAGE);
                if (attackAttr != null) {
                    attackAttr.setBaseValue(originalBaseAttackDamage);
                }
            }
            BossArenaGuardMod.LOGGER.info(
                    "Restored original boss attributes for {}: HP={}, Dmg={}",
                    bossTypeId,
                    originalBaseMaxHealth,
                    originalBaseAttackDamage
            );
        }
        bossScaled = false;
    }

    private void notifyAllRelatedPlayers(Component msg) {
        Set<UUID> notified = new HashSet<>();
        for (UUID id : activeParticipants) {
            ServerPlayer p = getServerPlayer(id);
            if (p != null) {
                p.displayClientMessage(msg, false);
                notified.add(id);
            }
        }
        for (UUID id : fallenParticipants) {
            if (!notified.contains(id)) {
                ServerPlayer p = getServerPlayer(id);
                if (p != null) {
                    p.displayClientMessage(msg, false);
                    notified.add(id);
                }
            }
        }
        for (ServerPlayer viewer : getNearbyViewers()) {
            if (!notified.contains(viewer.getUUID())) {
                viewer.displayClientMessage(msg, false);
            }
        }
    }

    private void sendBoundaryFeedback(ServerPlayer player, Vec3 hitPos, ParticleOptions dust, String message) {
        long now = level.getGameTime();
        Long last = feedbackCooldowns.get(player.getUUID());
        if (last == null || now - last >= 15L) {
            feedbackCooldowns.put(player.getUUID(), now);
            spawnImpactRipple(hitPos, dust);
            level.playSound(null, hitPos.x, hitPos.y + 1.0, hitPos.z, SoundEvents.SHIELD_BLOCK, SoundSource.BLOCKS, 1.1f, 0.75f);
            level.playSound(null, hitPos.x, hitPos.y + 1.0, hitPos.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.0f, 1.25f);
            player.displayClientMessage(Component.literal(message), true);
        }
    }

    private void spawnImpactRipple(Vec3 hitPos, ParticleOptions dust) {
        double angle = Math.atan2(hitPos.z - center.z, hitPos.x - center.x);
        double bx = center.x + Math.cos(angle) * radius;
        double bz = center.z + Math.sin(angle) * radius;
        double by = hitPos.y + 1.0;

        double tx = -Math.sin(angle);
        double tz = Math.cos(angle);

        List<ServerPlayer> viewers = getNearbyViewers();
        for (int i = 0; i < 16; i++) {
            double a = (Math.PI * 2.0 * i) / 16.0;
            double offH = Math.cos(a) * 1.4;
            double offV = Math.sin(a) * 1.4;
            double px = bx + tx * offH;
            double pz = bz + tz * offH;
            double py = by + offV;
            sendParticleToViewers(viewers, dust, px, py, pz, 1, 0, 0, 0, 0);
            if (i % 2 == 0) {
                sendParticleToViewers(viewers, ParticleTypes.ELECTRIC_SPARK, px, py, pz, 1, 0.05, 0.05, 0.05, 0.05);
            }
        }
    }

    private void playDissolveAnimation(boolean victory) {
        List<ServerPlayer> viewers = getNearbyViewers();
        int count = Math.min(48, Math.max(20, (int) radius));
        ParticleOptions ringParticle = victory ? GOLD_DUST : REJECT_DUST;
        ParticleOptions accentParticle = victory ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.END_ROD;

        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0 * i) / count;
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;
            sendParticleToViewers(viewers, ringParticle, x, center.y + 1.2, z, 2, 0.2, 1.0, 0.2, 0.04);
            if (i % 2 == 0) {
                sendParticleToViewers(viewers, accentParticle, x, center.y + 2.0, z, 2, 0.3, 1.2, 0.3, 0.08);
            }
        }

        if (victory) {
            sendParticleToViewers(viewers, ParticleTypes.TOTEM_OF_UNDYING, center.x, center.y + 2.0, center.z, 35, 1.5, 1.5, 1.5, 0.25);
        }
    }

    private void renderForcefield() {
        if ((tickAge & 1L) != 0L) {
            return;
        }

        List<ServerPlayer> viewers = getNearbyViewers();
        if (viewers.isEmpty()) {
            return;
        }

        double baseRingY = Math.max(minY + 0.5, Math.min(maxY - 4.5, center.y)) + 0.35;
        double upperRingY = baseRingY + 3.4;

        // 1. Dual counter-rotating circular perimeter energy rings
        int ringSegments = Math.min(144, Math.max(64, (int) (radius * 2.8)));
        double spinA = tickAge * 0.045;
        double spinB = -tickAge * 0.035;
        long waveStep = (tickAge / 2L) % 2L;

        for (int i = 0; i < ringSegments; i++) {
            double angleA = spinA + (Math.PI * 2.0 * i) / ringSegments;
            double ax = center.x + Math.cos(angleA) * radius;
            double az = center.z + Math.sin(angleA) * radius;
            double bobA = Math.sin(angleA * 4.0 + tickAge * 0.12) * 0.25;
            sendParticleToViewers(viewers, mainDust, ax, baseRingY + bobA, az, 1, 0, 0, 0, 0);

            if ((i & 1) == waveStep) {
                double angleB = spinB + (Math.PI * 2.0 * i) / ringSegments;
                double bx = center.x + Math.cos(angleB) * radius;
                double bz = center.z + Math.sin(angleB) * radius;
                double bobB = Math.cos(angleB * 3.0 - tickAge * 0.10) * 0.35;
                sendParticleToViewers(viewers, secondaryDust, bx, upperRingY + bobB, bz, 1, 0, 0, 0, 0);
            }
        }

        // 2. 12 helical rising energy pillars along the circular perimeter
        for (int p = 0; p < 12; p++) {
            double pillarAngle = (Math.PI * 2.0 * p) / 12.0;
            double px = center.x + Math.cos(pillarAngle) * radius;
            double pz = center.z + Math.sin(pillarAngle) * radius;
            double hPhase = ((tickAge * 0.22) + p * 0.65) % 7.5;
            double helixTwist = hPhase * 1.4;
            double hx = px + Math.cos(pillarAngle + helixTwist) * 0.45;
            double hz = pz + Math.sin(pillarAngle + helixTwist) * 0.45;
            sendParticleToViewers(viewers, ParticleTypes.END_ROD, hx, baseRingY + hPhase, hz, 1, 0, 0, 0, 0);
        }

        // 3. Reactive holographic diamond lattice wall near approaching players
        for (ServerPlayer viewer : viewers) {
            Vec3 pPos = viewer.position();
            double hDist = horizontalDist(pPos);
            double borderDist = Math.abs(hDist - radius);

            if (borderDist <= 12.5 && pPos.y >= minY - 4.0 && pPos.y <= maxY + 4.0) {
                renderReactiveWallArc(viewer, pPos);
            }
            if (pPos.y >= maxY - 6.5 && hDist <= radius + 4.0) {
                renderReactiveCeilingCanopy(viewer, pPos);
            }
        }
    }

    private void renderReactiveWallArc(ServerPlayer viewer, Vec3 pPos) {
        double centerAngle = Math.atan2(pPos.z - center.z, pPos.x - center.x);
        double angularHalfSpan = Math.min(0.65, 11.0 / Math.max(10.0, radius));
        int cols = 10;
        int rows = 6;
        long phase = (tickAge / 4L) & 1L;
        double startY = Math.max(minY, pPos.y - 0.5);

        for (int c = -cols; c <= cols; c++) {
            double a = centerAngle + (c * angularHalfSpan) / cols;
            double wx = center.x + Math.cos(a) * radius;
            double wz = center.z + Math.sin(a) * radius;

            for (int r = 0; r < rows; r++) {
                if (((c + r + phase) & 1L) != 0L) {
                    continue;
                }
                double wy = startY + r * 1.15;
                if (wy > maxY) {
                    continue;
                }
                viewer.serverLevel().sendParticles(viewer, mainDust, true, wx, wy, wz, 1, 0, 0, 0, 0);
                if (Math.abs(c) % 3 == 0 && r == 2 && (tickAge % 4L == 0L)) {
                    viewer.serverLevel().sendParticles(viewer, ParticleTypes.ELECTRIC_SPARK, true, wx, wy, wz, 1, 0.02, 0.02, 0.02, 0.01);
                }
            }
        }
    }

    private void renderReactiveCeilingCanopy(ServerPlayer viewer, Vec3 pPos) {
        double ceilingY = maxY;
        for (int gx = -2; gx <= 2; gx++) {
            for (int gz = -2; gz <= 2; gz++) {
                if (((gx + gz + (tickAge / 4L)) & 1L) != 0L) {
                    continue;
                }
                double wx = pPos.x + gx * 1.4;
                double wz = pPos.z + gz * 1.4;
                double dSq = (wx - center.x) * (wx - center.x) + (wz - center.z) * (wz - center.z);
                if (dSq <= radius * radius) {
                    viewer.serverLevel().sendParticles(viewer, secondaryDust, true, wx, ceilingY, wz, 1, 0, 0, 0, 0);
                }
            }
        }
    }

    private List<ServerPlayer> getNearbyViewers() {
        double viewRadiusSq = (radius + 56.0) * (radius + 56.0);
        List<ServerPlayer> viewers = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - center.x;
            double dz = player.getZ() - center.z;
            if (dx * dx + dz * dz <= viewRadiusSq) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    private <T extends ParticleOptions> void sendParticleToViewers(
            List<ServerPlayer> viewers,
            T particle,
            double x,
            double y,
            double z,
            int count,
            double dx,
            double dy,
            double dz,
            double speed
    ) {
        for (ServerPlayer viewer : viewers) {
            level.sendParticles(viewer, particle, true, x, y, z, count, dx, dy, dz, speed);
        }
    }

    private Vec3 findSafePos(double x, double targetY, double z, boolean clampTowardCenter) {
        int[] yOffsets = {0, 1, -1, 2, -2, 3};
        for (double step = 1.0; step >= (clampTowardCenter ? 0.4 : 1.0); step -= 0.25) {
            double cx = clampTowardCenter ? center.x + (x - center.x) * step : x;
            double cz = clampTowardCenter ? center.z + (z - center.z) * step : z;
            for (int dy : yOffsets) {
                double cy = targetY + dy;
                BlockPos feet = BlockPos.containing(cx, cy, cz);
                BlockPos head = feet.above();
                BlockPos floor = feet.below();
                BlockState sFeet = level.getBlockState(feet);
                BlockState sHead = level.getBlockState(head);
                BlockState sFloor = level.getBlockState(floor);
                if (!sFeet.isSuffocating(level, feet) && !sHead.isSuffocating(level, head)
                        && !sFloor.isAir() && !sFloor.getCollisionShape(level, floor).isEmpty()
                        && !sFloor.is(Blocks.LAVA) && !sFloor.getFluidState().is(FluidTags.LAVA)) {
                    return new Vec3(cx, cy, cz);
                }
            }
        }
        for (double step = 1.0; step >= (clampTowardCenter ? 0.4 : 1.0); step -= 0.25) {
            double cx = clampTowardCenter ? center.x + (x - center.x) * step : x;
            double cz = clampTowardCenter ? center.z + (z - center.z) * step : z;
            for (int dy : yOffsets) {
                double cy = targetY + dy;
                BlockPos feet = BlockPos.containing(cx, cy, cz);
                BlockPos head = feet.above();
                BlockState sFeet = level.getBlockState(feet);
                BlockState sHead = level.getBlockState(head);
                if (!sFeet.isSuffocating(level, feet) && !sHead.isSuffocating(level, head)) {
                    return new Vec3(cx, cy, cz);
                }
            }
        }
        return new Vec3(x, targetY, z);
    }

    public String getOutsideProjectileTag() {
        return "bossarenaguard_out_" + bossUuid;
    }

    public String getInsideProjectileTag() {
        return "bossarenaguard_in_" + bossUuid;
    }

    public void classifyProjectileOnSpawn(Projectile proj) {
        if (proj == null || finished) {
            return;
        }
        Entity owner = proj.getOwner();
        boolean projOutside = !isInsideInterior(proj.position(), 0.0);
        boolean ownerOutside = owner != null && !isInsideInterior(owner.position(), 0.0);
        boolean ownerNotParticipant = isSealed()
                && owner instanceof ServerPlayer sp
                && !isActiveParticipant(sp.getUUID());

        if (projOutside || ownerOutside || ownerNotParticipant) {
            proj.addTag(getOutsideProjectileTag());
            proj.removeTag(getInsideProjectileTag());
        } else {
            proj.addTag(getInsideProjectileTag());
            proj.removeTag(getOutsideProjectileTag());
        }
    }

    public boolean isOutsiderProjectile(Projectile proj) {
        if (proj == null) {
            return false;
        }
        if (proj.getTags().contains(getOutsideProjectileTag())) {
            return true;
        }
        Entity owner = proj.getOwner();
        if (owner != null && !isInsideInterior(owner.position(), 0.0)) {
            proj.addTag(getOutsideProjectileTag());
            return true;
        }
        if (isSealed() && owner instanceof ServerPlayer sp && !isActiveParticipant(sp.getUUID())) {
            proj.addTag(getOutsideProjectileTag());
            return true;
        }
        if (!proj.getTags().contains(getInsideProjectileTag())) {
            Vec3 prev = new Vec3(proj.xo, proj.yo, proj.zo);
            if (!isInsideInterior(prev, 0.0)) {
                proj.addTag(getOutsideProjectileTag());
                return true;
            }
        }
        return false;
    }

    public boolean isDamageFromOutside(DamageSource source) {
        if (source == null) {
            return false;
        }
        Entity direct = source.getDirectEntity();
        Entity attacker = source.getEntity();

        if (direct instanceof Projectile proj && isOutsiderProjectile(proj)) {
            return true;
        }
        if (direct != null && !(direct instanceof LivingEntity)) {
            Vec3 directPrev = new Vec3(direct.xo, direct.yo, direct.zo);
            if (!isInsideInterior(directPrev, 0.0) || !isInsideInterior(direct.position(), 0.0)) {
                return true;
            }
        }
        if (attacker != null) {
            if (!isInsideInterior(attacker.position(), 0.0)) {
                return true;
            }
            if (isSealed() && attacker instanceof ServerPlayer sp && !isActiveParticipant(sp.getUUID())) {
                return true;
            }
        }
        if (attacker == null && direct == null) {
            Vec3 srcPos = source.getSourcePosition();
            if (srcPos != null && !isInsideInterior(srcPos, 0.0)) {
                return true;
            }
        }
        return false;
    }

    public void enforceProjectileBarrier() {
        AABB box = new AABB(
                center.x - radius - 16.0,
                minY - 8.0,
                center.z - radius - 16.0,
                center.x + radius + 16.0,
                maxY + 12.0,
                center.z + radius + 16.0
        );
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, box);
        for (Projectile proj : projectiles) {
            if (proj.isRemoved()) {
                continue;
            }
            if (isSealed() && proj instanceof ThrownEnderpearl && !isOutsiderProjectile(proj)) {
                Vec3 curr = proj.position();
                Vec3 next = curr.add(proj.getDeltaMovement());
                if (!isInsideInterior(curr, 0.75) || !isInsideInterior(next, 0.75)) {
                    spawnImpactRipple(curr, mainDust);
                    level.playSound(null, curr.x, curr.y, curr.z, SoundEvents.SHIELD_BLOCK, SoundSource.BLOCKS, 1.0f, 0.85f);
                    if (proj.getOwner() instanceof ServerPlayer shooter) {
                        shooter.displayClientMessage(Component.literal("§6⛨ Pérola do Fim bloqueada pela barreira da arena!"), true);
                    }
                    proj.discard();
                    continue;
                }
            }
            if (!isOutsiderProjectile(proj)) {
                continue;
            }
            Vec3 prev = new Vec3(proj.xo, proj.yo, proj.zo);
            Vec3 curr = proj.position();
            Vec3 next = curr.add(proj.getDeltaMovement());
            if (doesSegmentEnterArena(prev, next, radius + 0.75) || isInsideInterior(curr, -0.75)) {
                deflectOrDestroyOutsiderProjectile(proj);
            }
        }
    }

    private boolean doesSegmentEnterArena(Vec3 from, Vec3 to, double checkRadius) {
        for (int i = 0; i <= 4; i++) {
            double t = i / 4.0;
            Vec3 pt = from.lerp(to, t);
            if (horizontalDist(pt) <= checkRadius && pt.y >= minY - 1.0 && pt.y <= maxY + 1.0) {
                return true;
            }
        }
        return false;
    }

    public void deflectOrDestroyOutsiderProjectile(Projectile proj) {
        if (proj == null || proj.isRemoved()) {
            return;
        }
        Vec3 pos = proj.position();
        double angle = Math.atan2(pos.z - center.z, pos.x - center.x);
        double hitX = center.x + Math.cos(angle) * radius;
        double hitZ = center.z + Math.sin(angle) * radius;
        double hitY = Math.max(minY + 0.5, Math.min(maxY - 0.5, pos.y));
        Vec3 hitPos = new Vec3(hitX, hitY, hitZ);

        String rippleTag = "bossarenaguard_rippled_" + bossUuid;
        if (!proj.getTags().contains(rippleTag)) {
            proj.addTag(rippleTag);
            spawnImpactRipple(hitPos, REJECT_DUST);
            level.playSound(null, hitX, hitY, hitZ, SoundEvents.SHIELD_BLOCK, SoundSource.BLOCKS, 1.0f, 1.15f);
            level.playSound(null, hitX, hitY, hitZ, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.9f, 1.35f);

            if (proj.getOwner() instanceof ServerPlayer shooter) {
                long now = level.getGameTime();
                Long last = feedbackCooldowns.get(shooter.getUUID());
                if (last == null || now - last >= 15L) {
                    feedbackCooldowns.put(shooter.getUUID(), now);
                    shooter.displayClientMessage(
                            Component.literal("§c⛨ A barreira bloqueou seu projétil! Entre na arena para enfrentar o boss."),
                            true
                    );
                }
            }
        }

        if (proj instanceof ThrownTrident) {
            double outX = center.x + Math.cos(angle) * (radius + 1.2);
            double outZ = center.z + Math.sin(angle) * (radius + 1.2);
            proj.setPos(outX, hitY, outZ);
            proj.setDeltaMovement(Math.cos(angle) * 0.25, -0.1, Math.sin(angle) * 0.25);
            proj.hurtMarked = true;
        } else {
            proj.discard();
        }
    }

    public void triggerOutsideAttackBlockedFeedback(Entity attacker, Vec3 fallbackPos, String message) {
        Vec3 ref = attacker != null ? attacker.position() : fallbackPos;
        double angle = Math.atan2(ref.z - center.z, ref.x - center.x);
        double hitX = center.x + Math.cos(angle) * radius;
        double hitZ = center.z + Math.sin(angle) * radius;
        double hitY = Math.max(minY + 0.5, Math.min(maxY - 0.5, ref.y));
        Vec3 hitPos = new Vec3(hitX, hitY, hitZ);

        if (attacker instanceof ServerPlayer player) {
            sendBoundaryFeedback(player, hitPos, REJECT_DUST, message);
        } else {
            spawnImpactRipple(hitPos, REJECT_DUST);
            level.playSound(null, hitX, hitY, hitZ, SoundEvents.SHIELD_BLOCK, SoundSource.BLOCKS, 1.0f, 1.15f);
        }
    }

    private ServerPlayer getServerPlayer(UUID id) {
        if (level.getServer() == null) {
            return null;
        }
        return level.getServer().getPlayerList().getPlayer(id);
    }
}
