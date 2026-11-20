package com.createplus.classrespec.mixin;

import java.util.Optional;

import com.createplus.classrespec.ClassRespec;
import com.createplus.classrespec.ClassRespec.ResetRequest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.puffish.skillsmod.client.gui.SkillsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SkillsScreen.class, remap = false)
public abstract class ClassSkillsScreenMixin extends Screen {
    @Shadow
    private Optional<ResourceLocation> optActiveCategoryId;

    @Unique
    private Button classRespec$button;

    protected ClassSkillsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void classRespec$addButton(CallbackInfo callback) {
        int buttonWidth = Math.max(94, font.width(ClassRespec.text("button")) + 14);
        classRespec$button = addRenderableWidget(Button.builder(ClassRespec.text("button"), button -> classRespec$confirm())
            .bounds(width - buttonWidth - 22, height - 42, buttonWidth, 20)
            .build());
        if (!classRespec$serverSupportsReset()) {
            classRespec$button.setTooltip(Tooltip.create(ClassRespec.text("server_missing")));
        }
        classRespec$updateButton();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void classRespec$renderButton(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        classRespec$updateButton();
        classRespec$button.render(graphics, mouseX, mouseY, delta);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void classRespec$clickButton(double x, double y, int mouseButton, CallbackInfoReturnable<Boolean> callback) {
        classRespec$updateButton();
        if (classRespec$button.mouseClicked(x, y, mouseButton)) {
            callback.setReturnValue(true);
        }
    }

    @Unique
    private void classRespec$updateButton() {
        Minecraft client = Minecraft.getInstance();
        classRespec$button.visible = optActiveCategoryId.filter(ClassRespec.CLASS_SKILLS::equals).isPresent();
        classRespec$button.active = classRespec$serverSupportsReset() && client.player != null && !client.player.isSpectator();
    }

    @Unique
    private static boolean classRespec$serverSupportsReset() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null
            && NetworkRegistry.hasChannel(connection.getConnection(), ConnectionProtocol.PLAY, ResetRequest.TYPE.id());
    }

    @Unique
    private void classRespec$confirm() {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (client.getConnection() != connection || client.player == null) {
                return;
            }
            client.setScreen(this);
            if (confirmed && classRespec$serverSupportsReset()) {
                PacketDistributor.sendToServer(ResetRequest.INSTANCE);
            }
        }, ClassRespec.text("confirm_title"), ClassRespec.text("confirm_message"),
            ClassRespec.text("confirm_button"), CommonComponents.GUI_CANCEL));
    }
}
