package com.createplus.systems.professions;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The 5 Albion-style Mastery Professions of Create+ (all levelable in parallel via RP practice).
 */
public enum ProfessionType {
    ENGENHEIRO("engenheiro", "Engenheiro", Items.PISTON,
            "Refino industrial, energia (PowerGrid/Diesel), trens e XP liquido."),
    FERREIRO("ferreiro", "Ferreiro / Armeiro", Items.ANVIL,
            "Forja, reparo e upgrade de armas, arcos, escudos e armaduras T2-T4."),
    TAUMATURGO("taumaturgo", "Taumaturgo", Items.AMETHYST_SHARD,
            "Pesquisa de aspectos, essentia, infusoes, amuletos e Golens (Thaumaturge)."),
    CHEF("chef", "Chef & Alquimista", Items.CAMPFIRE,
            "Banquetes de raid (Comfort/Nourishment) e pocoes empilhaveis."),
    NATURALISTA("naturalista", "Naturalista / Zoologo", Items.FISHING_ROD,
            "Fauna exotica (Alex's Mobs), mergulho/pesca profunda (Hybrid Aquatic) e pets.");

    public static final int MAX_LEVEL = 40;

    public final String id;
    public final String displayName;
    public final Item icon;
    public final String description;

    ProfessionType(String id, String displayName, Item icon, String description) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
    }

    public static ProfessionType byId(String raw) {
        if (raw == null) return null;
        for (ProfessionType t : values()) {
            if (t.id.equalsIgnoreCase(raw) || t.name().equalsIgnoreCase(raw)) {
                return t;
            }
        }
        return null;
    }

    public static int tierForLevel(int level) {
        if (level >= 30) return 4; // Mestre
        if (level >= 20) return 3; // Especialista
        if (level >= 10) return 2; // Aprendiz
        return 1;                  // Iniciante
    }

    public static String tierLabel(int tier) {
        return switch (tier) {
            case 4 -> "Tier 4 — Mestre";
            case 3 -> "Tier 3 — Especialista";
            case 2 -> "Tier 2 — Aprendiz";
            default -> "Tier 1 — Iniciante";
        };
    }

    public static int xpRequiredForNextLevel(int currentLevel) {
        if (currentLevel >= MAX_LEVEL) return 0;
        return 100 + (currentLevel * currentLevel * 12);
    }
}
