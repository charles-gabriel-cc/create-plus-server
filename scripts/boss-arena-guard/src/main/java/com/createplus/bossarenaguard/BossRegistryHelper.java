package com.createplus.bossarenaguard;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.Map;

public final class BossRegistryHelper {
    // Tripled baseline: previously 32.0m -> now 96.0m
    public static final double MIN_ARENA_RADIUS = 96.0;

    private static final Map<String, ArenaProfile> BOSS_PROFILES = ArenaProfile.loadDefaults();
    private static volatile ArenaProfileDatabase database;

    static void setDatabase(ArenaProfileDatabase source) {
        database = source;
    }

    private static ArenaProfile databaseProfile(String id) {
        ArenaProfileDatabase source = database;
        return source == null ? null : source.get(id);
    }

    private BossRegistryHelper() {
    }

    public static Entity unwrapEntity(Entity entity) {
        if (entity instanceof PartEntity<?> part) {
            return part.getParent();
        }
        return entity;
    }

    public static LivingEntity unwrapBoss(Entity entity) {
        Entity unwrapped = unwrapEntity(entity);
        if (unwrapped instanceof LivingEntity living && isBoss(living)) {
            return living;
        }
        return null;
    }

    public static boolean isBoss(Entity entity) {
        return entity instanceof LivingEntity
                && entity.level() instanceof ServerLevel
                && BossChecklistCatalog.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    private static ArenaProfile getFallbackProfile(LivingEntity boss) {
        // Dynamic heuristic fallback for custom/unregistered bosses (tripled)
        boolean airborneOrWater = boss.isNoGravity() || boss.isInWaterOrBubble();
        double width = Math.max(1.0, boss.getBbWidth());
        double height = Math.max(1.5, boss.getBbHeight());
        if (airborneOrWater) {
            double r = Math.max(114.0, Math.min(216.0, width * 24.0));
            double minOff = -72.0;
            double maxOff = Math.max(126.0, height * 30.0);
            return new ArenaProfile(r, minOff, maxOff);
        } else {
            double r = Math.max(MIN_ARENA_RADIUS, Math.min(162.0, width * 21.0));
            double minOff = -54.0;
            double maxOff = Math.max(90.0, height * 19.5);
            return new ArenaProfile(r, minOff, maxOff);
        }
    }

    public static ArenaGeometry computeArenaGeometry(ServerLevel level, LivingEntity boss) {
        Vec3 center = resolveHomeCenter(level, boss);
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType()).toString();
        ArenaProfile override = databaseProfile(id);
        ArenaProfile profile = override == null ? BOSS_PROFILES.get(id) : override;
        boolean calibrated = profile != null;
        if (profile == null) {
            profile = getFallbackProfile(boss);
        }

        double radius = calibrated ? profile.radius() : structureRadius(level, center, profile.radius());
        double worldFloor = level.getMinBuildHeight();
        double worldCeiling = level.getMaxBuildHeight();
        double minY = profile.dimensionHeight()
                ? worldFloor - 64.0 : Math.max(worldFloor, center.y + profile.lowerOffset());
        double maxY = profile.dimensionHeight()
                ? worldCeiling : Math.min(worldCeiling, center.y + profile.upperOffset());
        Integer ceiling = profile.ceilingY();
        if (ceiling != null && Math.max(center.y, boss.getBoundingBox().maxY) <= ceiling - 3.0) {
            maxY = Math.min(maxY, ceiling);
        }
        // A home position or an off-height summon must still fit the boss itself.
        minY = Math.min(minY, boss.getY() - 2.0);
        maxY = Math.max(maxY, boss.getBoundingBox().maxY + 2.0);
        return new ArenaGeometry(center, radius, minY, maxY);
    }

    private static double structureRadius(ServerLevel level, Vec3 center, double baseRadius) {
        BlockPos centerBlock = BlockPos.containing(center);
        try {
            Map<Structure, LongSet> structures = level.structureManager().getAllStructuresAt(centerBlock);
            double bestSpanRadius = -1.0;

            for (Structure structure : structures.keySet()) {
                StructureStart start = level.structureManager().getStructureWithPieceAt(centerBlock, structure);
                if (start == null || !start.isValid()) {
                    continue;
                }
                for (StructurePiece piece : start.getPieces()) {
                    BoundingBox box = piece.getBoundingBox();
                    if (box.isInside(centerBlock)) {
                        double pieceRadius = Math.max(box.getXSpan(), box.getZSpan()) / 2.0 + 2.0;
                        if (pieceRadius >= 16.0 && pieceRadius <= 360.0 && pieceRadius > bestSpanRadius) {
                            bestSpanRadius = pieceRadius;
                        }
                    }
                }
            }

            if (bestSpanRadius > 0.0) {
                double minAllowed = Math.max(MIN_ARENA_RADIUS, baseRadius * 0.85);
                double maxAllowed = baseRadius * 1.35;
                return Math.max(minAllowed, Math.min(maxAllowed, bestSpanRadius));
            }
        } catch (RuntimeException ignored) {
            // Structure data may be unavailable when an arena is created.
        }
        return baseRadius;
    }

    private static Vec3 resolveHomeCenter(ServerLevel level, LivingEntity boss) {
        // 1. Try Cataclysm IHomeEntity / LLibrary_Boss_Monster / IABoss_monster getHomePos()
        try {
            Method getHomePos = boss.getClass().getMethod("getHomePos");
            Object result = getHomePos.invoke(boss);
            if (result instanceof GlobalPos gp && gp.dimension().equals(level.dimension())) {
                Vec3 homeVec = Vec3.atCenterOf(gp.pos());
                if (homeVec.distanceToSqr(boss.position()) <= 240.0 * 240.0) {
                    return homeVec;
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. Try vanilla Mob home restriction
        if (boss instanceof Mob mob && mob.hasRestriction() && mob.getRestrictRadius() > 0.0f) {
            Vec3 restrictVec = Vec3.atCenterOf(mob.getRestrictCenter());
            if (restrictVec.distanceToSqr(boss.position()) <= 240.0 * 240.0) {
                return restrictVec;
            }
        }

        // 3. Default to boss's current position at combat start
        return boss.position();
    }

    public static BossPalette getPalette(LivingEntity boss) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType()).toString();
        return switch (id) {
            // Flame / Nether
            case "cataclysm:ignis", "cataclysm:netherite_monstrosity",
                 "cataclysm:ignited_revenant", "friendsandfoes:wildfire" ->
                    new BossPalette(new Vector3f(1.0f, 0.28f, 0.05f), new Vector3f(1.0f, 0.78f, 0.14f));

            // Abyss / Ocean
            case "cataclysm:the_leviathan", "minecraft:elder_guardian",
                 "cataclysm:coralssus", "cataclysm:clawdian", "hybrid_aquatic:karkinos" ->
                    new BossPalette(new Vector3f(0.05f, 0.85f, 0.96f), new Vector3f(0.14f, 0.25f, 0.95f));

            // End / Void
            case "minecraft:ender_dragon", "cataclysm:ender_guardian",
                 "cataclysm:ender_golem", "alexsmobs:void_worm" ->
                    new BossPalette(new Vector3f(0.90f, 0.24f, 1.0f), new Vector3f(0.45f, 0.08f, 0.80f));

            // Frost / Spectral Storm
            case "cataclysm:maledictus", "cataclysm:aptrgangr",
                 "cataclysm:scylla", "mowziesmobs:frostmaw" ->
                    new BossPalette(new Vector3f(0.16f, 0.95f, 0.80f), new Vector3f(0.76f, 0.96f, 1.0f));

            // Ancient Tech / Crimson Laser
            case "cataclysm:the_harbinger", "cataclysm:the_prowler" ->
                    new BossPalette(new Vector3f(1.0f, 0.08f, 0.14f), new Vector3f(1.0f, 0.50f, 0.12f));

            // Desert / Sun
            case "cataclysm:ancient_remnant", "cataclysm:wadjet",
                 "cataclysm:kobolediator", "mowziesmobs:umvuthi" ->
                    new BossPalette(new Vector3f(1.0f, 0.82f, 0.20f), new Vector3f(0.86f, 0.44f, 0.08f));

            // Sculk / Dark
            case "minecraft:warden", "deeperdarker:stalker",
                 "minecraft:wither", "ec_ad:sculksaur" ->
                    new BossPalette(new Vector3f(0.06f, 0.72f, 0.82f), new Vector3f(0.32f, 0.12f, 0.55f));

            // Dark Sorcery / Halloween (Born in Chaos)
            case "born_in_chaos_v1:lord_pumpkinhead",
                 "born_in_chaos_v1:lord_pumpkinhead_withouta_horse",
                 "born_in_chaos_v1:lord_the_headless",
                 "born_in_chaos_v1:sir_pumpkinhead",
                 "born_in_chaos_v1:sir_pumpkinhead_without_horse",
                 "born_in_chaos_v1:sir_the_headless",
                 "born_in_chaos_v1:nightmare_stalker" ->
                    new BossPalette(new Vector3f(1.0f, 0.45f, 0.05f), new Vector3f(0.55f, 0.08f, 0.85f));

            // Default Royal Violet & Cyan
            default ->
                    new BossPalette(new Vector3f(0.55f, 0.35f, 1.0f), new Vector3f(0.25f, 0.90f, 0.95f));
        };
    }

    public record ArenaGeometry(Vec3 center, double radius, double minY, double maxY) {
    }

    public record BossPalette(Vector3f fromColor, Vector3f toColor) {
    }
}
