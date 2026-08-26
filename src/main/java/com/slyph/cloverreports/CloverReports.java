package com.slyph.cloverreports;

import com.slyph.cloverreports.commands.CloverReportsCommand;
import com.slyph.cloverreports.commands.CloverReportsTabCompleter;
import com.slyph.cloverreports.commands.ReportCommand;
import com.slyph.cloverreports.commands.ReportSuggestionCache;
import com.slyph.cloverreports.commands.ReportTabCompleter;
import com.slyph.cloverreports.commands.ViewReportsCommand;
import com.slyph.cloverreports.commands.ViewReportsTabCompleter;
import com.slyph.cloverreports.database.DatabaseManager;
import com.slyph.cloverreports.export.LogExportService;
import com.slyph.cloverreports.gui.GUIListener;
import com.slyph.cloverreports.gui.submission.ReportSubmissionListener;
import com.slyph.cloverreports.identity.PlayerIdentityListener;
import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.models.ReportedPlayerIndex;
import com.slyph.cloverreports.reasons.ReportReasons;
import com.slyph.cloverreports.utils.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class CloverReports extends JavaPlugin {

    private static CloverReports instance;
    private DatabaseManager databaseManager;
    private ReportManager reportManager;
    private LogExportService exportService;
    private ReportSubmissionListener submissionListener;
    private final ReportSuggestionCache suggestionCache = new ReportSuggestionCache();
    private final AtomicBoolean reloadInProgress = new AtomicBoolean();
    private final AtomicLong suggestionGeneration = new AtomicLong();
    private BukkitTask suggestionRefreshTask;
    private ExecutorService databaseLifecycleExecutor;

    @Override
    public void onEnable() {
        instance = this;
        databaseLifecycleExecutor = createDatabaseLifecycleExecutor();
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        Messages.load(this);
        ReportReasons.load(this);

        try {
            databaseManager = DatabaseManager.fromCurrentConfig(this);
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Invalid database configuration", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!databaseManager.connect() || !databaseManager.createReportsTable()) {
            getLogger().severe("CloverReports не смог подключиться к базе данных и будет выключен.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        reportManager = new ReportManager(this, databaseManager);
        exportService = new LogExportService(this, reportManager);
        submissionListener = new ReportSubmissionListener(this, reportManager);
        registerCommands();
        scheduleSuggestionRefresh();

        GUIListener guiListener = new GUIListener(reportManager);
        PlayerIdentityListener identityListener = new PlayerIdentityListener(this, reportManager);
        getServer().getPluginManager().registerEvents(guiListener, this);
        getServer().getPluginManager().registerEvents(submissionListener, this);
        getServer().getPluginManager().registerEvents(identityListener, this);
        for (Player player : getServer().getOnlinePlayers()) {
            identityListener.register(player);
        }

        getServer().getScheduler().runTaskAsynchronously(this, reportManager::cleanupOldReports);
        getServer().getScheduler().runTaskTimerAsynchronously(this, reportManager::cleanupOldReports, 72_000L, 1_728_000L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, reportManager::recoverInterruptedPunishments, 1_200L, 1_200L);
        getServer().getConsoleSender().sendMessage(Messages.getChatArray("plugin-enabled"));
    }

    @Override
    public void onDisable() {
        if (suggestionRefreshTask != null) {
            suggestionRefreshTask.cancel();
            suggestionRefreshTask = null;
        }
        suggestionGeneration.incrementAndGet();
        suggestionCache.clear();
        if (exportService != null) {
            exportService.close();
        }
        ReportManager managerToClose = reportManager;
        DatabaseManager databaseToClose = databaseManager;
        reportManager = null;
        databaseManager = null;
        closeDatabaseLifecycleAsync(managerToClose, databaseToClose);
        if (instance != null) {
            getServer().getConsoleSender().sendMessage(Messages.getChatArray("plugin-disabled"));
        }
        instance = null;
    }

    private void registerCommands() {
        PluginCommand reportCommand = Objects.requireNonNull(getCommand("report"), "report");
        reportCommand.setExecutor(new ReportCommand(submissionListener));
        reportCommand.setTabCompleter(new ReportTabCompleter());

        PluginCommand viewReportsCommand = Objects.requireNonNull(getCommand("viewreports"), "viewreports");
        viewReportsCommand.setExecutor(new ViewReportsCommand(reportManager));
        viewReportsCommand.setTabCompleter(new ViewReportsTabCompleter(suggestionCache));

        PluginCommand cloverReportsCommand = Objects.requireNonNull(getCommand("cloverreports"), "cloverreports");
        cloverReportsCommand.setExecutor(new CloverReportsCommand(this, exportService));
        cloverReportsCommand.setTabCompleter(new CloverReportsTabCompleter(suggestionCache));
    }

    private void scheduleSuggestionRefresh() {
        if (suggestionRefreshTask != null) {
            suggestionRefreshTask.cancel();
        }
        long generation = suggestionGeneration.incrementAndGet();
        long refreshTicks = Math.max(1L, getConfig().getLong("review.list-refresh-seconds", 5L)) * 20L;
        suggestionRefreshTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> refreshSuggestionCache(generation), 0L, refreshTicks);
    }

    private void refreshSuggestionCache(long generation) {
        ReportManager current = reportManager;
        if (current == null) {
            return;
        }
        Optional<ReportedPlayerIndex> pending = current.getReportedPlayerIndex(ReportManager.STATUS_PENDING, 10_000);
        Optional<ReportedPlayerIndex> resolved = current.getReportedPlayerIndex(ReportManager.STATUS_RESOLVED, 10_000);
        if (generation == suggestionGeneration.get() && current == reportManager && pending.isPresent() && resolved.isPresent()) {
            suggestionCache.replace(pending.get(), resolved.get());
        }
    }

    public CompletableFuture<Boolean> reloadPluginAsync() {
        if (!reloadInProgress.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        DatabaseManager candidate;
        try {
            reloadConfig();
            getConfig().options().copyDefaults(true);
            Messages.load(this);
            ReportReasons.load(this);
            candidate = DatabaseManager.fromCurrentConfig(this);
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not load configuration during reload", exception);
            reloadInProgress.set(false);
            result.complete(false);
            return result;
        }

        ExecutorService executor = databaseLifecycleExecutor;
        if (executor == null || executor.isShutdown()) {
            reloadInProgress.set(false);
            result.complete(false);
            return result;
        }
        try {
            executor.execute(() -> prepareReload(candidate, result));
        } catch (RejectedExecutionException exception) {
            getLogger().log(Level.WARNING, "Database reload executor is unavailable", exception);
            reloadInProgress.set(false);
            result.complete(false);
        }
        return result;
    }

    private void prepareReload(DatabaseManager candidate, CompletableFuture<Boolean> result) {
        if (!candidate.connect() || !candidate.createReportsTable()) {
            candidate.disconnect();
            completeReload(result, false);
            return;
        }
        if (!isEnabled()) {
            candidate.disconnect();
            completeReload(result, false);
            return;
        }
        try {
            getServer().getScheduler().runTask(this, () -> applyReload(candidate, result));
        } catch (RuntimeException exception) {
            candidate.disconnect();
            getLogger().log(Level.WARNING, "Could not apply prepared database reload", exception);
            completeReload(result, false);
        }
    }

    private void applyReload(DatabaseManager candidate, CompletableFuture<Boolean> result) {
        if (!isEnabled()) {
            executeDatabaseLifecycle(candidate::disconnect);
            completeReload(result, false);
            return;
        }

        DatabaseManager previous = databaseManager;
        databaseManager = candidate;
        if (reportManager == null) {
            reportManager = new ReportManager(this, candidate);
        } else {
            previous = reportManager.replaceDatabase(candidate);
        }
        if (previous != null && previous != candidate) {
            executeDatabaseLifecycle(previous::disconnect);
        }

        suggestionCache.clear();
        scheduleSuggestionRefresh();
        getServer().getScheduler().runTaskAsynchronously(this, reportManager::cleanupOldReports);
        completeReload(result, true);
    }

    private void completeReload(CompletableFuture<Boolean> result, boolean success) {
        reloadInProgress.set(false);
        result.complete(success);
    }

    private ExecutorService createDatabaseLifecycleExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CloverReports-database-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
    }

    private boolean executeDatabaseLifecycle(Runnable operation) {
        ExecutorService executor = databaseLifecycleExecutor;
        if (executor == null || executor.isShutdown()) {
            return false;
        }
        try {
            executor.execute(operation);
            return true;
        } catch (RejectedExecutionException exception) {
            getLogger().log(Level.WARNING, "Database lifecycle operation was rejected", exception);
            return false;
        }
    }

    private void closeDatabaseLifecycleAsync(ReportManager manager, DatabaseManager database) {
        ExecutorService executor = databaseLifecycleExecutor;
        databaseLifecycleExecutor = null;
        if (executor == null) {
            if (database != null) {
                database.disconnect();
            }
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    if (manager != null) {
                        manager.releaseServerReviews();
                    }
                } finally {
                    if (database != null) {
                        database.disconnect();
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            getLogger().log(Level.WARNING, "Database shutdown operation was rejected", exception);
        } finally {
            executor.shutdown();
        }
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
