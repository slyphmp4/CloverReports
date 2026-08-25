package com.slyph.cloverreports.gui.submission;

import com.slyph.cloverreports.CloverReports;
import com.slyph.cloverreports.input.ChatInputRegistry;
import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.models.ReporterStats;
import com.slyph.cloverreports.reasons.ReportReason;
import com.slyph.cloverreports.submission.EvidenceUrlValidator;
import com.slyph.cloverreports.utils.ChatUtil;
import com.slyph.cloverreports.utils.InputValidator;
import com.slyph.cloverreports.utils.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ReportSubmissionListener implements Listener {

    private static final String CHAT_INPUT_OWNER = "report-evidence";
    private static final String BYPASS_COOLDOWN_PERMISSION = "cloverreports.report.cooldown.bypass";
    private static final String BYPASS_FALSE_REPORTS_PERMISSION = "cloverreports.report.false.bypass";
    private final CloverReports plugin;
    private final ReportManager reportManager;
    private final ReportSubmissionGUI gui;
    private final EvidenceUrlValidator evidenceValidator;
    private final SubmissionSuccessHandler successHandler;
    private final ConcurrentMap<UUID, PendingEvidenceInput> pendingEvidenceInputs;
    private final ConcurrentMap<UUID, BukkitTask> evidenceTimeouts;
    private final Set<UUID> submittingPlayers;
    private final ConcurrentMap<UUID, Long> cooldowns;

    public ReportSubmissionListener(CloverReports plugin, ReportManager reportManager) {
        this(plugin, reportManager, new ReportSubmissionGUI(), (player, targetName, caseId) -> {
        });
    }

    public ReportSubmissionListener(CloverReports plugin, ReportManager reportManager, ReportSubmissionGUI gui, SubmissionSuccessHandler successHandler) {
        this.plugin = plugin;
        this.reportManager = reportManager;
        this.gui = gui;
        this.evidenceValidator = new EvidenceUrlValidator(plugin);
        this.successHandler = successHandler;
        this.pendingEvidenceInputs = new ConcurrentHashMap<>();
        this.evidenceTimeouts = new ConcurrentHashMap<>();
        this.submittingPlayers = ConcurrentHashMap.newKeySet();
        this.cooldowns = new ConcurrentHashMap<>();
    }

    public void open(Player player, String targetName, UUID targetUuid) {
        if (!player.hasPermission("cloverreports.report")) {
            send(player, "no-permission", "&cУ вас нет прав.", Map.of());
            return;
        }
        if (!InputValidator.isValidPlayerName(targetName)) {
            send(player, "invalid-player-name", "&cНекорректный ник: %player%", Map.of("%player%", targetName == null ? "" : targetName));
            return;
        }

        Player onlineTarget = targetUuid == null ? Bukkit.getPlayerExact(targetName) : Bukkit.getPlayer(targetUuid);
        String canonicalName = onlineTarget == null ? targetName : onlineTarget.getName();
        UUID canonicalUuid = onlineTarget == null ? targetUuid : onlineTarget.getUniqueId();
        if ((canonicalUuid != null && canonicalUuid.equals(player.getUniqueId())) || canonicalName.equalsIgnoreCase(player.getName())) {
            send(player, "cannot-report-yourself", "&cНельзя подать жалобу на себя.", Map.of());
            return;
        }
        if (plugin.getConfig().getBoolean("report.online-only", false) && onlineTarget == null) {
            send(player, "player-not-found", "&cИгрок %player% не найден онлайн.", Map.of("%player%", canonicalName));
            return;
        }
        cancelEvidenceInput(player.getUniqueId());
        gui.openReasonSelection(player, canonicalName, canonicalUuid);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory topInventory = event.getView().getTopInventory();
        InventoryHolder inventoryHolder = topInventory.getHolder();
        if (!(inventoryHolder instanceof ReportSubmissionHolder)) {
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= topInventory.getSize()) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        ReportSubmissionHolder holder = (ReportSubmissionHolder) inventoryHolder;
        if (!player.hasPermission("cloverreports.report")) {
            player.closeInventory();
            send(player, "no-permission", "&cУ вас нет прав.", Map.of());
            return;
        }
        if (holder.getMenuType() == ReportSubmissionMenuType.REASON_SELECTION) {
            handleReasonSelection(player, holder, slot);
        } else {
            handleConfirmation(player, holder, slot, event.isRightClick());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ReportSubmissionHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        PendingEvidenceInput input = pendingEvidenceInputs.get(event.getPlayer().getUniqueId());
        if (input == null || !ChatInputRegistry.isOwnedBy(event.getPlayer().getUniqueId(), CHAT_INPUT_OWNER)) {
            return;
        }
        event.setCancelled(true);
        UUID playerUuid = event.getPlayer().getUniqueId();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleEvidenceChat(playerUuid, input, message));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        UUID playerUuid = event.getPlayer().getUniqueId();
        if (!ChatInputRegistry.isOwnedBy(playerUuid, CHAT_INPUT_OWNER)) {
            return;
        }
        PendingEvidenceInput input = pendingEvidenceInputs.get(playerUuid);
        if (input == null) {
            ChatInputRegistry.release(playerUuid, CHAT_INPUT_OWNER);
            return;
        }
        if (!pendingEvidenceInputs.remove(playerUuid, input)) {
            return;
        }
        event.setCancelled(true);
        ChatInputRegistry.release(playerUuid, CHAT_INPUT_OWNER);
        cancelEvidenceTimeout(playerUuid);
        send(event.getPlayer(), "evidence-input-cancelled", "&eДобавление ссылки отменено.", Map.of());
        gui.openConfirmation(event.getPlayer(), input.targetName, input.targetUuid, input.reasonPage, input.reason, input.previousEvidenceUrl);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        cancelEvidenceInput(playerUuid);
    }

    private void handleReasonSelection(Player player, ReportSubmissionHolder holder, int slot) {
        if (slot == ReportSubmissionGUI.PREVIOUS_SLOT && holder.getReasonPage() > 0) {
            gui.openReasonSelection(player, holder.getTargetName(), holder.getTargetUuid(), holder.getReasonPage() - 1, holder.getEvidenceUrl());
            return;
        }
        if (slot == ReportSubmissionGUI.NEXT_SLOT && holder.getReasonPage() + 1 < holder.getTotalReasonPages()) {
            gui.openReasonSelection(player, holder.getTargetName(), holder.getTargetUuid(), holder.getReasonPage() + 1, holder.getEvidenceUrl());
            return;
        }
        if (slot == ReportSubmissionGUI.CANCEL_SLOT) {
            cancelEvidenceInput(player.getUniqueId());
            player.closeInventory();
            return;
        }
        ReportReason reason = holder.getReason(slot);
        if (reason != null) {
            gui.openConfirmation(player, holder.getTargetName(), holder.getTargetUuid(), holder.getReasonPage(), reason, holder.getEvidenceUrl());
        }
    }

    private void handleConfirmation(Player player, ReportSubmissionHolder holder, int slot, boolean rightClick) {
        if (slot == ReportSubmissionGUI.BACK_SLOT) {
            gui.openReasonSelection(player, holder.getTargetName(), holder.getTargetUuid(), holder.getReasonPage(), holder.getEvidenceUrl());
            return;
        }
        if (slot == ReportSubmissionGUI.EVIDENCE_SLOT) {
            if (!player.hasPermission("cloverreports.report.evidence")) {
                send(player, "no-permission", "&cУ вас нет прав на добавление вложений.", Map.of());
                return;
            }
            if (rightClick && holder.getEvidenceUrl() != null) {
                gui.openConfirmation(player, holder.getTargetName(), holder.getTargetUuid(), holder.getReasonPage(), holder.getSelectedReason(), null);
                send(player, "evidence-removed", "&aСсылка удалена из жалобы.", Map.of());
                return;
            }
            beginEvidenceInput(player, holder);
            return;
        }
        if (slot == ReportSubmissionGUI.SUBMIT_SLOT) {
            submit(player, holder);
        }
    }

    private void beginEvidenceInput(Player player, ReportSubmissionHolder holder) {
        UUID playerUuid = player.getUniqueId();
        cancelEvidenceInput(playerUuid);
        if (!ChatInputRegistry.claim(playerUuid, CHAT_INPUT_OWNER)) {
            send(player, "chat-input-busy", "&eСначала завершите текущий ввод в чате.", Map.of());
            return;
        }
        PendingEvidenceInput input = new PendingEvidenceInput(holder.getTargetName(), holder.getTargetUuid(), holder.getReasonPage(), holder.getSelectedReason(), holder.getEvidenceUrl());
        pendingEvidenceInputs.put(playerUuid, input);
        long timeoutSeconds = Math.max(15L, plugin.getConfig().getLong("report.evidence.input-timeout-seconds", 120L));
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pendingEvidenceInputs.remove(playerUuid, input)) {
                return;
            }
            ChatInputRegistry.release(playerUuid, CHAT_INPUT_OWNER);
            evidenceTimeouts.remove(playerUuid);
            Player online = Bukkit.getPlayer(playerUuid);
            if (online != null) {
                send(online, "evidence-input-expired", "&eВремя ввода ссылки истекло.", Map.of());
            }
        }, timeoutSeconds * 20L);
        evidenceTimeouts.put(playerUuid, timeout);
        player.closeInventory();
        send(player, "evidence-input-start", "&bОтправьте в чат HTTP(S)-ссылку. &7Для отмены нажмите ЛКМ по воздуху или блоку.", Map.of());
    }

    private void handleEvidenceChat(UUID playerUuid, PendingEvidenceInput input, String message) {
        if (!pendingEvidenceInputs.remove(playerUuid, input)) {
            return;
        }
        ChatInputRegistry.release(playerUuid, CHAT_INPUT_OWNER);
        cancelEvidenceTimeout(playerUuid);
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        String normalizedInput = message.toLowerCase(Locale.ROOT);
        if (normalizedInput.equals("cancel") || normalizedInput.equals("отмена")) {
            send(player, "evidence-input-cancelled", "&eДобавление ссылки отменено.", Map.of());
            gui.openConfirmation(player, input.targetName, input.targetUuid, input.reasonPage, input.reason, input.previousEvidenceUrl);
            return;
        }
        if (normalizedInput.equals("remove") || normalizedInput.equals("удалить")) {
            send(player, "evidence-removed", "&aСсылка удалена из жалобы.", Map.of());
            gui.openConfirmation(player, input.targetName, input.targetUuid, input.reasonPage, input.reason, null);
            return;
        }

        EvidenceUrlValidator.ValidationResult result = evidenceValidator.validate(message);
        if (!result.isValid()) {
            send(player, "evidence-invalid", "&cНекорректная ссылка: &f%status%", Map.of("%status%", result.getStatus().name().toLowerCase(Locale.ROOT)));
            gui.openConfirmation(player, input.targetName, input.targetUuid, input.reasonPage, input.reason, input.previousEvidenceUrl);
            return;
        }
        send(player, "evidence-added", "&aСсылка добавлена к жалобе.", Map.of());
        gui.openConfirmation(player, input.targetName, input.targetUuid, input.reasonPage, input.reason, result.getNormalizedUrl());
    }

    private void submit(Player player, ReportSubmissionHolder holder) {
        cancelEvidenceInput(player.getUniqueId());
        ReportReason reason = holder.getSelectedReason();
        if (reason == null || !submittingPlayers.add(player.getUniqueId())) {
            return;
        }
        if (holder.getEvidenceUrl() != null && !player.hasPermission("cloverreports.report.evidence")) {
            send(player, "no-permission", "&cУ вас нет прав на добавление вложений.", Map.of());
            submittingPlayers.remove(player.getUniqueId());
            return;
        }
        String evidenceUrl;
        if (holder.getEvidenceUrl() != null) {
            EvidenceUrlValidator.ValidationResult validation = evidenceValidator.validate(holder.getEvidenceUrl());
            if (!validation.isValid()) {
                send(player, "evidence-invalid", "&cНекорректная ссылка: &f%status%", Map.of("%status%", validation.getStatus().name().toLowerCase(Locale.ROOT)));
                submittingPlayers.remove(player.getUniqueId());
                return;
            }
            evidenceUrl = validation.getNormalizedUrl();
        } else {
            evidenceUrl = null;
        }
        if ((holder.getTargetUuid() != null && holder.getTargetUuid().equals(player.getUniqueId())) || holder.getTargetName().equalsIgnoreCase(player.getName())) {
            send(player, "cannot-report-yourself", "&cНельзя подать жалобу на себя.", Map.of());
            submittingPlayers.remove(player.getUniqueId());
            return;
        }
        Player onlineTarget = holder.getTargetUuid() == null ? Bukkit.getPlayerExact(holder.getTargetName()) : Bukkit.getPlayer(holder.getTargetUuid());
        if (plugin.getConfig().getBoolean("report.online-only", false) && onlineTarget == null) {
            send(player, "player-not-found", "&cИгрок %player% не найден онлайн.", Map.of("%player%", holder.getTargetName()));
            submittingPlayers.remove(player.getUniqueId());
            return;
        }

        UUID reporterUuid = player.getUniqueId();
        String reporterName = player.getName();
        String targetName = holder.getTargetName();
        UUID targetUuid = holder.getTargetUuid();
        boolean bypassCooldown = player.hasPermission(BYPASS_COOLDOWN_PERMISSION);
        boolean bypassFalseReports = player.hasPermission(BYPASS_FALSE_REPORTS_PERMISSION);
        player.closeInventory();
        send(player, "report-submitting", "&eОтправляем жалобу...", Map.of());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AsyncSubmissionResult asyncResult;
            try {
                ReporterStats stats = bypassFalseReports ? new ReporterStats(0, 0) : reportManager.getReporterStats(reporterUuid, reporterName);
                boolean falsePenalty = !bypassFalseReports && isFalseReportPenaltyActive(stats);
                if (falsePenalty && isFalseReportBlockActive(stats)) {
                    asyncResult = AsyncSubmissionResult.falseBlocked(stats);
                } else {
                    long cooldownLeft = bypassCooldown ? 0L : getCooldownLeft(reporterUuid, falsePenalty);
                    if (cooldownLeft > 0L) {
                        asyncResult = AsyncSubmissionResult.cooldown(cooldownLeft, falsePenalty, stats);
                    } else {
                        UUID effectiveTargetUuid = targetUuid == null ? reportManager.resolveKnownPlayerUuid(targetName) : targetUuid;
                        if (reportManager.hasPunishedReport(effectiveTargetUuid, targetName)) {
                            asyncResult = AsyncSubmissionResult.punished();
                        } else {
                            ReportManager.SubmissionResult result = reportManager.submitReport(reporterUuid, reporterName, effectiveTargetUuid, targetName, reason.getKey(), reason.getName(), evidenceUrl);
                            int count = result.getStatus() == ReportManager.SubmissionStatus.SUCCESS ? reportManager.getActiveReportCount(targetName) : 0;
                            if (result.getStatus() == ReportManager.SubmissionStatus.SUCCESS && !bypassCooldown) {
                                cooldowns.put(reporterUuid, System.currentTimeMillis());
                                cleanupCooldowns();
                            }
                            asyncResult = AsyncSubmissionResult.database(result.getStatus(), result.getCaseId(), count);
                        }
                    }
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Report submission error: " + exception.getMessage());
                asyncResult = AsyncSubmissionResult.error();
            }
            AsyncSubmissionResult completedResult = asyncResult;
            Bukkit.getScheduler().runTask(plugin, () -> finishSubmission(reporterUuid, targetName, completedResult));
        });
    }

    private void finishSubmission(UUID reporterUuid, String targetName, AsyncSubmissionResult result) {
        submittingPlayers.remove(reporterUuid);
        Player player = Bukkit.getPlayer(reporterUuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (result.punished) {
            send(player, "already-punished", "&cИгрок %player% уже наказан.", Map.of("%player%", targetName));
            return;
        }
        if (result.falseBlocked) {
            send(player, "false-report-blocked", "&cВаши репорты временно ограничены.", falseReportPlaceholders(result.stats));
            return;
        }
        if (result.cooldownLeft > 0L) {
            Map<String, String> placeholders = result.falsePenalty ? falseReportPlaceholders(result.stats) : new HashMap<>();
            placeholders.put("%time%", String.valueOf(result.cooldownLeft));
            send(player, result.falsePenalty ? "false-report-cooldown" : "report-cooldown", "&cПодождите ещё %time% сек.", placeholders);
            return;
        }
        if (result.status == ReportManager.SubmissionStatus.DUPLICATE) {
            send(player, "already-reported", "&eВы уже подали жалобу на этого игрока.", Map.of());
            return;
        }
        if (result.status != ReportManager.SubmissionStatus.SUCCESS) {
            send(player, "report-error", "&cНе удалось отправить жалобу.", Map.of());
            return;
        }
        send(player, "report-sent", "&aЖалоба на %player% отправлена.", Map.of("%player%", targetName, "%case_id%", String.valueOf(result.caseId)));
        notifyModerators(targetName, result.count);
        successHandler.onSuccess(player, targetName, result.caseId);
    }

    private boolean isFalseReportPenaltyActive(ReporterStats stats) {
        if (!plugin.getConfig().getBoolean("false-reports.enabled", true)) {
            return false;
        }
        int minimumReviewed = plugin.getConfig().getInt("false-reports.min-reviewed-reports", 5);
        int maximumClosedPercent = plugin.getConfig().getInt("false-reports.max-closed-percent", 80);
        return stats.getReviewedReports() >= minimumReviewed && stats.isClosedPercentAtLeast(maximumClosedPercent);
    }

    private boolean isFalseReportBlockActive(ReporterStats stats) {
        if (!plugin.getConfig().getBoolean("false-reports.block-reporting", false)) {
            return false;
        }
        long blockSeconds = Math.max(1L, plugin.getConfig().getLong("false-reports.block-duration-seconds", 3_600L));
        return stats.getLatestReviewedAt() > 0L && System.currentTimeMillis() - stats.getLatestReviewedAt() < blockSeconds * 1_000L;
    }

    private long getCooldownLeft(UUID playerId, boolean falsePenalty) {
        int base = plugin.getConfig().getInt("report.cooldown-seconds", 60);
        int seconds = falsePenalty ? Math.max(base, plugin.getConfig().getInt("false-reports.penalty-cooldown-seconds", 300)) : base;
        if (seconds <= 0) {
            return 0L;
        }
        long remaining = cooldowns.getOrDefault(playerId, 0L) + seconds * 1_000L - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (long) Math.ceil(remaining / 1_000.0);
    }

    private Map<String, String> falseReportPlaceholders(ReporterStats stats) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%reviewed%", String.valueOf(stats.getReviewedReports()));
        placeholders.put("%closed%", String.valueOf(stats.getClosedReports()));
        placeholders.put("%punished%", String.valueOf(stats.getPunishedReports()));
        placeholders.put("%closed_percent%", String.valueOf(stats.getClosedPercent()));
        placeholders.put("%limit_percent%", String.valueOf(plugin.getConfig().getInt("false-reports.max-closed-percent", 80)));
        return placeholders;
    }

    private void cleanupCooldowns() {
        if (cooldowns.size() < 4_096) {
            return;
        }
        long maximumSeconds = Math.max(
                plugin.getConfig().getLong("report.cooldown-seconds", 60L),
                plugin.getConfig().getLong("false-reports.penalty-cooldown-seconds", 300L)
        );
        long cutoff = System.currentTimeMillis() - Math.max(0L, maximumSeconds) * 1_000L;
        cooldowns.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private void notifyModerators(String targetName, int count) {
        Map<String, String> placeholders = Map.of("%player%", targetName, "%count%", String.valueOf(count));
        Component hover = deserialize(String.join("\n", Messages.getChatList("notify-report-hover", placeholders)));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission("cloverreports.notify") || !online.hasPermission("cloverreports.view")) {
                continue;
            }
            for (String line : Messages.getChatList("notify-report", placeholders)) {
                online.sendMessage(deserialize(line)
                        .clickEvent(ClickEvent.runCommand("/viewreports player " + targetName))
                        .hoverEvent(HoverEvent.showText(hover)));
            }
        }
    }

    private Component deserialize(String line) {
        return LegacyComponentSerializer.legacySection().deserialize(ChatUtil.color(line));
    }

    private void send(Player player, String path, String fallback, Map<String, String> placeholders) {
        List<String> lines = Messages.getChatList(path, placeholders);
        if (lines.size() == 1 && lines.get(0).equals("messages." + path)) {
            lines = new ArrayList<>();
            lines.add(ChatUtil.color("&7"));
            lines.add(ChatUtil.color(replace(fallback, placeholders)));
            lines.add(ChatUtil.color("&7"));
        }
        player.sendMessage(lines.toArray(new String[0]));
    }

    private String replace(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private void cancelEvidenceInput(UUID playerUuid) {
        pendingEvidenceInputs.remove(playerUuid);
        cancelEvidenceTimeout(playerUuid);
        ChatInputRegistry.release(playerUuid, CHAT_INPUT_OWNER);
    }

    private void cancelEvidenceTimeout(UUID playerUuid) {
        BukkitTask timeout = evidenceTimeouts.remove(playerUuid);
        if (timeout != null) {
            timeout.cancel();
        }
    }

    @FunctionalInterface
    public interface SubmissionSuccessHandler {
        void onSuccess(Player player, String targetName, long caseId);
    }

    private static final class PendingEvidenceInput {

        private final String targetName;
        private final UUID targetUuid;
        private final int reasonPage;
        private final ReportReason reason;
        private final String previousEvidenceUrl;

        private PendingEvidenceInput(String targetName, UUID targetUuid, int reasonPage, ReportReason reason, String previousEvidenceUrl) {
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.reasonPage = reasonPage;
            this.reason = reason;
            this.previousEvidenceUrl = previousEvidenceUrl;
        }
    }

    private static final class AsyncSubmissionResult {

        private final ReportManager.SubmissionStatus status;
        private final long caseId;
        private final int count;
        private final boolean punished;
        private final long cooldownLeft;
        private final boolean falsePenalty;
        private final boolean falseBlocked;
        private final ReporterStats stats;

        private AsyncSubmissionResult(ReportManager.SubmissionStatus status, long caseId, int count, boolean punished, long cooldownLeft, boolean falsePenalty, boolean falseBlocked, ReporterStats stats) {
            this.status = status;
            this.caseId = caseId;
            this.count = count;
            this.punished = punished;
            this.cooldownLeft = cooldownLeft;
            this.falsePenalty = falsePenalty;
            this.falseBlocked = falseBlocked;
            this.stats = stats;
        }

        private static AsyncSubmissionResult database(ReportManager.SubmissionStatus status, long caseId, int count) {
            return new AsyncSubmissionResult(status, caseId, count, false, 0L, false, false, null);
        }

        private static AsyncSubmissionResult punished() {
            return new AsyncSubmissionResult(null, 0L, 0, true, 0L, false, false, null);
        }

        private static AsyncSubmissionResult cooldown(long cooldownLeft, boolean falsePenalty, ReporterStats stats) {
            return new AsyncSubmissionResult(null, 0L, 0, false, cooldownLeft, falsePenalty, false, stats);
        }

        private static AsyncSubmissionResult falseBlocked(ReporterStats stats) {
            return new AsyncSubmissionResult(null, 0L, 0, false, 0L, true, true, stats);
        }

        private static AsyncSubmissionResult error() {
            return new AsyncSubmissionResult(ReportManager.SubmissionStatus.ERROR, 0L, 0, false, 0L, false, false, null);
        }
    }
}
