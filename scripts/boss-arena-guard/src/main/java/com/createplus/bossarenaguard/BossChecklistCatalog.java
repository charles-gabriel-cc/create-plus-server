package com.createplus.bossarenaguard;

import com.google.gson.Gson;
import com.yori3o.boss_checklist.common.client.data.OverrideManager;
import com.yori3o.boss_checklist.common.server.data.ServerBossIdsLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class BossChecklistCatalog {
    private static final Gson GSON = new Gson();
    private static final int BOSS = 1;
    private static final int MINIBOSS = 2;
    private static volatile Set<ResourceLocation> bosses = Set.of();

    private BossChecklistCatalog() {
    }

    static boolean contains(ResourceLocation id) {
        return bosses.contains(id);
    }

    static boolean reload() {
        if (!ModList.get().isLoaded("boss_checklist")) {
            return false;
        }

        Map<String, Integer> types = new HashMap<>();
        try (var stream = ServerBossIdsLoader.class.getResourceAsStream("/assets/boss_checklist/bosses.json")) {
            if (stream == null) {
                throw new IOException("Boss Checklist definitions are missing");
            }
            loadTypes(new InputStreamReader(stream, StandardCharsets.UTF_8), types);
            if (Files.exists(OverrideManager.OVERRIDE_BOSSES_PATH)) {
                try (var overrides = Files.newBufferedReader(OverrideManager.OVERRIDE_BOSSES_PATH)) {
                    loadTypes(overrides, types);
                }
            }
        } catch (IOException | RuntimeException ex) {
            BossArenaGuardMod.LOGGER.warn("Could not read Boss Checklist categories. Keeping the last arena catalog.", ex);
            return false;
        }

        Set<ResourceLocation> next = new HashSet<>();
        types.forEach((id, type) -> {
            if ((type == BOSS || type == MINIBOSS) && ServerBossIdsLoader.isBoss(id)) {
                ResourceLocation key = ResourceLocation.tryParse(id);
                if (key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
                    next.add(key);
                }
            }
        });
        if (next.equals(bosses)) {
            return false;
        }
        bosses = Set.copyOf(next);
        BossArenaGuardMod.LOGGER.info("Boss Checklist: {} book entries eligible for arenas.", next.size());
        return true;
    }

    static void clear() {
        bosses = Set.of();
    }

    private static void loadTypes(Reader reader, Map<String, Integer> types) {
        for (Definition boss : GSON.fromJson(reader, Definition[].class)) {
            int fallback = boss.replace() ? BOSS : types.getOrDefault(boss.id(), BOSS);
            types.put(boss.id(), boss.type() == null ? fallback : boss.type());
        }
    }

    private record Definition(String id, Integer type, boolean replace) {
    }
}
