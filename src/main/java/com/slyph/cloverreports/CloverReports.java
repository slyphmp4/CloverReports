package com.slyph.cloverreports;

import com.slyph.cloverreports.commands.CloverReportsCommand;
import com.slyph.cloverreports.commands.CloverReportsTabCompleter;
import com.slyph.cloverreports.commands.ReportCommand;
import com.slyph.cloverreports.commands.ReportTabCompleter;
import com.slyph.cloverreports.commands.ViewReportsCommand;
import com.slyph.cloverreports.commands.ViewReportsTabCompleter;
import com.slyph.cloverreports.database.DatabaseManager;
import com.slyph.cloverreports.export.LogExportService;
import com.slyph.cloverreports.gui.GUIListener;
import com.slyph.cloverreports.gui.submission.ReportSubmissionListener;
import com.slyph.cloverreports.identity.PlayerIdentityListener;
import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.reasons.ReportReasons;
import com.slyph.cloverreports.utils.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class CloverReports extends JavaPlugin {

    private static CloverReports instance;
    private DatabaseManager databaseManager;
    private ReportManager reportManager;
    private LogExportService exportService;
    private ReportSubmissionListener submissionListener;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        Messages.load(this);
        ReportReasons.load(this);

        databaseManager = new DatabaseManager(this);
        if (!databaseManager.connect() || !databaseManager.createReportsTable()) {
            getLogger().severe("CloverReports не смог подключиться к базе данных и будет выключен.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        reportManager = new ReportManager(this, databaseManager);
        exportService = new LogExportService(this, reportManager);
        submissionListener = new ReportSubmissionListener(this, reportManager);
        registerCommands();

        GUIListener guiListener = new GUIListener(reportManager);
        PlayerIdentityListener identityListener = new PlayerIdentityListener(this, reportManager);
        getServer().getPluginManager().registerEvents(guiListener, this);
        getServer().getPluginManager().registerEvents(submissionListener, this);
        getServer().getPluginManager().registerEvents(identityListener, this);
        for (Player player : getServer().getOnlinePlayers()) {
            identityListener.register(player);
        }

        getServer().getScheduler().runTaskAsynchronously(this, reportManager::cleanupOldReports);
        getServer().getScheduler().runTaskTimerAsynchronously(this, reportManager::recoverInterruptedPunishments, 1_200L, 1_200L);
        getServer().getConsoleSender().sendMessage(Messages.getChatArray("plugin-enabled"));
    }

    @Override
    public void onDisable() {
        if (exportService != null) {
            exportService.close();
        }
        if (reportManager != null) {
            reportManager.releaseServerReviews();
        }
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        if (instance != null) {
            getServer().getConsoleSender().sendMessage(Messages.getChatArray("plugin-disabled"));
        }
        instance = null;
    }

    private void registerCommands() {
        PluginCommand reportCommand = Objects.requireNonNull(getCommand("report"), "report");
        reportCommand.setExecutor(new ReportCommand(submissionListener));
        reportCommand.setTabCompleter(new ReportTabCompleter());
        reportCommand.setPermissionMessage(String.join("\n", Messages.getChatList("no-permission")));

        PluginCommand viewReportsCommand = Objects.requireNonNull(getCommand("viewreports"), "viewreports");
        viewReportsCommand.setExecutor(new ViewReportsCommand(reportManager));
        viewReportsCommand.setTabCompleter(new ViewReportsTabCompleter(reportManager));
        viewReportsCommand.setPermissionMessage(String.join("\n", Messages.getChatList("no-permission")));

        PluginCommand cloverReportsCommand = Objects.requireNonNull(getCommand("cloverreports"), "cloverreports");
        cloverReportsCommand.setExecutor(new CloverReportsCommand(this, exportService));
        cloverReportsCommand.setTabCompleter(new CloverReportsTabCompleter(reportManager));
    }

    public boolean reloadPlugin() {
        reloadConfig();
        getConfig().options().copyDefaults(true);
        Messages.load(this);
        ReportReasons.load(this);

        DatabaseManager candidate = new DatabaseManager(this);
        if (!candidate.connect() || !candidate.createReportsTable()) {
            candidate.disconnect();
            return false;
        }

        DatabaseManager previous = databaseManager;
        databaseManager = candidate;
        if (reportManager == null) {
            reportManager = new ReportManager(this, candidate);
        } else {
            previous = reportManager.replaceDatabase(candidate);
        }
        if (previous != null && previous != candidate) {
            previous.disconnect();
        }

        String permissionMessage = String.join("\n", Messages.getChatList("no-permission"));
        PluginCommand reportCommand = getCommand("report");
        PluginCommand viewReportsCommand = getCommand("viewreports");
        if (reportCommand != null) {
            reportCommand.setPermissionMessage(permissionMessage);
        }
        if (viewReportsCommand != null) {
            viewReportsCommand.setPermissionMessage(permissionMessage);
        }
        getServer().getScheduler().runTaskAsynchronously(this, reportManager::cleanupOldReports);
        return true;
    }

    public static CloverReports getInstance() {
        return instance;
    }

    public ReportManager getReportManager() {
        return reportManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public LogExportService getExportService() {
        return exportService;
    }

    public ReportSubmissionListener getSubmissionListener() {
        return submissionListener;
    }
}
