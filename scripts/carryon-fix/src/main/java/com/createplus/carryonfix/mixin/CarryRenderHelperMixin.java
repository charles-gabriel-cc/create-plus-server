package com.createplus.carryonfix.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tschipp.carryon.client.render.CarryRenderHelper;
import tschipp.carryon.common.carry.CarryOnData;
import tschipp.carryon.common.carry.CarryOnDataManager;

/**
 * Stabilizes Carry On's carried entity rendering:
 * - Caches the client render Entity instance per player/NBT instead of deserializing a fresh Entity every frame.
 * - Zeroes rotations (including yBodyRot/yBodyRotO/yHeadRotO) and keeps old position synced to current position
 *   so EMF/Fresh Animations and other animated mob renderers do not flicker.
 */
@Mixin(value = CarryRenderHelper.class, remap = false)
public abstract class CarryRenderHelperMixin {
    @Unique
    private static final Map<UUID, CachedRenderEntity> createplus$CACHE = new HashMap<>();

    @Unique
    private record CachedRenderEntity(Level level, CompoundTag nbt, Entity entity) {}

    @Inject(method = "getRenderEntity", at = @At("HEAD"), cancellable = true)
    private static void createplus$cacheRenderEntity(Player player, CallbackInfoReturnable<Entity> cir) {
        if (player == null || player.level() == null || !player.level().isClientSide) {
            return;
        }
        CarryOnData carry = CarryOnDataManager.getCarryData(player);
        UUID playerId = player.getUUID();
        if (!carry.isCarrying(CarryOnData.CarryType.ENTITY)) {
            createplus$CACHE.remove(playerId);
            return;
        }
        if (carry.getActiveScript().isPresent()) {
            return;
        }

        Level level = player.level();
        CompoundTag entityTag = carry.getNbt().getCompound("entity");
        CachedRenderEntity cached = createplus$CACHE.get(playerId);
        if (cached == null || cached.level() != level || !cached.nbt().equals(entityTag)) {
            if (createplus$CACHE.size() > 32) {
                createplus$CACHE.entrySet().removeIf(e -> e.getValue().level() != level);
            }
            Entity created = carry.getEntity(level);
            if (created == null) {
                return;
            }
            cached = new CachedRenderEntity(level, entityTag.copy(), created);
            createplus$CACHE.put(playerId, cached);
        }

        Entity entity = cached.entity();
        createplus$stabilize(player, entity);
        cir.setReturnValue(entity);
    }

    @Inject(method = "applyEntityTransformations", at = @At("TAIL"))
    private static void createplus$stabilizeThirdPerson(
            Player player, float partialTicks, PoseStack poseStack, Entity entity, CallbackInfo ci) {
        if (player != null && entity != null) {
            createplus$stabilize(player, entity);
        }
    }

    @Unique
    private static void createplus$stabilize(Player player, Entity entity) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        entity.setPos(x, y, z);
        entity.xo = x;
        entity.yo = y;
        entity.zo = z;
        entity.xOld = x;
        entity.yOld = y;
        entity.zOld = z;
        entity.setYRot(0.0F);
        entity.yRotO = 0.0F;
        entity.setXRot(0.0F);
        entity.xRotO = 0.0F;
        entity.setYHeadRot(0.0F);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setOnGround(true);
        entity.tickCount = player.tickCount;
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = 0.0F;
            living.yBodyRotO = 0.0F;
            living.yHeadRot = 0.0F;
            living.yHeadRotO = 0.0F;
            living.hurtTime = 0;
            living.deathTime = 0;
            living.swingTime = 0;
            living.attackAnim = 0.0F;
            living.oAttackAnim = 0.0F;
        }
    }
}
