package com.slyph.cloverreports.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReportPage {

    private final Map<String, List<Report>> reports;
    private final int page;
    private final int totalPages;
    private final int totalPlayers;

    public ReportPage(Map<String, List<Report>> reports, int page, int totalPages, int totalPlayers) {
        Map<String, List<Report>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Report>> entry : reports.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.reports = Collections.unmodifiableMap(copy);
        this.page = page;
        this.totalPages = totalPages;
        this.totalPlayers = totalPlayers;
    }

    public Map<String, List<Report>> getReports() {
        return reports;
    }

    public int getPage() {
        return page;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public int getTotalPlayers() {
        return totalPlayers;
    }
}
