package com.createplus.requiredpacks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vanilla server.properties holds a single required pack; this pushes the extra ones listed in
 * config/createplus_required_packs.json after login. A decline disconnects the player because
 * require-resource-pack=true makes the server reject any declined pack.
 */
@Mod(RequiredPacks.MOD_ID)
public final class RequiredPacks {
    public static final String MOD_ID = "createplus_required_packs";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public RequiredPacks() {
        NeoForge.EVENT_BUS.addListener(RequiredPacks::onLogin);
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.server.isDedicatedServer()) {
            return;
        }
        Path file = FMLPaths.CONFIGDIR.get().resolve(MOD_ID + ".json");
        JsonObject config;
        // Read on every login so a deployed config change applies without a restart.
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not read {}; no extra packs sent", file, e);
            return;
        }
        Optional<Component> prompt = Optional.ofNullable(config.get("prompt"))
            .map(p -> Component.literal(p.getAsString()));
        for (JsonElement element : config.getAsJsonArray("packs")) {
            JsonObject pack = element.getAsJsonObject();
            String url = pack.get("url").getAsString();
            UUID id = UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
            player.connection.send(new ClientboundResourcePackPushPacket(
                id, url, pack.get("sha1").getAsString(), true, prompt));
            LOGGER.info("Sent required pack {} to {}", url, player.getGameProfile().getName());
        }
    }
}
