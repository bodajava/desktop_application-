package com.examhalls.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Thread-safe, lazily initialised singleton that owns the application's HikariCP connection pool.
 *
 * <h2>Configuration resolution (later sources override earlier ones)</h2>
 * <ol>
 *   <li>{@code application.properties} on the classpath (bundled defaults)</li>
 *   <li>An external file: {@code -Dapp.config=/path/file.properties}, or {@code ./config/application.properties}</li>
 *   <li>Environment variables: {@code DB_URL}, {@code DB_USERNAME}, {@code DB_PASSWORD}</li>
 *   <li>JVM system properties with the same keys, e.g. {@code -Ddb.password=...}</li>
 * </ol>
 * Keep real credentials out of the bundled file; supply them through (2), (3) or (4).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (Connection con = DatabaseConnection.getInstance().getConnection();
 *      PreparedStatement ps = con.prepareStatement("SELECT ...")) {
 *     ...
 * }
 * }</pre>
 * Always close connections (try-with-resources) — closing returns them to the pool.
 */
public final class DatabaseConnection {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnection.class);

    private static final String CLASSPATH_CONFIG = "/application.properties";
    private static final Path DEFAULT_EXTERNAL_CONFIG = Path.of("config", "application.properties");

    /**
     * volatile + double-checked locking instead of the holder idiom: if the first initialisation
     * fails (e.g. the DB is down at start-up) a later call can retry, whereas a failed static holder
     * would be permanently broken with NoClassDefFoundError.
     */
    private static volatile DatabaseConnection instance;

    private final HikariDataSource dataSource;

    private DatabaseConnection(Properties props) {
        this.dataSource = new HikariDataSource(buildHikariConfig(props));
        log.info("Connection pool '{}' started -> {}", dataSource.getPoolName(), dataSource.getJdbcUrl());
    }

    /**
     * Returns the singleton, creating the pool on first call.
     *
     * @throws DatabaseInitializationException if configuration is invalid or the pool cannot start
     */
    public static DatabaseConnection getInstance() {
        DatabaseConnection local = instance;
        if (local == null) {
            synchronized (DatabaseConnection.class) {
                local = instance;
                if (local == null) {
                    try {
                        local = new DatabaseConnection(loadProperties());
                    } catch (RuntimeException e) {
                        throw new DatabaseInitializationException("Unable to initialise the database connection pool", e);
                    }
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Borrows a connection from the pool. Caller must close it. */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Exposes the pool as a standard {@link DataSource} (useful for DAOs / tests). */
    public DataSource getDataSource() {
        return dataSource;
    }

    /** Lightweight liveness check — uses JDBC4 {@code isValid}, which ojdbc11 implements with a ping. */
    public boolean isHealthy() {
        try (Connection con = getConnection()) {
            return con.isValid(3);
        } catch (SQLException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Human-readable snapshot of pool usage, handy for a status bar or diagnostics. */
    public String poolStats() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        if (pool == null) {
            return "pool not started";
        }
        return String.format("active=%d idle=%d total=%d waiting=%d",
                pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getTotalConnections(), pool.getThreadsAwaitingConnection());
    }

    /** Closes the pool. Safe to call multiple times or when the pool was never created. */
    public static void shutdown() {
        synchronized (DatabaseConnection.class) {
            if (instance != null) {
                instance.dataSource.close();
                log.info("Connection pool closed");
                instance = null;
            }
        }
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    // ------------------------------------------------------------------ configuration

    private static HikariConfig buildHikariConfig(Properties p) {
        HikariConfig cfg = new HikariConfig();

        cfg.setJdbcUrl(required(p, "db.url"));
        cfg.setUsername(required(p, "db.username"));
        cfg.setPassword(p.getProperty("db.password", ""));
        cfg.setDriverClassName(p.getProperty("db.driver", "oracle.jdbc.OracleDriver"));

        cfg.setPoolName(p.getProperty("db.pool.name", "ExamHallsPool"));
        cfg.setMaximumPoolSize(intProp(p, "db.pool.maximumPoolSize", 10));
        cfg.setMinimumIdle(intProp(p, "db.pool.minimumIdle", 2));
        cfg.setConnectionTimeout(longProp(p, "db.pool.connectionTimeoutMs", 5_000));
        cfg.setIdleTimeout(longProp(p, "db.pool.idleTimeoutMs", 600_000));
        cfg.setMaxLifetime(longProp(p, "db.pool.maxLifetimeMs", 1_800_000));
        cfg.setValidationTimeout(longProp(p, "db.pool.validationTimeoutMs", 5_000));
        cfg.setLeakDetectionThreshold(longProp(p, "db.pool.leakDetectionThresholdMs", 0));
        cfg.setAutoCommit(Boolean.parseBoolean(p.getProperty("db.pool.autoCommit", "true")));
        // 1 = the pool makes ONE login attempt when it is created and fails fast. getInstance() then
        // throws and the next call retries, so the app still starts without a database — but a wrong
        // password is not retried in the background (Oracle's default profile locks an account after
        // 10 failed logins, which would lock EXAM_ADMIN during a demo).
        cfg.setInitializationFailTimeout(longProp(p, "db.pool.initializationFailTimeoutMs", 1));

        // Oracle driver tuning (passed straight to ojdbc)
        cfg.addDataSourceProperty("oracle.jdbc.implicitStatementCacheSize",
                p.getProperty("db.oracle.statementCacheSize", "50"));
        cfg.addDataSourceProperty("oracle.jdbc.defaultRowPrefetch",
                p.getProperty("db.oracle.defaultRowPrefetch", "50"));
        cfg.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT",
                p.getProperty("db.oracle.connectTimeoutMs", "4000"));
        cfg.addDataSourceProperty("v$session.program", "ExamHallsApp");

        return cfg;
    }

    /** Merged configuration (classpath defaults, external file, env, -D); also used by {@link AppSettings}. */
    public static Properties loadProperties() {
        Properties props = new Properties();

        try (InputStream in = DatabaseConnection.class.getResourceAsStream(CLASSPATH_CONFIG)) {
            if (in == null) {
                throw new IllegalStateException(CLASSPATH_CONFIG + " not found on classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + CLASSPATH_CONFIG, e);
        }

        String explicit = System.getProperty("app.config");
        Path external = explicit != null ? Path.of(explicit) : DEFAULT_EXTERNAL_CONFIG;
        if (Files.isRegularFile(external)) {
            try (InputStream in = Files.newInputStream(external)) {
                props.load(in);
                log.info("Loaded external configuration from {}", external.toAbsolutePath());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + external, e);
            }
        } else if (explicit != null) {
            throw new IllegalStateException("-Dapp.config points to a missing file: " + external);
        }

        overrideFromEnv(props, "db.url", "DB_URL");
        overrideFromEnv(props, "db.username", "DB_USERNAME");
        overrideFromEnv(props, "db.password", "DB_PASSWORD");

        for (String key : props.stringPropertyNames()) {
            String sys = System.getProperty(key);
            if (sys != null) {
                props.setProperty(key, sys);
            }
        }
        return props;
    }

    private static void overrideFromEnv(Properties props, String key, String envVar) {
        String value = System.getenv(envVar);
        if (value != null && !value.isBlank()) {
            props.setProperty(key, value);
        }
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required configuration property: " + key);
        }
        return v.trim();
    }

    private static int intProp(Properties p, String key, int def) {
        return (int) longProp(p, key, def);
    }

    private static long longProp(Properties p, String key, long def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Property '" + key + "' must be numeric but was: " + v, e);
        }
    }

    /** Unchecked wrapper so callers (e.g. JavaFX Tasks) can surface a clear message. */
    public static final class DatabaseInitializationException extends RuntimeException {
        public DatabaseInitializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
