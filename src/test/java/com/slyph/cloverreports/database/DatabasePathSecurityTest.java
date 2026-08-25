package com.slyph.cloverreports.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class DatabasePathSecurityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void allowsRelativePathInsidePluginDirectory() {
        Path resolved = DatabaseManager.resolveConfinedPath(temporaryDirectory, "db/reports.db", "sqlite.file");
        assertEquals(temporaryDirectory.resolve("db/reports.db").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void rejectsParentTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseManager.resolveConfinedPath(temporaryDirectory, "../outside.db", "sqlite.file"));
    }

    @Test
    void rejectsAbsolutePath() {
        Path absolute = temporaryDirectory.resolve("outside.db").toAbsolutePath();
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseManager.resolveConfinedPath(temporaryDirectory, absolute.toString(), "sqlite.file"));
    }

    @Test
    void rejectsExistingSymlinkTraversal() throws IOException {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Path link = temporaryDirectory.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment: " + exception.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseManager.resolveConfinedPath(temporaryDirectory, "link/reports.db", "sqlite.file"));
    }

    @Test
    void permitsDisablingMysqlTlsOnlyForExplicitLoopbackHosts() {
        assertTrue(DatabaseManager.isLocalDatabaseHost("localhost"));
        assertTrue(DatabaseManager.isLocalDatabaseHost("127.0.0.1"));
        assertTrue(DatabaseManager.isLocalDatabaseHost("::1"));
        assertTrue(DatabaseManager.isLocalDatabaseHost("[::1]"));
        assertFalse(DatabaseManager.isLocalDatabaseHost("mysql.internal"));
        assertFalse(DatabaseManager.isLocalDatabaseHost("192.168.1.20"));
        assertFalse(DatabaseManager.isLocalDatabaseHost(null));
    }
}
