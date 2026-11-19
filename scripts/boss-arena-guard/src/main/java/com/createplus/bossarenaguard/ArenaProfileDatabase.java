package com.createplus.bossarenaguard;

import com.google.gson.Gson;
import net.minecraft.resources.ResourceLocation;
import org.mariadb.jdbc.Driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class ArenaProfileDatabase implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final String SELECT_PROFILES = """
            SELECT boss_id, radius, lower_offset, upper_offset, height_mode, ceiling_y
            FROM boss_arena_profiles
            """;

    private final Path config;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Boss Arena Profile Database");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Map<String, ArenaProfile> profiles = Map.of();
    private volatile long revision;
    private String lastError;
    private boolean loaded;

    ArenaProfileDatabase(Path config) {
        this.config = config;
    }

    void start() {
        executor.scheduleWithFixedDelay(this::refresh, 0, 30, TimeUnit.SECONDS);
    }

    ArenaProfile get(String id) {
        return profiles.get(id);
    }

    long revision() {
        return revision;
    }

    private void refresh() {
        if (Files.notExists(config)) {
            return;
        }
        DatabaseConfig settings;
        try (var reader = Files.newBufferedReader(config)) {
            settings = GSON.fromJson(reader, DatabaseConfig.class);
            if (settings == null || settings.url() == null || !settings.url().startsWith("jdbc:mariadb://")
                    || settings.user() == null || settings.user().isBlank() || settings.password() == null) {
                throw new IllegalArgumentException("Invalid database settings");
            }
        } catch (IOException | RuntimeException ex) {
            warn("Could not read boss-arena-db.json. Expected url, user and password.");
            return;
        }

        Properties credentials = new Properties();
        credentials.setProperty("user", settings.user());
        credentials.setProperty("password", settings.password());
        credentials.setProperty("connectTimeout", "5000");
        credentials.setProperty("socketTimeout", "5000");
        Map<String, ArenaProfile> next = new HashMap<>();
        try (var connection = new Driver().connect(settings.url(), credentials);
             var query = connection.prepareStatement(SELECT_PROFILES)) {
            query.setQueryTimeout(5);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString("boss_id");
                    ResourceLocation key = ResourceLocation.tryParse(id);
                    if (key == null || !id.equals(key.toString())) {
                        warn("Invalid entity ID in boss_arena_profiles.");
                        return;
                    }
                    ArenaProfile profile;
                    try {
                        profile = ArenaProfile.configured(rows.getInt("radius"), rows.getInt("lower_offset"),
                                rows.getInt("upper_offset"), rows.getString("height_mode"),
                                rows.getObject("ceiling_y", Integer.class));
                    } catch (IllegalArgumentException ex) {
                        warn("Invalid arena profile for " + id + ".");
                        return;
                    }
                    next.put(id, profile);
                }
            }
        } catch (SQLException ex) {
            warn("Could not read boss_arena_profiles (SQLState " + ex.getSQLState() + ", code " + ex.getErrorCode() + ").");
            return;
        } catch (RuntimeException | LinkageError ex) {
            warn("Could not read boss_arena_profiles (" + ex.getClass().getSimpleName() + ").");
            return;
        }

        if (!loaded || !next.equals(profiles)) {
            profiles = Map.copyOf(next);
            revision++;
            BossArenaGuardMod.LOGGER.info("Loaded {} arena profiles from MariaDB.", next.size());
        } else if (lastError != null) {
            BossArenaGuardMod.LOGGER.info("Arena profile database connection recovered.");
        }
        loaded = true;
        lastError = null;
    }

    private void warn(String message) {
        if (!message.equals(lastError)) {
            BossArenaGuardMod.LOGGER.warn("{} Keeping the last valid arena profiles.", message);
            lastError = message;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record DatabaseConfig(String url, String user, String password) {
    }
}
