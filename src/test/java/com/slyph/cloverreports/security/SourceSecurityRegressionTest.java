package com.slyph.cloverreports.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceSecurityRegressionTest {

    @Test
    void reportGuiDoesNotResolveUuidLessNamesRemotely() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/slyph/cloverreports/gui/ReportsGUI.java"));
        assertFalse(source.contains("Bukkit.getOfflinePlayer(reportCase.getReportedName())"));
        assertTrue(source.contains("Bukkit.getOfflinePlayer(reportCase.getReportedUuid())"));
    }

    @Test
    void mysqlConnectorDoesNotBundleProtobuf() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        assertTrue(build.contains("exclude group: 'com.google.protobuf', module: 'protobuf-java'"));
    }
}
