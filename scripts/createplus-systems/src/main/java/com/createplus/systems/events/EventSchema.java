package com.createplus.systems.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * MariaDB schema bootstrap for Scheduled Server Events (America/Sao_Paulo fixed times).
 */
public final class EventSchema {
    private EventSchema() {
    }

    public static void ensureTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_event_schedule (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        event_type VARCHAR(32) NOT NULL,
                        display_name VARCHAR(96) NOT NULL,
                        hour_brt TINYINT NOT NULL,
                        minute_brt TINYINT NOT NULL,
                        duration_min INT NOT NULL DEFAULT 20,
                        min_players INT NOT NULL DEFAULT 1,
                        reward_spurs INT NOT NULL DEFAULT 512,
                        active TINYINT(1) NOT NULL DEFAULT 1,
                        INDEX idx_events_time (active, hour_brt, minute_brt)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_event_history (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_type VARCHAR(32) NOT NULL,
                        display_name VARCHAR(96) NOT NULL,
                        summary VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
        seedDefaultScheduleIfEmpty(conn);
    }

    private static void seedDefaultScheduleIfEmpty(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cp_event_schedule")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }
        record Seed(String type, String name, int hour, int min, int duration, int minPlayers, int reward) {
        }
        List<Seed> seeds = List.of(
                new Seed("SUPPLY_DROP", "Supply Drop — Caca ao Tesouro", 19, 0, 15, 1, 384),
                new Seed("DUNGEON_HOUR", "Hora da Dungeon (1.5x Moedas)", 20, 0, 60, 1, 0),
                new Seed("WORLD_BOSS", "Boss Mundial — Invasao", 21, 0, 20, 1, 768),
                new Seed("COMPETITION_FISHING", "Torneio de Pesca", 22, 0, 15, 1, 512)
        );
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO cp_event_schedule (event_type, display_name, hour_brt, minute_brt, duration_min, min_players, reward_spurs, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """)) {
            for (Seed s : seeds) {
                ps.setString(1, s.type());
                ps.setString(2, s.name());
                ps.setInt(3, s.hour());
                ps.setInt(4, s.min());
                ps.setInt(5, s.duration());
                ps.setInt(6, s.minPlayers());
                ps.setInt(7, s.reward());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
