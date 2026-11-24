package com.createplus.systems.core;

import com.createplus.systems.CreatePlusSystemsMod;
import com.google.gson.Gson;
import org.mariadb.jdbc.Driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Asynchronous MariaDB access layer shared by all Create+ server modules.
 * Reads createplus-db.json from the server directory, falling back to boss-arena-db.json.
 */
public final class DatabaseManager implements AutoCloseable {
    private static final Gson GSON = new Gson();

    private volatile Path serverRoot;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "CreatePlus-Systems-DB");
        thread.setDaemon(true);
        return thread;
    });

    private volatile DatabaseConfig cachedConfig;
    private volatile boolean configured;

    public DatabaseManager() {
        this.serverRoot = Path.of(".");
    }

    public boolean loadConfig(Path root) {
        this.serverRoot = root;
        Path primaryConfig = root.resolve("createplus-db.json");
        Path fallbackConfig = root.resolve("boss-arena-db.json");
        Path active = Files.exists(primaryConfig) ? primaryConfig : fallbackConfig;
        if (Files.notExists(active)) {
            CreatePlusSystemsMod.LOGGER.warn(
                    "Database config not found ({} or {}). Economy/Systems DB features are disabled.",
                    primaryConfig.getFileName(), fallbackConfig.getFileName()
            );
            this.configured = false;
            this.cachedConfig = null;
            return false;
        }
        try (var reader = Files.newBufferedReader(active)) {
            DatabaseConfig cfg = GSON.fromJson(reader, DatabaseConfig.class);
            if (cfg == null || cfg.url() == null || !cfg.url().startsWith("jdbc:mariadb://")
                    || cfg.user() == null || cfg.user().isBlank() || cfg.password() == null) {
                throw new IllegalArgumentException("Invalid url/user/password in " + active.getFileName());
            }
            this.cachedConfig = cfg;
            this.configured = true;
            CreatePlusSystemsMod.LOGGER.info("Loaded MariaDB configuration from {}.", active.getFileName());
            return true;
        } catch (IOException | RuntimeException ex) {
            CreatePlusSystemsMod.LOGGER.error("Failed to read {}: {}", active.getFileName(), ex.getMessage());
            this.configured = false;
            this.cachedConfig = null;
            return false;
        }
    }

    public Path getServerRoot() {
        return serverRoot;
    }

    public boolean isConfigured() {
        return configured && cachedConfig != null;
    }

    public Connection openConnection() throws SQLException {
        DatabaseConfig cfg = this.cachedConfig;
        if (cfg == null) {
            throw new SQLException("MariaDB is not configured.");
        }
        Properties props = new Properties();
        props.setProperty("user", cfg.user());
        props.setProperty("password", cfg.password());
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "8000");
        return new Driver().connect(cfg.url(), props);
    }

    public <T> CompletableFuture<T> supplyAsync(SqlFunction<Connection, T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = openConnection()) {
                return work.apply(conn);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }, executor);
    }

    public CompletableFuture<Void> runAsync(SqlConsumer<Connection> work) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = openConnection()) {
                work.accept(conn);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface SqlFunction<C, R> {
        R apply(C connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer<C> {
        void accept(C connection) throws SQLException;
    }

    private record DatabaseConfig(String url, String user, String password) {
    }
}
