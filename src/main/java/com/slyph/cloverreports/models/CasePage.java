package com.slyph.cloverreports.models;

import java.util.List;

public final class CasePage {

    private final List<ReportCase> cases;
    private final int page;
    private final int totalPages;
    private final int totalCases;

    public CasePage(List<ReportCase> cases, int page, int totalPages, int totalCases) {
        this.cases = List.copyOf(cases);
        this.page = page;
        this.totalPages = totalPages;
        this.totalCases = totalCases;
    }

    public List<ReportCase> getCases() {
        return cases;
    }

    public int getPage() {
        return page;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public int getTotalCases() {
        return totalCases;
    }
}
