package com.createplus.keybinddefaults;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 * Applies Create+ default keybindings so Create: Lanterns and other mods do not collide.
 * Each binding's default key is updated (so "Reset" uses the pack default) and applied once per
 * install while the player still holds the mod's original shipped key.
 */
@Mod(value = KeybindDefaults.MOD_ID, dist = Dist.CLIENT)
public final class KeybindDefaults {
    public static final String MOD_ID = "createplus_keybind_defaults";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<String, String> PACK_DEFAULTS = createPackDefaults();

    private KeyMapping[] seen;

    public KeybindDefaults() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private static Map<String, String> createPackDefaults() {
        Map<String, String> map = new LinkedHashMap<>();
        // Punchy Moves conflicts
        map.put("key.punchy_moves.free_camera", "key.keyboard.unknown");
        map.put("key.punchy_moves.emote.cool_emote", "key.keyboard.unknown");
        map.put("key.punchy_moves.camera_toggle_v", "key.keyboard.keypad.0");
        map.put("key.punchy_moves.emote_menu", "key.keyboard.keypad.1");
        map.put("key.punchy_moves.emote.hello", "key.keyboard.keypad.2");
        map.put("key.punchy.open_main_menu", "key.keyboard.keypad.9");
        // Create: Lanterns (originals kept explicit)
        map.put("key.createlanterns.ring_power", "key.keyboard.g");
        map.put("key.createlanterns.construct_wheel", "key.keyboard.r");
        map.put("key.createlanterns.suit", "key.keyboard.h");
        map.put("key.createlanterns.grip", "key.keyboard.v");
        map.put("key.createlanterns.grip_turn", "key.keyboard.tab");
        map.put("key.createlanterns.roll_left", "key.keyboard.z");
        map.put("key.createlanterns.roll_right", "key.keyboard.c");
        map.put("key.createlanterns.power", "key.keyboard.b");
        map.put("key.createlanterns.modifier", "key.keyboard.left.alt");
        // Rebound out of Create: Lanterns' way
        map.put("key.voice_chat", "key.mouse.5");
        map.put("key.voice_chat_group", "key.keyboard.page.up");
        map.put("key.hide_icons", "key.keyboard.end");
        map.put("zume.zoom", "key.mouse.4");
        map.put("quark.keybind.back", "key.keyboard.unknown");
        map.put("quark.keybind.variant_selector", "key.keyboard.period");
        map.put("quark.keybind.change_hotbar", "key.keyboard.comma");
        map.put("key.sophisticatedbackpacks.open_backpack", "key.keyboard.o");
        map.put("supplementaries.keybind.quiver", "key.keyboard.semicolon");
        map.put("key.createrailwaysnavigator.route_overlay_options", "key.keyboard.home");
        map.put("gui.xaero_new_waypoint", "key.keyboard.page.down");
        map.put("gui.xaero_enlarge_map", "key.keyboard.right.control");
        map.put("key.map_atlases.place_pin", "key.keyboard.insert");
        map.put("keybind.name.EMOTE_WHEEL", "key.keyboard.right.alt");
        map.put("keybind.name.MENU_ACCESS", "key.keyboard.unknown");
        map.put("keybind.name.ESSENTIAL_FRIENDS", "key.keyboard.unknown");
        map.put("keybind.name.CHAT_PEEK", "key.keyboard.unknown");
        map.put("keybind.name.COSMETIC_STUDIO", "key.keyboard.unknown");
        map.put("keybind.name.ZOOM", "key.keyboard.unknown");
        map.put("iris.keybind.reload", "key.keyboard.f8");
        map.put("iris.keybind.toggleShaders", "key.keyboard.f9");
        map.put("iris.keybind.shaderPackSelection", "key.keyboard.f10");
        return Map.copyOf(map);
    }

    // Some mods (e.g. Punchy Moves) create mappings after a resource reload and swap in a new options array.
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
        boolean changed = false;
        for (KeyMapping mapping : seen) {
            String targetName = PACK_DEFAULTS.get(mapping.getName());
            if (targetName == null) {
                continue;
            }
            InputConstants.Key targetKey = resolveKey(targetName);
            String originalDefault = mapping.getDefaultKey().getName();
            setDefaultKey(mapping, targetKey);
            if (done.add(mapping.getName())) {
                marked = true;
                String current = mapping.saveString();
                if (current.equals(originalDefault) && !current.equals(targetName)) {
                    mapping.setKey(targetKey);
                    changed = true;
                    LOGGER.info("Rebound {} from {} to {}", mapping.getName(), current, targetName);
                }
            }
        }
        if (changed) {
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

    private static InputConstants.Key resolveKey(String name) {
        if ("key.keyboard.unknown".equals(name)) {
            return InputConstants.UNKNOWN;
        }
        return InputConstants.getKey(name);
    }

    // Makes "Reset" in Controls restore the pack default instead of the mod's original key.
    private static void setDefaultKey(KeyMapping mapping, InputConstants.Key targetKey) {
        if (mapping.getDefaultKey().equals(targetKey)) {
            return;
        }
        try {
            Field field = KeyMapping.class.getDeclaredField("defaultKey");
            field.setAccessible(true);
            field.set(mapping, targetKey);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not change the default key of {}", mapping.getName(), e);
        }
    }
}
