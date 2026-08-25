package com.slyph.cloverreports.models;

import java.util.UUID;

public final class ModeratorNote {

    private final int id;
    private final long caseId;
    private final String reported;
    private final String moderator;
    private final UUID moderatorUuid;
    private final String note;
    private final long timestamp;
    private final String status;

    public ModeratorNote(int id, String reported, String moderator, String note, long timestamp, String status) {
        this(id, 0L, reported, moderator, null, note, timestamp, status);
    }

    public ModeratorNote(int id, long caseId, String reported, String moderator, UUID moderatorUuid, String note, long timestamp, String status) {
        this.id = id;
        this.caseId = caseId;
        this.reported = reported;
        this.moderator = moderator;
        this.moderatorUuid = moderatorUuid;
        this.note = note;
        this.timestamp = timestamp;
        this.status = status;
    }

    public int getId() {
        return id;
    }

    public long getCaseId() {
        return caseId;
    }

    public String getReported() {
        return reported;
    }

    public String getModerator() {
        return moderator;
    }

    public UUID getModeratorUuid() {
        return moderatorUuid;
    }

    public String getNote() {
        return note;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getStatus() {
        return status;
    }
}
