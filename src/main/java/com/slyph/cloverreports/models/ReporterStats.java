package com.slyph.cloverreports.models;

public final class ReporterStats {

    private final int closedReports;
    private final int punishedReports;
    private final long latestReviewedAt;

    public ReporterStats(int closedReports, int punishedReports) {
        this(closedReports, punishedReports, 0L);
    }

    public ReporterStats(int closedReports, int punishedReports, long latestReviewedAt) {
        this.closedReports = closedReports;
        this.punishedReports = punishedReports;
        this.latestReviewedAt = latestReviewedAt;
    }

    public int getClosedReports() {
        return closedReports;
    }

    public int getPunishedReports() {
        return punishedReports;
    }

    public int getReviewedReports() {
        return closedReports + punishedReports;
    }

    public long getLatestReviewedAt() {
        return latestReviewedAt;
    }

    public int getClosedPercent() {
        int reviewed = getReviewedReports();
        return reviewed <= 0 ? 0 : (int) (closedReports * 100L / reviewed);
    }

    public boolean isClosedPercentAtLeast(int percentage) {
        int reviewed = getReviewedReports();
        return reviewed > 0 && closedReports * 100L >= (long) percentage * reviewed;
    }
}
