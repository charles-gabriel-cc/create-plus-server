package com.createplus.systems.dungeons;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MariaDB schema bootstrap for Instanced Dungeons.
 */
public final class DungeonSchema {
    private DungeonSchema() {
    }

    public static void ensureTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_dungeon_runs (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        dungeon_id VARCHAR(64) NOT NULL,
                        party_members TEXT NOT NULL,
                        party_size INT NOT NULL,
                        duration_sec INT NOT NULL,
                        victory TINYINT(1) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_druns_dungeon (dungeon_id, victory)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_dungeon_cooldowns (
                        player_uuid CHAR(36) NOT NULL,
                        dungeon_id VARCHAR(64) NOT NULL,
                        available_at TIMESTAMP NOT NULL,
                        PRIMARY KEY (player_uuid, dungeon_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_dungeon_records (
                        dungeon_id VARCHAR(64) PRIMARY KEY,
                        best_time_sec INT NOT NULL,
                        holders VARCHAR(255) NOT NULL,
                        achieved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_dungeon_return_pos (
                        player_uuid CHAR(36) PRIMARY KEY,
                        dimension VARCHAR(128) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL,
                        pitch FLOAT NOT NULL,
                        saved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }
}
