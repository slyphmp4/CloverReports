package com.slyph.cloverreports.models;

import java.util.List;
import java.util.UUID;

public final class ReportCase {

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

    public ReportCase(long id, String reportedName, UUID reportedUuid, String identityKey, String status, long createdAt, long updatedAt, String resolvedBy, UUID resolvedByUuid, long resolvedAt, String action, String resolutionReason, String reviewOwner, UUID reviewOwnerUuid, String reviewServer, String reviewStatus, long reviewExpiresAt, List<Report> reports, List<ModeratorNote> notes) {
        this.id = id;
        this.reportedName = reportedName;
        this.reportedUuid = reportedUuid;
        this.identityKey = identityKey;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.resolvedBy = resolvedBy;
        this.resolvedByUuid = resolvedByUuid;
        this.resolvedAt = resolvedAt;
        this.action = action;
        this.resolutionReason = resolutionReason;
        this.reviewOwner = reviewOwner;
        this.reviewOwnerUuid = reviewOwnerUuid;
        this.reviewServer = reviewServer;
        this.reviewStatus = reviewStatus;
        this.reviewExpiresAt = reviewExpiresAt;
        this.reports = reports == null ? List.of() : List.copyOf(reports);
        this.notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public long getId() {
        return id;
    }

    public String getReportedName() {
        return reportedName;
    }

    public UUID getReportedUuid() {
        return reportedUuid;
    }

    public String getIdentityKey() {
        return identityKey;
    }

    public String getStatus() {
        return status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public UUID getResolvedByUuid() {
        return resolvedByUuid;
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

    public String getReviewOwner() {
        return reviewOwner;
    }

    public UUID getReviewOwnerUuid() {
        return reviewOwnerUuid;
    }

    public String getReviewServer() {
        return reviewServer;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public long getReviewExpiresAt() {
        return reviewExpiresAt;
    }

    public List<Report> getReports() {
        return reports;
    }

    public List<ModeratorNote> getNotes() {
        return notes;
    }
}
