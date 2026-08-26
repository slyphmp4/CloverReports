package com.slyph.cloverreports.models;

import java.util.List;

public final class ReportedPlayerIndex {

    private final List<Entry> entries;
    private final int totalCases;

    public ReportedPlayerIndex(List<Entry> entries, int totalCases) {
        this.entries = List.copyOf(entries);
        this.totalCases = Math.max(0, totalCases);
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public int getTotalCases() {
        return totalCases;
    }

    public record Entry(String displayName, int caseCount) {

        public Entry {
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("displayName must not be blank");
            }
            caseCount = Math.max(0, caseCount);
        }
    }
}
