package com.slyph.cloverreports.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceSecurityRegressionTest {

    @Test
    void reportGuiDoesNotResolveUuidLessNamesRemotely() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/slyph/cloverreports/gui/ReportsGUI.java"));
        assertFalse(source.contains("Bukkit.getOfflinePlayer(reportCase.getReportedName())"));
        assertTrue(source.contains("Bukkit.getOfflinePlayer(reportCase.getReportedUuid())"));

        String command = Files.readString(Path.of("src/main/java/com/slyph/cloverreports/commands/ReportCommand.java"));
        assertTrue(command.contains("Bukkit.getOfflinePlayerIfCached"));
        assertFalse(command.contains("Bukkit.getOfflinePlayer(args[0])"));
    }

    @Test
    void mysqlConnectorDoesNotBundleProtobuf() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        assertTrue(build.contains("exclude group: 'com.google.protobuf', module: 'protobuf-java'"));
    }

    @Test
    void submissionChecksCooldownAndKnownIdentityBeforeDatabaseHeavyQueries() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/slyph/cloverreports/gui/submission/ReportSubmissionListener.java"));
        int cooldown = source.indexOf("long initialCooldownLeft");
        int asynchronousWork = source.indexOf("runTaskAsynchronously", cooldown);
        int knownIdentity = source.indexOf("resolveKnownPlayerUuid", asynchronousWork);
        int reporterStatistics = source.indexOf("getReporterStats", knownIdentity);

        assertTrue(cooldown >= 0 && cooldown < asynchronousWork);
        assertTrue(knownIdentity >= 0 && knownIdentity < reporterStatistics);
    }

    @Test
    void tabCompletersReadOnlyTheAsynchronousSuggestionSnapshot() throws Exception {
        for (String fileName : List.of("CloverReportsTabCompleter.java", "ViewReportsTabCompleter.java")) {
            String source = Files.readString(Path.of("src/main/java/com/slyph/cloverreports/commands", fileName));
            assertTrue(source.contains("ReportSuggestionCache"), fileName);
            assertFalse(source.contains("getReportedPlayers("), fileName);
            assertFalse(source.contains("getReportedPlayerCount("), fileName);
        }
    }

    @Test
    void pinsBuildSupplyChainAndOfficialGradleWrapper() throws Exception {
        List<String> unpinnedActions = new ArrayList<>();
        try (var workflows = Files.walk(Path.of(".github", "workflows"))) {
            for (Path workflow : workflows.filter(path -> path.toString().endsWith(".yml")).toList()) {
                for (String line : Files.readAllLines(workflow)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("uses:")
                            && !trimmed.matches("uses: [A-Za-z0-9_.\\-/]+@[0-9a-f]{40}(?: # .+)?")) {
                        unpinnedActions.add(workflow + ": " + trimmed);
                    }
                }
            }
        }
        assertTrue(unpinnedActions.isEmpty(), unpinnedActions.toString());

        byte[] wrapper = Files.readAllBytes(Path.of("gradle", "wrapper", "gradle-wrapper.jar"));
        String wrapperHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wrapper));
        assertTrue(wrapperHash.equals("7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"));

        String verification = Files.readString(Path.of("gradle", "verification-metadata.xml"));
        assertTrue(verification.contains("<verify-metadata>true</verify-metadata>"));
        assertTrue(verification.contains("<sha256 value="));
    }
}
