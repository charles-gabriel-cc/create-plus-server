package com.createplus.systems.dungeons;

import com.createplus.systems.CreatePlusSystemsMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-driven definition of an instanced dungeon (loaded from config/createplus/dungeons/*.json).
 * Supports ready-made .nbt templates (single or tiled) or a built-in fallback arena for immediate testing.
 */
public record DungeonDefinition(
        String id,
        String displayName,
        int tier,
        String description,
        String keyItemId,
        int keyCount,
        int maxPlayers,
        int timeLimitSec,
        int cooldownSec,
        int arenaRadius,
        boolean environmentalHazard,
        List<TemplateTile> templateTiles,
        Offset spawnOffset,
        List<WaveDef> waves,
        BossDef boss,
        int rewardSpurs,
        List<RewardItem> rewardItems
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record TemplateTile(String structureId, int offsetX, int offsetY, int offsetZ) {
    }

    public record Offset(double x, double y, double z) {
    }

    public record WaveMob(String entityId, int count, double hpMultiplier) {
    }

    public record WaveDef(String title, List<WaveMob> mobs) {
    }

    public record BossDef(String entityId, String displayName, double hpMultiplier, Offset spawnOffset) {
    }

    public record RewardItem(String itemId, int count, double chance) {
    }

    public static Map<String, DungeonDefinition> loadAll(Path configDir) {
        Path dir = configDir.resolve("createplus").resolve("dungeons");
        try {
            Files.createDirectories(dir);
            boolean hasJson;
            try (var stream = Files.list(dir)) {
                hasJson = stream.anyMatch(p -> p.toString().endsWith(".json"));
            }
            if (!hasJson) {
                writeDefaults(dir);
            }
        } catch (IOException ex) {
            CreatePlusSystemsMod.LOGGER.error("Failed to prepare dungeons config dir: {}", ex.getMessage());
        }

        Map<String, DungeonDefinition> map = new LinkedHashMap<>();
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path file : files) {
                try (var reader = Files.newBufferedReader(file)) {
                    DungeonDefinition def = GSON.fromJson(reader, DungeonDefinition.class);
                    if (def != null && def.id() != null && !def.id().isBlank()) {
                        map.put(def.id(), def);
                    }
                } catch (Exception ex) {
                    CreatePlusSystemsMod.LOGGER.error("Invalid dungeon config {}: {}", file.getFileName(), ex.getMessage());
                }
            }
        } catch (IOException ex) {
            CreatePlusSystemsMod.LOGGER.error("Failed to list dungeon configs: {}", ex.getMessage());
        }
        return map;
    }

    private static void writeDefaults(Path dir) throws IOException {
        List<DungeonDefinition> defaults = List.of(
                new DungeonDefinition(
                        "cripta_tier1",
                        "Castelo em Ruinas (Tier 1)",
                        1,
                        "Castelo medieval em ruinas (48x45x48, ATi) com ondas de mortos-vivos e Ferrous Wroughtnaut.",
                        "", // Gratuita no piloto para testar imediatamente
                        0,
                        4,
                        480,
                        300,
                        26,
                        false,
                        List.of(new TemplateTile("createplussystems:ruined_castle", -24, 0, -24)),
                        new Offset(0, 6, -12),
                        List.of(
                                new WaveDef("Onda 1 — Guardioes do Templo", List.of(
                                        new WaveMob("minecraft:stray", 4, 1.0),
                                        new WaveMob("cataclysm:draugr", 4, 1.0)
                                )),
                                new WaveDef("Onda 2 — Ordem Caida", List.of(
                                        new WaveMob("minecraft:wither_skeleton", 3, 1.0),
                                        new WaveMob("minecraft:vindicator", 2, 1.0)
                                ))
                        ),
                        new BossDef("mowziesmobs:wroughtnaut", "Ferrous Wroughtnaut", 1.0, new Offset(0, 2, 8)),
                        320,
                        List.of(
                                new RewardItem("minecraft:diamond", 3, 1.0),
                                new RewardItem("minecraft:emerald", 6, 1.0),
                                new RewardItem("minecraft:echo_shard", 1, 0.35)
                        )
                ),
                new DungeonDefinition(
                        "forja_tier2",
                        "Caverna do Wither (Tier 2)",
                        2,
                        "Caverna sombria (45x45x45, ATi) — ambiente hostil: traga banquetes/pocoes do Chef!",
                        "minecraft:trial_key",
                        1,
                        5,
                        600,
                        900,
                        24,
                        true,
                        List.of(new TemplateTile("createplussystems:wither_cavern", -22, 0, -22)),
                        new Offset(0, 6, -10),
                        List.of(
                                new WaveDef("Onda 1 — Patrulha da Forja", List.of(
                                        new WaveMob("cataclysm:ignited_revenant", 2, 1.0),
                                        new WaveMob("cataclysm:koboleton", 4, 1.0)
                                )),
                                new WaveDef("Onda 2 — Legiao Incandescente", List.of(
                                        new WaveMob("cataclysm:ignited_berserker", 2, 1.0),
                                        new WaveMob("minecraft:wither_skeleton", 3, 1.1)
                                ))
                        ),
                        new BossDef("cataclysm:kobolediator", "Kobolediator da Forja", 1.0, new Offset(0, 3, 8)),
                        896,
                        List.of(
                                new RewardItem("minecraft:netherite_scrap", 2, 1.0),
                                new RewardItem("minecraft:diamond", 6, 1.0),
                                new RewardItem("minecraft:heart_of_the_sea", 1, 0.25)
                        )
                ),
                new DungeonDefinition(
                        "coliseu_tier3",
                        "Monumento Abissal (Tier 3)",
                        3,
                        "Monumento colossal (48x48x48, ATi) — raid endgame em grupo com Maledictus.",
                        "minecraft:ominous_trial_key",
                        1,
                        6,
                        900,
                        1800,
                        26,
                        true,
                        List.of(new TemplateTile("createplussystems:warden_monument", -24, 0, -24)),
                        new Offset(0, 8, -12),
                        List.of(
                                new WaveDef("Onda 1 — Vanguarda Amaldicoada", List.of(
                                        new WaveMob("cataclysm:elite_draugr", 4, 1.1),
                                        new WaveMob("cataclysm:aptrgangr", 2, 1.0)
                                )),
                                new WaveDef("Onda 2 — Sentinelas do Abismo", List.of(
                                        new WaveMob("cataclysm:ender_golem", 1, 1.0),
                                        new WaveMob("cataclysm:elite_draugr", 3, 1.1)
                                ))
                        ),
                        new BossDef("cataclysm:maledictus", "Maledictus do Coliseu", 1.0, new Offset(0, 6, 8)),
                        2048,
                        List.of(
                                new RewardItem("minecraft:nether_star", 1, 0.6),
                                new RewardItem("minecraft:netherite_scrap", 4, 1.0),
                                new RewardItem("minecraft:echo_shard", 4, 1.0)
                        )
                )
        );
        for (DungeonDefinition def : defaults) {
            Files.writeString(dir.resolve(def.id() + ".json"), GSON.toJson(def));
        }
    }
}
