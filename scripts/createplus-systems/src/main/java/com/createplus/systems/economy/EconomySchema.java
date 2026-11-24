package com.createplus.systems.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * MariaDB schema bootstrap and starter data for the Economy module.
 */
public final class EconomySchema {
    public static final ZoneId SERVER_ZONE = ZoneId.of("America/Sao_Paulo");

    private EconomySchema() {
    }

    public static void ensureTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_shop_items (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        category VARCHAR(48) NOT NULL,
                        display_name VARCHAR(96) NOT NULL,
                        item_id VARCHAR(128) NOT NULL,
                        item_snbt MEDIUMTEXT NULL,
                        amount INT NOT NULL DEFAULT 1,
                        buy_price INT NULL,
                        sell_price INT NULL,
                        sort_order INT NOT NULL DEFAULT 0,
                        active TINYINT(1) NOT NULL DEFAULT 1,
                        INDEX idx_shop_cat_active (category, active, sort_order),
                        INDEX idx_shop_item_id (item_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_daily_contracts (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        contract_date DATE NOT NULL,
                        profession VARCHAR(48) NOT NULL,
                        display_name VARCHAR(96) NOT NULL,
                        item_id VARCHAR(128) NOT NULL,
                        unit_price INT NOT NULL,
                        daily_cap INT NOT NULL,
                        INDEX idx_contracts_date (contract_date)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_daily_contract_progress (
                        contract_id INT NOT NULL,
                        player_uuid CHAR(36) NOT NULL,
                        delivered INT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (contract_id, player_uuid),
                        CONSTRAINT fk_contract_progress FOREIGN KEY (contract_id)
                            REFERENCES cp_daily_contracts(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_ah_listings (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        seller_uuid CHAR(36) NOT NULL,
                        seller_name VARCHAR(32) NOT NULL,
                        item_id VARCHAR(128) NOT NULL,
                        item_name VARCHAR(128) NOT NULL,
                        item_count INT NOT NULL,
                        item_snbt MEDIUMTEXT NOT NULL,
                        price INT NOT NULL,
                        status ENUM('ACTIVE','SOLD','CANCELLED','EXPIRED') NOT NULL DEFAULT 'ACTIVE',
                        buyer_uuid CHAR(36) NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at TIMESTAMP NOT NULL,
                        closed_at TIMESTAMP NULL,
                        INDEX idx_ah_status_exp (status, expires_at),
                        INDEX idx_ah_seller (seller_uuid, status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_ah_mailbox (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid CHAR(36) NOT NULL,
                        kind ENUM('COINS','ITEM') NOT NULL,
                        spurs INT NOT NULL DEFAULT 0,
                        item_snbt MEDIUMTEXT NULL,
                        reason VARCHAR(160) NOT NULL,
                        claimed TINYINT(1) NOT NULL DEFAULT 0,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        claimed_at TIMESTAMP NULL,
                        INDEX idx_mailbox_player_claimed (player_uuid, claimed)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_economy_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        actor_uuid CHAR(36) NOT NULL,
                        actor_name VARCHAR(32) NOT NULL,
                        action VARCHAR(32) NOT NULL,
                        spurs_delta INT NOT NULL,
                        item_id VARCHAR(128) NULL,
                        item_count INT NULL,
                        details VARCHAR(255) NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_eco_log_actor (actor_uuid, created_at),
                        INDEX idx_eco_log_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }

        seedStarterShopIfEmpty(conn);
        ensureTodayContracts(conn, LocalDate.now(SERVER_ZONE));
    }

    private static void seedStarterShopIfEmpty(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cp_shop_items")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        record Seed(String cat, String name, String itemId, int amount, Integer buy, Integer sell, int order) {
        }

        List<Seed> seeds = List.of(
                // Utilidades (Admin Shop)
                new Seed("utilidades", "Tocha (x16)", "minecraft:torch", 16, 16, null, 10),
                new Seed("utilidades", "Perola do Ender", "minecraft:ender_pearl", 1, 64, null, 20),
                new Seed("utilidades", "Etiqueta de Nome", "minecraft:name_tag", 1, 192, null, 30),
                new Seed("utilidades", "Sela", "minecraft:saddle", 1, 160, null, 40),
                new Seed("utilidades", "Esponja", "minecraft:sponge", 1, 256, null, 50),
                // Create & Logistica
                new Seed("create", "Lote de Andesito (x32)", "minecraft:andesite", 32, 32, null, 10),
                new Seed("create", "Bola de Slime (x4)", "minecraft:slime_ball", 4, 96, null, 20),
                new Seed("create", "Alga Seca (x16)", "minecraft:dried_kelp", 16, 24, null, 30),
                // Itens nao-automatizaveis comprados no /vender
                new Seed("venda_livre", "Diamante", "minecraft:diamond", 1, null, 48, 10),
                new Seed("venda_livre", "Fragmento de Netherita", "minecraft:netherite_scrap", 1, null, 192, 20),
                new Seed("venda_livre", "Coracao do Mar", "minecraft:heart_of_the_sea", 1, null, 320, 30),
                new Seed("venda_livre", "Estrela do Nether", "minecraft:nether_star", 1, null, 1024, 40),
                new Seed("venda_livre", "Fragmento de Eco", "minecraft:echo_shard", 1, null, 96, 50)
        );

        String sql = """
                INSERT INTO cp_shop_items (category, display_name, item_id, amount, buy_price, sell_price, sort_order, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Seed s : seeds) {
                ps.setString(1, s.cat());
                ps.setString(2, s.name());
                ps.setString(3, s.itemId());
                ps.setInt(4, s.amount());
                if (s.buy() == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, s.buy());
                if (s.sell() == null) ps.setNull(6, java.sql.Types.INTEGER); else ps.setInt(6, s.sell());
                ps.setInt(7, s.order());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public static void ensureTodayContracts(Connection conn, LocalDate today) throws SQLException {
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT COUNT(*) FROM cp_daily_contracts WHERE contract_date = ?")) {
            check.setDate(1, java.sql.Date.valueOf(today));
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        }

        // 1 daily Black Market order per profession (anti-inflation daily cap per player)
        record ContractSeed(String profession, String name, String itemId, int unitPrice, int cap) {
        }

        List<ContractSeed> defaults = List.of(
                new ContractSeed("Engenheiro", "Engrenagem de Latao / Barra de Latao", "create:brass_ingot", 12, 128),
                new ContractSeed("Ferreiro", "Barra de Ferro Refinada", "minecraft:iron_ingot", 4, 192),
                new ContractSeed("Taumaturgo", "Ambar / Salis Mundus", "thaumaturge:salis_mundus", 24, 64),
                new ContractSeed("Chef & Alquimista", "Ensopado / Prato Preparado", "farmersdelight:beef_stew", 20, 64),
                new ContractSeed("Naturalista", "Perola Negra / Coleta Marinha", "hybrid_aquatic:black_pearl", 48, 32)
        );

        String insert = """
                INSERT INTO cp_daily_contracts (contract_date, profession, display_name, item_id, unit_price, daily_cap)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (ContractSeed c : defaults) {
                ps.setDate(1, java.sql.Date.valueOf(today));
                ps.setString(2, c.profession());
                ps.setString(3, c.name());
                ps.setString(4, c.itemId());
                ps.setInt(5, c.unitPrice());
                ps.setInt(6, c.cap());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
