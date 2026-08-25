package com.slyph.cloverreports.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CaseSchemaMigrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesLegacyReportsIntoExactIdempotentCases() throws Exception {
        Path databasePath = temporaryDirectory.resolve("legacy.db");
        createLegacyDatabase(databasePath);
        DatabaseManager databaseManager = DatabaseManager.forSqlite(databasePath, Logger.getLogger("CloverReportsMigrationTest"));
        try {
            assertTrue(databaseManager.connect());
            assertTrue(databaseManager.createReportsTable());

            try (Connection connection = databaseManager.getConnection()) {
                assertEquals(3, scalar(connection, "SELECT COUNT(*) FROM report_cases"));
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM reports WHERE case_id IS NULL"));
                assertEquals(1, scalar(connection, "SELECT COUNT(DISTINCT case_id) FROM reports WHERE status = 'PENDING'"));
                assertEquals(2, scalar(connection, "SELECT COUNT(DISTINCT case_id) FROM reports WHERE status = 'RESOLVED'"));
                assertEquals(3, scalar(connection, "SELECT COUNT(DISTINCT reporter_identity_key) FROM reports WHERE status = 'PENDING'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM report_notes WHERE case_id IS NOT NULL"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM report_logs WHERE case_id IS NULL"));
                assertEquals("true", text(connection, "SELECT meta_value FROM cloverreports_meta WHERE meta_key = 'cases_migrated_v1'"));

                long pendingCase = longValue(connection, "SELECT case_id FROM reports WHERE status = 'PENDING' LIMIT 1");
                long noteCase = longValue(connection, "SELECT case_id FROM report_notes WHERE case_id IS NOT NULL LIMIT 1");
                long firstResolutionCase = longValue(connection, "SELECT case_id FROM reports WHERE id = 4");
                long matchingResolutionCase = longValue(connection, "SELECT case_id FROM reports WHERE id = 5");
                long repeatedResolutionCase = longValue(connection, "SELECT case_id FROM reports WHERE id = 6");
                assertEquals(pendingCase, noteCase);
                assertEquals(firstResolutionCase, matchingResolutionCase);
                assertNotEquals(firstResolutionCase, repeatedResolutionCase);

                assertTrue(indexExists(connection, "uq_cases_active_key"));
                assertTrue(indexExists(connection, "uq_reports_case_reporter"));
                assertThrows(SQLException.class, () -> insertDuplicateActiveCase(connection, pendingCase));
                assertThrows(SQLException.class, () -> createDuplicateReporterIdentity(connection));
            }

            assertTrue(databaseManager.createReportsTable());
            try (Connection connection = databaseManager.getConnection()) {
                assertEquals(3, scalar(connection, "SELECT COUNT(*) FROM report_cases"));
                assertEquals(6, scalar(connection, "SELECT COUNT(*) FROM reports"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM report_notes"));
            }
        } finally {
            databaseManager.disconnect();
        }
    }

    @Test
    void mergesRenamedUuidRowsIntoOneOpenCase() throws Exception {
        Path databasePath = temporaryDirectory.resolve("uuid-rename.db");
        String targetUuid = "671a53c8-23c1-4186-9561-12f760b7d4c2";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, reporter TEXT NOT NULL, reported TEXT NOT NULL, reported_uuid TEXT, reason TEXT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO reports (reporter, reported, reported_uuid, reason, timestamp, status) VALUES ('First', 'TargetOld', '" + targetUuid + "', 'Cheats', 100, 'PENDING')");
            statement.executeUpdate("INSERT INTO reports (reporter, reported, reported_uuid, reason, timestamp, status) VALUES ('Second', 'TargetNew', '" + targetUuid + "', 'Spam', 200, 'PENDING')");
        }

        DatabaseManager databaseManager = DatabaseManager.forSqlite(databasePath, Logger.getLogger("CloverReportsUuidMigrationTest"));
        try {
            assertTrue(databaseManager.connect());
            assertTrue(databaseManager.createReportsTable());
            try (Connection connection = databaseManager.getConnection()) {
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM report_cases WHERE status = 'PENDING'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(DISTINCT case_id) FROM reports"));
                assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM reports WHERE case_id IS NOT NULL"));
                assertEquals(targetUuid, text(connection, "SELECT reported_uuid FROM report_cases LIMIT 1"));
            }
        } finally {
            databaseManager.disconnect();
        }
    }

    private void createLegacyDatabase(Path databasePath) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, reporter TEXT NOT NULL, reported TEXT NOT NULL, reason TEXT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL, resolved_by TEXT, resolved_at INTEGER, action TEXT, resolution_reason TEXT, moderator_note TEXT, moderator_note_by TEXT)");
            statement.executeUpdate("CREATE TABLE report_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, moderator TEXT NOT NULL, action TEXT NOT NULL, reported TEXT NOT NULL, reason TEXT, note TEXT, timestamp INTEGER NOT NULL)");
        }

        String sql = "INSERT INTO reports (reporter, reported, reason, timestamp, status, resolved_by, resolved_at, action, resolution_reason, moderator_note, moderator_note_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            addLegacyReport(statement, "Alice", "Target", "Cheats", 100L, "PENDING", null, 0L, null, null, "Checked", "Moderator");
            addLegacyReport(statement, "Alice", "target", "Spam", 200L, "PROCESSING", null, 0L, null, null, null, null);
            addLegacyReport(statement, "Bob", "TARGET", "Other", 300L, "PENDING", null, 0L, null, null, null, null);
            addLegacyReport(statement, "Alice", "Target", "Cheats", 400L, "RESOLVED", "Moderator", 1_000L, "closed", "No evidence", null, null);
            addLegacyReport(statement, "Bob", "target", "Spam", 500L, "RESOLVED", "Moderator", 1_000L, "closed", "No evidence", null, null);
            addLegacyReport(statement, "Charlie", "Target", "Cheats", 600L, "RESOLVED", "Moderator", 2_000L, "punished", "Confirmed", null, null);
            statement.executeBatch();
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO report_logs (moderator, action, reported, reason, note, timestamp) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, "Moderator");
            statement.setString(2, "close");
            statement.setString(3, "Target");
            statement.setString(4, "No evidence");
            statement.setString(5, null);
            statement.setLong(6, 1_000L);
            statement.executeUpdate();
        }
    }

    private void addLegacyReport(PreparedStatement statement, String reporter, String reported, String reason,
                                 long timestamp, String status, String resolvedBy, long resolvedAt,
                                 String action, String resolutionReason, String note, String noteBy) throws SQLException {
        statement.setString(1, reporter);
        statement.setString(2, reported);
        statement.setString(3, reason);
        statement.setLong(4, timestamp);
        statement.setString(5, status);
        statement.setString(6, resolvedBy);
        if (resolvedAt > 0L) {
            statement.setLong(7, resolvedAt);
        } else {
            statement.setNull(7, java.sql.Types.BIGINT);
        }
        statement.setString(8, action);
        statement.setString(9, resolutionReason);
        statement.setString(10, note);
        statement.setString(11, noteBy);
        statement.addBatch();
    }

    private int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private long longValue(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private String text(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private boolean indexExists(Connection connection, String index) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            statement.setString(1, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void insertDuplicateActiveCase(Connection connection, long caseId) throws Exception {
        String activeKey = text(connection, "SELECT active_key FROM report_cases WHERE id = " + caseId);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO report_cases (identity_key, active_key, reported_name, reported_key, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, activeKey);
            statement.setString(2, activeKey);
            statement.setString(3, "Duplicate");
            statement.setString(4, "duplicate");
            statement.setString(5, "PENDING");
            statement.setLong(6, 1L);
            statement.setLong(7, 1L);
            statement.executeUpdate();
        }
    }

    private void createDuplicateReporterIdentity(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE reports SET reporter_identity_key = 'n:alice' WHERE id = 2");
        }
    }
}
