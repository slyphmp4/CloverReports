package com.slyph.cloverreports.gui;

import com.slyph.cloverreports.CloverReports;
import com.slyph.cloverreports.compat.InventoryCompat;
import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.managers.ReportManager.ReviewLease;
import com.slyph.cloverreports.models.CasePage;
import com.slyph.cloverreports.models.HistoryFilter;
import com.slyph.cloverreports.models.ModeratorNote;
import com.slyph.cloverreports.models.Report;
import com.slyph.cloverreports.models.ReportCase;
import com.slyph.cloverreports.reasons.ReportReason;
import com.slyph.cloverreports.reasons.ReportReasons;
import com.slyph.cloverreports.utils.ChatUtil;
import com.slyph.cloverreports.utils.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ReportsGUI {

    private static final int[] REPORT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] CLOSE_REASON_SLOTS = {10, 12, 14, 16, 20, 22, 24};
    private static final int[] BAN_REASON_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int REPORTS_PER_PAGE = REPORT_SLOTS.length;
    private static final int BACK_SLOT = 38;
    private static final int SWITCH_SLOT = 40;
    private static final int FORWARD_SLOT = 42;
    private static final int ACTION_BACK_SLOT = 22;
    private static final int CLOSE_REASON_BACK_SLOT = 31;
    private static final int CLOSE_REASON_PREVIOUS_SLOT = 30;
    private static final int CLOSE_REASON_NEXT_SLOT = 32;
    private static final int BAN_REASON_PREVIOUS_SLOT = 48;
    private static final int BAN_REASON_BACK_SLOT = 49;
    private static final int BAN_REASON_NEXT_SLOT = 50;
    private static final int BAN_CONFIRM_SLOT = 20;
    private static final int BAN_CANCEL_SLOT = 24;
    private static final String[] CIRCLED_NUMBERS = {
            "", "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
            "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳"
    };
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final ConcurrentMap<UUID, Long> REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    private ReportsGUI() {
    }

    public static void openMainGUI(Player player, ReportManager reportManager) {
        openMainGUI(player, reportManager, 0);
    }

    public static void openMainGUI(Player player, ReportManager reportManager, int page) {
        openReportsGUI(player, reportManager, ReportListType.ACTIVE, HistoryFilter.empty(), page);
    }

    public static void openPlayerReportsGUI(Player player, ReportManager reportManager, String filterPlayer, int page) {
        openReportsGUI(player, reportManager, ReportListType.ACTIVE, new HistoryFilter(filterPlayer, null, null, null, 0L, 0L), page);
    }

    public static void openHistoryGUI(Player player, ReportManager reportManager) {
        openHistoryGUI(player, reportManager, HistoryFilter.empty(), 0);
    }

    public static void openHistoryGUI(Player player, ReportManager reportManager, int page) {
        openHistoryGUI(player, reportManager, HistoryFilter.empty(), page);
    }

    public static void openHistoryGUI(Player player, ReportManager reportManager, String filterPlayer, int page) {
        openHistoryGUI(player, reportManager, new HistoryFilter(filterPlayer, null, null, null, 0L, 0L), page);
    }

    public static void openHistoryGUI(Player player, ReportManager reportManager, HistoryFilter filter, int page) {
        openReportsGUI(player, reportManager, ReportListType.HISTORY, filter, page);
    }

    public static void openReportsGUI(Player player, ReportManager reportManager, ReportListType listType, HistoryFilter filter, int page) {
        long request = REQUEST_SEQUENCE.incrementAndGet();
        REQUESTS.put(player.getUniqueId(), request);
        Inventory expectedInventory = player.getOpenInventory().getTopInventory();
        String status = listType == ReportListType.ACTIVE ? ReportManager.STATUS_PENDING : ReportManager.STATUS_RESOLVED;
        Bukkit.getScheduler().runTaskAsynchronously(CloverReports.getInstance(), () -> {
            CasePage casePage = reportManager.getCasePage(status, filter, page, REPORTS_PER_PAGE);
            Bukkit.getScheduler().runTask(CloverReports.getInstance(), () -> {
                if (!Long.valueOf(request).equals(REQUESTS.get(player.getUniqueId()))) {
                    return;
                }
                REQUESTS.remove(player.getUniqueId(), request);
                if (!player.isOnline()) {
                    return;
                }
                if (player.getOpenInventory().getTopInventory() != expectedInventory) {
                    return;
                }
                renderReportsGUI(player, listType, filter, casePage);
            });
        });
    }

    public static void openActionGUI(Player player, ReportCase reportCase, ReviewLease lease, ReportListType returnListType, HistoryFilter returnFilter, int returnPage) {
        ReportActionHolder holder = new ReportActionHolder(reportCase, lease, ReportMenuType.ACTION, returnListType, returnFilter, returnPage, 0, null);
        Inventory inventory = InventoryCompat.create(holder, 27, Messages.getGui("action.title", Map.of(
                "%player%", reportCase.getReportedName(),
                "%case_id%", String.valueOf(reportCase.getId())
        )));
        holder.setInventory(inventory);
        fillInventory(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(10, createItem(Material.LIME_DYE, Messages.getGui("action.delete.display"), Messages.getGuiList("action.delete.lore")));
        inventory.setItem(12, createItem(Material.ENDER_PEARL, Messages.getGui("action.teleport.display"), Messages.getGuiList("action.teleport.lore")));
        inventory.setItem(14, createItem(Material.WRITABLE_BOOK, Messages.getGui("action.note.display"), Messages.getGuiList("action.note.lore")));
        inventory.setItem(16, createItem(Material.NETHERITE_SWORD, Messages.getGui("action.ban.display"), Messages.getGuiList("action.ban.lore")));
        if (hasEvidence(reportCase)) {
            inventory.setItem(4, createItem(Material.MAP, Messages.getGui("action.evidence.display"), Messages.getGuiList("action.evidence.lore", Map.of(
                    "%count%", String.valueOf(countEvidence(reportCase)),
                    "%case_id%", String.valueOf(reportCase.getId())
            ))));
        }
        inventory.setItem(ACTION_BACK_SLOT, createItem(Material.ARROW, Messages.getGui("action.back.display"), Messages.getGuiList("action.back.lore")));
        player.openInventory(inventory);
    }

    public static void openCloseReasonSelectionGUI(Player player, ReportActionHolder source, int requestedPage) {
        List<String> reasons = getCloseReasons();
        int totalPages = Math.max(1, (int) Math.ceil(reasons.size() / (double) CLOSE_REASON_SLOTS.length));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        ReportActionHolder holder = childHolder(source, ReportMenuType.CLOSE_REASON_SELECTION, page, null);
        Inventory inventory = InventoryCompat.create(holder, 36, Messages.getGui("close-reason-selection.title", Map.of(
                "%player%", source.getReportedPlayer(),
                "%case_id%", String.valueOf(source.getCaseId())
        )));
        holder.setInventory(inventory);
        fillInventory(inventory, Material.GREEN_STAINED_GLASS_PANE);
        int offset = page * CLOSE_REASON_SLOTS.length;
        for (int index = 0; index < CLOSE_REASON_SLOTS.length && offset + index < reasons.size(); index++) {
            String reason = reasons.get(offset + index);
            int slot = CLOSE_REASON_SLOTS[index];
            holder.setReason(slot, reason);
            inventory.setItem(slot, createItem(Material.PAPER, Messages.getGui("close-reason-selection.reason.display", Map.of("%reason%", reason)), Messages.getGuiList("close-reason-selection.reason.lore", Map.of("%reason%", reason))));
        }
        if (page > 0) {
            inventory.setItem(CLOSE_REASON_PREVIOUS_SLOT, createItem(Material.ARROW, Messages.getGui("close-reason-selection.previous.display"), Messages.getGuiList("close-reason-selection.previous.lore", Map.of("%page%", String.valueOf(page)))));
        }
        if (page + 1 < totalPages) {
            inventory.setItem(CLOSE_REASON_NEXT_SLOT, createItem(Material.ARROW, Messages.getGui("close-reason-selection.next.display"), Messages.getGuiList("close-reason-selection.next.lore", Map.of("%page%", String.valueOf(page + 2)))));
        }
        inventory.setItem(CLOSE_REASON_BACK_SLOT, createItem(Material.BARRIER, Messages.getGui("close-reason-selection.back.display"), Messages.getGuiList("close-reason-selection.back.lore")));
        player.openInventory(inventory);
    }

    public static void openBanReasonSelectionGUI(Player player, ReportActionHolder source, int requestedPage) {
        List<ReportReason> reasons = ReportReasons.getReasons();
        int totalPages = Math.max(1, (int) Math.ceil(reasons.size() / (double) BAN_REASON_SLOTS.length));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        ReportActionHolder holder = childHolder(source, ReportMenuType.BAN_REASON_SELECTION, page, null);
        Inventory inventory = InventoryCompat.create(holder, 54, Messages.getGui("ban-reason-selection.title", Map.of(
                "%player%", source.getReportedPlayer(),
                "%case_id%", String.valueOf(source.getCaseId())
        )));
        holder.setInventory(inventory);
        fillInventory(inventory, Material.RED_STAINED_GLASS_PANE);
        int offset = page * BAN_REASON_SLOTS.length;
        for (int index = 0; index < BAN_REASON_SLOTS.length && offset + index < reasons.size(); index++) {
            ReportReason reason = reasons.get(offset + index);
            int slot = BAN_REASON_SLOTS[index];
            holder.setReason(slot, reason.getName());
            inventory.setItem(slot, createItem(Material.PAPER, Messages.getGui("ban-reason-selection.reason.display", Map.of("%reason%", ReportReasons.getDisplay(reason.getName()))), Messages.getGuiList("ban-reason-selection.reason.lore", Map.of("%reason%", ReportReasons.getDisplay(reason.getName())))));
        }
        if (page > 0) {
            inventory.setItem(BAN_REASON_PREVIOUS_SLOT, createItem(Material.ARROW, Messages.getGui("ban-reason-selection.previous.display"), Messages.getGuiList("ban-reason-selection.previous.lore", Map.of("%page%", String.valueOf(page)))));
        }
        if (page + 1 < totalPages) {
            inventory.setItem(BAN_REASON_NEXT_SLOT, createItem(Material.ARROW, Messages.getGui("ban-reason-selection.next.display"), Messages.getGuiList("ban-reason-selection.next.lore", Map.of("%page%", String.valueOf(page + 2)))));
        }
        inventory.setItem(BAN_REASON_BACK_SLOT, createItem(Material.BARRIER, Messages.getGui("ban-reason-selection.back.display"), Messages.getGuiList("ban-reason-selection.back.lore")));
        player.openInventory(inventory);
    }

    public static void openBanConfirmationGUI(Player player, ReportActionHolder source, String reason) {
        ReportActionHolder holder = childHolder(source, ReportMenuType.BAN_CONFIRMATION, source.getReasonPage(), reason);
        Inventory inventory = InventoryCompat.create(holder, 45, Messages.getGui("ban-confirm.title", Map.of(
                "%player%", source.getReportedPlayer(),
                "%case_id%", String.valueOf(source.getCaseId())
        )));
        holder.setInventory(inventory);
        fillConfirmation(inventory);
        inventory.setItem(BAN_CONFIRM_SLOT, createItem(Material.RED_CONCRETE, Messages.getGui("ban-confirm.confirm.display"), Messages.getGuiList("ban-confirm.confirm.lore", Map.of("%reason%", ReportReasons.getDisplay(reason)))));
        inventory.setItem(BAN_CANCEL_SLOT, createItem(Material.LIGHT_BLUE_CONCRETE, Messages.getGui("ban-confirm.cancel.display"), Messages.getGuiList("ban-confirm.cancel.lore")));
        player.openInventory(inventory);
    }

    public static int getReportsPerPage() {
        return REPORTS_PER_PAGE;
    }

    private static void renderReportsGUI(Player player, ReportListType listType, HistoryFilter filter, CasePage casePage) {
        ReportsHolder holder = new ReportsHolder(casePage.getPage(), casePage.getTotalPages(), listType, filter);
        Inventory inventory = InventoryCompat.create(holder, 54, getTitle(listType, filter, casePage));
        holder.setInventory(inventory);
        fillInventory(inventory, Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        List<ReportCase> cases = casePage.getCases();
        ItemStack placeholder = createItem(
                guiMaterial("report-placeholder.material", Material.GRAY_STAINED_GLASS_PANE),
                Messages.getGui("report-placeholder.display"),
                Messages.getGuiList("report-placeholder.lore")
        );
        for (int index = cases.size(); index < REPORT_SLOTS.length; index++) {
            inventory.setItem(REPORT_SLOTS[index], placeholder);
        }
        for (int index = 0; index < cases.size() && index < REPORT_SLOTS.length; index++) {
            ReportCase reportCase = cases.get(index);
            int slot = REPORT_SLOTS[index];
            holder.setCase(slot, reportCase);
            inventory.setItem(slot, createCaseHead(player, reportCase, listType));
        }
        if (cases.isEmpty()) {
            String key = listType == ReportListType.ACTIVE ? "empty-reports" : "empty-history";
            inventory.setItem(22, createItem(Material.CLOCK, Messages.getGui(key + ".display"), Messages.getGuiList(key + ".lore")));
        }
        if (casePage.getPage() > 0) {
            inventory.setItem(BACK_SLOT, createItem(Material.ARROW, Messages.getGui("nav.back.display"), Messages.getGuiList("nav.back.lore", Map.of("%page%", String.valueOf(casePage.getPage())))));
        }
        if (casePage.getPage() + 1 < casePage.getTotalPages()) {
            inventory.setItem(FORWARD_SLOT, createItem(Material.ARROW, Messages.getGui("nav.forward.display"), Messages.getGuiList("nav.forward.lore", Map.of("%page%", String.valueOf(casePage.getPage() + 2)))));
        }
        String switchKey = listType == ReportListType.ACTIVE ? "buttons.history" : "buttons.active";
        inventory.setItem(SWITCH_SLOT, createItem(listType == ReportListType.ACTIVE ? Material.WRITABLE_BOOK : Material.EMERALD, Messages.getGui(switchKey + ".display"), Messages.getGuiList(switchKey + ".lore")));
        player.openInventory(inventory);
    }

    private static String getTitle(ReportListType listType, HistoryFilter filter, CasePage casePage) {
        String path;
        if (filter.getPlayer() != null) {
            path = listType == ReportListType.ACTIVE ? "filtered-title" : "filtered-history-title";
        } else {
            path = listType == ReportListType.ACTIVE ? "main-title" : "history-title";
        }
        return Messages.getGui(path, Map.of(
                "%player%", filter.getPlayer() == null ? "*" : filter.getPlayer(),
                "%page%", String.valueOf(casePage.getPage() + 1),
                "%total%", String.valueOf(casePage.getTotalPages()),
                "%count%", String.valueOf(casePage.getTotalCases())
        ));
    }

    private static ItemStack createCaseHead(Player viewer, ReportCase reportCase, ReportListType listType) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (reportCase.getReportedUuid() != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(reportCase.getReportedUuid()));
        }
        String path = listType == ReportListType.ACTIVE ? "report-head" : "history-head";
        meta.displayName(ChatUtil.itemComponent(Messages.getGui(path + ".display", Map.of(
                "%player%", reportCase.getReportedName(),
                "%count%", String.valueOf(reportCase.getReports().size()),
                "%case_id%", String.valueOf(reportCase.getId())
        ))));
        meta.lore(ChatUtil.itemComponents(listType == ReportListType.ACTIVE ? createActiveLore(viewer, reportCase) : createHistoryLore(viewer, reportCase)));
        item.setItemMeta(meta);
        return item;
    }

    private static List<String> createActiveLore(Player viewer, ReportCase reportCase) {
        List<String> lore = new ArrayList<>(Messages.getGuiList("report-head.lore-prefix", basePlaceholders(reportCase)));
        lore.add(Messages.getGui("report-head.case-id-line", Map.of("%case_id%", String.valueOf(reportCase.getId()))));
        String reviewOwner = reportCase.getReviewOwner();
        String reviewStatus = reviewOwner == null ? ReportManager.LIVE_STATUS_NEW : reportCase.getReviewStatus();
        lore.add(Messages.getGui("report-head.review-status-line", Map.of(
                "%status%", getReviewStatusDisplay(reviewStatus),
                "%moderator%", reviewOwner == null ? "-" : reviewOwner
        )));
        if (reviewOwner != null) {
            lore.add(Messages.getGui("report-head.review-moderator-line", Map.of("%status%", getReviewStatusDisplay(reviewStatus), "%moderator%", reviewOwner)));
        }
        appendReports(lore, reportCase, "report-head", "limits.active-report-entries");
        appendEvidence(viewer, lore, reportCase, "report-head");
        appendNotes(lore, reportCase.getNotes(), "report-head");
        lore.addAll(Messages.getGuiList("report-head.lore-suffix"));
        return lore;
    }

    private static List<String> createHistoryLore(Player viewer, ReportCase reportCase) {
        List<String> lore = new ArrayList<>(Messages.getGuiList("history-head.lore-prefix", basePlaceholders(reportCase)));
        lore.add(Messages.getGui("history-head.case-id-line", Map.of("%case_id%", String.valueOf(reportCase.getId()))));
        lore.add(Messages.getGui("history-head.audit-line", Map.of(
                "%action%", getActionDisplay(reportCase.getAction()),
                "%moderator%", reportCase.getResolvedBy() == null ? "-" : reportCase.getResolvedBy(),
                "%time%", reportCase.getResolvedAt() <= 0L ? "-" : TIME_FORMATTER.format(Instant.ofEpochMilli(reportCase.getResolvedAt()))
        )));
        if (reportCase.getResolutionReason() != null && !reportCase.getResolutionReason().isBlank()) {
            lore.add(Messages.getGui("history-head.resolution-line", Map.of("%resolution%", reportCase.getResolutionReason(), "%action%", getActionDisplay(reportCase.getAction()))));
        }
        appendReports(lore, reportCase, "history-head", "limits.history-entries");
        appendEvidence(viewer, lore, reportCase, "history-head");
        appendNotes(lore, reportCase.getNotes(), "history-head");
        lore.addAll(Messages.getGuiList("history-head.lore-suffix"));
        return lore;
    }

    private static Map<String, String> basePlaceholders(ReportCase reportCase) {
        return Map.of(
                "%server%", CloverReports.getInstance().getConfig().getString("server.name", "Server"),
                "%count%", String.valueOf(reportCase.getReports().size()),
                "%player%", reportCase.getReportedName(),
                "%case_id%", String.valueOf(reportCase.getId()),
                "%playtime%", getPlaytime(reportCase)
        );
    }

    private static String getPlaytime(ReportCase reportCase) {
        Player target = reportCase.getReportedUuid() == null
                ? Bukkit.getPlayerExact(reportCase.getReportedName())
                : Bukkit.getPlayer(reportCase.getReportedUuid());
        if (target == null || !target.isOnline()) {
            return Messages.getGui("report-head.playtime-unknown");
        }
        long ticks = Math.max(0, target.getStatistic(Statistic.PLAY_ONE_MINUTE));
        return String.valueOf(ticks / 72_000L);
    }

    private static void appendEvidence(Player viewer, List<String> lore, ReportCase reportCase, String path) {
        if (!viewer.hasPermission("cloverreports.evidence.view")) {
            return;
        }
        List<Report> evidence = new ArrayList<>();
        for (Report report : reportCase.getReports()) {
            if (report.getEvidenceUrl() != null && !report.getEvidenceUrl().isBlank()) {
                evidence.add(report);
            }
        }
        if (evidence.isEmpty()) {
            return;
        }
        lore.addAll(Messages.getGuiList(path + ".evidence-prefix", Map.of("%count%", String.valueOf(evidence.size()))));
        int maximum = Math.max(1, Messages.getGuiInt("limits.evidence-entries", 5));
        for (int index = 0; index < Math.min(maximum, evidence.size()); index++) {
            Report report = evidence.get(index);
            String url = ChatUtil.escapeUserText(shorten(report.getEvidenceUrl(), 72));
            lore.add(Messages.getGui(path + ".evidence-line", Map.of("%reporter%", report.getReporter(), "%url%", url)));
        }
        if (evidence.size() > maximum) {
            lore.add(Messages.getGui(path + ".evidence-omitted-line", Map.of("%count%", String.valueOf(evidence.size() - maximum))));
        }
    }

    private static void appendReports(List<String> lore, ReportCase reportCase, String path, String limitPath) {
        if (reportCase.getReports().isEmpty()) {
            return;
        }
        lore.addAll(Messages.getGuiList(path + ".report-list-prefix", Map.of(
                "%count%", String.valueOf(reportCase.getReports().size()),
                "%case_id%", String.valueOf(reportCase.getId())
        )));
        int maximum = Math.max(1, Messages.getGuiInt(limitPath, 20));
        int displayedReports = Math.min(maximum, reportCase.getReports().size());
        for (int index = 0; index < displayedReports; index++) {
            Report report = reportCase.getReports().get(index);
            int number = index + 1;
            String circledNumber = formatCircledNumber(number);
            lore.addAll(Messages.getGuiList(path + ".report-entry-lines", Map.of(
                    "%reporter%", ChatUtil.escapeUserText(report.getReporter()),
                    "%reason%", ReportReasons.getDisplay(report.getReason()),
                    "%time%", report.getTimestamp() <= 0L ? "-" : TIME_FORMATTER.format(Instant.ofEpochMilli(report.getTimestamp())),
                    "%number%", circledNumber,
                    "%circled_number%", circledNumber,
                    "%raw_number%", String.valueOf(number)
            )));
            if (index + 1 < displayedReports) {
                lore.addAll(Messages.getGuiList(path + ".report-entry-separator"));
            }
        }
        if (reportCase.getReports().size() > maximum) {
            lore.addAll(Messages.getGuiList(path + ".report-list-omitted-lines", Map.of(
                    "%count%", String.valueOf(reportCase.getReports().size() - maximum)
            )));
        }
    }

    private static void appendNotes(List<String> lore, List<ModeratorNote> notes, String path) {
        if (notes.isEmpty()) {
            return;
        }
        lore.addAll(Messages.getGuiList(path + ".notes-prefix"));
        int maximum = Math.max(1, Messages.getGuiInt("limits.note-entries", 20));
        Map<String, List<ModeratorNote>> groupedNotes = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(maximum, notes.size()); index++) {
            ModeratorNote note = notes.get(index);
            groupedNotes.computeIfAbsent(note.getModerator().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(note);
        }
        int number = 0;
        for (List<ModeratorNote> moderatorNotes : groupedNotes.values()) {
            String moderator = moderatorNotes.get(0).getModerator();
            lore.addAll(Messages.getGuiList(path + ".note-group-lines", Map.of(
                    "%moderator%", ChatUtil.escapeUserText(moderator),
                    "%count%", String.valueOf(moderatorNotes.size())
            )));
            for (int index = 0; index < moderatorNotes.size(); index++) {
                ModeratorNote note = moderatorNotes.get(index);
                number++;
                String circledNumber = formatCircledNumber(number);
                lore.addAll(Messages.getGuiList(path + ".note-entry-lines", Map.of(
                        "%moderator%", ChatUtil.escapeUserText(moderator),
                        "%raw_note%", ChatUtil.escapeUserText(note.getNote()),
                        "%note%", moderator + ": " + ChatUtil.escapeUserText(note.getNote()),
                        "%time%", note.getTimestamp() <= 0L ? "-" : TIME_FORMATTER.format(Instant.ofEpochMilli(note.getTimestamp())),
                        "%number%", circledNumber,
                        "%circled_number%", circledNumber,
                        "%raw_number%", String.valueOf(number)
                )));
                if (index + 1 < moderatorNotes.size()) {
                    lore.addAll(Messages.getGuiList(path + ".note-entry-separator"));
                }
            }
        }
        lore.addAll(Messages.getGuiList(path + ".notes-suffix"));
    }

    private static String formatCircledNumber(int number) {
        return number > 0 && number < CIRCLED_NUMBERS.length ? CIRCLED_NUMBERS[number] : String.valueOf(number);
    }

    private static String getReviewStatusDisplay(String status) {
        if (ReportManager.LIVE_STATUS_IN_WORK.equalsIgnoreCase(status)) {
            return Messages.getGui("report-head.status-in-work");
        }
        if (ReportManager.LIVE_STATUS_WAITING_DECISION.equalsIgnoreCase(status)) {
            return Messages.getGui("report-head.status-waiting-decision");
        }
        return Messages.getGui("report-head.status-new");
    }

    private static String getActionDisplay(String action) {
        if (ReportManager.ACTION_CLOSED.equalsIgnoreCase(action)) {
            return Messages.getGui("history-head.action-closed");
        }
        if (ReportManager.ACTION_PUNISHED.equalsIgnoreCase(action)) {
            return Messages.getGui("history-head.action-punished");
        }
        return Messages.getGui("history-head.action-unknown");
    }

    private static ReportActionHolder childHolder(ReportActionHolder source, ReportMenuType type, int reasonPage, String banReason) {
        return new ReportActionHolder(source.getReportCase(), source.getLease(), type, source.getReturnListType(), source.getReturnFilter(), source.getReturnPage(), reasonPage, banReason);
    }

    private static List<String> getCloseReasons() {
        List<String> reasons = CloverReports.getInstance().getConfig().getStringList("close-reasons");
        return reasons.isEmpty() ? List.of("Недостаточно доказательств", "Игрок не нарушал", "Дубликат", "Уже обработано") : reasons;
    }

    private static boolean hasEvidence(ReportCase reportCase) {
        return countEvidence(reportCase) > 0;
    }

    private static int countEvidence(ReportCase reportCase) {
        int count = 0;
        for (Report report : reportCase.getReports()) {
            if (report.getEvidenceUrl() != null && !report.getEvidenceUrl().isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static String shorten(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(1, maximum - 1)) + "…";
    }

    private static void fillInventory(Inventory inventory, Material material) {
        ItemStack filler = createItem(material, Messages.getGui("filler.display"), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private static void fillConfirmation(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int column = slot % 9;
            Material material = column < 4 ? Material.RED_STAINED_GLASS_PANE : column == 4 ? Material.BLACK_STAINED_GLASS_PANE : Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            inventory.setItem(slot, createItem(material, Messages.getGui("filler.display"), List.of()));
        }
    }

    private static Material guiMaterial(String path, Material fallback) {
        Material material = Material.matchMaterial(Messages.getGui(path).trim().toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }

    private static ItemStack createItem(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(ChatUtil.itemComponent(displayName));
        meta.lore(ChatUtil.itemComponents(lore));
        item.setItemMeta(meta);
        return item;
    }
}
