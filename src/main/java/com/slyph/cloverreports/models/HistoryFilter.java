package com.slyph.cloverreports.models;

import java.util.Locale;

public final class HistoryFilter {

    private final String player;
    private final String playerKey;
    private final String moderator;
    private final String moderatorKey;
    private final String reason;
    private final String reasonKey;
    private final String action;
    private final long fromTimestamp;
    private final long toTimestamp;

    public HistoryFilter(String player, String moderator, String reason, String action, long fromTimestamp, long toTimestamp) {
        this(player, moderator, reason, reason, action, fromTimestamp, toTimestamp);
    }

    public HistoryFilter(String player, String moderator, String reason, String reasonKey, String action, long fromTimestamp, long toTimestamp) {
        this.player = blankToNull(player);
        this.playerKey = normalize(player);
        this.moderator = blankToNull(moderator);
        this.moderatorKey = normalize(moderator);
        this.reason = blankToNull(reason);
        this.reasonKey = normalize(reasonKey);
        this.action = blankToNull(action);
        this.fromTimestamp = Math.max(0L, fromTimestamp);
        this.toTimestamp = Math.max(0L, toTimestamp);
    }

    public static HistoryFilter empty() {
        return new HistoryFilter(null, null, null, null, null, 0L, 0L);
    }

    public String getPlayer() {
        return player;
    }

    public String getPlayerKey() {
        return playerKey;
    }

    public String getModerator() {
        return moderator;
    }

    public String getModeratorKey() {
        return moderatorKey;
    }

    public String getReason() {
        return reason;
    }

    public String getReasonKey() {
        return reasonKey;
    }

    public String getAction() {
        return action;
    }

    public long getFromTimestamp() {
        return fromTimestamp;
    }

    public long getToTimestamp() {
        return toTimestamp;
    }

    public boolean isEmpty() {
        return player == null && moderator == null && reason == null && reasonKey == null && action == null && fromTimestamp == 0L && toTimestamp == 0L;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
