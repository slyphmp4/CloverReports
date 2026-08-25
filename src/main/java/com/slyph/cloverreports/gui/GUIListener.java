package com.slyph.cloverreports.gui;

import com.slyph.cloverreports.CloverReports;
import com.slyph.cloverreports.input.ChatInputRegistry;
import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.managers.ReportManager.ModeratorNoteUpdateResult;
import com.slyph.cloverreports.managers.ReportManager.ReviewClaimResult;
import com.slyph.cloverreports.managers.ReportManager.ReviewClaimStatus;
import com.slyph.cloverreports.managers.ReportManager.ReviewLease;
import com.slyph.cloverreports.models.HistoryFilter;
import com.slyph.cloverreports.models.Report;
import com.slyph.cloverreports.models.ReportCase;
import com.slyph.cloverreports.reasons.ReportReasons;
import com.slyph.cloverreports.submission.EvidenceUrlValidator;
import com.slyph.cloverreports.utils.ChatUtil;
import com.slyph.cloverreports.utils.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GUIListener implements Listener {

    private static final String NOTE_INPUT_OWNER = "moderator-note";
    private static final int LIST_BACK_SLOT = 38;
    private static final int LIST_SWITCH_SLOT = 40;
    private static final int LIST_FORWARD_SLOT = 42;
    private static final int ACTION_CLOSE_SLOT = 10;
    private static final int ACTION_EVIDENCE_SLOT = 4;
    private static final int ACTION_TELEPORT_SLOT = 12;
    private static final int ACTION_NOTE_SLOT = 14;
    private static final int ACTION_BAN_SLOT = 16;
    private static final int ACTION_BACK_SLOT = 22;
    private static final int CLOSE_REASON_PREVIOUS_SLOT = 30;
    private static final int CLOSE_REASON_BACK_SLOT = 31;
    private static final int CLOSE_REASON_NEXT_SLOT = 32;
    private static final int BAN_REASON_PREVIOUS_SLOT = 48;
    private static final int BAN_REASON_BACK_SLOT = 49;
    private static final int BAN_REASON_NEXT_SLOT = 50;
    private static final int BAN_CONFIRM_SLOT = 20;
    private static final int BAN_CANCEL_SLOT = 24;

    private final CloverReports plugin;
    private final ReportManager reportManager;
    private final EvidenceUrlValidator evidenceValidator;
    private final ConcurrentMap<UUID, PendingNoteInput> pendingNoteInputs;
    private final ConcurrentMap<UUID, BukkitTask> noteInputTimeouts;
    private final Set<UUID> claimingPlayers;
    private final Set<UUID> validatingPlayers;
    private final ConcurrentMap<UUID, OperationSession> activeOperations;
    private final AtomicBoolean renewalRunning;
    private BukkitTask refreshTask;

    public GUIListener(ReportManager reportManager) {
        this.plugin = Objects.requireNonNull(CloverReports.getInstance(), "plugin");
        this.reportManager = Objects.requireNonNull(reportManager, "reportManager");
        this.evidenceValidator = new EvidenceUrlValidator(plugin);
        this.pendingNoteInputs = new ConcurrentHashMap<>();
        this.noteInputTimeouts = new ConcurrentHashMap<>();
        this.claimingPlayers = ConcurrentHashMap.newKeySet();
        this.validatingPlayers = ConcurrentHashMap.newKeySet();
        this.activeOperations = new ConcurrentHashMap<>();
        this.renewalRunning = new AtomicBoolean();
        startRenewalTask();
        startListRefreshTask();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory topInventory = event.getView().getTopInventory();
        InventoryHolder inventoryHolder = topInventory.getHolder();
        if (!(inventoryHolder instanceof ReportsHolder) && !(inventoryHolder instanceof ReportActionHolder)) {
            return;
        }

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        if (!player.hasPermission("cloverreports.view")) {
            player.closeInventory();
            send(player, "no-permission", "&cУ вас нет прав для этого действия.", Map.of());
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= topInventory.getSize()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }

        if (inventoryHolder instanceof ReportsHolder) {
            handleReportsMenu(player, (ReportsHolder) inventoryHolder, slot);
        } else {
            handleActionMenu(player, (ReportActionHolder) inventoryHolder, slot);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ReportsHolder || holder instanceof ReportActionHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player) || !(event.getInventory().getHolder() instanceof ReportActionHolder)) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        ReviewLease lease = ((ReportActionHolder) event.getInventory().getHolder()).getLease();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isLeaseRetained(playerId, lease)) {
                return;
            }
            releaseLeaseAsync(lease, true);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!ChatInputRegistry.isOwnedBy(playerId, NOTE_INPUT_OWNER)) {
            return;
        }
        PendingNoteInput input = pendingNoteInputs.get(playerId);
        if (input == null) {
            ChatInputRegistry.release(playerId, NOTE_INPUT_OWNER);
            return;
        }
        event.setCancelled(true);
        if (!pendingNoteInputs.remove(playerId, input)) {
            return;
        }
        ChatInputRegistry.release(playerId, NOTE_INPUT_OWNER);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        OperationSession operation = new OperationSession(input.lease);
        OperationSession existing = activeOperations.putIfAbsent(playerId, operation);
        if (existing != null) {
            runMain(() -> {
                cancelNoteInputTimeout(playerId);
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    send(player, "action-error", "&cНе удалось выполнить действие.", Map.of());
                }
                if (!matches(existing, input.lease)) {
                    releaseLeaseAsync(input.lease, true);
                }
            });
            return;
        }
        runMain(() -> beginNoteProcessing(playerId, input, operation, message));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        if (!ChatInputRegistry.isOwnedBy(playerId, NOTE_INPUT_OWNER)) {
            return;
        }
        PendingNoteInput input = pendingNoteInputs.get(playerId);
        if (input == null) {
            ChatInputRegistry.release(playerId, NOTE_INPUT_OWNER);
            return;
        }
        if (!pendingNoteInputs.remove(playerId, input)) {
            return;
        }
        event.setCancelled(true);
        ChatInputRegistry.release(playerId, NOTE_INPUT_OWNER);
        cancelNoteInputTimeout(playerId);
        OperationSession operation = new OperationSession(input.lease);
        OperationSession existing = activeOperations.putIfAbsent(playerId, operation);
        if (existing != null) {
            send(event.getPlayer(), "action-error", "&cНе удалось выполнить действие.", Map.of());
            if (!matches(existing, input.lease)) {
                releaseLeaseAsync(input.lease, true);
            }
            return;
        }
        send(event.getPlayer(), "note-input-cancelled", "&eДобавление заметки по %player% отменено.", Map.of(
                "%player%", input.reportCase.getReportedName(),
                "%case_id%", String.valueOf(input.reportCase.getId())
        ));
        renewNoteContext(playerId, input, operation);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        ChatInputRegistry.release(playerId, NOTE_INPUT_OWNER);
        PendingNoteInput pending = pendingNoteInputs.remove(playerId);
        cancelNoteInputTimeout(playerId);
        validatingPlayers.remove(playerId);

        Set<UUID> releasedTokens = new LinkedHashSet<>();
        OperationSession operation = activeOperations.get(playerId);
        if (pending != null && !matches(operation, pending.lease)) {
            releasedTokens.add(pending.lease.getToken());
            releaseLeaseAsync(pending.lease, true);
        }
        InventoryHolder holder = event.getPlayer().getOpenInventory().getTopInventory().getHolder();
        if (holder instanceof ReportActionHolder) {
            ReviewLease lease = ((ReportActionHolder) holder).getLease();
            if (!matches(operation, lease) && releasedTokens.add(lease.getToken())) {
                releaseLeaseAsync(lease, true);
            }
        }
    }

    private void handleReportsMenu(Player player, ReportsHolder holder, int slot) {
        if (claimingPlayers.contains(player.getUniqueId())) {
            return;
        }
        if (slot == LIST_SWITCH_SLOT) {
            ReportListType target = holder.getListType() == ReportListType.ACTIVE ? ReportListType.HISTORY : ReportListType.ACTIVE;
            openList(player, target, holder.getFilter(), 0);
            return;
        }
        if (slot == LIST_BACK_SLOT && holder.getPage() > 0) {
            openList(player, holder.getListType(), holder.getFilter(), holder.getPage() - 1);
            return;
        }
        if (slot == LIST_FORWARD_SLOT && holder.getPage() + 1 < holder.getTotalPages()) {
            openList(player, holder.getListType(), holder.getFilter(), holder.getPage() + 1);
            return;
        }
        if (holder.getListType() != ReportListType.ACTIVE) {
            return;
        }
        ReportCase reportCase = holder.getCase(slot);
        if (reportCase != null) {
            claimCase(player, holder, reportCase);
        }
    }

    private void claimCase(Player player, ReportsHolder source, ReportCase reportCase) {
        UUID playerId = player.getUniqueId();
        if (!claimingPlayers.add(playerId)) {
            return;
        }
        String moderatorName = player.getName();
        runAsync(() -> {
            ReviewClaimResult result = reportManager.claimReview(reportCase.getId(), playerId, moderatorName);
            ReportCase currentCase = result.isAcquired() ? reportManager.getCase(reportCase.getId()).orElse(reportCase) : reportCase;
            runMain(() -> finishClaim(playerId, source, currentCase, result));
        });
    }

    private void finishClaim(UUID playerId, ReportsHolder source, ReportCase reportCase, ReviewClaimResult result) {
        claimingPlayers.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (result.isAcquired()) {
            ReviewLease lease = result.getLease();
            if (player == null || !player.isOnline() || !isSameListContext(player, source)) {
                releaseLeaseAsync(lease, true);
                return;
            }
            ReportsGUI.openActionGUI(player, reportCase, lease, source.getListType(), source.getFilter(), source.getPage());
            String moderatorName = player.getName();
            runAsync(() -> reportManager.addLog(
                    reportCase.getId(),
                    playerId,
                    moderatorName,
                    "open-actions",
                    reportCase.getReportedName(),
                    reportCase.getReportedUuid(),
                    null,
                    null
            ));
            requestReportListRefresh();
            return;
        }
        if (player == null || !player.isOnline()) {
            return;
        }
        if (result.getStatus() == ReviewClaimStatus.HELD_BY_OTHER) {
            send(player, "report-in-work", "&eРепорт на %player% уже разбирает %moderator%.", Map.of(
                    "%player%", reportCase.getReportedName(),
                    "%moderator%", result.getOwner() == null ? "-" : result.getOwner(),
                    "%server%", result.getServerId() == null ? "-" : result.getServerId(),
                    "%case_id%", String.valueOf(reportCase.getId())
            ));
            requestReportListRefresh();
            return;
        }
        if (result.getStatus() == ReviewClaimStatus.CASE_NOT_OPEN || result.getStatus() == ReviewClaimStatus.NOT_FOUND) {
            sendSessionExpired(player, reportCase);
            requestReportListRefresh();
            return;
        }
        send(player, "action-error", "&cНе удалось выполнить действие.", Map.of());
    }

    private void handleActionMenu(Player player, ReportActionHolder holder, int slot) {
        ReviewLease lease = holder.getLease();
        if (lease == null || !player.getUniqueId().equals(lease.getModeratorUuid())) {
            sendSessionExpired(player, holder.getReportCase());
            player.closeInventory();
            return;
        }
        if (activeOperations.containsKey(player.getUniqueId()) || validatingPlayers.contains(player.getUniqueId())) {
            return;
        }
        if (holder.getMenuType() == ReportMenuType.ACTION) {
            handleActionClick(player, holder, slot);
        } else if (holder.getMenuType() == ReportMenuType.CLOSE_REASON_SELECTION) {
            handleCloseReasonSelectionClick(player, holder, slot);
        } else if (holder.getMenuType() == ReportMenuType.BAN_REASON_SELECTION) {
            handleBanReasonSelectionClick(player, holder, slot);
        } else if (holder.getMenuType() == ReportMenuType.BAN_CONFIRMATION) {
            handleBanConfirmationClick(player, holder, slot);
        }
    }

    private void handleActionClick(Player player, ReportActionHolder holder, int slot) {
        if (slot == ACTION_BACK_SLOT) {
            releaseAndOpenList(player, holder);
            return;
        }
        if (slot == ACTION_CLOSE_SLOT) {
            if (!requirePermission(player, "cloverreports.action.delete")) {
                return;
            }
            renewThen(player, holder, ReportManager.LIVE_STATUS_WAITING_DECISION, () -> ReportsGUI.openCloseReasonSelectionGUI(player, holder, 0));
            return;
        }
        if (slot == ACTION_EVIDENCE_SLOT) {
            if (!requirePermission(player, "cloverreports.evidence.view")) {
                return;
            }
            renewThen(player, holder, ReportManager.LIVE_STATUS_IN_WORK, () -> sendEvidence(player, holder.getReportCase()));
            return;
        }
        if (slot == ACTION_TELEPORT_SLOT) {
            if (!requirePermission(player, "cloverreports.action.teleport")) {
                return;
            }
            renewThen(player, holder, ReportManager.LIVE_STATUS_IN_WORK, () -> teleportToReported(player, holder));
            return;
        }
        if (slot == ACTION_NOTE_SLOT) {
            if (!requirePermission(player, "cloverreports.note")) {
                return;
            }
            beginNoteInput(player, holder);
            return;
        }
        if (slot == ACTION_BAN_SLOT) {
            if (!requirePermission(player, "cloverreports.action.ban")) {
                return;
            }
            renewThen(player, holder, ReportManager.LIVE_STATUS_WAITING_DECISION, () -> ReportsGUI.openBanReasonSelectionGUI(player, holder, 0));
        }
    }

    private void handleCloseReasonSelectionClick(Player player, ReportActionHolder holder, int slot) {
        if (slot == CLOSE_REASON_BACK_SLOT) {
            renewThen(player, holder, ReportManager.LIVE_STATUS_IN_WORK, () -> openAction(player, holder));
            return;
        }
        if (slot == CLOSE_REASON_PREVIOUS_SLOT && holder.getReasonPage() > 0) {
            renewThen(player, holder, ReportManager.LIVE_STATUS_WAITING_DECISION, () -> ReportsGUI.openCloseReasonSelectionGUI(player, holder, holder.getReasonPage() - 1));
            return;
        }
        if (slot == CLOSE_REASON_NEXT_SLOT) {
            renewThen(player, holder, ReportManager.LIVE_STATUS_WAITING_DECISION, () -> ReportsGUI.openCloseReasonSelectionGUI(player, holder, holder.getReasonPage() + 1));
            return;
        }
        String reason = holder.getReason(slot);
        if (reason == null || !requirePermission(player, "cloverreports.action.delete")) {
            return;
        }
        resolveClosedCase(player, holder, reason);
    }

    private void handleBanReasonSelectionClick(Player player, ReportActionHolder holder, int slot) {
        if (slot == BAN_REASON_BACK_SLOT) {
            renewThen(player, holder, ReportManager.LIVE_STATUS_IN_WORK, () -> openAction(player, holder));
            return;
        }
        if (slot == BAN_REASON_PREVIOUS_SLOT && holder.getReasonPage() > 0) {
            renewThen(player, holder, ReportManager.LIVE_STATUS_WAITING_DECISION, () -> ReportsGUI.openBanReasonSelectionGUI(player, holder, holder.getReasonPage() - 1));
            return;
        }
        if (slot == BAN_REASON_NEXT_SLOT) {
            renewThen(player, holder, ReportManager.LIVE_STATUS_WAITING_DECISION, () -> ReportsGUI.openBanReasonSelectionGUI(player, holder, holder.getReasonPage() + 1));
            return;
        }
        String reason = holder.getReason(slot);
        if (reason == null || !requirePermission(player, "cloverreports.action.ban")) {
            return;
        }
        renewThen(player, holder, ReportManager.LIVE_STATUS_WAITING_DECISION, () -> {
            ReportCase reportCase = holder.getReportCase();
            UUID moderatorUuid = player.getUniqueId();
            String moderatorName = player.getName();
            runAsync(() -> reportManager.addLog(
                    reportCase.getId(),
                    moderatorUuid,
                    moderatorName,
                    "select-ban-reason",
                    reportCase.getReportedName(),
                    reportCase.getReportedUuid(),
                    reason,
                    null
            ));
            ReportsGUI.openBanConfirmationGUI(player, holder, reason);
        });
    }

    private void handleBanConfirmationClick(Player player, ReportActionHolder holder, int slot) {
        if (slot == BAN_CANCEL_SLOT) {
            renewThen(player, holder, ReportManager.LIVE_STATUS_WAITING_DECISION, () -> ReportsGUI.openBanReasonSelectionGUI(player, holder, holder.getReasonPage()));
            return;
        }
        if (slot != BAN_CONFIRM_SLOT || !requirePermission(player, "cloverreports.action.ban")) {
            return;
        }
        startPunishment(player, holder);
    }

    private void resolveClosedCase(Player player, ReportActionHolder holder, String reason) {
        UUID playerId = player.getUniqueId();
        OperationSession operation = new OperationSession(holder.getLease());
        if (activeOperations.putIfAbsent(playerId, operation) != null) {
            return;
        }
        player.closeInventory();
        runAsync(() -> {
            boolean resolved = reportManager.resolveCase(holder.getLease(), ReportManager.ACTION_CLOSED, reason);
            if (!resolved) {
                reportManager.releaseReview(holder.getLease());
            }
            runMain(() -> {
                activeOperations.remove(playerId, operation);
                Player online = Bukkit.getPlayer(playerId);
                if (online != null && online.isOnline()) {
                    if (resolved) {
                        send(online, "report-deleted", "&aРепорты на %player% закрыты. Причина: %reason%", Map.of(
                                "%player%", holder.getReportedPlayer(),
                                "%reason%", reason,
                                "%case_id%", String.valueOf(holder.getCaseId())
                        ));
                    } else {
                        send(online, "action-error", "&cНе удалось выполнить действие.", Map.of());
                    }
                }
                requestReportListRefresh();
            });
        });
    }

    private void startPunishment(Player player, ReportActionHolder holder) {
        UUID playerId = player.getUniqueId();
        OperationSession operation = new OperationSession(holder.getLease());
        if (activeOperations.putIfAbsent(playerId, operation) != null) {
            return;
        }
        String moderatorName = player.getName();
        String reason = holder.getBanReason() == null || holder.getBanReason().isBlank() ? Messages.getPlain("ban-reason") : holder.getBanReason();
        player.closeInventory();
        runAsync(() -> {
            boolean started = reportManager.startPunishment(holder.getLease(), reason);
            if (!started) {
                reportManager.releaseReview(holder.getLease());
                runMain(() -> finishPunishment(playerId, operation, holder, PunishmentResult.notStarted()));
                return;
            }
            runMain(() -> {
                boolean commandsSuccessful = executePunishmentCommands(moderatorName, holder.getReportedPlayer(), reason);
                runAsync(() -> {
                    boolean finalized = commandsSuccessful
                            ? reportManager.completePunishment(holder.getLease(), reason)
                            : reportManager.cancelPunishment(holder.getLease());
                    if (!finalized || !commandsSuccessful) {
                        reportManager.releaseReview(holder.getLease());
                    }
                    PunishmentResult result = new PunishmentResult(true, commandsSuccessful, finalized);
                    runMain(() -> finishPunishment(playerId, operation, holder, result));
                });
            });
        });
    }

    private void finishPunishment(UUID playerId, OperationSession operation, ReportActionHolder holder, PunishmentResult result) {
        activeOperations.remove(playerId, operation);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            if (!result.started || result.commandsSuccessful && !result.finalized) {
                send(player, "action-error", "&cНе удалось выполнить действие.", Map.of());
            } else if (!result.commandsSuccessful) {
                send(player, "punishment-command-error", "&cНе все команды наказания для %player% выполнились успешно.", Map.of(
                        "%player%", holder.getReportedPlayer(),
                        "%case_id%", String.valueOf(holder.getCaseId())
                ));
            } else {
                send(player, "banned", "&aИгрок %player% наказан.", Map.of(
                        "%player%", holder.getReportedPlayer(),
                        "%case_id%", String.valueOf(holder.getCaseId())
                ));
            }
        }
        if (result.started && result.commandsSuccessful && result.finalized) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                send(online, "ban-announce", "&eИгрок %player% был наказан.", Map.of(
                        "%player%", holder.getReportedPlayer(),
                        "%case_id%", String.valueOf(holder.getCaseId())
                ));
            }
        }
        requestReportListRefresh();
    }

    private void beginNoteInput(Player player, ReportActionHolder holder) {
        UUID playerId = player.getUniqueId();
        if (pendingNoteInputs.containsKey(playerId)) {
            send(player, "chat-input-busy", "&eСначала завершите текущий ввод в чат.", Map.of());
            return;
        }
        if (!ChatInputRegistry.claim(playerId, NOTE_INPUT_OWNER)) {
            send(player, "chat-input-busy", "&eСначала завершите текущий ввод в чат.", Map.of());
            return;
        }
        boolean started = renewThen(
                player,
                holder,
                ReportManager.LIVE_STATUS_IN_WORK,
                () -> activateNoteInput(player, holder),
                () -> ChatInputRegistry.release(playerId, NOTE_INPUT_OWNER)
        );
        if (!started) {
            ChatInputRegistry.release(playerId, NOTE_INPUT_OWNER);
        }
    }

    private void activateNoteInput(Player player, ReportActionHolder holder) {
        UUID playerId = player.getUniqueId();
        PendingNoteInput input = new PendingNoteInput(holder);
        if (pendingNoteInputs.putIfAbsent(playerId, input) != null) {
            send(player, "chat-input-busy", "&eСначала завершите текущий ввод в чат.", Map.of());
            return;
        }
        player.closeInventory();
        send(player, "note-input-start", "&bНапишите заметку по %player% в чат. Для отмены нажмите ЛКМ по воздуху или блоку.", Map.of(
                "%player%", holder.getReportedPlayer(),
                "%case_id%", String.valueOf(holder.getCaseId())
        ));
        scheduleNoteInputTimeout(playerId, input);
    }

    private void beginNoteProcessing(UUID playerId, PendingNoteInput input, OperationSession operation, String message) {
        cancelNoteInputTimeout(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            activeOperations.remove(playerId, operation);
            releaseLeaseAsync(input.lease, true);
            return;
        }
        if (!player.hasPermission("cloverreports.note")) {
            activeOperations.remove(playerId, operation);
            send(player, "no-permission", "&cУ вас нет прав для этого действия.", Map.of());
            releaseLeaseAsync(input.lease, true);
            return;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.equals("cancel") || normalized.equals("\u043e\u0442\u043c\u0435\u043d\u0430")) {
            send(player, "note-input-cancelled", "&eДобавление заметки по %player% отменено.", Map.of(
                    "%player%", input.reportCase.getReportedName(),
                    "%case_id%", String.valueOf(input.reportCase.getId())
            ));
            renewNoteContext(playerId, input, operation);
            return;
        }
        int maximumLength = Math.max(1, plugin.getConfig().getInt("note-input.max-length", 512));
        if (message.isBlank() || message.length() > maximumLength) {
            if (message.length() > maximumLength) {
                send(player, "note-too-long", "&cЗаметка не должна быть длиннее %limit% символов.", Map.of("%limit%", String.valueOf(maximumLength)));
            } else {
                send(player, "action-error", "&cНе удалось выполнить действие.", Map.of());
            }
            renewNoteContext(playerId, input, operation);
            return;
        }

        runAsync(() -> {
            boolean renewed = reportManager.renewReview(input.lease, ReportManager.LIVE_STATUS_IN_WORK);
            ModeratorNoteUpdateResult result = renewed
                    ? reportManager.updateModeratorNote(input.reportCase.getId(), message, playerId, input.moderatorName)
                    : ModeratorNoteUpdateResult.NO_ACTIVE_REPORTS;
            runMain(() -> finishNoteUpdate(playerId, input, operation, renewed, result, message));
        });
    }

    private void renewNoteContext(UUID playerId, PendingNoteInput input, OperationSession operation) {
        runAsync(() -> {
            boolean renewed = reportManager.renewReview(input.lease, ReportManager.LIVE_STATUS_IN_WORK);
            runMain(() -> {
                activeOperations.remove(playerId, operation);
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    if (renewed) {
                        releaseLeaseAsync(input.lease, true);
                    }
                    return;
                }
                if (!renewed) {
                    sendSessionExpired(player, input.reportCase);
                    requestReportListRefresh();
                    return;
                }
                openAction(player, input);
            });
        });
    }

    private void finishNoteUpdate(UUID playerId, PendingNoteInput input, OperationSession operation, boolean renewed, ModeratorNoteUpdateResult result, String message) {
        activeOperations.remove(playerId, operation);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            if (renewed) {
                releaseLeaseAsync(input.lease, true);
            }
            return;
        }
        if (!renewed) {
            sendSessionExpired(player, input.reportCase);
            requestReportListRefresh();
            return;
        }
        if (result == ModeratorNoteUpdateResult.SUCCESS) {
            send(player, "note-updated", "&aЗаметка для %player% добавлена: %note%", Map.of(
                    "%player%", input.reportCase.getReportedName(),
                    "%note%", ChatUtil.escapeUserText(message),
                    "%case_id%", String.valueOf(input.reportCase.getId())
            ));
            requestReportListRefresh();
            openAction(player, input);
            return;
        }
        if (result == ModeratorNoteUpdateResult.LIMIT_REACHED) {
            send(player, "note-limit", "&cУ %player% уже максимум заметок: %limit%", Map.of(
                    "%player%", input.reportCase.getReportedName(),
                    "%limit%", String.valueOf(ReportManager.MAX_MODERATOR_NOTES_PER_PLAYER),
                    "%case_id%", String.valueOf(input.reportCase.getId())
            ));
            openAction(player, input);
            return;
        }
        if (result == ModeratorNoteUpdateResult.NO_ACTIVE_REPORTS) {
            send(player, "note-no-active", "&cАктивное дело по %player% не найдено.", Map.of(
                    "%player%", input.reportCase.getReportedName(),
                    "%case_id%", String.valueOf(input.reportCase.getId())
            ));
        } else {
            send(player, "action-error", "&cНе удалось выполнить действие.", Map.of());
        }
        releaseLeaseAsync(input.lease, true);
        requestReportListRefresh();
    }

    private void scheduleNoteInputTimeout(UUID playerId, PendingNoteInput input) {
        long seconds = Math.max(15L, plugin.getConfig().getLong("note-input.timeout-seconds", 120L));
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            noteInputTimeouts.remove(playerId);
            if (!pendingNoteInputs.remove(playerId, input)) {
                return;
            }
            ChatInputRegistry.release(playerId, NOTE_INPUT_OWNER);
            releaseLeaseAsync(input.lease, true);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                send(player, "note-input-expired", "&eВремя добавления заметки по %player% вышло.", Map.of(
                        "%player%", input.reportCase.getReportedName(),
                        "%case_id%", String.valueOf(input.reportCase.getId())
                ));
            }
        }, seconds * 20L);
        BukkitTask previous = noteInputTimeouts.put(playerId, timeout);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void cancelNoteInputTimeout(UUID playerId) {
        BukkitTask timeout = noteInputTimeouts.remove(playerId);
        if (timeout != null) {
            timeout.cancel();
        }
    }

    private void sendEvidence(Player player, ReportCase reportCase) {
        if (!player.hasPermission("cloverreports.evidence.view")) {
            send(player, "no-permission", "&cУ вас нет прав для просмотра доказательств.", Map.of());
            return;
        }
        Map<String, String> links = new LinkedHashMap<>();
        for (Report report : reportCase.getReports()) {
            String value = report.getEvidenceUrl();
            if (value == null || value.isBlank()) {
                continue;
            }
            EvidenceUrlValidator.ValidationResult validation = evidenceValidator.validate(value);
            if (validation.isValid()) {
                links.putIfAbsent(validation.getNormalizedUrl(), report.getReporter());
            }
        }
        if (links.isEmpty()) {
            send(player, "evidence-empty", "&eВ деле #%case_id% нет доступных доказательств.", Map.of(
                    "%player%", reportCase.getReportedName(),
                    "%case_id%", String.valueOf(reportCase.getId())
            ));
            return;
        }

        send(player, "evidence-header", "&bДоказательства по делу #%case_id% игрока %player%:", Map.of(
                "%player%", reportCase.getReportedName(),
                "%case_id%", String.valueOf(reportCase.getId()),
                "%count%", String.valueOf(links.size())
        ));
        int maximum = Math.max(1, plugin.getConfig().getInt("report.evidence.max-chat-links", 25));
        int sent = 0;
        for (Map.Entry<String, String> entry : links.entrySet()) {
            if (sent >= maximum) {
                break;
            }
            sendEvidenceLink(player, reportCase, entry.getValue(), entry.getKey());
            sent++;
        }
        if (links.size() > sent) {
            send(player, "evidence-omitted", "&7Не показано ссылок: %count%", Map.of(
                    "%count%", String.valueOf(links.size() - sent),
                    "%case_id%", String.valueOf(reportCase.getId())
            ));
        }
    }

    private void sendEvidenceLink(Player player, ReportCase reportCase, String reporter, String url) {
        Map<String, String> placeholders = Map.of(
                "%reporter%", ChatUtil.escapeUserText(reporter),
                "%player%", reportCase.getReportedName(),
                "%case_id%", String.valueOf(reportCase.getId())
        );
        List<String> lines = messageLines("evidence-link", "&7%reporter%:", placeholders);
        Component link = Component.text(url)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(url)));
        boolean appended = false;
        for (String line : lines) {
            String stripped = ChatColor.stripColor(line);
            Component component = deserialize(line);
            if (!appended && stripped != null && !stripped.isBlank()) {
                component = component.append(Component.space()).append(link);
                appended = true;
            }
            player.sendMessage(component);
        }
        if (!appended) {
            player.sendMessage(link);
        }
    }

    private void teleportToReported(Player player, ReportActionHolder holder) {
        ReportCase reportCase = holder.getReportCase();
        Player target = reportCase.getReportedUuid() == null
                ? Bukkit.getPlayerExact(reportCase.getReportedName())
                : Bukkit.getPlayer(reportCase.getReportedUuid());
        if (target == null || !target.isOnline()) {
            send(player, "player-not-found", "&cИгрок %player% не найден онлайн.", Map.of("%player%", reportCase.getReportedName()));
            return;
        }
        player.teleport(target.getLocation());
        if (plugin.getConfig().getBoolean("actions.teleport-spectator", true)) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        send(player, "teleport-message", "&bТелепортация к игроку %player%.", Map.of(
                "%player%", target.getName(),
                "%case_id%", String.valueOf(reportCase.getId())
        ));
        UUID moderatorUuid = player.getUniqueId();
        String moderatorName = player.getName();
        runAsync(() -> reportManager.addLog(
                reportCase.getId(),
                moderatorUuid,
                moderatorName,
                "teleport",
                reportCase.getReportedName(),
                reportCase.getReportedUuid(),
                null,
                null
        ));
        player.closeInventory();
    }

    private boolean renewThen(Player player, ReportActionHolder holder, String status, Runnable action) {
        return renewThen(player, holder, status, action, () -> {
        });
    }

    private boolean renewThen(Player player, ReportActionHolder holder, String status, Runnable action, Runnable abandoned) {
        UUID playerId = player.getUniqueId();
        if (!validatingPlayers.add(playerId)) {
            return false;
        }
        runAsync(() -> {
            boolean renewed = reportManager.renewReview(holder.getLease(), status);
            runMain(() -> {
                validatingPlayers.remove(playerId);
                Player online = Bukkit.getPlayer(playerId);
                if (online == null || !online.isOnline() || !isCurrentHolder(online, holder)) {
                    abandoned.run();
                    if (renewed) {
                        releaseLeaseAsync(holder.getLease(), true);
                    }
                    return;
                }
                if (!renewed) {
                    abandoned.run();
                    expireSession(online, holder.getReportCase(), holder.getLease());
                    return;
                }
                action.run();
            });
        });
        return true;
    }

    private void releaseAndOpenList(Player player, ReportActionHolder holder) {
        UUID playerId = player.getUniqueId();
        if (!validatingPlayers.add(playerId)) {
            return;
        }
        runAsync(() -> {
            reportManager.releaseReview(holder.getLease());
            runMain(() -> {
                validatingPlayers.remove(playerId);
                Player online = Bukkit.getPlayer(playerId);
                if (online != null && online.isOnline()) {
                    openList(online, holder.getReturnListType(), holder.getReturnFilter(), holder.getReturnPage());
                }
                requestReportListRefresh();
            });
        });
    }

    private void openAction(Player player, ReportActionHolder source) {
        ReportsGUI.openActionGUI(
                player,
                source.getReportCase(),
                source.getLease(),
                source.getReturnListType(),
                source.getReturnFilter(),
                source.getReturnPage()
        );
    }

    private void openAction(Player player, PendingNoteInput input) {
        ReportsGUI.openActionGUI(
                player,
                input.reportCase,
                input.lease,
                input.returnListType,
                input.returnFilter,
                input.returnPage
        );
    }

    private void openList(Player player, ReportListType listType, HistoryFilter filter, int page) {
        ReportsGUI.openReportsGUI(player, reportManager, listType, filter, page);
    }

    private boolean requirePermission(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        send(player, "no-permission", "&cУ вас нет прав для этого действия.", Map.of());
        return false;
    }

    private void expireSession(Player player, ReportCase reportCase, ReviewLease lease) {
        PendingNoteInput pending = pendingNoteInputs.get(player.getUniqueId());
        if (pending != null && sameLease(pending.lease, lease) && pendingNoteInputs.remove(player.getUniqueId(), pending)) {
            ChatInputRegistry.release(player.getUniqueId(), NOTE_INPUT_OWNER);
            cancelNoteInputTimeout(player.getUniqueId());
        }
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        if (holder instanceof ReportActionHolder && sameLease(((ReportActionHolder) holder).getLease(), lease)) {
            player.closeInventory();
        }
        sendSessionExpired(player, reportCase);
        requestReportListRefresh();
    }

    private void sendSessionExpired(Player player, ReportCase reportCase) {
        send(player, "report-session-expired", "&eСессия разбора дела по %player% истекла.", Map.of(
                "%player%", reportCase.getReportedName(),
                "%case_id%", String.valueOf(reportCase.getId())
        ));
    }

    private boolean isLeaseRetained(UUID playerId, ReviewLease lease) {
        PendingNoteInput pending = pendingNoteInputs.get(playerId);
        if (pending != null && sameLease(pending.lease, lease)) {
            return true;
        }
        OperationSession operation = activeOperations.get(playerId);
        if (matches(operation, lease)) {
            return true;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return false;
        }
        InventoryHolder current = player.getOpenInventory().getTopInventory().getHolder();
        return current instanceof ReportActionHolder && sameLease(((ReportActionHolder) current).getLease(), lease);
    }

    private boolean isCurrentHolder(Player player, ReportActionHolder expected) {
        return player.getOpenInventory().getTopInventory().getHolder() == expected;
    }

    private boolean isSameListContext(Player player, ReportsHolder expected) {
        InventoryHolder current = player.getOpenInventory().getTopInventory().getHolder();
        if (!(current instanceof ReportsHolder)) {
            return false;
        }
        ReportsHolder holder = (ReportsHolder) current;
        return holder.getListType() == expected.getListType()
                && holder.getPage() == expected.getPage()
                && sameFilter(holder.getFilter(), expected.getFilter());
    }

    private boolean sameFilter(HistoryFilter first, HistoryFilter second) {
        return Objects.equals(first.getPlayer(), second.getPlayer())
                && Objects.equals(first.getModerator(), second.getModerator())
                && Objects.equals(first.getReason(), second.getReason())
                && Objects.equals(first.getReasonKey(), second.getReasonKey())
                && Objects.equals(first.getAction(), second.getAction())
                && first.getFromTimestamp() == second.getFromTimestamp()
                && first.getToTimestamp() == second.getToTimestamp();
    }

    private boolean matches(OperationSession operation, ReviewLease lease) {
        return operation != null && sameLease(operation.lease, lease);
    }

    private boolean sameLease(ReviewLease first, ReviewLease second) {
        return first != null
                && second != null
                && first.getCaseId() == second.getCaseId()
                && first.getToken().equals(second.getToken());
    }

    private void releaseLeaseAsync(ReviewLease lease, boolean refresh) {
        if (lease == null) {
            return;
        }
        runAsync(() -> {
            boolean released = reportManager.releaseReview(lease);
            if (refresh && released) {
                runMain(this::requestReportListRefresh);
            }
        });
    }

    private void requestReportListRefresh() {
        if (refreshTask != null && !refreshTask.isCancelled()) {
            return;
        }
        refreshTask = Bukkit.getScheduler().runTask(plugin, () -> {
            refreshTask = null;
            for (Player online : Bukkit.getOnlinePlayers()) {
                InventoryHolder holder = online.getOpenInventory().getTopInventory().getHolder();
                if (holder instanceof ReportsHolder) {
                    ReportsHolder reportsHolder = (ReportsHolder) holder;
                    openList(online, reportsHolder.getListType(), reportsHolder.getFilter(), reportsHolder.getPage());
                }
            }
        });
    }

    private void startRenewalTask() {
        long timeout = Math.max(30L, plugin.getConfig().getLong("review.timeout-seconds", 300L));
        long interval = Math.max(5L, Math.min(30L, timeout / 3L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::renewOpenSessions, interval * 20L, interval * 20L);
    }

    private void startListRefreshTask() {
        long seconds = Math.max(2L, plugin.getConfig().getLong("review.list-refresh-seconds", 5L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::requestReportListRefresh, seconds * 20L, seconds * 20L);
    }

    private void renewOpenSessions() {
        if (!renewalRunning.compareAndSet(false, true)) {
            return;
        }
        Map<UUID, RenewalSession> sessions = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof ReportActionHolder) {
                ReportActionHolder actionHolder = (ReportActionHolder) holder;
                String status = actionHolder.getMenuType() == ReportMenuType.ACTION
                        ? ReportManager.LIVE_STATUS_IN_WORK
                        : ReportManager.LIVE_STATUS_WAITING_DECISION;
                addRenewal(sessions, new RenewalSession(player.getUniqueId(), actionHolder.getReportCase(), actionHolder.getLease(), status));
            }
        }
        for (Map.Entry<UUID, PendingNoteInput> entry : pendingNoteInputs.entrySet()) {
            PendingNoteInput input = entry.getValue();
            addRenewal(sessions, new RenewalSession(entry.getKey(), input.reportCase, input.lease, ReportManager.LIVE_STATUS_IN_WORK));
        }
        for (Map.Entry<UUID, OperationSession> entry : activeOperations.entrySet()) {
            OperationSession operation = entry.getValue();
            addRenewal(sessions, new RenewalSession(entry.getKey(), null, operation.lease, ReportManager.LIVE_STATUS_WAITING_DECISION));
        }
        if (sessions.isEmpty()) {
            renewalRunning.set(false);
            return;
        }

        runAsync(() -> {
            List<RenewalSession> failed = new ArrayList<>();
            for (RenewalSession session : sessions.values()) {
                if (!reportManager.renewReview(session.lease, session.status)) {
                    failed.add(session);
                }
            }
            runMain(() -> {
                renewalRunning.set(false);
                boolean changed = false;
                for (RenewalSession session : failed) {
                    OperationSession operation = activeOperations.get(session.playerId);
                    if (matches(operation, session.lease)) {
                        continue;
                    }
                    Player player = Bukkit.getPlayer(session.playerId);
                    PendingNoteInput pending = pendingNoteInputs.get(session.playerId);
                    ReportCase pendingCase = null;
                    if (pending != null && sameLease(pending.lease, session.lease) && pendingNoteInputs.remove(session.playerId, pending)) {
                        ChatInputRegistry.release(session.playerId, NOTE_INPUT_OWNER);
                        cancelNoteInputTimeout(session.playerId);
                        pendingCase = pending.reportCase;
                        changed = true;
                    }
                    boolean notified = false;
                    if (player != null && player.isOnline()) {
                        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                        if (holder instanceof ReportActionHolder && sameLease(((ReportActionHolder) holder).getLease(), session.lease)) {
                            player.closeInventory();
                            ReportCase reportCase = session.reportCase == null ? ((ReportActionHolder) holder).getReportCase() : session.reportCase;
                            sendSessionExpired(player, reportCase);
                            notified = true;
                            changed = true;
                        }
                        if (!notified && pendingCase != null) {
                            sendSessionExpired(player, pendingCase);
                        }
                    }
                }
                if (changed) {
                    requestReportListRefresh();
                }
            });
        });
    }

    private void addRenewal(Map<UUID, RenewalSession> sessions, RenewalSession candidate) {
        RenewalSession current = sessions.get(candidate.lease.getToken());
        if (current == null || ReportManager.LIVE_STATUS_WAITING_DECISION.equals(candidate.status)) {
            sessions.put(candidate.lease.getToken(), candidate);
        }
    }

    private boolean executePunishmentCommands(String moderatorName, String reportedPlayer, String reason) {
        List<String> commands = ReportReasons.getPunishmentCommands(reason);
        if (commands.isEmpty()) {
            commands = plugin.getConfig().getStringList("punishments.ban.commands");
        }
        if (commands.isEmpty()) {
            commands = List.of("ban %player% %reason%");
        }
        boolean success = true;
        for (String command : commands) {
            String prepared = command
                    .replace("%player%", reportedPlayer)
                    .replace("%moderator%", moderatorName)
                    .replace("%reason%", reason);
            if (prepared.startsWith("/")) {
                prepared = prepared.substring(1);
            }
            if (prepared.isBlank() || !Bukkit.dispatchCommand(Bukkit.getConsoleSender(), prepared)) {
                success = false;
            }
        }
        return success;
    }

    private void send(Player player, String path, String fallback, Map<String, String> placeholders) {
        player.sendMessage(messageLines(path, fallback, placeholders).toArray(new String[0]));
    }

    private List<String> messageLines(String path, String fallback, Map<String, String> placeholders) {
        List<String> lines = Messages.getChatList(path, placeholders);
        if (lines.size() != 1 || !lines.get(0).equals("messages." + path)) {
            return lines;
        }
        return List.of(ChatUtil.color("&7"), ChatUtil.color(replace(fallback, placeholders)), ChatUtil.color("&7"));
    }

    private String replace(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private Component deserialize(String value) {
        return LegacyComponentSerializer.legacySection().deserialize(value);
    }

    private void runAsync(Runnable action) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, action);
        }
    }

    private void runMain(Runnable action) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private static final class PendingNoteInput {

        private final ReportCase reportCase;
        private final ReviewLease lease;
        private final ReportListType returnListType;
        private final HistoryFilter returnFilter;
        private final int returnPage;
        private final String moderatorName;

        private PendingNoteInput(ReportActionHolder holder) {
            this.reportCase = holder.getReportCase();
            this.lease = holder.getLease();
            this.returnListType = holder.getReturnListType();
            this.returnFilter = holder.getReturnFilter();
            this.returnPage = holder.getReturnPage();
            this.moderatorName = holder.getLease().getModeratorName();
        }
    }

    private static final class OperationSession {

        private final ReviewLease lease;

        private OperationSession(ReviewLease lease) {
            this.lease = lease;
        }
    }

    private static final class RenewalSession {

        private final UUID playerId;
        private final ReportCase reportCase;
        private final ReviewLease lease;
        private final String status;

        private RenewalSession(UUID playerId, ReportCase reportCase, ReviewLease lease, String status) {
            this.playerId = playerId;
            this.reportCase = reportCase;
            this.lease = lease;
            this.status = status;
        }
    }

    private static final class PunishmentResult {

        private final boolean started;
        private final boolean commandsSuccessful;
        private final boolean finalized;

        private PunishmentResult(boolean started, boolean commandsSuccessful, boolean finalized) {
            this.started = started;
            this.commandsSuccessful = commandsSuccessful;
            this.finalized = finalized;
        }

        private static PunishmentResult notStarted() {
            return new PunishmentResult(false, false, false);
        }
    }
}
