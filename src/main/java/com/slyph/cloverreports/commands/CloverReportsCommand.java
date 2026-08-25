package com.slyph.cloverreports.commands;

import com.slyph.cloverreports.CloverReports;
import com.slyph.cloverreports.commands.filters.HistoryFilterParser;
import com.slyph.cloverreports.database.DatabaseManager;
import com.slyph.cloverreports.export.ExportFormat;
import com.slyph.cloverreports.export.ExportResult;
import com.slyph.cloverreports.export.ExportStatus;
import com.slyph.cloverreports.export.LogExportService;
import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.managers.ReportManager.ModeratorNoteUpdateResult;
import com.slyph.cloverreports.models.HistoryFilter;
import com.slyph.cloverreports.models.LogPage;
import com.slyph.cloverreports.models.ReportLog;
import com.slyph.cloverreports.utils.ChatUtil;
import com.slyph.cloverreports.utils.InputValidator;
import com.slyph.cloverreports.utils.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

public final class CloverReportsCommand implements CommandExecutor {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final CloverReports plugin;
    private final LogExportService exportService;

    public CloverReportsCommand(CloverReports plugin, LogExportService exportService) {
        this.plugin = plugin;
        this.exportService = exportService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("backup")) {
            return handleBackup(sender);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("note")) {
            return handleNote(sender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("logs")) {
            return handleLogs(sender, args);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("export")) {
            return handleExport(sender, args);
        }
        sender.sendMessage(Messages.getChatArray("cloverreports-usage"));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("cloverreports.reload")) {
            sender.sendMessage(Messages.getChatArray("no-permission"));
            return true;
        }
        boolean reloaded;
        try {
            reloaded = plugin.reloadPlugin();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("CloverReports reload error: " + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
            reloaded = false;
        }
        sender.sendMessage(Messages.getChatArray(reloaded ? "reload-success" : "reload-error"));
        return true;
    }

    private boolean handleBackup(CommandSender sender) {
        if (!sender.hasPermission("cloverreports.backup")) {
            sender.sendMessage(Messages.getChatArray("no-permission"));
            return true;
        }
        DatabaseManager databaseManager = plugin.getDatabaseManager();
        if (databaseManager == null) {
            sender.sendMessage(Messages.getChatArray("action-error"));
            return true;
        }
        sender.sendMessage(Messages.getChatArray("backup-started"));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DatabaseManager.BackupResult result = databaseManager.createSqliteBackup();
            Bukkit.getScheduler().runTask(plugin, () -> sendBackupResult(sender, result));
        });
        return true;
    }

    private boolean handleNote(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cloverreports.note")) {
            sender.sendMessage(Messages.getChatArray("no-permission"));
            return true;
        }
        String playerName = args[1];
        if (!InputValidator.isValidPlayerName(playerName)) {
            sender.sendMessage(Messages.getChatArray("invalid-player-name", Map.of("%player%", playerName)));
            return true;
        }
        String note = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        boolean clear = note.equalsIgnoreCase("clear");
        if (clear && !sender.hasPermission("cloverreports.note.clear-all")) {
            sender.sendMessage(Messages.getChatArray("no-permission"));
            return true;
        }
        int maximumLength = Math.max(1, plugin.getConfig().getInt("note-input.max-length", 512));
        if (!clear && (note.isBlank() || note.length() > maximumLength || !InputValidator.isSingleLine(note))) {
            sender.sendMessage(Messages.getChatArray("note-too-long", Map.of("%limit%", String.valueOf(maximumLength))));
            return true;
        }
        String storedNote = clear ? null : ChatUtil.escapeUserText(note);
        UUID moderatorUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : UUID.nameUUIDFromBytes(("console:" + sender.getName()).getBytes(StandardCharsets.UTF_8));
        String moderatorName = sender.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ReportManager manager = plugin.getReportManager();
            OptionalLong caseId = manager.findOpenCaseId(manager.resolveKnownPlayerUuid(playerName), playerName);
            ModeratorNoteUpdateResult result = caseId.isPresent()
                    ? manager.updateModeratorNote(caseId.getAsLong(), storedNote, moderatorUuid, moderatorName)
                    : ModeratorNoteUpdateResult.NO_ACTIVE_REPORTS;
            Bukkit.getScheduler().runTask(plugin, () -> sendNoteResult(sender, playerName, note, clear, result));
        });
        return true;
    }

    private boolean handleLogs(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cloverreports.logs")) {
            sender.sendMessage(Messages.getChatArray("no-permission"));
            return true;
        }
        HistoryFilterParser.Result parsed = HistoryFilterParser.parse(args, 1, ZoneId.systemDefault());
        if (!parsed.isValid()) {
            sender.sendMessage(Messages.getChatArray("history-filter-invalid", Map.of("%filter%", parsed.getInvalidToken())));
            return true;
        }
        HistoryFilter filter = parsed.getFilter();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            LogPage page = plugin.getReportManager().getLogPage(filter, parsed.getPage(), 10);
            Bukkit.getScheduler().runTask(plugin, () -> sendLogs(sender, filter, page));
        });
        return true;
    }

    private boolean handleExport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cloverreports.export")) {
            sender.sendMessage(Messages.getChatArray("no-permission"));
            return true;
        }
        Optional<ExportFormat> format = ExportFormat.parse(args[1]);
        if (format.isEmpty()) {
            sender.sendMessage(Messages.getChatArray("export-usage"));
            return true;
        }
        HistoryFilterParser.Result parsed = HistoryFilterParser.parse(args, 2, ZoneId.systemDefault());
        if (!parsed.isValid()) {
            sender.sendMessage(Messages.getChatArray("history-filter-invalid", Map.of("%filter%", parsed.getInvalidToken())));
            return true;
        }
        UUID requester = sender instanceof Player
                ? ((Player) sender).getUniqueId()
                : UUID.nameUUIDFromBytes(("console:" + sender.getName()).getBytes(StandardCharsets.UTF_8));
        sender.sendMessage(Messages.getChatArray("export-started", Map.of("%format%", format.get().getExtension().toUpperCase())));
        exportService.export(requester, format.get(), parsed.getFilter()).whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                sender.sendMessage(Messages.getChatArray("export-error", Map.of("%error%", ChatUtil.escapeUserText(throwable.getMessage()))));
                return;
            }
            sendExportResult(sender, result);
        }));
        return true;
    }

    private void sendBackupResult(CommandSender sender, DatabaseManager.BackupResult result) {
        if (!result.isSupported()) {
            sender.sendMessage(Messages.getChatArray("backup-unsupported", Map.of("%storage%", result.getStorageType())));
        } else if (!result.isSuccess() || result.getBackupFile() == null) {
            sender.sendMessage(Messages.getChatArray("backup-error", Map.of("%error%", result.getError() == null ? "-" : result.getError())));
        } else {
            sender.sendMessage(Messages.getChatArray("backup-success", Map.of("%file%", result.getBackupFile().getName(), "%path%", result.getBackupFile().getName())));
        }
    }

    private void sendNoteResult(CommandSender sender, String playerName, String note, boolean clear, ModeratorNoteUpdateResult result) {
        if (result == ModeratorNoteUpdateResult.LIMIT_REACHED) {
            sender.sendMessage(Messages.getChatArray("note-limit", Map.of("%player%", playerName, "%limit%", String.valueOf(ReportManager.MAX_MODERATOR_NOTES_PER_PLAYER))));
        } else if (result == ModeratorNoteUpdateResult.NO_ACTIVE_REPORTS) {
            sender.sendMessage(Messages.getChatArray("note-no-active", Map.of("%player%", playerName)));
        } else if (result == ModeratorNoteUpdateResult.ERROR) {
            sender.sendMessage(Messages.getChatArray("action-error"));
        } else {
            sender.sendMessage(Messages.getChatArray(clear ? "note-cleared" : "note-updated", Map.of("%player%", playerName, "%note%", clear ? "" : ChatUtil.escapeUserText(note))));
        }
    }

    private void sendLogs(CommandSender sender, HistoryFilter filter, LogPage page) {
        String player = filter.getPlayer() == null ? "*" : filter.getPlayer();
        List<ReportLog> logs = page.getLogs();
        if (logs.isEmpty()) {
            sender.sendMessage(Messages.getChatArray("logs-empty", Map.of("%player%", player)));
            return;
        }
        sender.sendMessage(Messages.getChatArray("logs-header", Map.of(
                "%player%", player,
                "%page%", String.valueOf(page.getPage() + 1),
                "%total%", String.valueOf(page.getTotalPages()),
                "%count%", String.valueOf(page.getTotalItems())
        )));
        for (ReportLog log : logs) {
            sender.sendMessage(Messages.getChatArray("logs-line", Map.of(
                    "%id%", String.valueOf(log.getId()),
                    "%case_id%", String.valueOf(log.getCaseId()),
                    "%time%", TIME_FORMATTER.format(Instant.ofEpochMilli(log.getTimestamp())),
                    "%moderator%", log.getModerator(),
                    "%action%", log.getAction(),
                    "%player%", log.getReported(),
                    "%reason%", log.getReason() == null ? "-" : ChatUtil.escapeUserText(log.getReason()),
                    "%note%", log.getNote() == null ? "-" : ChatUtil.escapeUserText(log.getNote())
            )));
        }
    }

    private void sendExportResult(CommandSender sender, ExportResult result) {
        if (result.getStatus() == ExportStatus.SUCCESS && result.getFile() != null) {
            sender.sendMessage(Messages.getChatArray("export-success", Map.of(
                    "%file%", result.getFile().getFileName().toString(),
                    "%path%", result.getFile().getFileName().toString(),
                    "%rows%", String.valueOf(result.getRowCount())
            )));
        } else if (result.getStatus() == ExportStatus.ALREADY_RUNNING) {
            sender.sendMessage(Messages.getChatArray("export-already-running"));
        } else if (result.getStatus() == ExportStatus.LIMIT_EXCEEDED) {
            sender.sendMessage(Messages.getChatArray("export-limit", Map.of("%limit%", String.valueOf(result.getMaxRows()))));
        } else {
            sender.sendMessage(Messages.getChatArray("export-error", Map.of("%error%", result.getError() == null ? result.getStatus().name() : ChatUtil.escapeUserText(result.getError()))));
        }
    }
}
