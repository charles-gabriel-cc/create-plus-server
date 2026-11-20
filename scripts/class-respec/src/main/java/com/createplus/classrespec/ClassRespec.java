package com.createplus.classrespec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.puffish.skillsmod.api.Category;
import net.puffish.skillsmod.api.SkillsAPI;

@Mod(ClassRespec.MOD_ID)
public final class ClassRespec {
    public static final String MOD_ID = "createplus_class_respec";
    public static final ResourceLocation CLASS_SKILLS =
        ResourceLocation.fromNamespaceAndPath("skill_tree_rpgs", "class_skills");

    public ClassRespec(IEventBus modBus) {
        modBus.addListener(this::registerPayloads);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToServer(ResetRequest.TYPE, ResetRequest.CODEC, ClassRespec::handleReset);
    }

    private static void handleReset(ResetRequest request, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && !player.isSpectator()) {
            String message = resetClassSkills(player) ? "reset_done" : "nothing_to_reset";
            player.displayClientMessage(text(message), true);
        }
    }

    static boolean resetClassSkills(ServerPlayer player) {
        Category category = SkillsAPI.getCategory(CLASS_SKILLS).orElse(null);
        if (category == null || !category.isUnlocked(player) || category.streamUnlockedSkills(player).findAny().isEmpty()) {
            return false;
        }
        category.resetSkills(player);
        return true;
    }

    public static Component text(String key) {
        return Component.translatable(MOD_ID + "." + key);
    }

    public record ResetRequest() implements CustomPacketPayload {
        public static final ResetRequest INSTANCE = new ResetRequest();
        public static final Type<ResetRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "reset_class_skills"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ResetRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<ResetRequest> type() {
            return TYPE;
        }
    }
}
