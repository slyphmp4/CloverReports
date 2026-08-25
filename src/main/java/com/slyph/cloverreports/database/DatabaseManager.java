package com.slyph.cloverreports.database;

import com.slyph.cloverreports.CloverReports;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class DatabaseManager {

    private static final DateTimeFormatter BACKUP_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String META_LEGACY_NOTES_MIGRATED = "legacy_notes_migrated";
    private static final String META_CASES_MIGRATED = "cases_migrated_v1";
    private final CloverReports plugin;
    private final Logger logger;
    private final Settings fixedSettings;
    private final Object lifecycleLock;
    private final AtomicBoolean backupRunning;
    private volatile HikariDataSource dataSource;
    private volatile Settings activeSettings;

    public DatabaseManager(CloverReports plugin) {
        this(plugin, plugin.getLogger(), null);
    }

    private DatabaseManager(CloverReports plugin, Logger logger, Settings fixedSettings) {
        this.plugin = plugin;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.fixedSettings = fixedSettings;
        this.lifecycleLock = new Object();
        this.backupRunning = new AtomicBoolean();
    }

    public static DatabaseManager forSqlite(Path databasePath, Logger logger) {
        Path absolutePath = databasePath.toAbsolutePath().normalize();
        Settings settings = Settings.sqlite(absolutePath, absolutePath.getParent(), 5_000L);
        return new DatabaseManager(null, logger, settings);
    }

    public boolean connect() {
        synchronized (lifecycleLock) {
            if (isConnected()) {
                return true;
            }

            HikariDataSource candidate = null;
            try {
                Settings settings = fixedSettings == null ? loadSettings() : fixedSettings;
                prepareDirectories(settings);
                HikariConfig config = createPoolConfig(settings);
                candidate = new HikariDataSource(config);
                try (Connection connection = candidate.getConnection()) {
                    configureConnection(connection, settings);
                    try (Statement statement = connection.createStatement();
                         ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                        if (!resultSet.next()) {
                            throw new SQLException("Database validation returned no result");
                        }
                    }
                }
                dataSource = candidate;
                candidate = null;
                activeSettings = settings;
                logger.info(settings.mysql ? "MySQL connected." : "SQLite connected: " + settings.sqliteFile.getFileName());
                return true;
            } catch (Exception exception) {
                closeDataSource(candidate);
                closeDataSource(dataSource);
                dataSource = null;
                activeSettings = null;
                logger.severe("Database connect error: " + safeMessage(exception));
                return false;
            }
        }
    }

    public boolean createReportsTable() {
        if (!isConnected()) {
            return false;
        }

        try (Connection connection = getConnection()) {
            Settings settings = requireSettings();
            configureConnection(connection, settings);
            boolean schemaLock = settings.mysql && acquireSchemaLock(connection);
            try {
                createReportsTable(connection, settings.mysql);
                createLogsTable(connection, settings.mysql);
                createNotesTable(connection, settings.mysql);
                createMetaTable(connection, settings.mysql);
                createCasesTable(connection, settings.mysql);
                createIdentitiesTable(connection, settings.mysql);
                createNameHistoryTable(connection, settings.mysql);
                createReviewLeasesTable(connection, settings.mysql);
                migrateSchema(connection, settings.mysql);
                migrateLegacyNotes(connection);
                migrateCases(connection);
            } finally {
                if (schemaLock) {
                    releaseSchemaLock(connection);
                }
            }
            return true;
        } catch (SQLException exception) {
            logger.severe("Database schema error: " + safeMessage(exception));
            return false;
        }
    }

    private boolean acquireSchemaLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, "cloverreports_schema_v2");
            statement.setInt(2, plugin == null ? 30 : Math.max(5, plugin.getConfig().getInt("mysql.schema-lock-timeout-seconds", 120)));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new SQLException("Timed out waiting for CloverReports schema lock");
                }
                return true;
            }
        }
    }

    private void releaseSchemaLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, "cloverreports_schema_v2");
            statement.executeQuery();
        } catch (SQLException exception) {
            logger.warning("Could not release CloverReports schema lock: " + safeMessage(exception));
        }
    }

    public Connection getConnection() throws SQLException {
        HikariDataSource current = dataSource;
        if (current == null || current.isClosed()) {
            throw new SQLException("Database pool is not connected");
        }
        return current.getConnection();
    }

    public boolean isConnected() {
        HikariDataSource current = dataSource;
        return current != null && !current.isClosed();
    }

    public void disconnect() {
        synchronized (lifecycleLock) {
            HikariDataSource current = dataSource;
            dataSource = null;
            activeSettings = null;
            closeDataSource(current);
        }
    }

    public String getStorageType() {
        Settings settings = activeSettings;
        if (settings != null) {
            return settings.mysql ? "mysql" : "sqlite";
        }
        Settings configured = fixedSettings == null ? loadSettings() : fixedSettings;
        return configured.mysql ? "mysql" : "sqlite";
    }

    public boolean isSqliteStorage() {
        return getStorageType().equals("sqlite");
    }

    public BackupResult createSqliteBackup() {
        if (!isSqliteStorage()) {
            return BackupResult.unsupported(getStorageType());
        }
        if (!backupRunning.compareAndSet(false, true)) {
            return BackupResult.failure("backup is already running");
        }

        Path backupFile = null;
        try {
            Settings settings = requireSettings();
            Path backupDirectory = resolveBackupDirectory(settings);
            Files.createDirectories(backupDirectory);
            backupFile = createUniqueBackupPath(settings.sqliteFile, backupDirectory);
            String escapedPath = backupFile.toAbsolutePath().toString().replace("'", "''");
            try (Connection connection = getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escapedPath + "'");
            }
            validateSqliteBackup(backupFile);
            return BackupResult.success(backupFile.toFile());
        } catch (Exception exception) {
            if (backupFile != null) {
                try {
                    Files.deleteIfExists(backupFile);
                } catch (IOException ignored) {
                }
            }
            return BackupResult.failure(safeMessage(exception));
        } finally {
            backupRunning.set(false);
        }
    }

    private Settings loadSettings() {
        String type = normalizeStorageType(plugin.getConfig().getString("storage.type", "sqlite"));
        long connectionTimeout = Math.max(1_000L, plugin.getConfig().getLong("storage.connection-timeout-ms", 5_000L));
        if (type.equals("mysql")) {
            return Settings.mysql(
                    plugin.getConfig().getString("mysql.host", "localhost"),
                    Math.max(1, Math.min(65_535, plugin.getConfig().getInt("mysql.port", 3306))),
                    plugin.getConfig().getString("mysql.database", "cloverreports"),
                    plugin.getConfig().getString("mysql.username", "cloverreports"),
                    plugin.getConfig().getString("mysql.password", ""),
                    Math.max(2, plugin.getConfig().getInt("mysql.maximum-pool-size", 10)),
                    connectionTimeout,
                    Math.max(1_000, plugin.getConfig().getInt("mysql.socket-timeout-ms", 10_000)),
                    plugin.getConfig().getBoolean("mysql.use-ssl", true)
            );
        }

        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path databaseFile = resolveConfinedPath(dataFolder, plugin.getConfig().getString("sqlite.file", "reports.db"), "sqlite.file");
        return Settings.sqlite(databaseFile, dataFolder, connectionTimeout);
    }

    private HikariConfig createPoolConfig(Settings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("CloverReports-" + (settings.mysql ? "MySQL" : "SQLite"));
        config.setConnectionTimeout(settings.connectionTimeoutMillis);
        config.setValidationTimeout(Math.min(settings.connectionTimeoutMillis, 3_000L));
        config.setInitializationFailTimeout(settings.connectionTimeoutMillis);
        config.setAutoCommit(true);

        if (settings.mysql) {
            String sslMode = settings.useSsl ? "VERIFY_IDENTITY" : "DISABLED";
            String url = "jdbc:mysql://" + settings.host + ":" + settings.port + "/" + settings.database
                    + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                    + "&sslMode=" + sslMode
                    + "&allowPublicKeyRetrieval=" + (!settings.useSsl)
                    + "&connectTimeout=" + settings.connectionTimeoutMillis
                    + "&socketTimeout=" + settings.socketTimeoutMillis
                    + "&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&rewriteBatchedStatements=true";
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl(url);
            config.setUsername(settings.username);
            config.setPassword(settings.password);
            config.setMaximumPoolSize(settings.maximumPoolSize);
            config.setMinimumIdle(1);
            config.setIdleTimeout(600_000L);
            config.setMaxLifetime(1_800_000L);
            config.setKeepaliveTime(120_000L);
        } else {
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + settings.sqliteFile);
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(1);
            config.setConnectionTestQuery("SELECT 1");
            config.addDataSourceProperty("foreign_keys", "true");
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");
            config.addDataSourceProperty("busy_timeout", "5000");
        }
        return config;
    }

    private void prepareDirectories(Settings settings) throws IOException {
        if (settings.mysql) {
            return;
        }
        Path parent = settings.sqliteFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private void configureConnection(Connection connection, Settings settings) throws SQLException {
        if (settings.mysql) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private void createReportsTable(Connection connection, boolean mysql) throws SQLException {
        String id = mysql ? "INT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String text = mysql ? "VARCHAR(64)" : "TEXT";
        String longType = mysql ? "BIGINT" : "INTEGER";
        String sql = "CREATE TABLE IF NOT EXISTS reports ("
                + "id " + id + ","
                + "case_id " + longType + ","
                + "reporter " + text + " NOT NULL,"
                + "reporter_key " + text + " NOT NULL,"
                + "reporter_uuid " + (mysql ? "CHAR(36)" : "TEXT") + ","
                + "reporter_identity_key " + (mysql ? "VARCHAR(80)" : "TEXT") + " NOT NULL,"
                + "reported " + text + " NOT NULL,"
                + "reported_key " + text + " NOT NULL,"
                + "reported_uuid " + (mysql ? "CHAR(36)" : "TEXT") + ","
                + "reason TEXT NOT NULL,"
                + "reason_key " + text + ","
                + "evidence_url TEXT,"
                + "timestamp " + longType + " NOT NULL,"
                + "status VARCHAR(20) NOT NULL,"
                + "resolved_by " + text + ","
                + "resolved_at " + longType + ","
                + "action VARCHAR(32),"
                + "resolution_reason TEXT,"
                + "moderator_note TEXT,"
                + "moderator_note_by " + text
                + ")" + tableSuffix(mysql);
        execute(connection, sql);
    }

    private void createLogsTable(Connection connection, boolean mysql) throws SQLException {
        String id = mysql ? "INT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String text = mysql ? "VARCHAR(64)" : "TEXT";
        String longType = mysql ? "BIGINT" : "INTEGER";
        String sql = "CREATE TABLE IF NOT EXISTS report_logs ("
                + "id " + id + ","
                + "case_id " + longType + ","
                + "moderator " + text + " NOT NULL,"
                + "moderator_uuid " + (mysql ? "CHAR(36)" : "TEXT") + ","
                + "action VARCHAR(32) NOT NULL,"
                + "reported " + text + " NOT NULL,"
                + "reported_key " + text + " NOT NULL,"
                + "reported_uuid " + (mysql ? "CHAR(36)" : "TEXT") + ","
                + "reason TEXT,"
                + "note TEXT,"
                + "timestamp " + longType + " NOT NULL"
                + ")" + tableSuffix(mysql);
        execute(connection, sql);
    }

    private void createNotesTable(Connection connection, boolean mysql) throws SQLException {
        String id = mysql ? "INT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String text = mysql ? "VARCHAR(64)" : "TEXT";
        String longType = mysql ? "BIGINT" : "INTEGER";
        String sql = "CREATE TABLE IF NOT EXISTS report_notes ("
                + "id " + id + ","
                + "case_id " + longType + ","
                + "reported " + text + " NOT NULL,"
                + "reported_key " + text + " NOT NULL,"
                + "moderator " + text + " NOT NULL,"
                + "moderator_uuid " + (mysql ? "CHAR(36)" : "TEXT") + ","
                + "note TEXT NOT NULL,"
                + "timestamp " + longType + " NOT NULL,"
                + "status VARCHAR(20) NOT NULL"
                + ")" + tableSuffix(mysql);
        execute(connection, sql);
    }

    private void createMetaTable(Connection connection, boolean mysql) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS cloverreports_meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255) NOT NULL)"
                + tableSuffix(mysql);
        execute(connection, sql);
    }

    private void createCasesTable(Connection connection, boolean mysql) throws SQLException {
        String id = mysql ? "BIGINT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String text = mysql ? "VARCHAR(64)" : "TEXT";
        String identity = mysql ? "VARCHAR(80)" : "TEXT";
        String uuid = mysql ? "CHAR(36)" : "TEXT";
        String longType = mysql ? "BIGINT" : "INTEGER";
        String sql = "CREATE TABLE IF NOT EXISTS report_cases ("
                + "id " + id + ","
                + "identity_key " + identity + " NOT NULL,"
                + "active_key " + identity + ","
                + "reported_name " + text + " NOT NULL,"
                + "reported_key " + text + " NOT NULL,"
                + "reported_uuid " + uuid + ","
                + "status VARCHAR(20) NOT NULL,"
                + "created_at " + longType + " NOT NULL,"
                + "updated_at " + longType + " NOT NULL,"
                + "resolved_by " + text + ","
                + "resolved_by_uuid " + uuid + ","
                + "resolved_at " + longType + ","
                + "action VARCHAR(32),"
                + "resolution_reason TEXT,"
                + "review_owner " + text + ","
                + "review_owner_uuid " + uuid + ","
                + "review_server " + text + ","
                + "review_status VARCHAR(32),"
                + "review_expires_at " + longType
                + ")" + tableSuffix(mysql);
        execute(connection, sql);
    }

    private void createIdentitiesTable(Connection connection, boolean mysql) throws SQLException {
        String text = mysql ? "VARCHAR(64)" : "TEXT";
        String uuid = mysql ? "CHAR(36)" : "TEXT";
        String longType = mysql ? "BIGINT" : "INTEGER";
        String sql = "CREATE TABLE IF NOT EXISTS player_identities ("
                + "player_uuid " + uuid + " PRIMARY KEY,"
                + "player_name " + text + " NOT NULL,"
                + "name_key " + text + " NOT NULL,"
                + "updated_at " + longType + " NOT NULL"
                + ")" + tableSuffix(mysql);
        execute(connection, sql);
    }

    private void createReviewLeasesTable(Connection connection, boolean mysql) throws SQLException {
        String text = mysql ? "VARCHAR(64)" : "TEXT";
        String uuid = mysql ? "CHAR(36)" : "TEXT";
        String longType = mysql ? "BIGINT" : "INTEGER";
        String sql = "CREATE TABLE IF NOT EXISTS case_review_leases ("
                + "case_id " + longType + " PRIMARY KEY,"
                + "owner_uuid " + uuid + " NOT NULL,"
                + "owner_name " + text + " NOT NULL,"
                + "server_id " + text + " NOT NULL,"
                + "lease_token " + uuid + " NOT NULL,"
                + "review_status VARCHAR(32) NOT NULL,"
                + "expires_at " + longType + " NOT NULL,"
                + "updated_at " + longType + " NOT NULL"
                + ")" + tableSuffix(mysql);
        execute(connection, sql);
    }

    private void createNameHistoryTable(Connection connection, boolean mysql) throws SQLException {
        String text = mysql ? "VARCHAR(64)" : "TEXT";
        String uuid = mysql ? "CHAR(36)" : "TEXT";
        String longType = mysql ? "BIGINT" : "INTEGER";
        String sql = "CREATE TABLE IF NOT EXISTS player_name_history ("
                + "player_uuid " + uuid + " NOT NULL,"
                + "name_key " + text + " NOT NULL,"
                + "display_name " + text + " NOT NULL,"
                + "first_seen_at " + longType + " NOT NULL,"
                + "last_seen_at " + longType + " NOT NULL,"
                + "PRIMARY KEY (player_uuid, name_key)"
                + ")" + tableSuffix(mysql);
        execute(connection, sql);
    }

    private void migrateSchema(Connection connection, boolean mysql) throws SQLException {
        String text = mysql ? "VARCHAR(64)" : "TEXT";
        String uuid = mysql ? "CHAR(36)" : "TEXT";
        String longType = mysql ? "BIGINT" : "INTEGER";
        addColumnIfMissing(connection, "reports", "case_id", longType);
        addColumnIfMissing(connection, "reports", "reporter_key", text);
        addColumnIfMissing(connection, "reports", "reported_key", text);
        addColumnIfMissing(connection, "reports", "reporter_uuid", uuid);
        addColumnIfMissing(connection, "reports", "reporter_identity_key", mysql ? "VARCHAR(80)" : "TEXT");
        addColumnIfMissing(connection, "reports", "reported_uuid", uuid);
        addColumnIfMissing(connection, "reports", "reason_key", text);
        addColumnIfMissing(connection, "reports", "evidence_url", "TEXT");
        addColumnIfMissing(connection, "reports", "resolved_by", text);
        addColumnIfMissing(connection, "reports", "resolved_at", longType);
        addColumnIfMissing(connection, "reports", "action", "VARCHAR(32)");
        addColumnIfMissing(connection, "reports", "resolution_reason", "TEXT");
        addColumnIfMissing(connection, "reports", "moderator_note", "TEXT");
        addColumnIfMissing(connection, "reports", "moderator_note_by", text);
        addColumnIfMissing(connection, "report_logs", "case_id", longType);
        addColumnIfMissing(connection, "report_logs", "reported_key", text);
        addColumnIfMissing(connection, "report_logs", "moderator_uuid", uuid);
        addColumnIfMissing(connection, "report_logs", "reported_uuid", uuid);
        addColumnIfMissing(connection, "report_notes", "case_id", longType);
        addColumnIfMissing(connection, "report_notes", "reported_key", text);
        addColumnIfMissing(connection, "report_notes", "moderator_uuid", uuid);

        execute(connection, "UPDATE reports SET reporter_key = LOWER(reporter) WHERE reporter_key IS NULL OR reporter_key = ''");
        execute(connection, "UPDATE reports SET reported_key = LOWER(reported) WHERE reported_key IS NULL OR reported_key = ''");
        execute(connection, "UPDATE reports SET reason_key = LOWER(reason) WHERE reason_key IS NULL OR reason_key = ''");
        execute(connection, mysql
                ? "UPDATE reports SET reporter_identity_key = CONCAT('n:', reporter_key) WHERE (reporter_identity_key IS NULL OR reporter_identity_key = '') AND reporter_key IS NOT NULL"
                : "UPDATE reports SET reporter_identity_key = 'n:' || reporter_key WHERE (reporter_identity_key IS NULL OR reporter_identity_key = '') AND reporter_key IS NOT NULL");
        execute(connection, "UPDATE report_logs SET reported_key = LOWER(reported) WHERE reported_key IS NULL OR reported_key = ''");
        execute(connection, "UPDATE report_notes SET reported_key = LOWER(reported) WHERE reported_key IS NULL OR reported_key = ''");

        createIndex(connection, "reports", "idx_reports_status_reported_key_time", "CREATE INDEX idx_reports_status_reported_key_time ON reports (status, reported_key, timestamp)");
        createIndex(connection, "reports", "idx_reports_case_time", "CREATE INDEX idx_reports_case_time ON reports (case_id, timestamp)");
        createIndex(connection, "reports", "idx_reports_case_reason", "CREATE INDEX idx_reports_case_reason ON reports (case_id, reason_key)");
        createIndex(connection, "reports", "idx_reports_reporter_uuid_status", "CREATE INDEX idx_reports_reporter_uuid_status ON reports (reporter_uuid, status)");
        createIndex(connection, "reports", "idx_reports_reported_uuid_status", "CREATE INDEX idx_reports_reported_uuid_status ON reports (reported_uuid, status)");
        createIndex(connection, "reports", "idx_reports_reporter_target_status", "CREATE INDEX idx_reports_reporter_target_status ON reports (reporter_key, reported_key, status)");
        createIndex(connection, "reports", "idx_reports_reporter_status_action", "CREATE INDEX idx_reports_reporter_status_action ON reports (reporter_key, status, action)");
        createIndex(connection, "reports", "idx_reports_status_resolved_at", "CREATE INDEX idx_reports_status_resolved_at ON reports (status, resolved_at)");
        createIndex(connection, "report_logs", "idx_report_logs_target_time", "CREATE INDEX idx_report_logs_target_time ON report_logs (reported_key, timestamp)");
        createIndex(connection, "report_logs", "idx_report_logs_case_time", "CREATE INDEX idx_report_logs_case_time ON report_logs (case_id, timestamp)");
        createIndex(connection, "report_logs", "idx_report_logs_moderator_uuid_time", "CREATE INDEX idx_report_logs_moderator_uuid_time ON report_logs (moderator_uuid, timestamp)");
        createIndex(connection, "report_logs", "idx_report_logs_action_time", "CREATE INDEX idx_report_logs_action_time ON report_logs (action, timestamp)");
        createIndex(connection, "report_logs", "idx_report_logs_time_id", "CREATE INDEX idx_report_logs_time_id ON report_logs (timestamp, id)");
        createIndex(connection, "report_notes", "idx_report_notes_target_status_time", "CREATE INDEX idx_report_notes_target_status_time ON report_notes (reported_key, status, timestamp)");
        createIndex(connection, "report_notes", "idx_report_notes_case_time", "CREATE INDEX idx_report_notes_case_time ON report_notes (case_id, timestamp)");
        createIndex(connection, "report_cases", "idx_cases_status_updated", "CREATE INDEX idx_cases_status_updated ON report_cases (status, updated_at)");
        createIndex(connection, "report_cases", "idx_cases_status_resolved", "CREATE INDEX idx_cases_status_resolved ON report_cases (status, resolved_at, id)");
        createIndex(connection, "report_cases", "idx_cases_identity_status", "CREATE INDEX idx_cases_identity_status ON report_cases (identity_key, status)");
        createIndex(connection, "report_cases", "idx_cases_reported_uuid_status", "CREATE INDEX idx_cases_reported_uuid_status ON report_cases (reported_uuid, status)");
        createIndex(connection, "report_cases", "idx_cases_resolver_status_time", "CREATE INDEX idx_cases_resolver_status_time ON report_cases (resolved_by_uuid, status, resolved_at)");
        createIndex(connection, "report_cases", "idx_cases_action_status_time", "CREATE INDEX idx_cases_action_status_time ON report_cases (action, status, resolved_at)");
        createIndex(connection, "player_identities", "idx_identities_name_key", "CREATE INDEX idx_identities_name_key ON player_identities (name_key)");
        createIndex(connection, "player_name_history", "idx_name_history_lookup", "CREATE INDEX idx_name_history_lookup ON player_name_history (name_key, last_seen_at)");
        createIndex(connection, "case_review_leases", "idx_review_leases_expiry", "CREATE INDEX idx_review_leases_expiry ON case_review_leases (expires_at)");
    }

    private void migrateLegacyNotes(Connection connection) throws SQLException {
        if ("true".equalsIgnoreCase(getMeta(connection, META_LEGACY_NOTES_MIGRATED))) {
            return;
        }

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            String select = "SELECT reported, reported_key, COALESCE(moderator_note_by, '-') AS moderator, moderator_note, MIN(timestamp) AS note_time, status "
                    + "FROM reports WHERE moderator_note IS NOT NULL AND TRIM(moderator_note) <> '' "
                    + "GROUP BY reported, reported_key, moderator_note_by, moderator_note, status";
            try (PreparedStatement statement = connection.prepareStatement(select);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    insertLegacyNoteIfMissing(connection, resultSet);
                }
            }
            setMeta(connection, META_LEGACY_NOTES_MIGRATED, "true");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void migrateCases(Connection connection) throws SQLException {
        if ("true".equalsIgnoreCase(getMeta(connection, META_CASES_MIGRATED))) {
            createIndex(connection, "report_cases", "uq_cases_active_key", "CREATE UNIQUE INDEX uq_cases_active_key ON report_cases (active_key)");
            createIndex(connection, "reports", "uq_reports_case_reporter", "CREATE UNIQUE INDEX uq_reports_case_reporter ON reports (case_id, reporter_identity_key)");
            return;
        }

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            execute(connection, "UPDATE report_notes SET status = 'PENDING' WHERE status <> 'RESOLVED'");
            Map<String, LegacyCaseGroup> groups = loadLegacyCaseGroups(connection);
            for (LegacyCaseGroup group : groups.values()) {
                long caseId = insertLegacyCase(connection, group);
                try (PreparedStatement updateReport = connection.prepareStatement(
                        "UPDATE reports SET case_id = ?, status = ? WHERE id = ?")) {
                    for (int reportId : group.reportIds) {
                        updateReport.setLong(1, caseId);
                        updateReport.setString(2, group.status);
                        updateReport.setInt(3, reportId);
                        updateReport.addBatch();
                    }
                    updateReport.executeBatch();
                }
                if (group.status.equals("PENDING")) {
                    try (PreparedStatement updateNotes = connection.prepareStatement(
                            "UPDATE report_notes SET case_id = ? WHERE case_id IS NULL AND reported_key = ? AND status = ?")) {
                        for (String reportedKey : group.reportedKeys) {
                            updateNotes.setLong(1, caseId);
                            updateNotes.setString(2, reportedKey);
                            updateNotes.setString(3, "PENDING");
                            updateNotes.addBatch();
                        }
                        updateNotes.executeBatch();
                    }
                }
            }
            execute(connection, "UPDATE reports SET resolved_by = NULL, resolved_at = NULL, action = NULL, resolution_reason = NULL WHERE status = 'PENDING'");
            deduplicateLegacyReporterKeys(connection);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
        createIndex(connection, "report_cases", "uq_cases_active_key", "CREATE UNIQUE INDEX uq_cases_active_key ON report_cases (active_key)");
        createIndex(connection, "reports", "uq_reports_case_reporter", "CREATE UNIQUE INDEX uq_reports_case_reporter ON reports (case_id, reporter_identity_key)");
        setMeta(connection, META_CASES_MIGRATED, "true");
    }

    private void deduplicateLegacyReporterKeys(Connection connection) throws SQLException {
        String sql = "SELECT case_id, reporter_identity_key FROM reports WHERE case_id IS NOT NULL AND reporter_identity_key IS NOT NULL "
                + "GROUP BY case_id, reporter_identity_key HAVING COUNT(*) > 1";
        try (PreparedStatement groups = connection.prepareStatement(sql);
             ResultSet resultSet = groups.executeQuery()) {
            while (resultSet.next()) {
                long caseId = resultSet.getLong("case_id");
                String identityKey = resultSet.getString("reporter_identity_key");
                try (PreparedStatement duplicates = connection.prepareStatement(
                        "SELECT id FROM reports WHERE case_id = ? AND reporter_identity_key = ? ORDER BY id ASC")) {
                    duplicates.setLong(1, caseId);
                    duplicates.setString(2, identityKey);
                    try (ResultSet ids = duplicates.executeQuery()) {
                        boolean first = true;
                        while (ids.next()) {
                            if (first) {
                                first = false;
                                continue;
                            }
                            int id = ids.getInt(1);
                            try (PreparedStatement update = connection.prepareStatement(
                                    "UPDATE reports SET reporter_identity_key = ? WHERE id = ?")) {
                                update.setString(1, identityKey + ":legacy:" + id);
                                update.setInt(2, id);
                                update.executeUpdate();
                            }
                        }
                    }
                }
            }
        }
    }

    private Map<String, LegacyCaseGroup> loadLegacyCaseGroups(Connection connection) throws SQLException {
        Map<String, LegacyCaseGroup> groups = new LinkedHashMap<>();
        String sql = "SELECT id, reported, reported_key, reported_uuid, status, timestamp, resolved_by, resolved_at, action, resolution_reason "
                + "FROM reports WHERE case_id IS NULL ORDER BY timestamp ASC, id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String sourceStatus = resultSet.getString("status");
                String status = "RESOLVED".equalsIgnoreCase(sourceStatus) ? "RESOLVED" : "PENDING";
                String reportedKey = resultSet.getString("reported_key");
                String reportedUuid = resultSetValue(resultSet, "reported_uuid");
                String targetIdentity = reportedUuid == null || reportedUuid.isBlank()
                        ? "n:" + reportedKey
                        : "u:" + reportedUuid.toLowerCase(Locale.ROOT);
                long timestamp = resultSet.getLong("timestamp");
                long resolvedAt = resultSet.getLong("resolved_at");
                String groupKey = status.equals("PENDING")
                        ? "P|" + targetIdentity
                        : "R|" + targetIdentity + "|" + resolvedAt + "|" + safe(resultSet.getString("resolved_by"))
                        + "|" + safe(resultSet.getString("action")) + "|" + safe(resultSet.getString("resolution_reason"));
                LegacyCaseGroup group = groups.computeIfAbsent(groupKey, ignored -> new LegacyCaseGroup(
                        resultSetValue(resultSet, "reported"),
                        reportedKey,
                        reportedUuid,
                        status,
                        timestamp,
                        status.equals("RESOLVED") ? resultSetValue(resultSet, "resolved_by") : null,
                        status.equals("RESOLVED") ? resolvedAt : 0L,
                        status.equals("RESOLVED") ? resultSetValue(resultSet, "action") : null,
                        status.equals("RESOLVED") ? resultSetValue(resultSet, "resolution_reason") : null
                ));
                group.reportIds.add(resultSet.getInt("id"));
                group.reportedKeys.add(reportedKey);
                group.createdAt = Math.min(group.createdAt, timestamp);
                group.updatedAt = Math.max(group.updatedAt, status.equals("RESOLVED") && resolvedAt > 0L ? resolvedAt : timestamp);
            }
        }
        return groups;
    }

    private long insertLegacyCase(Connection connection, LegacyCaseGroup group) throws SQLException {
        String identityKey = group.reportedUuid == null || group.reportedUuid.isBlank()
                ? "n:" + group.reportedKey
                : "u:" + group.reportedUuid.toLowerCase(Locale.ROOT);
        String sql = "INSERT INTO report_cases (identity_key, active_key, reported_name, reported_key, reported_uuid, status, created_at, updated_at, resolved_by, resolved_at, action, resolution_reason) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, identityKey);
            statement.setString(2, group.status.equals("PENDING") ? identityKey : null);
            statement.setString(3, group.reportedName);
            statement.setString(4, group.reportedKey);
            statement.setString(5, group.reportedUuid);
            statement.setString(6, group.status);
            statement.setLong(7, group.createdAt);
            statement.setLong(8, group.updatedAt);
            statement.setString(9, group.resolvedBy);
            if (group.resolvedAt > 0L) {
                statement.setLong(10, group.resolvedAt);
            } else {
                statement.setNull(10, java.sql.Types.BIGINT);
            }
            statement.setString(11, group.action);
            statement.setString(12, group.resolutionReason);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Case migration did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    private String resultSetValue(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void insertLegacyNoteIfMissing(Connection connection, ResultSet source) throws SQLException {
        String reportedKey = source.getString("reported_key");
        String moderator = source.getString("moderator");
        String note = source.getString("moderator_note");
        String status = source.getString("status");
        String existsSql = "SELECT 1 FROM report_notes WHERE reported_key = ? AND moderator = ? AND note = ? AND status = ?";
        try (PreparedStatement exists = connection.prepareStatement(existsSql)) {
            exists.setString(1, reportedKey);
            exists.setString(2, moderator);
            exists.setString(3, note);
            exists.setString(4, status);
            try (ResultSet resultSet = exists.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
        }

        String insertSql = "INSERT INTO report_notes (reported, reported_key, moderator, note, timestamp, status) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            insert.setString(1, source.getString("reported"));
            insert.setString(2, reportedKey);
            insert.setString(3, moderator);
            insert.setString(4, note);
            insert.setLong(5, source.getLong("note_time"));
            insert.setString(6, status);
            insert.executeUpdate();
        }
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition) throws SQLException {
        if (hasColumn(connection, table, column)) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            int columns = resultSet.getMetaData().getColumnCount();
            for (int index = 1; index <= columns; index++) {
                if (resultSet.getMetaData().getColumnName(index).equalsIgnoreCase(column)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void createIndex(Connection connection, String table, String index, String sql) throws SQLException {
        if (hasIndex(connection, table, index)) {
            return;
        }
        execute(connection, sql);
    }

    private boolean hasIndex(Connection connection, String table, String index) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getIndexInfo(null, null, candidate, false, false)) {
                while (resultSet.next()) {
                    String existing = resultSet.getString("INDEX_NAME");
                    if (existing != null && existing.equalsIgnoreCase(index)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String getMeta(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT meta_value FROM cloverreports_meta WHERE meta_key = ?")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private void setMeta(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE cloverreports_meta SET meta_value = ? WHERE meta_key = ?")) {
            update.setString(1, value);
            update.setString(2, key);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO cloverreports_meta (meta_key, meta_value) VALUES (?, ?)")) {
            insert.setString(1, key);
            insert.setString(2, value);
            insert.executeUpdate();
        }
    }

    private Path resolveBackupDirectory(Settings settings) throws IOException {
        Path root = settings.dataFolder.toAbsolutePath().normalize();
        String configuredFolder = plugin == null ? "backups" : plugin.getConfig().getString("backup.folder", "backups");
        try {
            return resolveConfinedPath(root, configuredFolder, "backup.folder");
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    private Path createUniqueBackupPath(Path databaseFile, Path directory) {
        String fileName = databaseFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : ".db";
        String timestamp = BACKUP_TIME_FORMATTER.format(LocalDateTime.now());
        Path candidate = directory.resolve(base + "-" + timestamp + extension);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(base + "-" + timestamp + "-" + suffix + extension);
            suffix++;
        }
        return candidate;
    }

    private void validateSqliteBackup(Path backupFile) throws SQLException {
        try (Connection validation = DriverManager.getConnection("jdbc:sqlite:" + backupFile);
             Statement statement = validation.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
            if (!resultSet.next() || !"ok".equalsIgnoreCase(resultSet.getString(1))) {
                throw new SQLException("backup integrity check failed");
            }
        }
    }

    private Settings requireSettings() throws SQLException {
        Settings settings = activeSettings;
        if (settings == null) {
            throw new SQLException("Database settings are unavailable");
        }
        return settings;
    }

    static Path resolveConfinedPath(Path dataFolder, String configuredValue, String settingName) {
        Path root = Objects.requireNonNull(dataFolder, "dataFolder").toAbsolutePath().normalize();
        String configured = configuredValue == null ? "" : configuredValue.trim();
        if (configured.isEmpty()) {
            throw new IllegalArgumentException(settingName + " must not be empty");
        }
        Path relative = Path.of(configured);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException(settingName + " must be relative to the plugin data folder");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException(settingName + " must stay inside the plugin data folder");
        }
        Path cursor = root;
        for (Path segment : root.relativize(target)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException(settingName + " must not traverse symbolic links");
            }
        }
        return target;
    }

    private String normalizeStorageType(String configuredType) {
        String type = configuredType == null ? "sqlite" : configuredType.trim().toLowerCase(Locale.ROOT);
        if (type.equals("mysql")) {
            return "mysql";
        }
        if (!type.equals("sqlite") && !type.equals("local")) {
            logger.warning("Unknown storage type '" + configuredType + "', SQLite selected.");
        }
        return "sqlite";
    }

    private String tableSuffix(boolean mysql) {
        return mysql ? " ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : "";
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void closeDataSource(HikariDataSource source) {
        if (source != null && !source.isClosed()) {
            source.close();
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public static final class BackupResult {

        private final boolean success;
        private final boolean supported;
        private final String storageType;
        private final File backupFile;
        private final String error;

        private BackupResult(boolean success, boolean supported, String storageType, File backupFile, String error) {
            this.success = success;
            this.supported = supported;
            this.storageType = storageType;
            this.backupFile = backupFile;
            this.error = error;
        }

        public static BackupResult success(File backupFile) {
            return new BackupResult(true, true, "sqlite", backupFile, null);
        }

        public static BackupResult unsupported(String storageType) {
            return new BackupResult(false, false, storageType, null, null);
        }

        public static BackupResult failure(String error) {
            return new BackupResult(false, true, "sqlite", null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isSupported() {
            return supported;
        }

        public String getStorageType() {
            return storageType;
        }

        public File getBackupFile() {
            return backupFile;
        }

        public String getError() {
            return error;
        }
    }

    private static final class LegacyCaseGroup {

        private final String reportedName;
        private final String reportedKey;
        private final String reportedUuid;
        private final String status;
        private final String resolvedBy;
        private final long resolvedAt;
        private final String action;
        private final String resolutionReason;
        private final List<Integer> reportIds;
        private final Set<String> reportedKeys;
        private long createdAt;
        private long updatedAt;

        private LegacyCaseGroup(String reportedName, String reportedKey, String reportedUuid, String status, long timestamp, String resolvedBy, long resolvedAt, String action, String resolutionReason) {
            this.reportedName = reportedName;
            this.reportedKey = reportedKey;
            this.reportedUuid = reportedUuid;
            this.status = status;
            this.resolvedBy = resolvedBy;
            this.resolvedAt = resolvedAt;
            this.action = action;
            this.resolutionReason = resolutionReason;
            this.reportIds = new ArrayList<>();
            this.reportedKeys = new LinkedHashSet<>();
            this.reportedKeys.add(reportedKey);
            this.createdAt = timestamp;
            this.updatedAt = resolvedAt > 0L ? resolvedAt : timestamp;
        }
    }

    private static final class Settings {

        private final boolean mysql;
        private final Path sqliteFile;
        private final Path dataFolder;
        private final String host;
        private final int port;
        private final String database;
        private final String username;
        private final String password;
        private final int maximumPoolSize;
        private final long connectionTimeoutMillis;
        private final int socketTimeoutMillis;
        private final boolean useSsl;

        private Settings(boolean mysql, Path sqliteFile, Path dataFolder, String host, int port, String database, String username, String password, int maximumPoolSize, long connectionTimeoutMillis, int socketTimeoutMillis, boolean useSsl) {
            this.mysql = mysql;
            this.sqliteFile = sqliteFile;
            this.dataFolder = dataFolder;
            this.host = host;
            this.port = port;
            this.database = database;
            this.username = username;
            this.password = password;
            this.maximumPoolSize = maximumPoolSize;
            this.connectionTimeoutMillis = connectionTimeoutMillis;
            this.socketTimeoutMillis = socketTimeoutMillis;
            this.useSsl = useSsl;
        }

        private static Settings sqlite(Path sqliteFile, Path dataFolder, long connectionTimeoutMillis) {
            return new Settings(false, sqliteFile, dataFolder, null, 0, null, null, null, 1, connectionTimeoutMillis, 0, false);
        }

        private static Settings mysql(String host, int port, String database, String username, String password, int maximumPoolSize, long connectionTimeoutMillis, int socketTimeoutMillis, boolean useSsl) {
            return new Settings(true, null, null, host, port, database, username, password, maximumPoolSize, connectionTimeoutMillis, socketTimeoutMillis, useSsl);
        }
    }
}
