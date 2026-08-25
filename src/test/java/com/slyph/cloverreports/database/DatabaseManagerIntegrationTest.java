package com.slyph.cloverreports.database;

import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.models.Report;
import com.slyph.cloverreports.models.ReportPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DatabaseManagerIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    private DatabaseManager databaseManager;
    private ReportManager reportManager;

    @BeforeEach
    void setUp() {
        databaseManager = DatabaseManager.forSqlite(temporaryDirectory.resolve("reports.db"), Logger.getLogger("CloverReportsTest"));
        assertTrue(databaseManager.connect());
        assertTrue(databaseManager.createReportsTable());
        reportManager = new ReportManager(databaseManager, Logger.getLogger("CloverReportsTest"));
    }

    @AfterEach
    void tearDown() {
        databaseManager.disconnect();
    }

    @Test
    void storesCaseInsensitiveReportsAndLoadsOnlyRequestedPage() {
        for (int target = 0; target < 31; target++) {
            int reportCount = target % 3 + 1;
            for (int reporter = 0; reporter < reportCount; reporter++) {
                assertTrue(reportManager.addReport("Reporter" + reporter, "Target" + target, "Reason " + target));
            }
        }

        assertTrue(reportManager.hasActiveReport("reporter0", "target0"));
        assertFalse(reportManager.addReport("REPORTER0", "TARGET0", "Duplicate"));
        ReportPage firstPage = reportManager.getReportPage(ReportManager.STATUS_PENDING, null, 0, 14);
        ReportPage lastPage = reportManager.getReportPage(ReportManager.STATUS_PENDING, null, 20, 14);

        assertEquals(31, firstPage.getTotalPlayers());
        assertEquals(3, firstPage.getTotalPages());
        assertEquals(14, firstPage.getReports().size());
        assertEquals(2, lastPage.getPage());
        assertEquals(3, lastPage.getReports().size());
    }

    @Test
    void resolvesReportsAndNotesInOneConsistentState() {
        assertTrue(reportManager.addReport("Reporter", "Target", "Cheats"));
        assertTrue(reportManager.claimReview("Target", "Moderator"));
        assertEquals(ReportManager.ModeratorNoteUpdateResult.SUCCESS,
                reportManager.updateModeratorNote("Target", "Evidence checked", "Moderator"));
        assertTrue(reportManager.updateReportsStatus(
                "Target",
                ReportManager.STATUS_RESOLVED,
                "Moderator",
                ReportManager.ACTION_CLOSED,
                "Not enough evidence"
        ));

        ReportPage active = reportManager.getReportPage(ReportManager.STATUS_PENDING, null, 0, 14);
        ReportPage history = reportManager.getReportPage(ReportManager.STATUS_RESOLVED, "target", 0, 14);
        List<Report> reports = history.getReports().values().iterator().next();

        assertEquals(0, active.getTotalPlayers());
        assertEquals(1, history.getTotalPlayers());
        assertEquals(1, reports.size());
        assertEquals(1, reports.get(0).getModeratorNotes().size());
        assertEquals("Evidence checked", reports.get(0).getModeratorNotes().get(0).getNote());
        assertEquals(2, reportManager.getLogs("TARGET", 10).size());
    }

    @Test
    void createsIntegrityCheckedOnlineBackup() throws Exception {
        assertTrue(reportManager.addReport("Reporter", "Target", "Reason"));
        DatabaseManager.BackupResult result = databaseManager.createSqliteBackup();

        assertTrue(result.isSuccess());
        assertNotNull(result.getBackupFile());
        assertTrue(result.getBackupFile().isFile());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + result.getBackupFile());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM reports")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void withstandsConcurrentSubmissionsAndEnforcesNoteLimit() throws Exception {
        int workers = 16;
        int reports = 1_600;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<Boolean>> reportTasks = java.util.stream.IntStream.range(0, reports)
                    .mapToObj(index -> (Callable<Boolean>) () -> reportManager.addReport(
                            "Reporter" + index,
                            "Target" + index,
                            "Stress reason"
                    ))
                    .collect(java.util.stream.Collectors.toList());
            List<Future<Boolean>> reportResults = executor.invokeAll(reportTasks);
            for (Future<Boolean> result : reportResults) {
                assertTrue(result.get());
            }

            assertTrue(reportManager.addReport("NoteReporter", "NoteTarget", "Notes"));
            List<Callable<ReportManager.ModeratorNoteUpdateResult>> noteTasks = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(index -> (Callable<ReportManager.ModeratorNoteUpdateResult>) () ->
                            reportManager.updateModeratorNote("NoteTarget", "Note " + index, "Moderator" + index))
                    .collect(java.util.stream.Collectors.toList());
            List<Future<ReportManager.ModeratorNoteUpdateResult>> noteResults = executor.invokeAll(noteTasks);
            long successes = 0;
            long limited = 0;
            for (Future<ReportManager.ModeratorNoteUpdateResult> result : noteResults) {
                if (result.get() == ReportManager.ModeratorNoteUpdateResult.SUCCESS) {
                    successes++;
                } else if (result.get() == ReportManager.ModeratorNoteUpdateResult.LIMIT_REACHED) {
                    limited++;
                }
            }

            assertEquals(reports + 1, reportManager.getReportedPlayerCount(ReportManager.STATUS_PENDING, null));
            assertEquals(ReportManager.MAX_MODERATOR_NOTES_PER_PLAYER, successes);
            assertEquals(64 - ReportManager.MAX_MODERATOR_NOTES_PER_PLAYER, limited);
            ReportPage notePage = reportManager.getReportPage(ReportManager.STATUS_PENDING, "notetarget", 0, 14);
            Report noteReport = notePage.getReports().values().iterator().next().get(0);
            assertEquals(ReportManager.MAX_MODERATOR_NOTES_PER_PLAYER, noteReport.getModeratorNotes().size());
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void paginatesOneHundredThousandRowsWithinBoundedTime() throws Exception {
        int rows = 100_000;
        int targets = 25_000;
        try (Connection connection = databaseManager.getConnection()) {
            connection.setAutoCommit(false);
            String caseSql = "INSERT INTO report_cases (id, identity_key, active_key, reported_name, reported_key, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(caseSql)) {
                for (int targetIndex = 0; targetIndex < targets; targetIndex++) {
                    long caseId = targetIndex + 1L;
                    String target = "Target" + targetIndex;
                    String targetKey = target.toLowerCase();
                    statement.setLong(1, caseId);
                    statement.setString(2, "n:" + targetKey);
                    statement.setString(3, "n:" + targetKey);
                    statement.setString(4, target);
                    statement.setString(5, targetKey);
                    statement.setString(6, ReportManager.STATUS_PENDING);
                    statement.setLong(7, targetIndex);
                    statement.setLong(8, targetIndex);
                    statement.addBatch();
                    if ((targetIndex + 1) % 1_000 == 0) {
                        statement.executeBatch();
                    }
                }
                statement.executeBatch();
            }
            String reportSql = "INSERT INTO reports (case_id, reporter, reporter_key, reporter_identity_key, reported, reported_key, reason, timestamp, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(reportSql)) {
                for (int index = 0; index < rows; index++) {
                    String reporter = "Reporter" + index;
                    String target = "Target" + (index % targets);
                    statement.setLong(1, index % targets + 1L);
                    statement.setString(2, reporter);
                    statement.setString(3, reporter.toLowerCase());
                    statement.setString(4, "n:" + reporter.toLowerCase());
                    statement.setString(5, target);
                    statement.setString(6, target.toLowerCase());
                    statement.setString(7, "Stress");
                    statement.setLong(8, index);
                    statement.setString(9, ReportManager.STATUS_PENDING);
                    statement.addBatch();
                    if ((index + 1) % 1_000 == 0) {
                        statement.executeBatch();
                    }
                }
                statement.executeBatch();
            }
            connection.commit();
        }

        long started = System.nanoTime();
        ReportPage page = reportManager.getReportPage(ReportManager.STATUS_PENDING, null, 800, 14);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(targets, page.getTotalPlayers());
        assertEquals(14, page.getReports().size());
        assertTrue(elapsedMillis < 5_000L, "Page query took " + elapsedMillis + " ms");
        assertTrue(reportManager.getReportedPlayers(ReportManager.STATUS_PENDING, "target199").size() <= 30);
    }
}
