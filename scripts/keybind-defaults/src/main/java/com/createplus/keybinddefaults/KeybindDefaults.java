package com.createplus.keybinddefaults;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ships Punchy Moves' free camera (B) and the Fluid & Foolish "Static" emote (K) unbound, since both keys
 * collide with other bindings in the pack. Each binding is cleared once per install and only while it still
 * holds the mod's own key, so a key the player chooses later is kept.
 */
@Mod(value = KeybindDefaults.MOD_ID, dist = Dist.CLIENT)
public final class KeybindDefaults {
    public static final String MOD_ID = "createplus_keybind_defaults";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<String, String> SHIPPED_KEYS = Map.of(
        "key.punchy_moves.free_camera", "key.keyboard.b",
        "key.punchy_moves.emote.cool_emote", "key.keyboard.k");

    private KeyMapping[] seen;

    public KeybindDefaults() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // Punchy Moves creates these mappings after a resource reload and swaps in a new options array.
    private void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft.options;
        if (options == null || options.keyMappings == seen) {
            return;
        }
        seen = options.keyMappings;
        // Game-directory root: kept out of git and the packwiz index, so a fresh install starts unmarked.
        Path marker = minecraft.gameDirectory.toPath().resolve(MOD_ID + ".txt");
        Set<String> done = new LinkedHashSet<>();
        try {
            if (Files.exists(marker)) {
                done.addAll(Files.readAllLines(marker, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            LOGGER.error("Could not read {}", marker, e);
            return;
        }
        boolean marked = false;
        boolean unbound = false;
        for (KeyMapping mapping : seen) {
            String shipped = SHIPPED_KEYS.get(mapping.getName());
            if (shipped == null) {
                continue;
            }
            unbindDefault(mapping);
            if (done.add(mapping.getName())) {
                marked = true;
                if (mapping.saveString().equals(shipped)) {
                    mapping.setKey(InputConstants.UNKNOWN);
                    unbound = true;
                    LOGGER.info("Unbound {} (was {})", mapping.getName(), shipped);
                }
            }
        }
        if (unbound) {
            KeyMapping.resetMapping();
            options.save();
        }
        if (marked) {
            try {
                Files.write(marker, done, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("Could not write {}", marker, e);
            }
        }
    }

    // Makes "Reset" in Controls leave the binding empty instead of restoring B or K.
    private static void unbindDefault(KeyMapping mapping) {
        if (mapping.getDefaultKey() == InputConstants.UNKNOWN) {
            return;
        }
        try {
            Field field = KeyMapping.class.getDeclaredField("defaultKey");
            field.setAccessible(true);
            field.set(mapping, InputConstants.UNKNOWN);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not change the default key of {}", mapping.getName(), e);
        }
    }
}
