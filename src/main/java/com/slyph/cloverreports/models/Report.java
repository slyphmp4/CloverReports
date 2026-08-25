package com.slyph.cloverreports.models;

import java.util.List;
import java.util.UUID;

public final class Report {

    private final int id;
    private final long caseId;
    private final String reporter;
    private final UUID reporterUuid;
    private final String reported;
    private final UUID reportedUuid;
    private final String reason;
    private final String evidenceUrl;
    private final long timestamp;
    private final String status;
    private final String resolvedBy;
    private final long resolvedAt;
    private final String action;
    private final String resolutionReason;
    private final String moderatorNote;
    private final String moderatorNoteBy;
    private final List<ModeratorNote> moderatorNotes;

    public Report(int id, String reporter, String reported, String reason, long timestamp, String status, String resolvedBy, long resolvedAt, String action, String moderatorNote, String moderatorNoteBy) {
        this(id, 0L, reporter, null, reported, null, reason, null, timestamp, status, resolvedBy, resolvedAt, action, null, moderatorNote, moderatorNoteBy, List.of());
    }

    public Report(int id, String reporter, String reported, String reason, long timestamp, String status, String resolvedBy, long resolvedAt, String action, String resolutionReason, String moderatorNote, String moderatorNoteBy) {
        this(id, 0L, reporter, null, reported, null, reason, null, timestamp, status, resolvedBy, resolvedAt, action, resolutionReason, moderatorNote, moderatorNoteBy, List.of());
    }

    public Report(int id, String reporter, String reported, String reason, long timestamp, String status, String resolvedBy, long resolvedAt, String action, String resolutionReason, String moderatorNote, String moderatorNoteBy, List<ModeratorNote> moderatorNotes) {
        this(id, 0L, reporter, null, reported, null, reason, null, timestamp, status, resolvedBy, resolvedAt, action, resolutionReason, moderatorNote, moderatorNoteBy, moderatorNotes);
    }

    public Report(int id, long caseId, String reporter, UUID reporterUuid, String reported, UUID reportedUuid, String reason, String evidenceUrl, long timestamp, String status, String resolvedBy, long resolvedAt, String action, String resolutionReason, String moderatorNote, String moderatorNoteBy, List<ModeratorNote> moderatorNotes) {
        this.id = id;
        this.caseId = caseId;
        this.reporter = reporter;
        this.reporterUuid = reporterUuid;
        this.reported = reported;
        this.reportedUuid = reportedUuid;
        this.reason = reason;
        this.evidenceUrl = evidenceUrl;
        this.timestamp = timestamp;
        this.status = status;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.action = action;
        this.resolutionReason = resolutionReason;
        this.moderatorNote = moderatorNote;
        this.moderatorNoteBy = moderatorNoteBy;
        this.moderatorNotes = moderatorNotes == null ? List.of() : List.copyOf(moderatorNotes);
    }

    public int getId() {
        return id;
    }

    public long getCaseId() {
        return caseId;
    }

    public String getReporter() {
        return reporter;
    }

    public UUID getReporterUuid() {
        return reporterUuid;
    }

    public String getReported() {
        return reported;
    }

    public UUID getReportedUuid() {
        return reportedUuid;
    }

    public String getReason() {
        return reason;
    }

    public String getEvidenceUrl() {
        return evidenceUrl;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getStatus() {
        return status;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public long getResolvedAt() {
        return resolvedAt;
    }

    public String getAction() {
        return action;
    }

    public String getResolutionReason() {
        return resolutionReason;
    }

    public String getModeratorNote() {
        return moderatorNote;
    }

    public String getModeratorNoteBy() {
        return moderatorNoteBy;
    }

    public List<ModeratorNote> getModeratorNotes() {
        return moderatorNotes;
    }

    public Report withModeratorNotes(List<ModeratorNote> notes) {
        return new Report(id, caseId, reporter, reporterUuid, reported, reportedUuid, reason, evidenceUrl, timestamp, status, resolvedBy, resolvedAt, action, resolutionReason, moderatorNote, moderatorNoteBy, notes);
    }
}
