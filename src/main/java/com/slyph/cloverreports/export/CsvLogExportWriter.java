package com.slyph.cloverreports.export;

import com.slyph.cloverreports.models.ReportLog;

import java.io.IOException;
import java.io.Writer;
import java.util.UUID;

final class CsvLogExportWriter implements LogExportWriter {

    private static final String[] HEADER = {
            "id",
            "case_id",
            "moderator_uuid",
            "moderator",
            "action",
            "reported_uuid",
            "reported",
            "reason",
            "note",
            "timestamp",
            "case_action",
            "case_resolved_at"
    };

    @Override
    public void begin(Writer writer) throws IOException {
        writeRow(writer, HEADER, false);
    }

    @Override
    public void write(Writer writer, ReportLog log) throws IOException {
        String[] values = {
                Integer.toString(log.getId()),
                Long.toString(log.getCaseId()),
                uuid(log.getModeratorUuid()),
                log.getModerator(),
                log.getAction(),
                uuid(log.getReportedUuid()),
                log.getReported(),
                log.getReason(),
                log.getNote(),
                Long.toString(log.getTimestamp()),
                log.getCaseAction(),
                Long.toString(log.getCaseResolvedAt())
        };
        writeRow(writer, values, true);
    }

    @Override
    public void finish(Writer writer) throws IOException {
        writer.flush();
    }

    private static void writeRow(Writer writer, String[] values, boolean protectFormulas) throws IOException {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                writer.write(',');
            }
            String value = values[index] == null ? "" : values[index];
            writeCell(writer, protectFormulas ? protectFormula(value) : value);
        }
        writer.write("\r\n");
    }

    private static void writeCell(Writer writer, String value) throws IOException {
        boolean quote = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == ',' || character == '"' || character == '\r' || character == '\n') {
                quote = true;
                break;
            }
        }
        if (!quote) {
            writer.write(value);
            return;
        }
        writer.write('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"') {
                writer.write("\"\"");
            } else {
                writer.write(character);
            }
        }
        writer.write('"');
    }

    private static String protectFormula(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        if (index >= value.length()) {
            return value;
        }
        char character = value.charAt(index);
        if (character == '=' || character == '+' || character == '-' || character == '@') {
            return "'" + value;
        }
        return value;
    }

    private static String uuid(UUID value) {
        return value == null ? "" : value.toString();
    }
}
