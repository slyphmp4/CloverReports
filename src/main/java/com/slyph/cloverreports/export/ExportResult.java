package com.slyph.cloverreports.export;

import java.nio.file.Path;

public final class ExportResult {

    private final ExportStatus status;
    private final Path file;
    private final int rowCount;
    private final int maxRows;
    private final String error;

    private ExportResult(ExportStatus status, Path file, int rowCount, int maxRows, String error) {
        this.status = status;
        this.file = file;
        this.rowCount = rowCount;
        this.maxRows = maxRows;
        this.error = error;
    }

    public static ExportResult success(Path file, int rowCount) {
        return new ExportResult(ExportStatus.SUCCESS, file, rowCount, 0, null);
    }

    public static ExportResult alreadyRunning() {
        return new ExportResult(ExportStatus.ALREADY_RUNNING, null, 0, 0, null);
    }

    public static ExportResult limitExceeded(int maxRows) {
        return new ExportResult(ExportStatus.LIMIT_EXCEEDED, null, 0, maxRows, null);
    }

    public static ExportResult serviceClosed() {
        return new ExportResult(ExportStatus.SERVICE_CLOSED, null, 0, 0, null);
    }

    public static ExportResult failed(String error) {
        return new ExportResult(ExportStatus.FAILED, null, 0, 0, error);
    }

    public ExportStatus getStatus() {
        return status;
    }

    public Path getFile() {
        return file;
    }

    public int getRowCount() {
        return rowCount;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public String getError() {
        return error;
    }

    public boolean isSuccess() {
        return status == ExportStatus.SUCCESS;
    }
}
