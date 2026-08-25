package com.slyph.cloverreports.gui.submission;

import com.slyph.cloverreports.reasons.ReportReason;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ReportSubmissionHolder implements InventoryHolder {

    private final ReportSubmissionMenuType menuType;
    private final String targetName;
    private final UUID targetUuid;
    private final int reasonPage;
    private final int totalReasonPages;
    private final ReportReason selectedReason;
    private final String evidenceUrl;
    private final Map<Integer, ReportReason> reasonsBySlot;
    private Inventory inventory;

    public ReportSubmissionHolder(ReportSubmissionMenuType menuType, String targetName, UUID targetUuid, int reasonPage, int totalReasonPages, ReportReason selectedReason, String evidenceUrl) {
        this.menuType = menuType;
        this.targetName = targetName;
        this.targetUuid = targetUuid;
        this.reasonPage = reasonPage;
        this.totalReasonPages = totalReasonPages;
        this.selectedReason = selectedReason;
        this.evidenceUrl = evidenceUrl;
        this.reasonsBySlot = new HashMap<>();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public ReportSubmissionMenuType getMenuType() {
        return menuType;
    }

    public String getTargetName() {
        return targetName;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public int getReasonPage() {
        return reasonPage;
    }

    public int getTotalReasonPages() {
        return totalReasonPages;
    }

    public ReportReason getSelectedReason() {
        return selectedReason;
    }

    public String getEvidenceUrl() {
        return evidenceUrl;
    }

    public void setReason(int slot, ReportReason reason) {
        reasonsBySlot.put(slot, reason);
    }

    public ReportReason getReason(int slot) {
        return reasonsBySlot.get(slot);
    }
}
