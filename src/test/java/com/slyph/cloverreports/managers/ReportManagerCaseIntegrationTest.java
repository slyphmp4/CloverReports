package com.slyph.cloverreports.managers;

import com.slyph.cloverreports.database.DatabaseManager;
import com.slyph.cloverreports.models.CasePage;
import com.slyph.cloverreports.models.HistoryFilter;
import com.slyph.cloverreports.models.ReportCase;
import com.slyph.cloverreports.models.ReportLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReportManagerCaseIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    private DatabaseManager databaseManager;
    private ReportManager firstManager;
    private ReportManager secondManager;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("CloverReportsCaseTest");
        databaseManager = DatabaseManager.forSqlite(temporaryDirectory.resolve("cases.db"), logger);
        assertTrue(databaseManager.connect());
        assertTrue(databaseManager.createReportsTable());
        firstManager = new ReportManager(databaseManager, logger);
        secondManager = new ReportManager(databaseManager, logger);
    }

    @AfterEach
    void tearDown() {
        databaseManager.disconnect();
    }

    @Test
    void linksReportsNotesAndLogsToExactRepeatedCases() {
        UUID targetUuid = UUID.randomUUID();
        UUID firstReporterUuid = UUID.randomUUID();
        UUID secondReporterUuid = UUID.randomUUID();
        UUID thirdReporterUuid = UUID.randomUUID();
        UUID moderatorUuid = UUID.randomUUID();

        ReportManager.SubmissionResult first = firstManager.submitReport(
                firstReporterUuid, "FirstReporter", targetUuid, "Target", "cheats", "Cheats", "https://example.com/evidence-1"
        );
        ReportManager.SubmissionResult second = firstManager.submitReport(
                secondReporterUuid, "SecondReporter", targetUuid, "Target", "spam", "Spam", null
        );

        assertEquals(ReportManager.SubmissionStatus.SUCCESS, first.getStatus());
        assertEquals(ReportManager.SubmissionStatus.SUCCESS, second.getStatus());
        assertEquals(first.getCaseId(), second.getCaseId());
        assertEquals(ReportManager.ModeratorNoteUpdateResult.SUCCESS,
                firstManager.updateModeratorNote(first.getCaseId(), "Checked evidence", moderatorUuid, "Moderator"));

        ReportManager.ReviewClaimResult firstClaim = firstManager.claimReview(first.getCaseId(), moderatorUuid, "Moderator");
        assertTrue(firstClaim.isAcquired());
        assertTrue(firstManager.resolveCase(firstClaim.getLease(), ReportManager.ACTION_CLOSED, "Insufficient evidence"));

        ReportCase firstCase = firstManager.getCase(first.getCaseId()).orElseThrow();
        assertEquals(ReportManager.STATUS_RESOLVED, firstCase.getStatus());
        assertEquals(2, firstCase.getReports().size());
        assertEquals(1, firstCase.getNotes().size());
        assertEquals(first.getCaseId(), firstCase.getNotes().get(0).getCaseId());
        assertEquals("https://example.com/evidence-1", firstCase.getReports().stream()
                .filter(report -> report.getReporter().equals("FirstReporter"))
                .findFirst()
                .orElseThrow()
                .getEvidenceUrl());

        ReportManager.SubmissionResult third = firstManager.submitReport(
                thirdReporterUuid, "ThirdReporter", targetUuid, "Target", "other", "Other", null
        );
        assertEquals(ReportManager.SubmissionStatus.SUCCESS, third.getStatus());
        assertNotEquals(first.getCaseId(), third.getCaseId());

        ReportManager.ReviewClaimResult secondClaim = firstManager.claimReview(third.getCaseId(), moderatorUuid, "Moderator");
        assertTrue(secondClaim.isAcquired());
        assertTrue(firstManager.resolveCase(secondClaim.getLease(), ReportManager.ACTION_PUNISHED, "Confirmed"));

        CasePage history = firstManager.getCasePage(
                ReportManager.STATUS_RESOLVED,
                new HistoryFilter("Target", null, null, null, 0L, 0L),
                0,
                20
        );
        assertEquals(2, history.getTotalCases());
        assertEquals(2, history.getCases().stream().map(ReportCase::getId).distinct().count());

        List<ReportLog> logs = firstManager.getLogs("Target", 20);
        assertTrue(logs.size() >= 3);
        assertTrue(logs.stream().allMatch(log -> log.getCaseId() == first.getCaseId() || log.getCaseId() == third.getCaseId()));
    }

    @Test
    void keepsTargetAndReporterIdentityAcrossRenames() {
        UUID targetUuid = UUID.randomUUID();
        UUID reporterUuid = UUID.randomUUID();
        UUID anotherReporterUuid = UUID.randomUUID();
        firstManager.registerIdentity(targetUuid, "TargetOld");
        firstManager.registerIdentity(reporterUuid, "ReporterOld");

        ReportManager.SubmissionResult first = firstManager.submitReport(
                reporterUuid, "ReporterOld", targetUuid, "TargetOld", "cheats", "Cheats", null
        );
        assertEquals(ReportManager.SubmissionStatus.SUCCESS, first.getStatus());

        firstManager.registerIdentity(targetUuid, "TargetNew");
        firstManager.registerIdentity(reporterUuid, "ReporterNew");

        ReportManager.SubmissionResult duplicate = firstManager.submitReport(
                reporterUuid, "ReporterNew", targetUuid, "TargetNew", "spam", "Spam", null
        );
        ReportManager.SubmissionResult another = firstManager.submitReport(
                anotherReporterUuid, "AnotherReporter", targetUuid, "TargetNew", "spam", "Spam", null
        );

        assertEquals(ReportManager.SubmissionStatus.DUPLICATE, duplicate.getStatus());
        assertEquals(first.getCaseId(), duplicate.getCaseId());
        assertEquals(ReportManager.SubmissionStatus.SUCCESS, another.getStatus());
        assertEquals(first.getCaseId(), another.getCaseId());
        assertEquals(targetUuid, firstManager.resolveKnownPlayerUuid("TargetOld"));
        assertEquals(targetUuid, firstManager.resolveKnownPlayerUuid("TargetNew"));

        ReportCase reportCase = firstManager.getCase(first.getCaseId()).orElseThrow();
        assertEquals("TargetNew", reportCase.getReportedName());
        assertEquals(targetUuid, reportCase.getReportedUuid());
        assertEquals(2, reportCase.getReports().size());
        assertTrue(reportCase.getReports().stream().allMatch(report -> targetUuid.equals(report.getReportedUuid())));
    }

    @Test
    void bindsPreviouslyNameOnlyReporterBeforeRename() {
        UUID targetUuid = UUID.randomUUID();
        UUID reporterUuid = UUID.randomUUID();
        firstManager.registerIdentity(targetUuid, "Target");

        ReportManager.SubmissionResult first = firstManager.submitReport(
                null, "ReporterOld", targetUuid, "Target", "cheats", "Cheats", null
        );
        assertEquals(ReportManager.SubmissionStatus.SUCCESS, first.getStatus());

        firstManager.registerIdentity(reporterUuid, "ReporterOld");
        firstManager.registerIdentity(reporterUuid, "ReporterNew");
        ReportManager.SubmissionResult duplicate = firstManager.submitReport(
                reporterUuid, "ReporterNew", targetUuid, "Target", "spam", "Spam", null
        );

        assertEquals(ReportManager.SubmissionStatus.DUPLICATE, duplicate.getStatus());
        assertEquals(first.getCaseId(), duplicate.getCaseId());
        assertEquals(1, firstManager.getCase(first.getCaseId()).orElseThrow().getReports().size());
    }

    @Test
    void mergesNameOnlyCaseWhenUuidIdentityBecomesKnown() {
        UUID targetUuid = UUID.randomUUID();
        firstManager.registerIdentity(targetUuid, "TargetNew");
        ReportManager.SubmissionResult uuidCase = firstManager.submitReport(
                UUID.randomUUID(), "FirstReporter", targetUuid, "TargetNew", "cheats", "Cheats", null
        );
        ReportManager.SubmissionResult nameCase = firstManager.submitReport(
                UUID.randomUUID(), "SecondReporter", null, "TargetOld", "spam", "Spam", null
        );
        assertEquals(ReportManager.SubmissionStatus.SUCCESS, uuidCase.getStatus());
        assertEquals(ReportManager.SubmissionStatus.SUCCESS, nameCase.getStatus());
        assertNotEquals(uuidCase.getCaseId(), nameCase.getCaseId());

        firstManager.registerIdentity(targetUuid, "TargetOld");

        CasePage active = firstManager.getCasePage(ReportManager.STATUS_PENDING, HistoryFilter.empty(), 0, 20);
        assertEquals(1, active.getTotalCases());
        assertEquals(2, active.getCases().get(0).getReports().size());
        assertEquals(targetUuid, active.getCases().get(0).getReportedUuid());
        assertEquals("TargetOld", active.getCases().get(0).getReportedName());
    }

    @Test
    void enforcesOneReporterAndOneOpenCaseAcrossManagers() throws Exception {
        UUID targetUuid = UUID.randomUUID();
        UUID reporterUuid = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<ReportManager.SubmissionResult>> tasks = IntStream.range(0, 96)
                    .mapToObj(index -> (Callable<ReportManager.SubmissionResult>) () -> {
                        ReportManager manager = index % 2 == 0 ? firstManager : secondManager;
                        return manager.submitReport(reporterUuid, "Reporter", targetUuid, "Target", "cheats", "Cheats", null);
                    })
                    .collect(Collectors.toList());
            List<Future<ReportManager.SubmissionResult>> futures = executor.invokeAll(tasks);
            long successful = 0L;
            long duplicate = 0L;
            for (Future<ReportManager.SubmissionResult> future : futures) {
                ReportManager.SubmissionStatus status = future.get().getStatus();
                if (status == ReportManager.SubmissionStatus.SUCCESS) {
                    successful++;
                } else if (status == ReportManager.SubmissionStatus.DUPLICATE) {
                    duplicate++;
                }
            }

            assertEquals(1L, successful);
            assertEquals(95L, duplicate);
            assertEquals(1, countRows("report_cases"));
            assertEquals(1, countRows("reports"));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void sharesLeaseOwnershipAndRejectsStaleTokens() throws Exception {
        UUID targetUuid = UUID.randomUUID();
        UUID firstModeratorUuid = UUID.randomUUID();
        UUID secondModeratorUuid = UUID.randomUUID();
        long caseId = firstManager.submitReport(
                UUID.randomUUID(), "Reporter", targetUuid, "Target", "cheats", "Cheats", null
        ).getCaseId();

        ReportManager.ReviewClaimResult firstClaim = firstManager.claimReview(caseId, firstModeratorUuid, "FirstModerator");
        assertTrue(firstClaim.isAcquired());

        ReportManager.ReviewClaimResult held = secondManager.claimReview(caseId, secondModeratorUuid, "SecondModerator");
        assertEquals(ReportManager.ReviewClaimStatus.HELD_BY_OTHER, held.getStatus());
        assertEquals("FirstModerator", held.getOwner());

        ReportManager.ReviewClaimResult rotated = firstManager.claimReview(caseId, firstModeratorUuid, "FirstModerator");
        assertTrue(rotated.isAcquired());
        assertNotEquals(firstClaim.getLease().getToken(), rotated.getLease().getToken());
        assertFalse(firstManager.ownsReview(firstClaim.getLease()));
        assertTrue(firstManager.ownsReview(rotated.getLease()));
        assertFalse(firstManager.releaseReview(firstClaim.getLease()));

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE case_review_leases SET expires_at = 0 WHERE case_id = ?")) {
            statement.setLong(1, caseId);
            assertEquals(1, statement.executeUpdate());
        }

        ReportManager.ReviewClaimResult takeover = secondManager.claimReview(caseId, secondModeratorUuid, "SecondModerator");
        assertTrue(takeover.isAcquired());
        assertFalse(firstManager.ownsReview(rotated.getLease()));
        assertTrue(secondManager.ownsReview(takeover.getLease()));
        assertTrue(secondManager.resolveCase(takeover.getLease(), ReportManager.ACTION_CLOSED, "Done"));
        assertEquals(0, countRows("case_review_leases"));
        assertEquals(ReportManager.STATUS_RESOLVED, firstManager.getCase(caseId).orElseThrow().getStatus());
    }

    @Test
    void cancelsPunishmentWithoutPoolTimeoutAndKeepsLease() {
        UUID targetUuid = UUID.randomUUID();
        UUID moderatorUuid = UUID.randomUUID();
        long caseId = firstManager.submitReport(
                UUID.randomUUID(), "Reporter", targetUuid, "Target", "cheats", "Cheats", null
        ).getCaseId();
        ReportManager.ReviewClaimResult claim = firstManager.claimReview(caseId, moderatorUuid, "Moderator");
        assertTrue(claim.isAcquired());
        assertTrue(firstManager.startPunishment(claim.getLease(), "Testing"));

        long started = System.nanoTime();
        assertTrue(firstManager.cancelPunishment(claim.getLease()));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 1_500L, "Cancellation took " + elapsedMillis + " ms");
        assertTrue(firstManager.ownsReview(claim.getLease()));
        assertEquals(ReportManager.STATUS_PENDING, firstManager.getCase(caseId).orElseThrow().getStatus());
    }

    @Test
    void filtersHistoryAndLogsByModeratorReasonAndAction() {
        UUID targetUuid = UUID.randomUUID();
        UUID firstModeratorUuid = UUID.randomUUID();
        UUID secondModeratorUuid = UUID.randomUUID();
        long firstCaseId = firstManager.submitReport(
                UUID.randomUUID(), "FirstReporter", targetUuid, "Target", "cheats", "Cheats", null
        ).getCaseId();
        assertEquals(ReportManager.ModeratorNoteUpdateResult.SUCCESS,
                firstManager.updateModeratorNote(firstCaseId, "Checked", firstModeratorUuid, "FirstModerator"));
        ReportManager.ReviewClaimResult firstClaim = firstManager.claimReview(firstCaseId, firstModeratorUuid, "FirstModerator");
        assertTrue(firstClaim.isAcquired());
        assertTrue(firstManager.resolveCase(firstClaim.getLease(), ReportManager.ACTION_CLOSED, "No violation"));

        long secondCaseId = firstManager.submitReport(
                UUID.randomUUID(), "SecondReporter", targetUuid, "Target", "spam", "Spam", null
        ).getCaseId();
        ReportManager.ReviewClaimResult secondClaim = firstManager.claimReview(secondCaseId, secondModeratorUuid, "SecondModerator");
        assertTrue(secondClaim.isAcquired());
        assertTrue(firstManager.resolveCase(secondClaim.getLease(), ReportManager.ACTION_PUNISHED, "Confirmed"));

        CasePage byModerator = firstManager.getCasePage(
                ReportManager.STATUS_RESOLVED,
                new HistoryFilter(null, "FirstModerator", null, null, 0L, 0L),
                0,
                20
        );
        CasePage byReason = firstManager.getCasePage(
                ReportManager.STATUS_RESOLVED,
                new HistoryFilter(null, null, "Cheats", "cheats", null, 0L, 0L),
                0,
                20
        );
        CasePage byNoteAction = firstManager.getCasePage(
                ReportManager.STATUS_RESOLVED,
                new HistoryFilter(null, null, null, "note", 0L, 0L),
                0,
                20
        );
        CasePage byPunishment = firstManager.getCasePage(
                ReportManager.STATUS_RESOLVED,
                new HistoryFilter(null, null, null, ReportManager.ACTION_PUNISHED, 0L, 0L),
                0,
                20
        );

        assertEquals(List.of(firstCaseId), byModerator.getCases().stream().map(ReportCase::getId).collect(Collectors.toList()));
        assertEquals(List.of(firstCaseId), byReason.getCases().stream().map(ReportCase::getId).collect(Collectors.toList()));
        assertEquals(List.of(firstCaseId), byNoteAction.getCases().stream().map(ReportCase::getId).collect(Collectors.toList()));
        assertEquals(List.of(secondCaseId), byPunishment.getCases().stream().map(ReportCase::getId).collect(Collectors.toList()));
        assertFalse(firstManager.getLogPage(new HistoryFilter(null, null, "Cheats", "cheats", null, 0L, 0L), 0, 20).getLogs().isEmpty());
    }

    private int countRows(String table) throws Exception {
        try (Connection connection = databaseManager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
