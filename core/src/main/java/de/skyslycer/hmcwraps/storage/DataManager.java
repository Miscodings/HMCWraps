package de.skyslycer.hmcwraps.storage;

import de.skyslycer.hmcwraps.HMCWrapsPlugin;
import de.skyslycer.hmcwraps.serialization.database.DatabaseSettings;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Persists per-player data (favorites, filter) in SQLite or MySQL, with an in-memory cache so the
 * synchronous {@link Storage} getters never touch the database.
 * With type "pdc" no database is used and the storages fall back to the player's PersistentDataContainer.
 */
public class DataManager {

    private static final String TABLE = "HMCWRAPSDATABASE";

    /** Cached row. A null field means "not stored in the database yet". */
    public static class PlayerData {
        public volatile String favorites;
        public volatile Boolean filter;
    }

    private final HMCWrapsPlugin plugin;
    private final DatabaseSettings settings;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "HMCWraps-Database"));
    private final boolean mysql;
    private final boolean enabled;
    private Connection connection;

    public DataManager(HMCWrapsPlugin plugin, DatabaseSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        var type = settings.getType().toLowerCase();
        this.mysql = type.equals("mysql");
        boolean wanted = mysql || type.equals("sqlite");
        if (!wanted && !type.equals("pdc") && !type.equals("none")) {
            plugin.getLogger().warning("Invalid database type '" + type + "', using PDC storage.");
        }
        this.enabled = wanted && setup();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public PlayerData getCached(UUID uuid) {
        return cache.get(uuid);
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    /** Blocking load, call from an async thread (e.g. AsyncPlayerPreLoginEvent). */
    public void load(UUID uuid) {
        if (!enabled) {
            return;
        }
        try {
            cache.put(uuid, executor.submit(() -> query(uuid)).get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load data for " + uuid + ": " + e.getMessage());
            cache.put(uuid, new PlayerData());
        }
    }

    public void loadAsync(UUID uuid) {
        if (enabled) {
            plugin.getFoliaLib().getScheduler().runAsync(task -> load(uuid));
        }
    }

    public void setFavorites(UUID uuid, String favorites) {
        var data = cache.computeIfAbsent(uuid, k -> new PlayerData());
        data.favorites = favorites;
        save(uuid, data);
    }

    public void setFilter(UUID uuid, boolean filter) {
        var data = cache.computeIfAbsent(uuid, k -> new PlayerData());
        data.filter = filter;
        save(uuid, data);
    }

    private void save(UUID uuid, PlayerData data) {
        var favorites = data.favorites;
        var filter = data.filter;
        executor.execute(() -> {
            try (var ps = prepare("REPLACE INTO " + TABLE + "(UUID,FAVORITES,FILTER) VALUES(?,?,?);")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, favorites);
                if (filter == null) {
                    ps.setNull(3, Types.TINYINT);
                } else {
                    ps.setInt(3, filter ? 1 : 0);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not save data for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private PlayerData query(UUID uuid) throws SQLException {
        var data = new PlayerData();
        try (var ps = prepare("SELECT FAVORITES, FILTER FROM " + TABLE + " WHERE UUID = ?;")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    data.favorites = rs.getString("FAVORITES");
                    int filter = rs.getInt("FILTER");
                    data.filter = rs.wasNull() ? null : filter == 1;
                }
            }
        }
        return data;
    }

    private boolean setup() {
        try {
            return executor.submit(() -> {
                try (var ps = prepare("CREATE TABLE IF NOT EXISTS `" + TABLE + "` " +
                        "(UUID varchar(36) PRIMARY KEY, FAVORITES MEDIUMTEXT, FILTER TINYINT);")) {
                    ps.execute();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not set up the " + (mysql ? "MySQL" : "SQLite")
                            + " database, falling back to PDC storage: " + e.getMessage());
                    return false;
                }
            }).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not set up the database, falling back to PDC storage: " + e.getMessage());
            return false;
        }
    }

    /** Only call from the executor thread. */
    private PreparedStatement prepare(String sql) throws SQLException {
        if (connection == null || connection.isClosed() || (mysql && !connection.isValid(2))) {
            connection = open();
        }
        return connection.prepareStatement(sql);
    }

    private Connection open() throws SQLException {
        if (mysql) {
            var my = settings.getMysql();
            var props = new Properties();
            props.setProperty("user", my.getUser());
            props.setProperty("password", my.getPassword());
            return DriverManager.getConnection("jdbc:mysql://" + my.getHost() + ":" + my.getPort() + "/" + my.getDatabase(), props);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException(e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + new File(plugin.getDataFolder(), "database.db"));
    }

    /** Flushes pending writes and closes the connection. */
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
            if (connection != null) {
                connection.close();
            }
        } catch (Exception ignored) {
        }
        cache.clear();
    }

}
