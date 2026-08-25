package com.slyph.cloverreports.export;

import com.slyph.cloverreports.models.ReportLog;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExportWriterTest {

    private static final UUID MODERATOR_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REPORTED_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void writesSafeRfc4180Csv() throws Exception {
        ReportLog log = new ReportLog(7, 42L, "=Moderator", MODERATOR_UUID, "close", "Player", REPORTED_UUID, "line,\"quote\"", "+SUM(1,1)", 123L, "closed", 124L);
        StringWriter output = new StringWriter();
        CsvLogExportWriter writer = new CsvLogExportWriter();
        writer.begin(output);
        writer.write(output, log);
        writer.finish(output);

        String csv = output.toString();
        assertTrue(csv.startsWith("id,case_id,moderator_uuid"));
        assertTrue(csv.contains("'=Moderator"));
        assertTrue(csv.contains("\"line,\"\"quote\"\"\""));
        assertTrue(csv.contains("\"'+SUM(1,1)\""));
        assertTrue(csv.endsWith("\r\n"));
    }

    @Test
    void writesValidEscapedJson() throws Exception {
        ReportLog log = new ReportLog(7, 42L, "Moderator", MODERATOR_UUID, "note", "Player", REPORTED_UUID, "quote \" and newline\n", "slash \\ and tab\t", 123L, "closed", 124L);
        StringWriter output = new StringWriter();
        JsonLogExportWriter writer = new JsonLogExportWriter();
        writer.begin(output);
        writer.write(output, log);
        writer.finish(output);

        Object parsed = new Yaml().load(output.toString());
        List<?> values = (List<?>) parsed;
        Map<?, ?> row = (Map<?, ?>) values.get(0);
        assertEquals(7, row.get("id"));
        assertEquals(42, row.get("case_id"));
        assertEquals("quote \" and newline\n", row.get("reason"));
        assertEquals("slash \\ and tab\t", row.get("note"));
        assertEquals(MODERATOR_UUID.toString(), row.get("moderator_uuid"));
    }
}
