package com.slyph.cloverreports.export;

import java.util.Locale;
import java.util.Optional;

public enum ExportFormat {

    CSV("csv"),
    JSON("json");

    private final String extension;

    ExportFormat(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }

    public static Optional<ExportFormat> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ExportFormat format : values()) {
            if (format.extension.equals(normalized) || format.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
