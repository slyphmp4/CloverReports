package com.slyph.cloverreports.export;

import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.models.HistoryFilter;
import com.slyph.cloverreports.models.ReportLog;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class LogExportService implements AutoCloseable {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int DEFAULT_MAX_ROWS = 100_000;
    private static final int DEFAULT_BATCH_SIZE = 1_000;

    private final JavaPlugin plugin;
    private final ReportManager reportManager;
    private final Path dataDirectory;
    private final Path exportDirectory;
    private final int maxRows;
    private final int batchSize;
    private final ConcurrentMap<UUID, CompletableFuture<ExportResult>> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LogExportService(JavaPlugin plugin, ReportManager reportManager) {
        this(
                plugin,
                reportManager,
                plugin.getConfig().getInt("export.max-rows", DEFAULT_MAX_ROWS),
                plugin.getConfig().getInt("export.batch-size", DEFAULT_BATCH_SIZE)
        );
    }

    public LogExportService(JavaPlugin plugin, ReportManager reportManager, int maxRows, int batchSize) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.reportManager = Objects.requireNonNull(reportManager, "reportManager");
        this.dataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        this.exportDirectory = dataDirectory.resolve("exports").normalize();
        if (!exportDirectory.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("Invalid export directory");
        }
        this.maxRows = Math.max(1, maxRows);
        this.batchSize = Math.max(1, Math.min(2_000, batchSize));
    }

    public CompletableFuture<ExportResult> export(UUID requester, ExportFormat format, HistoryFilter filter) {
        return export(requester, null, format, filter);
    }

    public CompletableFuture<ExportResult> export(UUID requester, String requestedName, ExportFormat format, HistoryFilter filter) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(format, "format");
        if (closed.get()) {
            return CompletableFuture.completedFuture(ExportResult.serviceClosed());
        }

        CompletableFuture<ExportResult> future = new CompletableFuture<>();
        if (jobs.putIfAbsent(requester, future) != null) {
            return CompletableFuture.completedFuture(ExportResult.alreadyRunning());
        }

        HistoryFilter safeFilter = filter == null ? HistoryFilter.empty() : filter;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> runExport(requester, requestedName, format, safeFilter, future));
        } catch (RuntimeException exception) {
            jobs.remove(requester, future);
            plugin.getLogger().log(Level.SEVERE, "Could not schedule report log export", exception);
            future.complete(ExportResult.failed(exceptionMessage(exception)));
        }
        return future;
    }

    public boolean isRunning(UUID requester) {
        return requester != null && jobs.containsKey(requester);
    }

    public int getRunningJobCount() {
        return jobs.size();
    }

    public int getMaxRows() {
        return maxRows;
    }

    public Path getExportDirectory() {
        return exportDirectory;
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private void runExport(UUID requester, String requestedName, ExportFormat format, HistoryFilter filter, CompletableFuture<ExportResult> future) {
        Path partFile = null;
        try {
            if (closed.get()) {
                future.complete(ExportResult.serviceClosed());
                return;
            }
            int totalRows = reportManager.countLogs(filter);
            if (totalRows > maxRows) {
                future.complete(ExportResult.limitExceeded(maxRows));
                return;
            }

            Files.createDirectories(dataDirectory);
            Files.createDirectories(exportDirectory);
            verifyExportDirectory();
            Path targetFile = createTargetPath(requester, requestedName, format);
            partFile = targetFile.resolveSibling(targetFile.getFileName().toString() + ".part");
            LogExportWriter exportWriter = createWriter(format);
            int rowCount;
            try (BufferedWriter writer = Files.newBufferedWriter(
                    partFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                exportWriter.begin(writer);
                rowCount = writeBatches(writer, exportWriter, filter);
                exportWriter.finish(writer);
            }
            moveCompleted(partFile, targetFile);
            partFile = null;
            future.complete(ExportResult.success(targetFile, rowCount));
        } catch (RowLimitException exception) {
            future.complete(ExportResult.limitExceeded(maxRows));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not export report logs", exception);
            future.complete(ExportResult.failed(exceptionMessage(exception)));
        } finally {
            if (partFile != null) {
                try {
                    Files.deleteIfExists(partFile);
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not delete incomplete report log export", exception);
                }
            }
            jobs.remove(requester, future);
        }
    }

    private int writeBatches(BufferedWriter writer, LogExportWriter exportWriter, HistoryFilter filter) throws IOException, RowLimitException {
        long beforeTimestamp = 0L;
        int beforeId = 0;
        int rowCount = 0;
        while (true) {
            if (closed.get()) {
                throw new IOException("Export service is closed");
            }
            int remaining = maxRows - rowCount;
            int requestedRows = remaining >= batchSize ? batchSize : remaining + 1;
            List<ReportLog> logs = reportManager.getLogBatch(filter, beforeTimestamp, beforeId, requestedRows);
            if (logs.isEmpty()) {
                return rowCount;
            }
            for (ReportLog log : logs) {
                if (rowCount >= maxRows) {
                    throw new RowLimitException();
                }
                exportWriter.write(writer, log);
                rowCount++;
            }
            if (logs.size() < requestedRows) {
                return rowCount;
            }
            ReportLog last = logs.get(logs.size() - 1);
            if (last.getTimestamp() <= 0L || (last.getTimestamp() == beforeTimestamp && last.getId() == beforeId)) {
                throw new IOException("Report log cursor did not advance");
            }
            beforeTimestamp = last.getTimestamp();
            beforeId = last.getId();
        }
    }

    private Path createTargetPath(UUID requester, String requestedName, ExportFormat format) throws IOException {
        String baseName = sanitizeBaseName(requestedName);
        if (baseName.isEmpty()) {
            baseName = "report-logs";
        }
        String timestamp = FILE_TIMESTAMP.format(LocalDateTime.now());
        String requesterPart = requester.toString().substring(0, 8);
        String uniquePart = UUID.randomUUID().toString().substring(0, 8);
        String stem = baseName + "-" + timestamp + "-" + requesterPart + "-" + uniquePart;
        for (int attempt = 0; attempt < 100; attempt++) {
            String suffix = attempt == 0 ? "" : "-" + attempt;
            Path candidate = exportDirectory.resolve(stem + suffix + "." + format.getExtension()).normalize();
            verifyTarget(candidate);
            Path part = candidate.resolveSibling(candidate.getFileName().toString() + ".part");
            if (!Files.exists(candidate) && !Files.exists(part)) {
                return candidate;
            }
        }
        throw new IOException("Could not allocate export file name");
    }

    private String sanitizeBaseName(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            return "";
        }
        String value = requestedName.trim();
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            String extension = value.substring(dot + 1);
            if (ExportFormat.parse(extension).isPresent()) {
                value = value.substring(0, dot);
            }
        }
        StringBuilder result = new StringBuilder(Math.min(64, value.length()));
        boolean separator = false;
        for (int index = 0; index < value.length() && result.length() < 64; index++) {
            char character = value.charAt(index);
            if (character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-') {
                result.append(character);
                separator = false;
            } else if (!separator && result.length() > 0) {
                result.append('-');
                separator = true;
            }
        }
        while (result.length() > 0 && result.charAt(result.length() - 1) == '-') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    private void verifyExportDirectory() throws IOException {
        Path realData = dataDirectory.toRealPath();
        Path realExport = exportDirectory.toRealPath();
        if (!realExport.startsWith(realData)) {
            throw new IOException("Export directory is outside plugin data directory");
        }
    }

    private void verifyTarget(Path target) throws IOException {
        if (!target.startsWith(exportDirectory) || !target.getParent().equals(exportDirectory)) {
            throw new IOException("Invalid export file path");
        }
    }

    private static LogExportWriter createWriter(ExportFormat format) {
        if (format == ExportFormat.CSV) {
            return new CsvLogExportWriter();
        }
        return new JsonLogExportWriter();
    }

    private static void moveCompleted(Path partFile, Path targetFile) throws IOException {
        try {
            Files.move(partFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(partFile, targetFile);
        }
    }

    private static String exceptionMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static final class RowLimitException extends Exception {

        private static final long serialVersionUID = 1L;
    }
}
