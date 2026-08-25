package com.slyph.cloverreports.export;

import com.slyph.cloverreports.models.ReportLog;

import java.io.IOException;
import java.io.Writer;
import java.util.UUID;

final class JsonLogExportWriter implements LogExportWriter {

    private boolean first;

    @Override
    public void begin(Writer writer) throws IOException {
        first = true;
        writer.write("[\n");
    }

    @Override
    public void write(Writer writer, ReportLog log) throws IOException {
        if (!first) {
            writer.write(",\n");
        }
        first = false;
        writer.write("  {");
        writeNumber(writer, "id", log.getId(), true);
        writeNumber(writer, "case_id", log.getCaseId(), false);
        writeString(writer, "moderator_uuid", uuid(log.getModeratorUuid()));
        writeString(writer, "moderator", log.getModerator());
        writeString(writer, "action", log.getAction());
        writeString(writer, "reported_uuid", uuid(log.getReportedUuid()));
        writeString(writer, "reported", log.getReported());
        writeString(writer, "reason", log.getReason());
        writeString(writer, "note", log.getNote());
        writeNumber(writer, "timestamp", log.getTimestamp(), false);
        writeString(writer, "case_action", log.getCaseAction());
        writeNumber(writer, "case_resolved_at", log.getCaseResolvedAt(), false);
        writer.write("}");
    }

    @Override
    public void finish(Writer writer) throws IOException {
        writer.write("\n]\n");
        writer.flush();
    }

    private static void writeString(Writer writer, String name, String value) throws IOException {
        writer.write(',');
        writeQuoted(writer, name);
        writer.write(':');
        if (value == null) {
            writer.write("null");
        } else {
            writeQuoted(writer, value);
        }
    }

    private static void writeNumber(Writer writer, String name, long value, boolean first) throws IOException {
        if (!first) {
            writer.write(',');
        }
        writeQuoted(writer, name);
        writer.write(':');
        writer.write(Long.toString(value));
    }

    private static void writeQuoted(Writer writer, String value) throws IOException {
        writer.write('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    writer.write("\\\"");
                    break;
                case '\\':
                    writer.write("\\\\");
                    break;
                case '\b':
                    writer.write("\\b");
                    break;
                case '\f':
                    writer.write("\\f");
                    break;
                case '\n':
                    writer.write("\\n");
                    break;
                case '\r':
                    writer.write("\\r");
                    break;
                case '\t':
                    writer.write("\\t");
                    break;
                default:
                    if (character < 0x20 || character == '\u2028' || character == '\u2029' || isUnpairedSurrogate(value, index)) {
                        writeUnicodeEscape(writer, character);
                    } else {
                        writer.write(character);
                    }
                    break;
            }
        }
        writer.write('"');
    }

    private static boolean isUnpairedSurrogate(String value, int index) {
        char character = value.charAt(index);
        if (Character.isHighSurrogate(character)) {
            return index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        return Character.isLowSurrogate(character) && (index == 0 || !Character.isHighSurrogate(value.charAt(index - 1)));
    }

    private static void writeUnicodeEscape(Writer writer, char character) throws IOException {
        String hex = Integer.toHexString(character);
        writer.write("\\u");
        for (int index = hex.length(); index < 4; index++) {
            writer.write('0');
        }
        writer.write(hex);
    }

    private static String uuid(UUID value) {
        return value == null ? null : value.toString();
    }
}
