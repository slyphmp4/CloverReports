package com.slyph.cloverreports.managers;

import com.slyph.cloverreports.CloverReports;
import com.slyph.cloverreports.database.DatabaseManager;
import com.slyph.cloverreports.models.CasePage;
import com.slyph.cloverreports.models.HistoryFilter;
import com.slyph.cloverreports.models.LogPage;
import com.slyph.cloverreports.models.ModeratorNote;
import com.slyph.cloverreports.models.Report;
import com.slyph.cloverreports.models.ReportCase;
import com.slyph.cloverreports.models.ReportLog;
import com.slyph.cloverreports.models.ReportPage;
import com.slyph.cloverreports.models.ReporterStats;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public final class ReportManager {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String ACTION_CLOSED = "closed";
    public static final String ACTION_PUNISHED = "punished";
    public static final int MAX_MODERATOR_NOTES_PER_PLAYER = 20;
    public static final String LIVE_STATUS_NEW = "new";
    public static final String LIVE_STATUS_IN_WORK = "in_work";
    public static final String LIVE_STATUS_WAITING_DECISION = "waiting_decision";
    private static final int MAX_SUGGESTIONS = 30;
    private static final long CACHE_MILLIS = 2_000L;
    private final CloverReports plugin;
    private final Logger logger;
    private final ReentrantLock mutationLock;
    private final ConcurrentMap<Long, ReviewLease> localLeases;
    private final ConcurrentMap<String, CachedSuggestions> suggestionCache;
    private final ConcurrentMap<String, CachedCount> countCache;
    private volatile DatabaseManager databaseManager;

    public ReportManager(CloverReports plugin, DatabaseManager databaseManager) {
        this(plugin, databaseManager, plugin.getLogger());
    }

    public ReportManager(DatabaseManager databaseManager, Logger logger) {
        this(null, databaseManager, logger);
    }

    private ReportManager(CloverReports plugin, DatabaseManager databaseManager, Logger logger) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.logger = logger;
        this.mutationLock = new ReentrantLock();
        this.localLeases = new ConcurrentHashMap<>();
        this.suggestionCache = new ConcurrentHashMap<>();
        this.countCache = new ConcurrentHashMap<>();
    }

    public DatabaseManager replaceDatabase(DatabaseManager replacement) {
        mutationLock.lock();
        try {
            DatabaseManager previous = databaseManager;
            databaseManager = replacement;
            localLeases.clear();
            invalidateCaches();
            return previous;
        } finally {
            mutationLock.unlock();
        }
    }

    public void registerIdentity(UUID playerUuid, String playerName) {
        if (playerUuid == null || playerName == null || playerName.isBlank()) {
            return;
        }
        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long now = databaseNow(connection);
                String uuid = playerUuid.toString();
                String name = trimToLength(playerName, 64);
                String nameKey = normalizePlayer(name);
                upsertIdentity(connection, uuid, name, nameKey, now);
                upsertNameHistory(connection, uuid, name, nameKey, now);
                backfillActiveIdentity(connection, uuid, name, nameKey, now);
                backfillReporterIdentity(connection, uuid, nameKey);
                connection.commit();
                invalidateCaches();
            } catch (SQLException exception) {
                rollback(connection);
                logError("Identity registration", exception);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            logError("Identity registration", exception);
        } finally {
            mutationLock.unlock();
        }
    }

    public UUID resolveKnownPlayerUuid(String playerName) {
        try (Connection connection = databaseManager.getConnection()) {
            return findIdentityUuid(connection, playerName);
        } catch (SQLException exception) {
            logError("Identity lookup", exception);
            return null;
        }
    }

    public boolean hasActiveReport(String reporter, String reported) {
        return hasActiveReport(null, reporter, resolveKnownPlayerUuid(reported), reported);
    }

    public boolean hasActiveReport(UUID reporterUuid, String reporter, UUID reportedUuid, String reported) {
        OptionalLong caseId = findOpenCaseId(reportedUuid, reported);
        if (caseId.isEmpty()) {
            return false;
        }
        String identityKey = identityKey(reporterUuid, reporter);
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM reports WHERE case_id = ? AND reporter_identity_key = ?")) {
            statement.setLong(1, caseId.getAsLong());
            statement.setString(2, identityKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            logError("Active report check", exception);
            return false;
        }
    }

    public boolean hasPunishedReport(String reported) {
        return hasPunishedReport(resolveKnownPlayerUuid(reported), reported);
    }

    public boolean hasPunishedReport(UUID reportedUuid, String reported) {
        long blockSeconds = getLongSetting("report.punished-block-seconds", 300L);
        if (blockSeconds <= 0L) {
            return false;
        }
        try (Connection connection = databaseManager.getConnection()) {
            long cutoff = databaseNow(connection) - blockSeconds * 1_000L;
            StringBuilder sql = new StringBuilder("SELECT 1 FROM report_cases WHERE status = ? AND action = ? AND resolved_at >= ? AND ");
            List<Object> parameters = new ArrayList<>();
            parameters.add(STATUS_RESOLVED);
            parameters.add(ACTION_PUNISHED);
            parameters.add(cutoff);
            appendIdentityPredicate(sql, parameters, "reported_uuid", "reported_key", reportedUuid, reported);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bind(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        } catch (SQLException exception) {
            logError("Punished report check", exception);
            return false;
        }
    }

    public boolean addReport(String reporter, String reported, String reason) {
        return submitReport(null, reporter, resolveKnownPlayerUuid(reported), reported, normalizeReason(reason), reason, null).getStatus() == SubmissionStatus.SUCCESS;
    }

    public SubmissionResult submitReport(UUID reporterUuid, String reporter, UUID reportedUuid, String reported, String reasonKey, String reason, String evidenceUrl) {
        String safeReporter = trimToLength(reporter, 64);
        String safeReported = trimToLength(reported, 64);
        String safeReason = trimToLength(reason, getIntSetting("report.max-reason-length", 256));
        String safeReasonKey = trimToLength(reasonKey == null || reasonKey.isBlank() ? normalizeReason(safeReason) : reasonKey.toLowerCase(Locale.ROOT), 64);
        String safeEvidenceUrl = trimNullable(evidenceUrl, Math.max(64, getIntSetting("report.evidence.max-url-length", 2_048)));
        String reporterKey = normalizePlayer(safeReporter);
        String reportedKey = normalizePlayer(safeReported);
        String reporterIdentityKey = identityKey(reporterUuid, safeReporter);
        String targetIdentityKey = identityKey(reportedUuid, safeReported);
        if (reporterKey.isEmpty() || reportedKey.isEmpty() || safeReason.isBlank()
                || reporterUuid != null && reporterUuid.equals(reportedUuid)
                || reporterKey.equals(reportedKey)) {
            return SubmissionResult.error();
        }

        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long now = databaseNow(connection);
                if (reporterUuid != null && reportedUuid != null && !reporterUuid.equals(reportedUuid)) {
                    if (reporterUuid.toString().compareTo(reportedUuid.toString()) < 0) {
                        upsertObservedIdentity(connection, reporterUuid, safeReporter, reporterKey, now);
                        upsertObservedIdentity(connection, reportedUuid, safeReported, reportedKey, now);
                    } else {
                        upsertObservedIdentity(connection, reportedUuid, safeReported, reportedKey, now);
                        upsertObservedIdentity(connection, reporterUuid, safeReporter, reporterKey, now);
                    }
                } else if (reportedUuid != null) {
                    upsertObservedIdentity(connection, reportedUuid, safeReported, reportedKey, now);
                } else if (reporterUuid != null) {
                    upsertObservedIdentity(connection, reporterUuid, safeReporter, reporterKey, now);
                }
                if (reportedUuid != null) {
                    backfillActiveIdentity(connection, reportedUuid.toString(), safeReported, reportedKey, now);
                }
                if (reporterUuid != null) {
                    backfillReporterIdentity(connection, reporterUuid.toString(), reporterKey);
                }
                long caseId = findOrCreateOpenCase(connection, reportedUuid, safeReported, reportedKey, targetIdentityKey, now);
                if (hasReporterInCase(connection, caseId, reporterIdentityKey, true)) {
                    connection.rollback();
                    return SubmissionResult.duplicate(caseId);
                }
                String insert = "INSERT INTO reports (case_id, reporter, reporter_key, reporter_uuid, reporter_identity_key, reported, reported_key, reported_uuid, reason, reason_key, evidence_url, timestamp, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setLong(1, caseId);
                    statement.setString(2, safeReporter);
                    statement.setString(3, reporterKey);
                    setUuid(statement, 4, reporterUuid);
                    statement.setString(5, reporterIdentityKey);
                    statement.setString(6, safeReported);
                    statement.setString(7, reportedKey);
                    setUuid(statement, 8, reportedUuid);
                    statement.setString(9, safeReason);
                    statement.setString(10, safeReasonKey);
                    statement.setString(11, safeEvidenceUrl);
                    statement.setLong(12, now);
                    statement.setString(13, STATUS_PENDING);
                    statement.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE report_cases SET updated_at = ?, reported_name = ?, reported_key = ?, reported_uuid = COALESCE(reported_uuid, ?) WHERE id = ?")) {
                    update.setLong(1, now);
                    update.setString(2, safeReported);
                    update.setString(3, reportedKey);
                    setUuid(update, 4, reportedUuid);
                    update.setLong(5, caseId);
                    update.executeUpdate();
                }
                connection.commit();
                invalidateCaches();
                return SubmissionResult.success(caseId);
            } catch (SQLException exception) {
                rollback(connection);
                if (isConstraintViolation(exception)) {
                    OptionalLong existing = findOpenCaseId(reportedUuid, safeReported);
                    return existing.isPresent() ? SubmissionResult.duplicate(existing.getAsLong()) : SubmissionResult.error();
                }
                logError("Report submission", exception);
                return SubmissionResult.error();
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            logError("Report submission", exception);
            return SubmissionResult.error();
        } finally {
            mutationLock.unlock();
        }
    }

    public int getActiveReportCount(String reported) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        if (caseId.isEmpty()) {
            return 0;
        }
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM reports WHERE case_id = ?")) {
            statement.setLong(1, caseId.getAsLong());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            logError("Active report count", exception);
            return 0;
        }
    }

    public ReporterStats getReporterStats(String reporter) {
        return getReporterStats(null, reporter);
    }

    public ReporterStats getReporterStats(UUID reporterUuid, String reporter) {
        int days = Math.max(1, getIntSetting("false-reports.statistics-days", 30));
        String identity = identityKey(reporterUuid, reporter);
        String sql = "SELECT "
                + "SUM(CASE WHEN c.action = ? THEN 1 ELSE 0 END) AS closed_count,"
                + "SUM(CASE WHEN c.action = ? THEN 1 ELSE 0 END) AS punished_count,"
                + "MAX(c.resolved_at) AS latest_reviewed_at "
                + "FROM reports r JOIN report_cases c ON c.id = r.case_id "
                + "WHERE r.reporter_identity_key = ? AND c.status = ? AND c.resolved_at >= ?";
        try (Connection connection = databaseManager.getConnection()) {
            long cutoff = databaseNow(connection) - days * 86_400_000L;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ACTION_CLOSED);
                statement.setString(2, ACTION_PUNISHED);
                statement.setString(3, identity);
                statement.setString(4, STATUS_RESOLVED);
                statement.setLong(5, cutoff);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return new ReporterStats(resultSet.getInt("closed_count"), resultSet.getInt("punished_count"), resultSet.getLong("latest_reviewed_at"));
                    }
                }
            }
        } catch (SQLException exception) {
            logError("Reporter statistics", exception);
        }
        return new ReporterStats(0, 0);
    }

    public CasePage getCasePage(String status, HistoryFilter filter, int requestedPage, int pageSize) {
        int safeSize = Math.max(1, Math.min(100, pageSize));
        HistoryFilter safeFilter = filter == null ? HistoryFilter.empty() : filter;
        try (Connection connection = databaseManager.getConnection()) {
            CaseFilterSql where = buildCaseFilter(connection, status, safeFilter);
            int total = countCases(connection, where);
            int totalPages = Math.max(1, (int) Math.ceil(total / (double) safeSize));
            int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
            List<Long> caseIds = loadCaseIds(connection, where, status, page, safeSize);
            List<ReportCase> cases = loadCases(connection, caseIds);
            return new CasePage(cases, page, totalPages, total);
        } catch (SQLException exception) {
            logError("Case page load", exception);
            return new CasePage(List.of(), 0, 1, 0);
        }
    }

    public Optional<ReportCase> getCase(long caseId) {
        try (Connection connection = databaseManager.getConnection()) {
            List<ReportCase> cases = loadCases(connection, List.of(caseId));
            return cases.isEmpty() ? Optional.empty() : Optional.of(cases.get(0));
        } catch (SQLException exception) {
            logError("Case load", exception);
            return Optional.empty();
        }
    }

    public ReportPage getReportPage(String status, String reportedFilter, int requestedPage, int pageSize) {
        HistoryFilter filter = reportedFilter == null ? HistoryFilter.empty() : new HistoryFilter(reportedFilter, null, null, null, 0L, 0L);
        CasePage casePage = getCasePage(status, filter, requestedPage, pageSize);
        Map<String, List<Report>> grouped = new LinkedHashMap<>();
        for (ReportCase reportCase : casePage.getCases()) {
            String key = reportCase.getReportedName();
            if (grouped.containsKey(key)) {
                key = key + " #" + reportCase.getId();
            }
            List<Report> reports = new ArrayList<>(reportCase.getReports());
            if (!reports.isEmpty() && !reportCase.getNotes().isEmpty()) {
                reports.set(0, reports.get(0).withModeratorNotes(reportCase.getNotes()));
            }
            grouped.put(key, reports);
        }
        return new ReportPage(grouped, casePage.getPage(), casePage.getTotalPages(), casePage.getTotalCases());
    }

    public int getActiveReportedPlayerCount() {
        return getReportedPlayerCount(STATUS_PENDING, null);
    }

    public int getReportedPlayerCount(String status, String reportedFilter) {
        String cacheKey = status + '\n' + normalizePlayer(reportedFilter);
        long now = System.currentTimeMillis();
        CachedCount cached = countCache.get(cacheKey);
        if (cached != null && cached.expiresAt >= now) {
            return cached.value;
        }
        HistoryFilter filter = reportedFilter == null ? HistoryFilter.empty() : new HistoryFilter(reportedFilter, null, null, null, 0L, 0L);
        int count = getCasePage(status, filter, 0, 1).getTotalCases();
        countCache.put(cacheKey, new CachedCount(count, now + CACHE_MILLIS));
        return count;
    }

    public List<String> getReportedPlayers(String status, String input) {
        String normalized = normalizePlayer(input);
        String cacheKey = status + '\n' + normalized;
        long now = System.currentTimeMillis();
        CachedSuggestions cached = suggestionCache.get(cacheKey);
        if (cached != null && cached.expiresAt >= now) {
            return cached.values;
        }
        List<String> values = new ArrayList<>();
        String sql = "SELECT COALESCE(pi.player_name, c.reported_name) AS display_name "
                + "FROM report_cases c LEFT JOIN player_identities pi ON pi.player_uuid = c.reported_uuid "
                + "WHERE c.status = ? AND (c.reported_key LIKE ? ESCAPE '!' OR LOWER(COALESCE(pi.player_name, c.reported_name)) LIKE ? ESCAPE '!') "
                + "GROUP BY c.identity_key, COALESCE(pi.player_name, c.reported_name) ORDER BY MAX(c.updated_at) DESC LIMIT ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, escapeLike(normalized) + "%");
            statement.setString(3, escapeLike(normalized) + "%");
            statement.setInt(4, MAX_SUGGESTIONS);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
            }
        } catch (SQLException exception) {
            logError("Case suggestions", exception);
        }
        List<String> immutable = List.copyOf(values);
        if (suggestionCache.size() >= 256) {
            suggestionCache.clear();
        }
        suggestionCache.put(cacheKey, new CachedSuggestions(immutable, now + CACHE_MILLIS));
        return immutable;
    }

    public OptionalLong findOpenCaseId(UUID targetUuid, String targetName) {
        try (Connection connection = databaseManager.getConnection()) {
            return findOpenCaseId(connection, targetUuid, targetName, false);
        } catch (SQLException exception) {
            logError("Open case lookup", exception);
            return OptionalLong.empty();
        }
    }

    public ReviewClaimResult claimReview(long caseId, UUID moderatorUuid, String moderatorName) {
        UUID safeModeratorUuid = moderatorUuid == null ? legacyUuid(moderatorName) : moderatorUuid;
        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String caseStatus = lockCaseStatus(connection, caseId);
                if (caseStatus == null) {
                    connection.rollback();
                    return ReviewClaimResult.notFound();
                }
                if (!caseStatus.equals(STATUS_PENDING)) {
                    connection.rollback();
                    return ReviewClaimResult.caseNotOpen();
                }
                long now = databaseNow(connection);
                LeaseRow existing = loadLease(connection, caseId, true);
                if (existing != null && existing.expiresAt > now
                        && (!existing.ownerUuid.equals(safeModeratorUuid) || !existing.serverId.equals(getServerId()))) {
                    connection.rollback();
                    return ReviewClaimResult.held(existing.ownerName, existing.serverId);
                }
                UUID token = UUID.randomUUID();
                long expiresAt = now + getReviewTimeoutMillis();
                if (existing == null) {
                    insertLease(connection, caseId, safeModeratorUuid, moderatorName, token, LIVE_STATUS_IN_WORK, now, expiresAt);
                } else {
                    updateLease(connection, caseId, safeModeratorUuid, moderatorName, token, LIVE_STATUS_IN_WORK, now, expiresAt);
                }
                connection.commit();
                ReviewLease lease = new ReviewLease(caseId, safeModeratorUuid, moderatorName, getServerId(), token, expiresAt);
                localLeases.put(caseId, lease);
                invalidateCaches();
                return ReviewClaimResult.acquired(lease);
            } catch (SQLException exception) {
                rollback(connection);
                logError("Review claim", exception);
                return ReviewClaimResult.error();
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            logError("Review claim", exception);
            return ReviewClaimResult.error();
        } finally {
            mutationLock.unlock();
        }
    }

    public boolean claimReview(String reported, String moderator) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        return caseId.isPresent() && claimReview(caseId.getAsLong(), legacyUuid(moderator), moderator).isAcquired();
    }

    public boolean renewReview(ReviewLease lease, String liveStatus) {
        if (lease == null) {
            return false;
        }
        try (Connection connection = databaseManager.getConnection()) {
            long now = databaseNow(connection);
            long expires = now + getReviewTimeoutMillis();
            String sql = "UPDATE case_review_leases SET review_status = ?, expires_at = ?, updated_at = ? "
                    + "WHERE case_id = ? AND owner_uuid = ? AND server_id = ? AND lease_token = ? AND expires_at >= ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, liveStatus);
                statement.setLong(2, expires);
                statement.setLong(3, now);
                statement.setLong(4, lease.caseId);
                statement.setString(5, lease.moderatorUuid.toString());
                statement.setString(6, lease.serverId);
                statement.setString(7, lease.token.toString());
                statement.setLong(8, now);
                boolean renewed = statement.executeUpdate() > 0;
                if (renewed) {
                    ReviewLease updated = lease.withExpiry(expires);
                    localLeases.put(lease.caseId, updated);
                }
                return renewed;
            }
        } catch (SQLException exception) {
            logError("Review renewal", exception);
            return false;
        }
    }

    public void updateReviewStatus(String reported, String moderator, String liveStatus) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        if (caseId.isPresent()) {
            ReviewLease lease = localLeases.get(caseId.getAsLong());
            if (lease != null && lease.moderatorName.equalsIgnoreCase(moderator)) {
                renewReview(lease, liveStatus);
            }
        }
    }

    public boolean ownsReview(ReviewLease lease) {
        if (lease == null) {
            return false;
        }
        try (Connection connection = databaseManager.getConnection()) {
            return validateLease(connection, lease, databaseNow(connection), false);
        } catch (SQLException exception) {
            logError("Review ownership check", exception);
            return false;
        }
    }

    public boolean ownsReview(String reported, String moderator) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        if (caseId.isEmpty()) {
            return false;
        }
        ReviewLease lease = localLeases.get(caseId.getAsLong());
        return lease != null && lease.moderatorName.equalsIgnoreCase(moderator) && ownsReview(lease);
    }

    public boolean releaseReview(ReviewLease lease) {
        if (lease == null) {
            return false;
        }
        String sql = "DELETE FROM case_review_leases WHERE case_id = ? AND owner_uuid = ? AND server_id = ? AND lease_token = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lease.caseId);
            statement.setString(2, lease.moderatorUuid.toString());
            statement.setString(3, lease.serverId);
            statement.setString(4, lease.token.toString());
            boolean released = statement.executeUpdate() > 0;
            localLeases.computeIfPresent(lease.caseId, (caseId, current) -> current.token.equals(lease.token) ? null : current);
            invalidateCaches();
            return released;
        } catch (SQLException exception) {
            logError("Review release", exception);
            return false;
        }
    }

    public void releaseReview(String reported, String moderator) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        if (caseId.isPresent()) {
            ReviewLease lease = localLeases.get(caseId.getAsLong());
            if (lease != null && lease.moderatorName.equalsIgnoreCase(moderator)) {
                releaseReview(lease);
            }
        }
    }

    public void releaseReview(String reported) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        if (caseId.isPresent()) {
            ReviewLease lease = localLeases.get(caseId.getAsLong());
            if (lease != null) {
                releaseReview(lease);
            }
        }
    }

    public boolean releaseReviewsByModerator(String moderator) {
        String sql = "DELETE FROM case_review_leases WHERE server_id = ? AND LOWER(owner_name) = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, getServerId());
            statement.setString(2, normalizePlayer(moderator));
            boolean changed = statement.executeUpdate() > 0;
            localLeases.entrySet().removeIf(entry -> entry.getValue().moderatorName.equalsIgnoreCase(moderator));
            invalidateCaches();
            return changed;
        } catch (SQLException exception) {
            logError("Moderator review release", exception);
            return false;
        }
    }

    public int releaseServerReviews() {
        List<ReviewLease> leases = new ArrayList<>(localLeases.values());
        if (leases.isEmpty()) {
            return 0;
        }
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM case_review_leases WHERE case_id = ? AND lease_token = ?")) {
            for (ReviewLease lease : leases) {
                statement.setLong(1, lease.caseId);
                statement.setString(2, lease.token.toString());
                statement.addBatch();
            }
            int released = 0;
            for (int changed : statement.executeBatch()) {
                if (changed > 0 || changed == Statement.SUCCESS_NO_INFO) {
                    released++;
                }
            }
            localLeases.clear();
            invalidateCaches();
            return released;
        } catch (SQLException exception) {
            logError("Server review release", exception);
            return 0;
        }
    }

    public String getReviewModerator(String reported) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        return caseId.isEmpty() ? null : getLeaseOwner(caseId.getAsLong(), true);
    }

    public String getReviewStatus(String reported) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        if (caseId.isEmpty()) {
            return LIVE_STATUS_NEW;
        }
        try (Connection connection = databaseManager.getConnection()) {
            LeaseRow lease = loadLease(connection, caseId.getAsLong(), false);
            return lease == null || lease.expiresAt < databaseNow(connection) ? LIVE_STATUS_NEW : lease.status;
        } catch (SQLException exception) {
            return LIVE_STATUS_NEW;
        }
    }

    public boolean updateReportsStatus(String reported, String newStatus, String moderator, String action) {
        return updateReportsStatus(reported, newStatus, moderator, action, null);
    }

    public boolean updateReportsStatus(String reported, String newStatus, String moderator, String action, String resolutionReason) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        if (caseId.isEmpty()) {
            return false;
        }
        ReviewLease lease = localLeases.get(caseId.getAsLong());
        return lease != null && resolveCase(lease, action, resolutionReason);
    }

    public boolean resolveCase(ReviewLease lease, String action, String resolutionReason) {
        if (lease == null) {
            return false;
        }
        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long now = databaseNow(connection);
                String status = lockCaseStatus(connection, lease.caseId);
                if (!STATUS_PENDING.equals(status) || !validateLease(connection, lease, now, true)) {
                    connection.rollback();
                    return false;
                }
                resolveCaseRows(connection, lease, action, resolutionReason, now);
                connection.commit();
                localLeases.remove(lease.caseId);
                invalidateCaches();
                return true;
            } catch (SQLException exception) {
                rollback(connection);
                logError("Case resolution", exception);
                return false;
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            logError("Case resolution", exception);
            return false;
        } finally {
            mutationLock.unlock();
        }
    }

    public boolean startPunishment(String reported, String moderator, String resolutionReason) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        if (caseId.isEmpty()) {
            return false;
        }
        ReviewLease lease = localLeases.get(caseId.getAsLong());
        return lease != null && startPunishment(lease, resolutionReason);
    }

    public boolean startPunishment(ReviewLease lease, String resolutionReason) {
        if (lease == null) {
            return false;
        }
        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long now = databaseNow(connection);
                String status = lockCaseStatus(connection, lease.caseId);
                if (!STATUS_PENDING.equals(status) || !validateLease(connection, lease, now, true)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement updateCase = connection.prepareStatement(
                        "UPDATE report_cases SET status = ?, updated_at = ?, resolved_by = ?, resolved_by_uuid = ?, resolved_at = ?, action = ?, resolution_reason = ? WHERE id = ?")) {
                    updateCase.setString(1, STATUS_PROCESSING);
                    updateCase.setLong(2, now);
                    updateCase.setString(3, lease.moderatorName);
                    updateCase.setString(4, lease.moderatorUuid.toString());
                    updateCase.setLong(5, now);
                    updateCase.setString(6, ACTION_PUNISHED);
                    updateCase.setString(7, resolutionReason);
                    updateCase.setLong(8, lease.caseId);
                    updateCase.executeUpdate();
                }
                updateLegacyReportStatus(connection, lease.caseId, STATUS_PROCESSING, lease, ACTION_PUNISHED, resolutionReason, now);
                connection.commit();
                invalidateCaches();
                return true;
            } catch (SQLException exception) {
                rollback(connection);
                logError("Punishment start", exception);
                return false;
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            logError("Punishment start", exception);
            return false;
        } finally {
            mutationLock.unlock();
        }
    }

    public boolean completePunishment(String reported, String moderator, String resolutionReason) {
        OptionalLong caseId = findCaseIdForLocalAction(reported);
        ReviewLease lease = caseId.isPresent() ? localLeases.get(caseId.getAsLong()) : null;
        if (lease != null && !lease.moderatorName.equalsIgnoreCase(moderator)) {
            return false;
        }
        return lease != null && completePunishment(lease, resolutionReason);
    }

    public boolean completePunishment(ReviewLease lease, String resolutionReason) {
        if (lease == null) {
            return false;
        }
        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long now = databaseNow(connection);
                String status = lockCaseStatus(connection, lease.caseId);
                if (!STATUS_PROCESSING.equals(status) || !validateLease(connection, lease, now, true)) {
                    connection.rollback();
                    return false;
                }
                resolveCaseRows(connection, lease, ACTION_PUNISHED, resolutionReason, now);
                connection.commit();
                localLeases.remove(lease.caseId);
                invalidateCaches();
                return true;
            } catch (SQLException exception) {
                rollback(connection);
                logError("Punishment completion", exception);
                return false;
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            logError("Punishment completion", exception);
            return false;
        } finally {
            mutationLock.unlock();
        }
    }

    public void cancelPunishment(String reported, String moderator) {
        OptionalLong caseId = findCaseIdForLocalAction(reported);
        ReviewLease lease = caseId.isPresent() ? localLeases.get(caseId.getAsLong()) : null;
        if (lease != null) {
            if (lease.moderatorName.equalsIgnoreCase(moderator)) {
                cancelPunishment(lease);
            }
        }
    }

    public boolean cancelPunishment(ReviewLease lease) {
        if (lease == null) {
            return false;
        }
        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long now = databaseNow(connection);
                if (!STATUS_PROCESSING.equals(lockCaseStatus(connection, lease.caseId)) || !validateLease(connection, lease, now, true)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE report_cases SET status = ?, active_key = identity_key, updated_at = ?, resolved_by = NULL, resolved_by_uuid = NULL, resolved_at = NULL, action = NULL, resolution_reason = NULL WHERE id = ?")) {
                    statement.setString(1, STATUS_PENDING);
                    statement.setLong(2, now);
                    statement.setLong(3, lease.caseId);
                    statement.executeUpdate();
                }
                clearLegacyResolution(connection, lease.caseId);
                long expiresAt = now + getReviewTimeoutMillis();
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE case_review_leases SET review_status = ?, expires_at = ?, updated_at = ? WHERE case_id = ? AND lease_token = ?")) {
                    statement.setString(1, LIVE_STATUS_IN_WORK);
                    statement.setLong(2, expiresAt);
                    statement.setLong(3, now);
                    statement.setLong(4, lease.caseId);
                    statement.setString(5, lease.token.toString());
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                localLeases.put(lease.caseId, lease.withExpiry(expiresAt));
                invalidateCaches();
                return true;
            } catch (SQLException exception) {
                rollback(connection);
                logError("Punishment rollback", exception);
                return false;
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            logError("Punishment rollback", exception);
            return false;
        } finally {
            mutationLock.unlock();
        }
    }

    public ModeratorNoteUpdateResult updateModeratorNote(String reported, String note, String moderator) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        return caseId.isEmpty() ? ModeratorNoteUpdateResult.NO_ACTIVE_REPORTS
                : updateModeratorNote(caseId.getAsLong(), note, legacyUuid(moderator), moderator);
    }

    public ModeratorNoteUpdateResult updateModeratorNote(long caseId, String note, UUID moderatorUuid, String moderator) {
        String prepared = trimNullable(note, getIntSetting("note-input.max-length", 512));
        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!STATUS_PENDING.equals(lockCaseStatus(connection, caseId))) {
                    connection.rollback();
                    return ModeratorNoteUpdateResult.NO_ACTIVE_REPORTS;
                }
                long now = databaseNow(connection);
                String reportedName = getCaseReportedName(connection, caseId);
                if (prepared == null) {
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM report_notes WHERE case_id = ?")) {
                        delete.setLong(1, caseId);
                        delete.executeUpdate();
                    }
                    updateLegacyModeratorNote(connection, caseId, null, null);
                    insertLog(connection, caseId, moderator, moderatorUuid, "note-clear", reportedName, resolveCaseUuid(connection, caseId), null, null, now);
                } else {
                    if (countNotes(connection, caseId, true) >= MAX_MODERATOR_NOTES_PER_PLAYER) {
                        connection.rollback();
                        return ModeratorNoteUpdateResult.LIMIT_REACHED;
                    }
                    String insert = "INSERT INTO report_notes (case_id, reported, reported_key, moderator, moderator_uuid, note, timestamp, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement statement = connection.prepareStatement(insert)) {
                        statement.setLong(1, caseId);
                        statement.setString(2, reportedName);
                        statement.setString(3, normalizePlayer(reportedName));
                        statement.setString(4, trimToLength(moderator, 64));
                        setUuid(statement, 5, moderatorUuid);
                        statement.setString(6, prepared);
                        statement.setLong(7, now);
                        statement.setString(8, STATUS_PENDING);
                        statement.executeUpdate();
                    }
                    updateLegacyModeratorNote(connection, caseId, prepared, moderator);
                    insertLog(connection, caseId, moderator, moderatorUuid, "note", reportedName, resolveCaseUuid(connection, caseId), null, prepared, now);
                }
                connection.commit();
                invalidateCaches();
                return ModeratorNoteUpdateResult.SUCCESS;
            } catch (SQLException exception) {
                rollback(connection);
                logError("Moderator note update", exception);
                return ModeratorNoteUpdateResult.ERROR;
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            logError("Moderator note update", exception);
            return ModeratorNoteUpdateResult.ERROR;
        } finally {
            mutationLock.unlock();
        }
    }

    public void addLog(String moderator, String action, String reported, String reason, String note) {
        OptionalLong caseId = findOpenCaseId(resolveKnownPlayerUuid(reported), reported);
        addLog(caseId.orElse(0L), legacyUuid(moderator), moderator, action, reported, resolveKnownPlayerUuid(reported), reason, note);
    }

    public void addLog(long caseId, UUID moderatorUuid, String moderator, String action, String reported, UUID reportedUuid, String reason, String note) {
        try (Connection connection = databaseManager.getConnection()) {
            insertLog(connection, caseId, moderator, moderatorUuid, action, reported, reportedUuid, reason, note, databaseNow(connection));
        } catch (SQLException exception) {
            logError("Action log insert", exception);
        }
    }

    public List<ReportLog> getLogs(String reported, int limit) {
        HistoryFilter filter = new HistoryFilter(reported, null, null, null, 0L, 0L);
        return getLogPage(filter, 0, limit).getLogs();
    }

    public LogPage getLogPage(HistoryFilter filter, int requestedPage, int pageSize) {
        int safeSize = Math.max(1, Math.min(50, pageSize));
        HistoryFilter safeFilter = filter == null ? HistoryFilter.empty() : filter;
        try (Connection connection = databaseManager.getConnection()) {
            LogFilterSql where = buildLogFilter(connection, safeFilter);
            int total = countLogs(connection, where);
            int totalPages = Math.max(1, (int) Math.ceil(total / (double) safeSize));
            int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
            String sql = "SELECT l.id, l.case_id, l.moderator, l.moderator_uuid, l.action, l.reported, l.reported_uuid, l.reason, l.note, l.timestamp, c.action AS case_action, c.resolved_at AS case_resolved_at "
                    + "FROM report_logs l LEFT JOIN report_cases c ON c.id = l.case_id WHERE " + where.clause
                    + " ORDER BY l.timestamp DESC, l.id DESC LIMIT ? OFFSET ?";
            List<Object> parameters = new ArrayList<>(where.parameters);
            parameters.add(safeSize);
            parameters.add(page * safeSize);
            List<ReportLog> logs = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        logs.add(createReportLog(resultSet));
                    }
                }
            }
            return new LogPage(logs, page, totalPages, total);
        } catch (SQLException exception) {
            logError("Log page load", exception);
            return new LogPage(List.of(), 0, 1, 0);
        }
    }

    public List<ReportLog> getLogBatch(HistoryFilter filter, long beforeTimestamp, int beforeId, int limit) {
        HistoryFilter safeFilter = filter == null ? HistoryFilter.empty() : filter;
        try (Connection connection = databaseManager.getConnection()) {
            LogFilterSql where = buildLogFilter(connection, safeFilter);
            StringBuilder clause = new StringBuilder(where.clause);
            List<Object> parameters = new ArrayList<>(where.parameters);
            if (beforeTimestamp > 0L) {
                clause.append(" AND (l.timestamp < ? OR (l.timestamp = ? AND l.id < ?))");
                parameters.add(beforeTimestamp);
                parameters.add(beforeTimestamp);
                parameters.add(beforeId);
            }
            parameters.add(Math.max(1, Math.min(2_000, limit)));
            String sql = "SELECT l.id, l.case_id, l.moderator, l.moderator_uuid, l.action, l.reported, l.reported_uuid, l.reason, l.note, l.timestamp, c.action AS case_action, c.resolved_at AS case_resolved_at "
                    + "FROM report_logs l LEFT JOIN report_cases c ON c.id = l.case_id WHERE " + clause
                    + " ORDER BY l.timestamp DESC, l.id DESC LIMIT ?";
            List<ReportLog> logs = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        logs.add(createReportLog(resultSet));
                    }
                }
            }
            return logs;
        } catch (SQLException exception) {
            logError("Log batch load", exception);
            return List.of();
        }
    }

    public int countLogs(HistoryFilter filter) {
        try (Connection connection = databaseManager.getConnection()) {
            return countLogs(connection, buildLogFilter(connection, filter == null ? HistoryFilter.empty() : filter));
        } catch (SQLException exception) {
            logError("Log count", exception);
            return 0;
        }
    }

    public int cleanupOldReports() {
        if (!getBooleanSetting("cleanup.enabled", true)) {
            return 0;
        }
        int reportDays = Math.max(1, getIntSetting("cleanup.resolved-days", 30));
        int logDays = Math.max(reportDays, getIntSetting("cleanup.logs-days", 90));
        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long now = databaseNow(connection);
                long reportCutoff = now - reportDays * 86_400_000L;
                long logCutoff = now - logDays * 86_400_000L;
                recoverInterruptedPunishments(connection);
                int removed = execute(connection, "DELETE FROM case_review_leases WHERE expires_at < ?", now);
                removed += execute(connection, "DELETE FROM report_notes WHERE case_id IN (SELECT id FROM report_cases WHERE status = ? AND resolved_at < ?)", STATUS_RESOLVED, reportCutoff);
                removed += execute(connection, "DELETE FROM reports WHERE case_id IN (SELECT id FROM report_cases WHERE status = ? AND resolved_at < ?)", STATUS_RESOLVED, reportCutoff);
                removed += execute(connection, "DELETE FROM report_cases WHERE status = ? AND resolved_at < ?", STATUS_RESOLVED, reportCutoff);
                removed += execute(connection, "DELETE FROM report_logs WHERE timestamp < ?", logCutoff);
                connection.commit();
                invalidateCaches();
                return removed;
            } catch (SQLException exception) {
                rollback(connection);
                logError("Cleanup", exception);
                return 0;
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            logError("Cleanup", exception);
            return 0;
        } finally {
            mutationLock.unlock();
        }
    }

    public void recoverInterruptedPunishments() {
        mutationLock.lock();
        try (Connection connection = databaseManager.getConnection()) {
            recoverInterruptedPunishments(connection);
            invalidateCaches();
        } catch (SQLException exception) {
            logError("Punishment recovery", exception);
        } finally {
            mutationLock.unlock();
        }
    }

    public enum ModeratorNoteUpdateResult {
        SUCCESS,
        NO_ACTIVE_REPORTS,
        LIMIT_REACHED,
        ERROR
    }

    public enum SubmissionStatus {
        SUCCESS,
        DUPLICATE,
        ERROR
    }

    public enum ReviewClaimStatus {
        ACQUIRED,
        HELD_BY_OTHER,
        CASE_NOT_OPEN,
        NOT_FOUND,
        ERROR
    }

    public static final class SubmissionResult {

        private final SubmissionStatus status;
        private final long caseId;

        private SubmissionResult(SubmissionStatus status, long caseId) {
            this.status = status;
            this.caseId = caseId;
        }

        public static SubmissionResult success(long caseId) {
            return new SubmissionResult(SubmissionStatus.SUCCESS, caseId);
        }

        public static SubmissionResult duplicate(long caseId) {
            return new SubmissionResult(SubmissionStatus.DUPLICATE, caseId);
        }

        public static SubmissionResult error() {
            return new SubmissionResult(SubmissionStatus.ERROR, 0L);
        }

        public SubmissionStatus getStatus() {
            return status;
        }

        public long getCaseId() {
            return caseId;
        }
    }

    public static final class ReviewLease {

        private final long caseId;
        private final UUID moderatorUuid;
        private final String moderatorName;
        private final String serverId;
        private final UUID token;
        private final long expiresAt;

        private ReviewLease(long caseId, UUID moderatorUuid, String moderatorName, String serverId, UUID token, long expiresAt) {
            this.caseId = caseId;
            this.moderatorUuid = moderatorUuid;
            this.moderatorName = moderatorName;
            this.serverId = serverId;
            this.token = token;
            this.expiresAt = expiresAt;
        }

        public long getCaseId() {
            return caseId;
        }

        public UUID getModeratorUuid() {
            return moderatorUuid;
        }

        public String getModeratorName() {
            return moderatorName;
        }

        public String getServerId() {
            return serverId;
        }

        public UUID getToken() {
            return token;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        private ReviewLease withExpiry(long expiry) {
            return new ReviewLease(caseId, moderatorUuid, moderatorName, serverId, token, expiry);
        }
    }

    public static final class ReviewClaimResult {

        private final ReviewClaimStatus status;
        private final ReviewLease lease;
        private final String owner;
        private final String serverId;

        private ReviewClaimResult(ReviewClaimStatus status, ReviewLease lease, String owner, String serverId) {
            this.status = status;
            this.lease = lease;
            this.owner = owner;
            this.serverId = serverId;
        }

        public static ReviewClaimResult acquired(ReviewLease lease) {
            return new ReviewClaimResult(ReviewClaimStatus.ACQUIRED, lease, null, null);
        }

        public static ReviewClaimResult held(String owner, String serverId) {
            return new ReviewClaimResult(ReviewClaimStatus.HELD_BY_OTHER, null, owner, serverId);
        }

        public static ReviewClaimResult caseNotOpen() {
            return new ReviewClaimResult(ReviewClaimStatus.CASE_NOT_OPEN, null, null, null);
        }

        public static ReviewClaimResult notFound() {
            return new ReviewClaimResult(ReviewClaimStatus.NOT_FOUND, null, null, null);
        }

        public static ReviewClaimResult error() {
            return new ReviewClaimResult(ReviewClaimStatus.ERROR, null, null, null);
        }

        public ReviewClaimStatus getStatus() {
            return status;
        }

        public ReviewLease getLease() {
            return lease;
        }

        public String getOwner() {
            return owner;
        }

        public String getServerId() {
            return serverId;
        }

        public boolean isAcquired() {
            return status == ReviewClaimStatus.ACQUIRED;
        }
    }

    private void upsertIdentity(Connection connection, String uuid, String name, String nameKey, long now) throws SQLException {
        String sql = databaseManager.getStorageType().equals("mysql")
                ? "INSERT INTO player_identities (player_uuid, player_name, name_key, updated_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), name_key = VALUES(name_key), updated_at = VALUES(updated_at)"
                : "INSERT INTO player_identities (player_uuid, player_name, name_key, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, name_key = excluded.name_key, updated_at = excluded.updated_at";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, uuid);
            insert.setString(2, name);
            insert.setString(3, nameKey);
            insert.setLong(4, now);
            insert.executeUpdate();
        }
    }

    private void upsertObservedIdentity(Connection connection, UUID uuid, String name, String nameKey, long now) throws SQLException {
        String value = uuid.toString();
        upsertIdentity(connection, value, name, nameKey, now);
        upsertNameHistory(connection, value, name, nameKey, now);
    }

    private void upsertNameHistory(Connection connection, String uuid, String name, String nameKey, long now) throws SQLException {
        String sql = databaseManager.getStorageType().equals("mysql")
                ? "INSERT INTO player_name_history (player_uuid, name_key, display_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), last_seen_at = VALUES(last_seen_at)"
                : "INSERT INTO player_name_history (player_uuid, name_key, display_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(player_uuid, name_key) DO UPDATE SET display_name = excluded.display_name, last_seen_at = excluded.last_seen_at";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, uuid);
            insert.setString(2, nameKey);
            insert.setString(3, name);
            insert.setLong(4, now);
            insert.setLong(5, now);
            insert.executeUpdate();
        }
    }

    private void backfillActiveIdentity(Connection connection, String uuid, String name, String nameKey, long now) throws SQLException {
        String identity = "u:" + uuid.toLowerCase(Locale.ROOT);
        List<ActiveCaseIdentityRow> cases = new ArrayList<>();
        String selectSql = "SELECT id, reported_uuid, status, created_at, updated_at FROM report_cases WHERE status IN (?, ?) AND (reported_uuid = ? OR (reported_uuid IS NULL AND reported_key = ?)) ORDER BY CASE WHEN status = ? THEN 0 ELSE 1 END, CASE WHEN EXISTS (SELECT 1 FROM case_review_leases bl WHERE bl.case_id = report_cases.id AND bl.expires_at >= ?) THEN 0 ELSE 1 END, CASE WHEN reported_uuid IS NOT NULL THEN 0 ELSE 1 END, id ASC"
                + (databaseManager.getStorageType().equals("mysql") ? " FOR UPDATE" : "");
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, STATUS_PENDING);
            select.setString(2, STATUS_PROCESSING);
            select.setString(3, uuid);
            select.setString(4, nameKey);
            select.setString(5, STATUS_PROCESSING);
            select.setLong(6, now);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    cases.add(new ActiveCaseIdentityRow(
                            resultSet.getLong("id"),
                            resultSet.getString("status"),
                            resultSet.getLong("created_at"),
                            resultSet.getLong("updated_at")
                    ));
                }
            }
        }
        if (cases.isEmpty()) {
            return;
        }
        ActiveCaseIdentityRow target = cases.get(0);
        for (int index = 1; index < cases.size(); index++) {
            try (PreparedStatement clear = connection.prepareStatement("UPDATE report_cases SET active_key = NULL WHERE id = ?")) {
                clear.setLong(1, cases.get(index).id);
                clear.executeUpdate();
            }
        }
        long createdAt = target.createdAt;
        long updatedAt = Math.max(now, target.updatedAt);
        for (int index = 1; index < cases.size(); index++) {
            ActiveCaseIdentityRow source = cases.get(index);
            createdAt = Math.min(createdAt, source.createdAt);
            updatedAt = Math.max(updatedAt, source.updatedAt);
            mergeActiveCase(connection, target.id, source.id, name, nameKey, uuid, target.status);
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE report_cases SET reported_uuid = ?, reported_name = ?, reported_key = ?, identity_key = ?, active_key = ?, created_at = ?, updated_at = ? WHERE id = ?")) {
            update.setString(1, uuid);
            update.setString(2, name);
            update.setString(3, nameKey);
            update.setString(4, identity);
            update.setString(5, identity);
            update.setLong(6, createdAt);
            update.setLong(7, updatedAt);
            update.setLong(8, target.id);
            update.executeUpdate();
        }
        try (PreparedStatement updateReports = connection.prepareStatement(
                "UPDATE reports SET reported_uuid = ?, reported = ?, reported_key = ?, status = ? WHERE case_id = ?")) {
            updateReports.setString(1, uuid);
            updateReports.setString(2, name);
            updateReports.setString(3, nameKey);
            updateReports.setString(4, target.status);
            updateReports.setLong(5, target.id);
            updateReports.executeUpdate();
        }
    }

    private void mergeActiveCase(Connection connection, long targetCaseId, long sourceCaseId, String name, String nameKey, String uuid, String status) throws SQLException {
        List<long[]> sourceReports = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT id, reporter_identity_key FROM reports WHERE case_id = ? ORDER BY id ASC")) {
            select.setLong(1, sourceCaseId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    sourceReports.add(new long[]{resultSet.getLong("id"), sourceCaseId});
                }
            }
        }
        for (long[] sourceReport : sourceReports) {
            String reporterIdentity;
            try (PreparedStatement select = connection.prepareStatement("SELECT reporter_identity_key FROM reports WHERE id = ?")) {
                select.setLong(1, sourceReport[0]);
                try (ResultSet resultSet = select.executeQuery()) {
                    reporterIdentity = resultSet.next() ? resultSet.getString(1) : null;
                }
            }
            boolean duplicate = false;
            if (reporterIdentity != null) {
                try (PreparedStatement select = connection.prepareStatement("SELECT 1 FROM reports WHERE case_id = ? AND reporter_identity_key = ?")) {
                    select.setLong(1, targetCaseId);
                    select.setString(2, reporterIdentity);
                    try (ResultSet resultSet = select.executeQuery()) {
                        duplicate = resultSet.next();
                    }
                }
            }
            if (duplicate) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM reports WHERE id = ?")) {
                    delete.setLong(1, sourceReport[0]);
                    delete.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement("UPDATE reports SET case_id = ?, reported = ?, reported_key = ?, reported_uuid = ?, status = ? WHERE id = ?")) {
                    update.setLong(1, targetCaseId);
                    update.setString(2, name);
                    update.setString(3, nameKey);
                    update.setString(4, uuid);
                    update.setString(5, status);
                    update.setLong(6, sourceReport[0]);
                    update.executeUpdate();
                }
            }
        }
        execute(connection, "UPDATE report_notes SET case_id = ?, reported = ?, reported_key = ?, status = ? WHERE case_id = ?", targetCaseId, name, nameKey, status, sourceCaseId);
        execute(connection, "UPDATE report_logs SET case_id = ?, reported = ?, reported_key = ?, reported_uuid = ? WHERE case_id = ?", targetCaseId, name, nameKey, uuid, sourceCaseId);
        execute(connection, "DELETE FROM case_review_leases WHERE case_id = ?", sourceCaseId);
        execute(connection, "DELETE FROM report_cases WHERE id = ?", sourceCaseId);
    }

    private void backfillReporterIdentity(Connection connection, String uuid, String nameKey) throws SQLException {
        String reporterIdentity = "u:" + uuid.toLowerCase(Locale.ROOT);
        List<long[]> reports = new ArrayList<>();
        String selectSql = "SELECT id, case_id FROM reports WHERE reporter_uuid IS NULL AND reporter_key = ? AND status IN (?, ?) ORDER BY id ASC"
                + (databaseManager.getStorageType().equals("mysql") ? " FOR UPDATE" : "");
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, nameKey);
            select.setString(2, STATUS_PENDING);
            select.setString(3, STATUS_PROCESSING);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    reports.add(new long[]{resultSet.getLong("id"), resultSet.getLong("case_id")});
                }
            }
        }
        for (long[] report : reports) {
            boolean duplicate;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT 1 FROM reports WHERE case_id = ? AND reporter_identity_key = ? AND id <> ?")) {
                select.setLong(1, report[1]);
                select.setString(2, reporterIdentity);
                select.setLong(3, report[0]);
                try (ResultSet resultSet = select.executeQuery()) {
                    duplicate = resultSet.next();
                }
            }
            if (duplicate) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM reports WHERE id = ?")) {
                    delete.setLong(1, report[0]);
                    delete.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE reports SET reporter_uuid = ?, reporter_identity_key = ? WHERE id = ?")) {
                    update.setString(1, uuid);
                    update.setString(2, reporterIdentity);
                    update.setLong(3, report[0]);
                    update.executeUpdate();
                }
            }
        }
    }

    private UUID findIdentityUuid(Connection connection, String playerName) throws SQLException {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        UUID direct = parseUuid(playerName);
        if (direct != null) {
            return direct;
        }
        String sql = "SELECT player_uuid FROM player_name_history WHERE name_key = ? ORDER BY last_seen_at DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizePlayer(playerName));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? parseUuid(resultSet.getString(1)) : null;
            }
        }
    }

    private long findOrCreateOpenCase(Connection connection, UUID targetUuid, String name, String nameKey, String identityKey, long now) throws SQLException {
        OptionalLong existing = findOpenCaseId(connection, targetUuid, name, true);
        if (existing.isPresent()) {
            return existing.getAsLong();
        }
        String sql = "INSERT INTO report_cases (identity_key, active_key, reported_name, reported_key, reported_uuid, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, identityKey);
            statement.setString(2, identityKey);
            statement.setString(3, name);
            statement.setString(4, nameKey);
            setUuid(statement, 5, targetUuid);
            statement.setString(6, STATUS_PENDING);
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Case insert did not return an id");
                }
                return keys.getLong(1);
            }
        } catch (SQLException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            OptionalLong raced = findOpenCaseId(connection, targetUuid, name, true);
            if (raced.isPresent()) {
                return raced.getAsLong();
            }
            throw exception;
        }
    }

    private OptionalLong findOpenCaseId(Connection connection, UUID targetUuid, String targetName, boolean lock) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id FROM report_cases WHERE status = ? AND ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(STATUS_PENDING);
        appendIdentityPredicate(sql, parameters, "reported_uuid", "reported_key", targetUuid, targetName);
        sql.append(" ORDER BY updated_at DESC LIMIT 1");
        if (lock && databaseManager.getStorageType().equals("mysql")) {
            sql.append(" FOR UPDATE");
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? OptionalLong.of(resultSet.getLong(1)) : OptionalLong.empty();
            }
        }
    }

    private boolean hasReporterInCase(Connection connection, long caseId, String reporterIdentityKey, boolean lock) throws SQLException {
        String sql = "SELECT id FROM reports WHERE case_id = ? AND reporter_identity_key = ?"
                + (lock && databaseManager.getStorageType().equals("mysql") ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, caseId);
            statement.setString(2, reporterIdentityKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private CaseFilterSql buildCaseFilter(Connection connection, String status, HistoryFilter filter) throws SQLException {
        StringBuilder clause = new StringBuilder("c.status = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(status);
        if (filter.getPlayer() != null) {
            clause.append(" AND ");
            appendIdentityPredicate(clause, parameters, "c.reported_uuid", "c.reported_key", findIdentityUuid(connection, filter.getPlayer()), filter.getPlayer());
        }
        if (filter.getModerator() != null) {
            UUID moderatorUuid = findIdentityUuid(connection, filter.getModerator());
            StringBuilder resolutionPredicate = new StringBuilder();
            List<Object> resolutionParameters = new ArrayList<>();
            appendIdentityPredicate(resolutionPredicate, resolutionParameters, "c.resolved_by_uuid", "LOWER(c.resolved_by)", moderatorUuid, filter.getModerator());
            StringBuilder logPredicate = new StringBuilder();
            List<Object> logParameters = new ArrayList<>();
            appendIdentityPredicate(logPredicate, logParameters, "mf.moderator_uuid", "LOWER(mf.moderator)", moderatorUuid, filter.getModerator());
            clause.append(" AND (").append(resolutionPredicate).append(" OR EXISTS (SELECT 1 FROM report_logs mf WHERE mf.case_id = c.id AND ").append(logPredicate).append("))");
            parameters.addAll(resolutionParameters);
            parameters.addAll(logParameters);
        }
        if (filter.getAction() != null) {
            clause.append(" AND (c.action = ? OR EXISTS (SELECT 1 FROM report_logs af WHERE af.case_id = c.id AND af.action = ?))");
            parameters.add(filter.getAction().toLowerCase(Locale.ROOT));
            parameters.add(filter.getAction().toLowerCase(Locale.ROOT));
        }
        if (filter.getReasonKey() != null) {
            clause.append(" AND EXISTS (SELECT 1 FROM reports rf WHERE rf.case_id = c.id AND (rf.reason_key = ? OR LOWER(rf.reason) = ?))");
            parameters.add(filter.getReasonKey());
            parameters.add(filter.getReason() == null ? filter.getReasonKey() : filter.getReason().toLowerCase(Locale.ROOT));
        }
        if (filter.getFromTimestamp() > 0L) {
            clause.append(" AND COALESCE(c.resolved_at, c.updated_at) >= ?");
            parameters.add(filter.getFromTimestamp());
        }
        if (filter.getToTimestamp() > 0L) {
            clause.append(" AND COALESCE(c.resolved_at, c.updated_at) < ?");
            parameters.add(filter.getToTimestamp());
        }
        return new CaseFilterSql(clause.toString(), parameters);
    }

    private int countCases(Connection connection, CaseFilterSql filter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM report_cases c WHERE " + filter.clause)) {
            bind(statement, filter.parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private List<Long> loadCaseIds(Connection connection, CaseFilterSql filter, String status, int page, int size) throws SQLException {
        String order = STATUS_PENDING.equals(status)
                ? "(SELECT COUNT(*) FROM reports rc WHERE rc.case_id = c.id) DESC, c.updated_at DESC, c.id DESC"
                : "c.resolved_at DESC, c.id DESC";
        String sql = "SELECT c.id FROM report_cases c WHERE " + filter.clause + " ORDER BY " + order + " LIMIT ? OFFSET ?";
        List<Object> parameters = new ArrayList<>(filter.parameters);
        parameters.add(size);
        parameters.add(page * size);
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getLong(1));
                }
            }
        }
        return ids;
    }

    private List<ReportCase> loadCases(Connection connection, List<Long> caseIds) throws SQLException {
        if (caseIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ReportCaseBuilder> builders = new LinkedHashMap<>();
        for (long id : caseIds) {
            builders.put(id, null);
        }
        long now = databaseNow(connection);
        String sql = "SELECT c.*, COALESCE(pi.player_name, c.reported_name) AS current_name, "
                + "l.owner_uuid AS lease_owner_uuid, l.owner_name AS lease_owner_name, l.server_id AS lease_server, l.review_status AS lease_status, l.expires_at AS lease_expires "
                + "FROM report_cases c LEFT JOIN player_identities pi ON pi.player_uuid = c.reported_uuid "
                + "LEFT JOIN case_review_leases l ON l.case_id = c.id AND l.expires_at >= ? WHERE c.id IN (" + placeholders(caseIds.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            for (int index = 0; index < caseIds.size(); index++) {
                statement.setLong(index + 2, caseIds.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long id = resultSet.getLong("id");
                    builders.put(id, new ReportCaseBuilder(resultSet));
                }
            }
        }
        loadCaseReports(connection, builders);
        loadCaseNotes(connection, builders);
        List<ReportCase> result = new ArrayList<>();
        for (long id : caseIds) {
            ReportCaseBuilder builder = builders.get(id);
            if (builder != null) {
                result.add(builder.build());
            }
        }
        return result;
    }

    private void loadCaseReports(Connection connection, Map<Long, ReportCaseBuilder> builders) throws SQLException {
        String sql = "SELECT r.id, r.case_id, r.reporter, r.reporter_uuid, r.reported, r.reported_uuid, r.reason, r.evidence_url, r.timestamp, "
                + "c.status, c.resolved_by, c.resolved_at, c.action, c.resolution_reason, r.moderator_note, r.moderator_note_by "
                + "FROM reports r JOIN report_cases c ON c.id = r.case_id WHERE r.case_id IN (" + placeholders(builders.size()) + ") ORDER BY r.timestamp DESC, r.id DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindLongs(statement, new ArrayList<>(builders.keySet()));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ReportCaseBuilder builder = builders.get(resultSet.getLong("case_id"));
                    if (builder != null) {
                        builder.reports.add(createReport(resultSet));
                    }
                }
            }
        }
    }

    private void loadCaseNotes(Connection connection, Map<Long, ReportCaseBuilder> builders) throws SQLException {
        String sql = "SELECT id, case_id, reported, moderator, moderator_uuid, note, timestamp, status FROM report_notes "
                + "WHERE case_id IN (" + placeholders(builders.size()) + ") ORDER BY timestamp ASC, id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindLongs(statement, new ArrayList<>(builders.keySet()));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ReportCaseBuilder builder = builders.get(resultSet.getLong("case_id"));
                    if (builder != null) {
                        builder.notes.add(createModeratorNote(resultSet));
                    }
                }
            }
        }
    }

    private String lockCaseStatus(Connection connection, long caseId) throws SQLException {
        String sql = "SELECT status FROM report_cases WHERE id = ?"
                + (databaseManager.getStorageType().equals("mysql") ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, caseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private LeaseRow loadLease(Connection connection, long caseId, boolean lock) throws SQLException {
        String sql = "SELECT owner_uuid, owner_name, server_id, lease_token, review_status, expires_at FROM case_review_leases WHERE case_id = ?"
                + (lock && databaseManager.getStorageType().equals("mysql") ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, caseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? new LeaseRow(resultSet) : null;
            }
        }
    }

    private void insertLease(Connection connection, long caseId, UUID moderatorUuid, String moderatorName, UUID token, String status, long now, long expiresAt) throws SQLException {
        String sql = "INSERT INTO case_review_leases (case_id, owner_uuid, owner_name, server_id, lease_token, review_status, expires_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, caseId);
            statement.setString(2, moderatorUuid.toString());
            statement.setString(3, trimToLength(moderatorName, 64));
            statement.setString(4, getServerId());
            statement.setString(5, token.toString());
            statement.setString(6, status);
            statement.setLong(7, expiresAt);
            statement.setLong(8, now);
            statement.executeUpdate();
        }
    }

    private void updateLease(Connection connection, long caseId, UUID moderatorUuid, String moderatorName, UUID token, String status, long now, long expiresAt) throws SQLException {
        String sql = "UPDATE case_review_leases SET owner_uuid = ?, owner_name = ?, server_id = ?, lease_token = ?, review_status = ?, expires_at = ?, updated_at = ? WHERE case_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, moderatorUuid.toString());
            statement.setString(2, trimToLength(moderatorName, 64));
            statement.setString(3, getServerId());
            statement.setString(4, token.toString());
            statement.setString(5, status);
            statement.setLong(6, expiresAt);
            statement.setLong(7, now);
            statement.setLong(8, caseId);
            statement.executeUpdate();
        }
    }

    private boolean validateLease(Connection connection, ReviewLease lease, long now, boolean lock) throws SQLException {
        LeaseRow row = loadLease(connection, lease.caseId, lock);
        return row != null
                && row.ownerUuid.equals(lease.moderatorUuid)
                && row.serverId.equals(lease.serverId)
                && row.token.equals(lease.token)
                && row.expiresAt >= now;
    }

    private void resolveCaseRows(Connection connection, ReviewLease lease, String action, String resolutionReason, long now) throws SQLException {
        try (PreparedStatement updateCase = connection.prepareStatement(
                "UPDATE report_cases SET status = ?, active_key = NULL, updated_at = ?, resolved_by = ?, resolved_by_uuid = ?, resolved_at = ?, action = ?, resolution_reason = ? WHERE id = ?")) {
            updateCase.setString(1, STATUS_RESOLVED);
            updateCase.setLong(2, now);
            updateCase.setString(3, lease.moderatorName);
            updateCase.setString(4, lease.moderatorUuid.toString());
            updateCase.setLong(5, now);
            updateCase.setString(6, action);
            updateCase.setString(7, resolutionReason);
            updateCase.setLong(8, lease.caseId);
            updateCase.executeUpdate();
        }
        updateLegacyReportStatus(connection, lease.caseId, STATUS_RESOLVED, lease, action, resolutionReason, now);
        try (PreparedStatement notes = connection.prepareStatement("UPDATE report_notes SET status = ? WHERE case_id = ?")) {
            notes.setString(1, STATUS_RESOLVED);
            notes.setLong(2, lease.caseId);
            notes.executeUpdate();
        }
        String reported = getCaseReportedName(connection, lease.caseId);
        insertLog(connection, lease.caseId, lease.moderatorName, lease.moderatorUuid,
                ACTION_PUNISHED.equalsIgnoreCase(action) ? "punish" : "close",
                reported, resolveCaseUuid(connection, lease.caseId), resolutionReason, null, now);
        try (PreparedStatement deleteLease = connection.prepareStatement("DELETE FROM case_review_leases WHERE case_id = ? AND lease_token = ?")) {
            deleteLease.setLong(1, lease.caseId);
            deleteLease.setString(2, lease.token.toString());
            deleteLease.executeUpdate();
        }
    }

    private void updateLegacyReportStatus(Connection connection, long caseId, String status, ReviewLease lease, String action, String reason, long now) throws SQLException {
        String sql = "UPDATE reports SET status = ?, resolved_by = ?, resolved_at = ?, action = ?, resolution_reason = ? WHERE case_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, lease.moderatorName);
            statement.setLong(3, now);
            statement.setString(4, action);
            statement.setString(5, reason);
            statement.setLong(6, caseId);
            statement.executeUpdate();
        }
    }

    private void clearLegacyResolution(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE reports SET status = ?, resolved_by = NULL, resolved_at = NULL, action = NULL, resolution_reason = NULL WHERE case_id = ?")) {
            statement.setString(1, STATUS_PENDING);
            statement.setLong(2, caseId);
            statement.executeUpdate();
        }
    }

    private void updateLegacyModeratorNote(Connection connection, long caseId, String note, String moderator) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE reports SET moderator_note = ?, moderator_note_by = ? WHERE case_id = ?")) {
            statement.setString(1, note);
            statement.setString(2, moderator);
            statement.setLong(3, caseId);
            statement.executeUpdate();
        }
    }

    private int countNotes(Connection connection, long caseId, boolean lock) throws SQLException {
        String sql = "SELECT id FROM report_notes WHERE case_id = ?"
                + (lock && databaseManager.getStorageType().equals("mysql") ? " FOR UPDATE" : "");
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, caseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    count++;
                }
            }
        }
        return count;
    }

    private void insertLog(Connection connection, long caseId, String moderator, UUID moderatorUuid, String action, String reported, UUID reportedUuid, String reason, String note, long timestamp) throws SQLException {
        String sql = "INSERT INTO report_logs (case_id, moderator, moderator_uuid, action, reported, reported_key, reported_uuid, reason, note, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (caseId > 0L) {
                statement.setLong(1, caseId);
            } else {
                statement.setNull(1, Types.BIGINT);
            }
            statement.setString(2, trimToLength(moderator, 64));
            setUuid(statement, 3, moderatorUuid);
            statement.setString(4, trimToLength(action, 32));
            statement.setString(5, trimToLength(reported, 64));
            statement.setString(6, normalizePlayer(reported));
            setUuid(statement, 7, reportedUuid);
            statement.setString(8, trimNullable(reason, 512));
            statement.setString(9, trimNullable(note, 512));
            statement.setLong(10, timestamp);
            statement.executeUpdate();
        }
    }

    private LogFilterSql buildLogFilter(Connection connection, HistoryFilter filter) throws SQLException {
        StringBuilder clause = new StringBuilder("1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (filter.getPlayer() != null) {
            clause.append(" AND ");
            appendIdentityPredicate(clause, parameters, "l.reported_uuid", "l.reported_key", findIdentityUuid(connection, filter.getPlayer()), filter.getPlayer());
        }
        if (filter.getModerator() != null) {
            clause.append(" AND ");
            appendIdentityPredicate(clause, parameters, "l.moderator_uuid", "LOWER(l.moderator)", findIdentityUuid(connection, filter.getModerator()), filter.getModerator());
        }
        if (filter.getAction() != null) {
            String action = filter.getAction().toLowerCase(Locale.ROOT);
            String logAction = ACTION_CLOSED.equals(action) ? "close" : ACTION_PUNISHED.equals(action) ? "punish" : action;
            clause.append(" AND (l.action = ? OR l.action = ? OR c.action = ?)");
            parameters.add(action);
            parameters.add(logAction);
            parameters.add(action);
        }
        if (filter.getReasonKey() != null) {
            clause.append(" AND (LOWER(COALESCE(l.reason, '')) = ? OR EXISTS (SELECT 1 FROM reports rf WHERE rf.case_id = l.case_id AND (rf.reason_key = ? OR LOWER(rf.reason) = ?)))");
            parameters.add(filter.getReason() == null ? filter.getReasonKey() : filter.getReason().toLowerCase(Locale.ROOT));
            parameters.add(filter.getReasonKey());
            parameters.add(filter.getReason() == null ? filter.getReasonKey() : filter.getReason().toLowerCase(Locale.ROOT));
        }
        if (filter.getFromTimestamp() > 0L) {
            clause.append(" AND l.timestamp >= ?");
            parameters.add(filter.getFromTimestamp());
        }
        if (filter.getToTimestamp() > 0L) {
            clause.append(" AND l.timestamp < ?");
            parameters.add(filter.getToTimestamp());
        }
        return new LogFilterSql(clause.toString(), parameters);
    }

    private int countLogs(Connection connection, LogFilterSql filter) throws SQLException {
        String sql = "SELECT COUNT(*) FROM report_logs l LEFT JOIN report_cases c ON c.id = l.case_id WHERE " + filter.clause;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, filter.parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private Report createReport(ResultSet resultSet) throws SQLException {
        return new Report(
                resultSet.getInt("id"),
                resultSet.getLong("case_id"),
                resultSet.getString("reporter"),
                parseUuid(resultSet.getString("reporter_uuid")),
                resultSet.getString("reported"),
                parseUuid(resultSet.getString("reported_uuid")),
                resultSet.getString("reason"),
                resultSet.getString("evidence_url"),
                resultSet.getLong("timestamp"),
                resultSet.getString("status"),
                resultSet.getString("resolved_by"),
                resultSet.getLong("resolved_at"),
                resultSet.getString("action"),
                resultSet.getString("resolution_reason"),
                resultSet.getString("moderator_note"),
                resultSet.getString("moderator_note_by"),
                List.of()
        );
    }

    private ModeratorNote createModeratorNote(ResultSet resultSet) throws SQLException {
        return new ModeratorNote(
                resultSet.getInt("id"),
                resultSet.getLong("case_id"),
                resultSet.getString("reported"),
                resultSet.getString("moderator"),
                parseUuid(resultSet.getString("moderator_uuid")),
                resultSet.getString("note"),
                resultSet.getLong("timestamp"),
                resultSet.getString("status")
        );
    }

    private ReportLog createReportLog(ResultSet resultSet) throws SQLException {
        return new ReportLog(
                resultSet.getInt("id"),
                resultSet.getLong("case_id"),
                resultSet.getString("moderator"),
                parseUuid(resultSet.getString("moderator_uuid")),
                resultSet.getString("action"),
                resultSet.getString("reported"),
                parseUuid(resultSet.getString("reported_uuid")),
                resultSet.getString("reason"),
                resultSet.getString("note"),
                resultSet.getLong("timestamp"),
                resultSet.getString("case_action"),
                resultSet.getLong("case_resolved_at")
        );
    }

    private String getCaseReportedName(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT reported_name FROM report_cases WHERE id = ?")) {
            statement.setLong(1, caseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : "-";
            }
        }
    }

    private UUID resolveCaseUuid(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT reported_uuid FROM report_cases WHERE id = ?")) {
            statement.setLong(1, caseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? parseUuid(resultSet.getString(1)) : null;
            }
        }
    }

    private OptionalLong findCaseIdForLocalAction(String reported) {
        UUID uuid = resolveKnownPlayerUuid(reported);
        StringBuilder sql = new StringBuilder("SELECT id FROM report_cases WHERE status IN (?, ?) AND ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(STATUS_PENDING);
        parameters.add(STATUS_PROCESSING);
        appendIdentityPredicate(sql, parameters, "reported_uuid", "reported_key", uuid, reported);
        sql.append(" ORDER BY updated_at DESC LIMIT 1");
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? OptionalLong.of(resultSet.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException exception) {
            logError("Local action case lookup", exception);
            return OptionalLong.empty();
        }
    }

    private String getLeaseOwner(long caseId, boolean requireActive) {
        try (Connection connection = databaseManager.getConnection()) {
            LeaseRow row = loadLease(connection, caseId, false);
            long now = databaseNow(connection);
            return row == null || requireActive && row.expiresAt < now ? null : row.ownerName;
        } catch (SQLException exception) {
            return null;
        }
    }

    private ReviewLease findLocalLeaseByModerator(String moderator) {
        for (ReviewLease lease : localLeases.values()) {
            if (lease.moderatorName.equalsIgnoreCase(moderator)) {
                return lease;
            }
        }
        return null;
    }

    private void recoverInterruptedPunishments(Connection connection) throws SQLException {
        long cutoff = databaseNow(connection) - Math.max(30L, getLongSetting("actions.processing-timeout-seconds", 120L)) * 1_000L;
        List<Long> caseIds = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM report_cases WHERE status = ? AND updated_at < ?")) {
            select.setString(1, STATUS_PROCESSING);
            select.setLong(2, cutoff);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    caseIds.add(resultSet.getLong(1));
                }
            }
        }
        for (long caseId : caseIds) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE report_cases SET status = ?, active_key = identity_key, updated_at = ?, resolved_by = NULL, resolved_by_uuid = NULL, resolved_at = NULL, action = NULL, resolution_reason = NULL WHERE id = ?")) {
                update.setString(1, STATUS_PENDING);
                update.setLong(2, databaseNow(connection));
                update.setLong(3, caseId);
                update.executeUpdate();
            }
            clearLegacyResolution(connection, caseId);
        }
    }

    private long databaseNow(Connection connection) throws SQLException {
        if (!databaseManager.getStorageType().equals("mysql")) {
            return System.currentTimeMillis();
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)")) {
            return resultSet.next() ? resultSet.getLong(1) : System.currentTimeMillis();
        }
    }

    private void appendIdentityPredicate(StringBuilder sql, List<Object> parameters, String uuidColumn, String nameColumn, UUID uuid, String name) {
        if (uuid != null) {
            sql.append("(").append(uuidColumn).append(" = ? OR ").append(nameColumn).append(" = ?)");
            parameters.add(uuid.toString());
            parameters.add(normalizePlayer(name));
        } else {
            sql.append(nameColumn).append(" = ?");
            parameters.add(normalizePlayer(name));
        }
    }

    private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            int parameter = index + 1;
            if (value == null) {
                statement.setNull(parameter, Types.VARCHAR);
            } else if (value instanceof Integer) {
                statement.setInt(parameter, (Integer) value);
            } else if (value instanceof Long) {
                statement.setLong(parameter, (Long) value);
            } else {
                statement.setString(parameter, String.valueOf(value));
            }
        }
    }

    private void bindLongs(PreparedStatement statement, List<Long> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setLong(index + 1, values.get(index));
        }
    }

    private void setUuid(PreparedStatement statement, int parameter, UUID uuid) throws SQLException {
        if (uuid == null) {
            statement.setNull(parameter, Types.VARCHAR);
        } else {
            statement.setString(parameter, uuid.toString());
        }
    }

    private int execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, List.of(parameters));
            return statement.executeUpdate();
        }
    }

    private String placeholders(int count) {
        StringJoiner joiner = new StringJoiner(",");
        for (int index = 0; index < count; index++) {
            joiner.add("?");
        }
        return joiner.toString();
    }

    private String identityKey(UUID uuid, String name) {
        return uuid == null ? "n:" + normalizePlayer(name) : "u:" + uuid.toString().toLowerCase(Locale.ROOT);
    }

    private String normalizeReason(String reason) {
        return reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePlayer(String player) {
        return player == null ? "" : player.trim().toLowerCase(Locale.ROOT);
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private UUID legacyUuid(String name) {
        return UUID.nameUUIDFromBytes(("cloverreports:legacy:" + normalizePlayer(name)).getBytes(StandardCharsets.UTF_8));
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String trimToLength(String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maximumLength ? trimmed : trimmed.substring(0, maximumLength);
    }

    private String trimNullable(String value, int maximumLength) {
        return value == null || value.isBlank() ? null : trimToLength(value, maximumLength);
    }

    private boolean isConstraintViolation(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && state.startsWith("23")
                || exception.getMessage() != null && exception.getMessage().toLowerCase(Locale.ROOT).contains("unique");
    }

    private long getReviewTimeoutMillis() {
        return Math.max(30L, getLongSetting("review.timeout-seconds", 300L)) * 1_000L;
    }

    private String getServerId() {
        if (plugin == null) {
            return "test";
        }
        return plugin.getConfig().getString("server.id", plugin.getConfig().getString("server.name", "server"));
    }

    private boolean getBooleanSetting(String path, boolean fallback) {
        return plugin == null ? fallback : plugin.getConfig().getBoolean(path, fallback);
    }

    private int getIntSetting(String path, int fallback) {
        return plugin == null ? fallback : plugin.getConfig().getInt(path, fallback);
    }

    private long getLongSetting(String path, long fallback) {
        return plugin == null ? fallback : plugin.getConfig().getLong(path, fallback);
    }

    private void invalidateCaches() {
        suggestionCache.clear();
        countCache.clear();
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            logError("Transaction rollback", exception);
        }
    }

    private void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException exception) {
            logError("Connection state restore", exception);
        }
    }

    private void logError(String operation, SQLException exception) {
        logger.severe(operation + " error: " + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
    }

    private static final class ReportCaseBuilder {

        private final long id;
        private final String reportedName;
        private final UUID reportedUuid;
        private final String identityKey;
        private final String status;
        private final long createdAt;
        private final long updatedAt;
        private final String resolvedBy;
        private final UUID resolvedByUuid;
        private final long resolvedAt;
        private final String action;
        private final String resolutionReason;
        private final String reviewOwner;
        private final UUID reviewOwnerUuid;
        private final String reviewServer;
        private final String reviewStatus;
        private final long reviewExpiresAt;
        private final List<Report> reports;
        private final List<ModeratorNote> notes;

        private ReportCaseBuilder(ResultSet resultSet) throws SQLException {
            this.id = resultSet.getLong("id");
            this.reportedName = resultSet.getString("current_name");
            this.reportedUuid = parseUuidStatic(resultSet.getString("reported_uuid"));
            this.identityKey = resultSet.getString("identity_key");
            this.status = resultSet.getString("status");
            this.createdAt = resultSet.getLong("created_at");
            this.updatedAt = resultSet.getLong("updated_at");
            this.resolvedBy = resultSet.getString("resolved_by");
            this.resolvedByUuid = parseUuidStatic(resultSet.getString("resolved_by_uuid"));
            this.resolvedAt = resultSet.getLong("resolved_at");
            this.action = resultSet.getString("action");
            this.resolutionReason = resultSet.getString("resolution_reason");
            this.reviewOwner = resultSet.getString("lease_owner_name");
            this.reviewOwnerUuid = parseUuidStatic(resultSet.getString("lease_owner_uuid"));
            this.reviewServer = resultSet.getString("lease_server");
            this.reviewStatus = resultSet.getString("lease_status");
            this.reviewExpiresAt = resultSet.getLong("lease_expires");
            this.reports = new ArrayList<>();
            this.notes = new ArrayList<>();
        }

        private ReportCase build() {
            return new ReportCase(id, reportedName, reportedUuid, identityKey, status, createdAt, updatedAt, resolvedBy, resolvedByUuid, resolvedAt, action, resolutionReason, reviewOwner, reviewOwnerUuid, reviewServer, reviewStatus, reviewExpiresAt, reports, notes);
        }

        private static UUID parseUuidStatic(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    private static final class ActiveCaseIdentityRow {

        private final long id;
        private final String status;
        private final long createdAt;
        private final long updatedAt;

        private ActiveCaseIdentityRow(long id, String status, long createdAt, long updatedAt) {
            this.id = id;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    private static final class LeaseRow {

        private final UUID ownerUuid;
        private final String ownerName;
        private final String serverId;
        private final UUID token;
        private final String status;
        private final long expiresAt;

        private LeaseRow(ResultSet resultSet) throws SQLException {
            this.ownerUuid = UUID.fromString(resultSet.getString("owner_uuid"));
            this.ownerName = resultSet.getString("owner_name");
            this.serverId = resultSet.getString("server_id");
            this.token = UUID.fromString(resultSet.getString("lease_token"));
            this.status = resultSet.getString("review_status");
            this.expiresAt = resultSet.getLong("expires_at");
        }
    }

    private static final class CaseFilterSql {

        private final String clause;
        private final List<Object> parameters;

        private CaseFilterSql(String clause, List<Object> parameters) {
            this.clause = clause;
            this.parameters = parameters;
        }
    }

    private static final class LogFilterSql {

        private final String clause;
        private final List<Object> parameters;

        private LogFilterSql(String clause, List<Object> parameters) {
            this.clause = clause;
            this.parameters = parameters;
        }
    }

    private static final class CachedSuggestions {

        private final List<String> values;
        private final long expiresAt;

        private CachedSuggestions(List<String> values, long expiresAt) {
            this.values = Collections.unmodifiableList(values);
            this.expiresAt = expiresAt;
        }
    }

    private static final class CachedCount {

        private final int value;
        private final long expiresAt;

        private CachedCount(int value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
