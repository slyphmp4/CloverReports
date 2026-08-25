package com.slyph.cloverreports.gui;

import com.slyph.cloverreports.managers.ReportManager.ReviewLease;
import com.slyph.cloverreports.models.HistoryFilter;
import com.slyph.cloverreports.models.ReportCase;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class ReportActionHolder implements InventoryHolder {

    private final ReportCase reportCase;
    private final ReviewLease lease;
    private final ReportMenuType menuType;
    private final ReportListType returnListType;
    private final HistoryFilter returnFilter;
    private final int returnPage;
    private final int reasonPage;
    private final String banReason;
    private final Map<Integer, String> reasonsBySlot;
    private Inventory inventory;

    public ReportActionHolder(ReportCase reportCase, ReviewLease lease, ReportMenuType menuType, ReportListType returnListType, HistoryFilter returnFilter, int returnPage, int reasonPage, String banReason) {
        this.reportCase = reportCase;
        this.lease = lease;
        this.menuType = menuType;
        this.returnListType = returnListType;
        this.returnFilter = returnFilter == null ? HistoryFilter.empty() : returnFilter;
        this.returnPage = returnPage;
        this.reasonPage = reasonPage;
        this.banReason = banReason;
        this.reasonsBySlot = new HashMap<>();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public ReportCase getReportCase() {
        return reportCase;
    }

    public long getCaseId() {
        return reportCase.getId();
    }

    public String getReportedPlayer() {
        return reportCase.getReportedName();
    }

    public ReviewLease getLease() {
        return lease;
    }

    public ReportMenuType getMenuType() {
        return menuType;
    }

    public ReportListType getReturnListType() {
        return returnListType;
    }

    public HistoryFilter getReturnFilter() {
        return returnFilter;
    }

    public int getReturnPage() {
        return returnPage;
    }

    public int getReasonPage() {
        return reasonPage;
    }

    public String getBanReason() {
        return banReason;
    }

    public void setReason(int slot, String reason) {
        reasonsBySlot.put(slot, reason);
    }

    public String getReason(int slot) {
        return reasonsBySlot.get(slot);
    }
}
