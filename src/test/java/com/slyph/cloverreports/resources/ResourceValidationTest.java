package com.slyph.cloverreports.resources;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceValidationTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void parsesEveryYamlResourceWithoutControlCharacters() throws IOException {
        for (String fileName : List.of("plugin.yml", "config.yml", "messages.yml", "gui.yml", "reasons.yml")) {
            Path file = RESOURCES.resolve(fileName);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Object parsed = new Yaml().load(content);
            assertNotNull(parsed, fileName);
            for (int index = 0; index < content.length(); index++) {
                char character = content.charAt(index);
                assertTrue(character < 0x80 || character > 0x9F, fileName + " contains C1 control character");
                assertTrue(character != '\uFFFD', fileName + " contains replacement character");
            }
        }
    }

    @Test
    void parsesGithubAutomationYaml() throws IOException {
        for (String fileName : List.of("build.yml", "release.yml")) {
            Path file = Path.of(".github", "workflows", fileName);
            try (InputStream inputStream = Files.newInputStream(file)) {
                assertNotNull(new Yaml().load(inputStream), fileName);
            }
        }
        try (InputStream inputStream = Files.newInputStream(Path.of(".github", "dependabot.yml"))) {
            assertNotNull(new Yaml().load(inputStream), "dependabot.yml");
        }
    }

    @Test
    void declaresRequiredPluginMetadata() throws IOException {
        Map<?, ?> plugin = loadMap("plugin.yml");
        assertEquals("CloverReports", plugin.get("name"));
        assertEquals("com.slyph.cloverreports.CloverReports", plugin.get("main"));
        assertEquals("26.2", plugin.get("api-version"));
        assertTrue(((List<?>) plugin.get("authors")).contains("slyph"));
        assertNotNull(((Map<?, ?>) plugin.get("commands")).get("report"));
        assertNotNull(((Map<?, ?>) plugin.get("commands")).get("viewreports"));
        assertNotNull(((Map<?, ?>) plugin.get("commands")).get("cloverreports"));
    }

    @Test
    void keepsChatMessagesEditableAsListsWithSpacing() throws IOException {
        Map<?, ?> root = loadMap("messages.yml");
        Map<?, ?> messages = (Map<?, ?>) root.get("messages");
        for (Map.Entry<?, ?> entry : messages.entrySet()) {
            if (!(entry.getValue() instanceof List<?>)) {
                continue;
            }
            if (entry.getKey().equals("notify-report-hover")) {
                continue;
            }
            List<?> lines = (List<?>) entry.getValue();
            assertTrue(lines.size() >= 3, String.valueOf(entry.getKey()));
            assertEquals("&7", lines.get(0), String.valueOf(entry.getKey()));
            assertEquals("&7", lines.get(lines.size() - 1), String.valueOf(entry.getKey()));
        }
    }

    @Test
    void keepsReportLoreSectionsVisuallySeparated() throws IOException {
        Map<?, ?> root = loadMap("gui.yml");
        Map<?, ?> gui = (Map<?, ?>) root.get("gui");
        Map<?, ?> placeholder = (Map<?, ?>) gui.get("report-placeholder");
        assertEquals("GRAY_STAINED_GLASS_PANE", placeholder.get("material"));
        for (String sectionName : List.of("report-head", "history-head")) {
            Map<?, ?> section = (Map<?, ?>) gui.get(sectionName);
            List<?> reportListPrefix = (List<?>) section.get("report-list-prefix");
            assertEquals("", reportListPrefix.get(0), sectionName);
            assertTrue(String.valueOf(reportListPrefix.get(1)).contains("Репорты игроков"), sectionName);
            assertEquals(List.of(""), section.get("report-entry-separator"), sectionName);
            assertEquals(List.of(""), section.get("note-entry-separator"), sectionName);
            assertEquals(List.of(), section.get("notes-suffix"), sectionName);
            List<?> notesPrefix = (List<?>) section.get("notes-prefix");
            assertEquals("", notesPrefix.get(0), sectionName);
            assertTrue(String.valueOf(notesPrefix.get(1)).contains("Заметки модераторов"), sectionName);
        }
    }

    @Test
    void shipsWithSecureReportAbuseAndEvidenceDefaults() throws IOException {
        Map<?, ?> root = loadMap("config.yml");
        Map<?, ?> report = (Map<?, ?>) root.get("report");
        Map<?, ?> evidence = (Map<?, ?>) report.get("evidence");
        Map<?, ?> cleanup = (Map<?, ?>) root.get("cleanup");

        assertEquals(Boolean.TRUE, report.get("require-known-player"));
        assertTrue(((Number) report.get("max-active-cases-per-reporter")).intValue() > 0);
        assertTrue(((Number) report.get("max-reports-per-window")).intValue() > 0);
        assertEquals(Boolean.FALSE, evidence.get("allow-any-host"));
        assertTrue(!((List<?>) evidence.get("allowed-hosts")).isEmpty());
        assertTrue(((Number) cleanup.get("pending-days")).intValue() > 0);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> loadMap(String fileName) throws IOException {
        try (InputStream inputStream = Files.newInputStream(RESOURCES.resolve(fileName))) {
            return new Yaml().load(inputStream);
        }
    }
}
