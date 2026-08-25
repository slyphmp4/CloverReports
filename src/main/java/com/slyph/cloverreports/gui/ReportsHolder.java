package com.slyph.cloverreports.gui;

import com.slyph.cloverreports.models.HistoryFilter;
import com.slyph.cloverreports.models.ReportCase;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class ReportsHolder implements InventoryHolder {

    private final int page;
    private final int totalPages;
    private final ReportListType listType;
    private final HistoryFilter filter;
    private final Map<Integer, ReportCase> casesBySlot;
    private Inventory inventory;

    public ReportsHolder(int page, int totalPages, ReportListType listType, HistoryFilter filter) {
        this.page = page;
        this.totalPages = totalPages;
        this.listType = listType;
        this.filter = filter == null ? HistoryFilter.empty() : filter;
        this.casesBySlot = new HashMap<>();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public int getPage() {
        return page;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public ReportListType getListType() {
        return listType;
    }

    public HistoryFilter getFilter() {
        return filter;
    }

    public void setCase(int slot, ReportCase reportCase) {
        casesBySlot.put(slot, reportCase);
    }

    public ReportCase getCase(int slot) {
        return casesBySlot.get(slot);
    }
}
