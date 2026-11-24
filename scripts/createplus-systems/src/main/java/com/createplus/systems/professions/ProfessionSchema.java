package com.createplus.systems.professions;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MariaDB schema bootstrap for Mastery Professions.
 */
public final class ProfessionSchema {
    private ProfessionSchema() {
    }

    public static void ensureTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_player_professions (
                        player_uuid CHAR(36) NOT NULL,
                        player_name VARCHAR(32) NOT NULL,
                        profession_id VARCHAR(32) NOT NULL,
                        level INT NOT NULL DEFAULT 1,
                        xp INT NOT NULL DEFAULT 0,
                        tier INT NOT NULL DEFAULT 1,
                        mastered_at TIMESTAMP NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, profession_id),
                        INDEX idx_prof_ranking (profession_id, level DESC, xp DESC)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS cp_player_discoveries (
                        player_uuid CHAR(36) NOT NULL,
                        profession_id VARCHAR(32) NOT NULL,
                        discovery_key VARCHAR(160) NOT NULL,
                        discovered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, profession_id, discovery_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }
}
