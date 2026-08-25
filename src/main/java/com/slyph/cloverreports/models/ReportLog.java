package com.slyph.cloverreports.models;

import java.util.UUID;

public final class ReportLog {

    private final int id;
    private final long caseId;
    private final String moderator;
    private final UUID moderatorUuid;
    private final String action;
    private final String reported;
    private final UUID reportedUuid;
    private final String reason;
    private final String note;
    private final long timestamp;
    private final String caseAction;
    private final long caseResolvedAt;

    public ReportLog(int id, String moderator, String action, String reported, String reason, String note, long timestamp) {
        this(id, 0L, moderator, null, action, reported, null, reason, note, timestamp, null, 0L);
    }

    public ReportLog(int id, long caseId, String moderator, UUID moderatorUuid, String action, String reported, UUID reportedUuid, String reason, String note, long timestamp, String caseAction, long caseResolvedAt) {
        this.id = id;
        this.caseId = caseId;
        this.moderator = moderator;
        this.moderatorUuid = moderatorUuid;
        this.action = action;
        this.reported = reported;
        this.reportedUuid = reportedUuid;
        this.reason = reason;
        this.note = note;
        this.timestamp = timestamp;
        this.caseAction = caseAction;
        this.caseResolvedAt = caseResolvedAt;
    }

    public int getId() {
        return id;
    }

    public long getCaseId() {
        return caseId;
    }

    public String getModerator() {
        return moderator;
    }

    public UUID getModeratorUuid() {
        return moderatorUuid;
    }

    public String getAction() {
        return action;
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

    public String getNote() {
        return note;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getCaseAction() {
        return caseAction;
    }

    public long getCaseResolvedAt() {
        return caseResolvedAt;
    }
}
