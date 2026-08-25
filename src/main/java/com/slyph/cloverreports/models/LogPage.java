package com.slyph.cloverreports.models;

import java.util.List;

public final class LogPage {

    private final List<ReportLog> logs;
    private final int page;
    private final int totalPages;
    private final int totalItems;

    public LogPage(List<ReportLog> logs, int page, int totalPages, int totalItems) {
        this.logs = List.copyOf(logs);
        this.page = page;
        this.totalPages = totalPages;
        this.totalItems = totalItems;
    }

    public List<ReportLog> getLogs() {
        return logs;
    }

    public int getPage() {
        return page;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public int getTotalItems() {
        return totalItems;
    }
}
