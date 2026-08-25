from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"expected exactly one match in {path}, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


def replace_all(path, old, new, minimum=1):
    text = read(path)
    count = text.count(old)
    if count < minimum:
        raise RuntimeError(f"expected at least {minimum} matches in {path}, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new))


# Release + dependency hardening.
replace_once("build.gradle", "version = '1.1.2'", "version = '1.1.3'")
replace_once(
    "build.gradle",
    "    implementation 'com.zaxxer:HikariCP:5.1.0'\n"
    "    implementation 'com.mysql:mysql-connector-j:8.4.0'\n"
    "    implementation 'org.xerial:sqlite-jdbc:3.45.3.0'",
    "    implementation 'com.zaxxer:HikariCP:7.1.0'\n"
    "    implementation('com.mysql:mysql-connector-j:26.7.0') {\n"
    "        exclude group: 'com.google.protobuf', module: 'protobuf-java'\n"
    "    }\n"
    "    implementation 'org.xerial:sqlite-jdbc:3.53.2.1'"
)

# Secure defaults.
replace_once("src/main/resources/config.yml", '  username: "root"\n  password: "password"', '  username: "cloverreports"\n  password: ""')
replace_once("src/main/resources/config.yml", "  use-ssl: false", "  use-ssl: true")
replace_once(
    "src/main/resources/config.yml",
    "  cooldown-seconds: 60\n  online-only: false",
    "  cooldown-seconds: 60\n  attempt-min-interval-ms: 1500\n  max-reports-per-case: 100\n  online-only: false"
)
replace_once("src/main/resources/config.yml", "    require-https: false", "    require-https: true")
replace_once("src/main/resources/config.yml", "    allowed-ports: [80, 443]", "    allowed-ports: [443]")

# Separate destructive note permission.
replace_once(
    "src/main/resources/plugin.yml",
    "  cloverreports.note:\n    description: Разрешение на заметки модераторов\n    default: op\n",
    "  cloverreports.note:\n    description: Разрешение на заметки модераторов\n    default: op\n"
    "  cloverreports.note.clear-all:\n    description: Разрешение удалять все заметки по делу\n    default: op\n"
)

# Do not resolve attacker-controlled UUID-less names through Mojang while rendering GUI.
replace_once("src/main/java/com/slyph/cloverreports/gui/ReportsGUI.java", "import org.bukkit.OfflinePlayer;\n", "")
replace_once(
    "src/main/java/com/slyph/cloverreports/gui/ReportsGUI.java",
    "        OfflinePlayer target = reportCase.getReportedUuid() == null\n"
    "                ? Bukkit.getOfflinePlayer(reportCase.getReportedName())\n"
    "                : Bukkit.getOfflinePlayer(reportCase.getReportedUuid());\n"
    "        meta.setOwningPlayer(target);",
    "        if (reportCase.getReportedUuid() != null) {\n"
    "            meta.setOwningPlayer(Bukkit.getOfflinePlayer(reportCase.getReportedUuid()));\n"
    "        }"
)

# Add a cheap per-player attempt throttle before any database work.
submission = "src/main/java/com/slyph/cloverreports/gui/submission/ReportSubmissionListener.java"
replace_once(
    submission,
    "    private final Set<UUID> submittingPlayers;\n    private final ConcurrentMap<UUID, Long> cooldowns;",
    "    private final Set<UUID> submittingPlayers;\n"
    "    private final ConcurrentMap<UUID, Long> cooldowns;\n"
    "    private final ConcurrentMap<UUID, Long> reportAttempts;"
)
replace_once(
    submission,
    "        this.submittingPlayers = ConcurrentHashMap.newKeySet();\n        this.cooldowns = new ConcurrentHashMap<>();",
    "        this.submittingPlayers = ConcurrentHashMap.newKeySet();\n"
    "        this.cooldowns = new ConcurrentHashMap<>();\n"
    "        this.reportAttempts = new ConcurrentHashMap<>();"
)
replace_once(
    submission,
    "    private void submit(Player player, ReportSubmissionHolder holder) {\n"
    "        cancelEvidenceInput(player.getUniqueId());\n"
    "        ReportReason reason = holder.getSelectedReason();\n"
    "        if (reason == null || !submittingPlayers.add(player.getUniqueId())) {\n"
    "            return;\n"
    "        }",
    "    private void submit(Player player, ReportSubmissionHolder holder) {\n"
    "        UUID playerUuid = player.getUniqueId();\n"
    "        cancelEvidenceInput(playerUuid);\n"
    "        ReportReason reason = holder.getSelectedReason();\n"
    "        if (reason == null || !submittingPlayers.add(playerUuid)) {\n"
    "            return;\n"
    "        }\n"
    "        long attemptWaitMillis = claimReportAttempt(playerUuid);\n"
    "        if (attemptWaitMillis > 0L) {\n"
    "            submittingPlayers.remove(playerUuid);\n"
    "            long seconds = Math.max(1L, (long) Math.ceil(attemptWaitMillis / 1_000.0));\n"
    "            send(player, \"report-rate-limited\", \"&cСлишком часто. Повторите через %time% сек.\", Map.of(\"%time%\", String.valueOf(seconds)));\n"
    "            return;\n"
    "        }"
)
replace_once(
    submission,
    "        if (result.status == ReportManager.SubmissionStatus.DUPLICATE) {\n"
    "            send(player, \"already-reported\", \"&eВы уже подали жалобу на этого игрока.\", Map.of());\n"
    "            return;\n"
    "        }",
    "        if (result.status == ReportManager.SubmissionStatus.DUPLICATE) {\n"
    "            send(player, \"already-reported\", \"&eВы уже подали жалобу на этого игрока.\", Map.of());\n"
    "            return;\n"
    "        }\n"
    "        if (result.status == ReportManager.SubmissionStatus.CAPACITY) {\n"
    "            send(player, \"report-case-full\", \"&cПо этому игроку уже достигнут безопасный лимит активных жалоб.\", Map.of());\n"
    "            return;\n"
    "        }"
)
replace_once(
    submission,
    "    private long getCooldownLeft(UUID playerId, boolean falsePenalty) {",
    "    private long claimReportAttempt(UUID playerId) {\n"
    "        long interval = Math.max(250L, Math.min(60_000L, plugin.getConfig().getLong(\"report.attempt-min-interval-ms\", 1_500L)));\n"
    "        long now = System.currentTimeMillis();\n"
    "        Long previous = reportAttempts.put(playerId, now);\n"
    "        cleanupReportAttempts(now, interval);\n"
    "        if (previous == null) {\n"
    "            return 0L;\n"
    "        }\n"
    "        long elapsed = Math.max(0L, now - previous);\n"
    "        return elapsed >= interval ? 0L : interval - elapsed;\n"
    "    }\n\n"
    "    private void cleanupReportAttempts(long now, long interval) {\n"
    "        if (reportAttempts.size() < 4_096) {\n"
    "            return;\n"
    "        }\n"
    "        long cutoff = now - Math.max(60_000L, interval * 4L);\n"
    "        reportAttempts.entrySet().removeIf(entry -> entry.getValue() < cutoff);\n"
    "        if (reportAttempts.size() > 8_192) {\n"
    "            reportAttempts.clear();\n"
    "        }\n"
    "    }\n\n"
    "    private long getCooldownLeft(UUID playerId, boolean falsePenalty) {"
)

# Cap the number of active reports materialized into one case.
manager = "src/main/java/com/slyph/cloverreports/managers/ReportManager.java"
replace_once(
    manager,
    "    public static final int MAX_MODERATOR_NOTES_PER_PLAYER = 20;",
    "    public static final int MAX_MODERATOR_NOTES_PER_PLAYER = 20;\n"
    "    public static final int DEFAULT_MAX_REPORTS_PER_CASE = 100;"
)
replace_once(
    manager,
    "                if (hasReporterInCase(connection, caseId, reporterIdentityKey, true)) {\n"
    "                    connection.rollback();\n"
    "                    return SubmissionResult.duplicate(caseId);\n"
    "                }\n"
    "                String insert = \"INSERT INTO reports",
    "                if (hasReporterInCase(connection, caseId, reporterIdentityKey, true)) {\n"
    "                    connection.rollback();\n"
    "                    return SubmissionResult.duplicate(caseId);\n"
    "                }\n"
    "                int maximumReports = Math.max(1, Math.min(10_000, getIntSetting(\"report.max-reports-per-case\", DEFAULT_MAX_REPORTS_PER_CASE)));\n"
    "                if (countReportsInCase(connection, caseId) >= maximumReports) {\n"
    "                    connection.rollback();\n"
    "                    return SubmissionResult.capacity(caseId);\n"
    "                }\n"
    "                String insert = \"INSERT INTO reports"
)
replace_once(
    manager,
    "    public enum SubmissionStatus {\n        SUCCESS,\n        DUPLICATE,\n        ERROR\n    }",
    "    public enum SubmissionStatus {\n        SUCCESS,\n        DUPLICATE,\n        CAPACITY,\n        ERROR\n    }"
)
replace_once(
    manager,
    "        public static SubmissionResult duplicate(long caseId) {\n"
    "            return new SubmissionResult(SubmissionStatus.DUPLICATE, caseId);\n"
    "        }\n\n"
    "        public static SubmissionResult error() {",
    "        public static SubmissionResult duplicate(long caseId) {\n"
    "            return new SubmissionResult(SubmissionStatus.DUPLICATE, caseId);\n"
    "        }\n\n"
    "        public static SubmissionResult capacity(long caseId) {\n"
    "            return new SubmissionResult(SubmissionStatus.CAPACITY, caseId);\n"
    "        }\n\n"
    "        public static SubmissionResult error() {"
)
replace_once(
    manager,
    "    private CaseFilterSql buildCaseFilter(Connection connection, String status, HistoryFilter filter) throws SQLException {",
    "    private int countReportsInCase(Connection connection, long caseId) throws SQLException {\n"
    "        try (PreparedStatement statement = connection.prepareStatement(\"SELECT COUNT(*) FROM reports WHERE case_id = ?\")) {\n"
    "            statement.setLong(1, caseId);\n"
    "            try (ResultSet resultSet = statement.executeQuery()) {\n"
    "                return resultSet.next() ? resultSet.getInt(1) : 0;\n"
    "            }\n"
    "        }\n"
    "    }\n\n"
    "    private CaseFilterSql buildCaseFilter(Connection connection, String status, HistoryFilter filter) throws SQLException {"
)
replace_once(
    manager,
    "        int count = getCasePage(status, filter, 0, 1).getTotalCases();\n"
    "        countCache.put(cacheKey, new CachedCount(count, now + CACHE_MILLIS));",
    "        int count = getCasePage(status, filter, 0, 1).getTotalCases();\n"
    "        if (countCache.size() >= 256) {\n"
    "            countCache.clear();\n"
    "        }\n"
    "        countCache.put(cacheKey, new CachedCount(count, now + CACHE_MILLIS));"
)

# Constrain plugin-controlled paths and secure MySQL transport defaults.
database = "src/main/java/com/slyph/cloverreports/database/DatabaseManager.java"
replace_once(
    database,
    "            Settings settings = fixedSettings == null ? loadSettings() : fixedSettings;\n"
    "            HikariDataSource candidate = null;\n"
    "            try {",
    "            HikariDataSource candidate = null;\n"
    "            try {\n"
    "                Settings settings = fixedSettings == null ? loadSettings() : fixedSettings;"
)
replace_once(
    database,
    "                    plugin.getConfig().getString(\"mysql.host\", \"localhost\"),\n"
    "                    plugin.getConfig().getInt(\"mysql.port\", 3306),\n"
    "                    plugin.getConfig().getString(\"mysql.database\", \"cloverreports\"),\n"
    "                    plugin.getConfig().getString(\"mysql.username\", \"root\"),\n"
    "                    plugin.getConfig().getString(\"mysql.password\", \"\"),",
    "                    plugin.getConfig().getString(\"mysql.host\", \"localhost\"),\n"
    "                    Math.max(1, Math.min(65_535, plugin.getConfig().getInt(\"mysql.port\", 3306))),\n"
    "                    plugin.getConfig().getString(\"mysql.database\", \"cloverreports\"),\n"
    "                    plugin.getConfig().getString(\"mysql.username\", \"cloverreports\"),\n"
    "                    plugin.getConfig().getString(\"mysql.password\", \"\"),"
)
replace_once(database, "                    plugin.getConfig().getBoolean(\"mysql.use-ssl\", false)", "                    plugin.getConfig().getBoolean(\"mysql.use-ssl\", true)")
replace_once(
    database,
    "        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();\n"
    "        Path databaseFile = dataFolder.resolve(plugin.getConfig().getString(\"sqlite.file\", \"reports.db\")).normalize();\n"
    "        return Settings.sqlite(databaseFile, dataFolder, connectionTimeout);",
    "        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();\n"
    "        Path databaseFile = resolveConfinedPath(dataFolder, plugin.getConfig().getString(\"sqlite.file\", \"reports.db\"), \"sqlite.file\");\n"
    "        return Settings.sqlite(databaseFile, dataFolder, connectionTimeout);"
)
replace_once(
    database,
    "            String url = \"jdbc:mysql://\" + settings.host + \":\" + settings.port + \"/\" + settings.database\n"
    "                    + \"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC\"\n"
    "                    + \"&allowPublicKeyRetrieval=true&useSSL=\" + settings.useSsl\n"
    "                    + \"&connectTimeout=\" + settings.connectionTimeoutMillis",
    "            String sslMode = settings.useSsl ? \"VERIFY_IDENTITY\" : \"DISABLED\";\n"
    "            String url = \"jdbc:mysql://\" + settings.host + \":\" + settings.port + \"/\" + settings.database\n"
    "                    + \"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC\"\n"
    "                    + \"&sslMode=\" + sslMode\n"
    "                    + \"&allowPublicKeyRetrieval=\" + (!settings.useSsl)\n"
    "                    + \"&connectTimeout=\" + settings.connectionTimeoutMillis"
)
replace_once(
    database,
    "    private Path resolveBackupDirectory(Settings settings) throws IOException {\n"
    "        Path root = settings.dataFolder.toAbsolutePath().normalize();\n"
    "        String configuredFolder = plugin == null ? \"backups\" : plugin.getConfig().getString(\"backup.folder\", \"backups\");\n"
    "        Path directory = root.resolve(configuredFolder).normalize();\n"
    "        if (!directory.startsWith(root)) {\n"
    "            throw new IOException(\"backup folder must be inside the plugin data folder\");\n"
    "        }\n"
    "        return directory;\n"
    "    }",
    "    private Path resolveBackupDirectory(Settings settings) throws IOException {\n"
    "        Path root = settings.dataFolder.toAbsolutePath().normalize();\n"
    "        String configuredFolder = plugin == null ? \"backups\" : plugin.getConfig().getString(\"backup.folder\", \"backups\");\n"
    "        try {\n"
    "            return resolveConfinedPath(root, configuredFolder, \"backup.folder\");\n"
    "        } catch (IllegalArgumentException exception) {\n"
    "            throw new IOException(exception.getMessage(), exception);\n"
    "        }\n"
    "    }"
)
replace_once(
    database,
    "    private String normalizeStorageType(String configuredType) {",
    "    static Path resolveConfinedPath(Path dataFolder, String configuredValue, String settingName) {\n"
    "        Path root = Objects.requireNonNull(dataFolder, \"dataFolder\").toAbsolutePath().normalize();\n"
    "        String configured = configuredValue == null ? \"\" : configuredValue.trim();\n"
    "        if (configured.isEmpty()) {\n"
    "            throw new IllegalArgumentException(settingName + \" must not be empty\");\n"
    "        }\n"
    "        Path relative = Path.of(configured);\n"
    "        if (relative.isAbsolute()) {\n"
    "            throw new IllegalArgumentException(settingName + \" must be relative to the plugin data folder\");\n"
    "        }\n"
    "        Path target = root.resolve(relative).normalize();\n"
    "        if (!target.startsWith(root)) {\n"
    "            throw new IllegalArgumentException(settingName + \" must stay inside the plugin data folder\");\n"
    "        }\n"
    "        Path cursor = root;\n"
    "        for (Path segment : root.relativize(target)) {\n"
    "            cursor = cursor.resolve(segment);\n"
    "            if (Files.isSymbolicLink(cursor)) {\n"
    "                throw new IllegalArgumentException(settingName + \" must not traverse symbolic links\");\n"
    "            }\n"
    "        }\n"
    "        return target;\n"
    "    }\n\n"
    "    private String normalizeStorageType(String configuredType) {"
)

# Destructive note clearing requires its own permission, and do not disclose absolute filesystem paths in chat.
command = "src/main/java/com/slyph/cloverreports/commands/CloverReportsCommand.java"
replace_once(
    command,
    "        boolean clear = note.equalsIgnoreCase(\"clear\");\n"
    "        int maximumLength = Math.max(1, plugin.getConfig().getInt(\"note-input.max-length\", 512));",
    "        boolean clear = note.equalsIgnoreCase(\"clear\");\n"
    "        if (clear && !sender.hasPermission(\"cloverreports.note.clear-all\")) {\n"
    "            sender.sendMessage(Messages.getChatArray(\"no-permission\"));\n"
    "            return true;\n"
    "        }\n"
    "        int maximumLength = Math.max(1, plugin.getConfig().getInt(\"note-input.max-length\", 512));"
)
replace_once(command, 'Map.of("%file%", result.getBackupFile().getName(), "%path%", result.getBackupFile().getAbsolutePath())', 'Map.of("%file%", result.getBackupFile().getName(), "%path%", result.getBackupFile().getName())')
replace_once(command, '"%path%", result.getFile().toAbsolutePath().toString(),', '"%path%", result.getFile().getFileName().toString(),')

# Do not suggest destructive clear operation to staff without the separate permission.
replace_once(
    "src/main/java/com/slyph/cloverreports/commands/CloverReportsTabCompleter.java",
    "            if (args.length == 3 && \"clear\".startsWith(args[2].toLowerCase(Locale.ROOT))) {\n"
    "                return List.of(\"clear\");\n"
    "            }",
    "            if (args.length == 3 && sender.hasPermission(\"cloverreports.note.clear-all\")\n"
    "                    && \"clear\".startsWith(args[2].toLowerCase(Locale.ROOT))) {\n"
    "                return List.of(\"clear\");\n"
    "            }"
)

# Add focused path-confinement tests.
test_path = ROOT / "src/test/java/com/slyph/cloverreports/database/DatabasePathSecurityTest.java"
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(
    '''package com.slyph.cloverreports.database;\n\n'''
    '''import org.junit.jupiter.api.Test;\n'''
    '''import org.junit.jupiter.api.io.TempDir;\n\n'''
    '''import java.io.IOException;\n'''
    '''import java.nio.file.Files;\n'''
    '''import java.nio.file.Path;\n\n'''
    '''import static org.junit.jupiter.api.Assertions.assertEquals;\n'''
    '''import static org.junit.jupiter.api.Assertions.assertThrows;\n\n'''
    '''final class DatabasePathSecurityTest {\n\n'''
    '''    @TempDir\n'''
    '''    Path temporaryDirectory;\n\n'''
    '''    @Test\n'''
    '''    void allowsRelativePathInsidePluginDirectory() {\n'''
    '''        Path resolved = DatabaseManager.resolveConfinedPath(temporaryDirectory, "db/reports.db", "sqlite.file");\n'''
    '''        assertEquals(temporaryDirectory.resolve("db/reports.db").toAbsolutePath().normalize(), resolved);\n'''
    '''    }\n\n'''
    '''    @Test\n'''
    '''    void rejectsParentTraversal() {\n'''
    '''        assertThrows(IllegalArgumentException.class,\n'''
    '''                () -> DatabaseManager.resolveConfinedPath(temporaryDirectory, "../outside.db", "sqlite.file"));\n'''
    '''    }\n\n'''
    '''    @Test\n'''
    '''    void rejectsAbsolutePath() {\n'''
    '''        Path absolute = temporaryDirectory.resolve("outside.db").toAbsolutePath();\n'''
    '''        assertThrows(IllegalArgumentException.class,\n'''
    '''                () -> DatabaseManager.resolveConfinedPath(temporaryDirectory, absolute.toString(), "sqlite.file"));\n'''
    '''    }\n\n'''
    '''    @Test\n'''
    '''    void rejectsExistingSymlinkTraversal() throws IOException {\n'''
    '''        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));\n'''
    '''        Path link = temporaryDirectory.resolve("link");\n'''
    '''        Files.createSymbolicLink(link, outside);\n'''
    '''        assertThrows(IllegalArgumentException.class,\n'''
    '''                () -> DatabaseManager.resolveConfinedPath(temporaryDirectory, "link/reports.db", "sqlite.file"));\n'''
    '''    }\n'''
    '''}\n''',
    encoding="utf-8"
)

# Add a source-level regression test for the security-sensitive GUI lookup.
gui_test = ROOT / "src/test/java/com/slyph/cloverreports/security/SourceSecurityRegressionTest.java"
gui_test.parent.mkdir(parents=True, exist_ok=True)
gui_test.write_text(
    '''package com.slyph.cloverreports.security;\n\n'''
    '''import org.junit.jupiter.api.Test;\n\n'''
    '''import java.nio.file.Files;\n'''
    '''import java.nio.file.Path;\n\n'''
    '''import static org.junit.jupiter.api.Assertions.assertFalse;\n'''
    '''import static org.junit.jupiter.api.Assertions.assertTrue;\n\n'''
    '''final class SourceSecurityRegressionTest {\n\n'''
    '''    @Test\n'''
    '''    void reportGuiDoesNotResolveUuidLessNamesRemotely() throws Exception {\n'''
    '''        String source = Files.readString(Path.of("src/main/java/com/slyph/cloverreports/gui/ReportsGUI.java"));\n'''
    '''        assertFalse(source.contains("Bukkit.getOfflinePlayer(reportCase.getReportedName())"));\n'''
    '''        assertTrue(source.contains("Bukkit.getOfflinePlayer(reportCase.getReportedUuid())"));\n'''
    '''    }\n\n'''
    '''    @Test\n'''
    '''    void mysqlConnectorDoesNotBundleProtobuf() throws Exception {\n'''
    '''        String build = Files.readString(Path.of("build.gradle"));\n'''
    '''        assertTrue(build.contains("exclude group: 'com.google.protobuf', module: 'protobuf-java'"));\n'''
    '''    }\n'''
    '''}\n''',
    encoding="utf-8"
)

# Document the release hardening at a high level.
readme = read("README.md")
security_section = '''\n\n## Security hardening\n\nCloverReports 1.1.3 confines SQLite/backup paths to the plugin data directory, avoids remote profile lookups for UUID-less report targets, rate-limits report submission attempts, caps active reports per case, defaults evidence links to HTTPS, uses verified TLS by default for MySQL, and excludes the unused protobuf/X DevAPI dependency from Connector/J. Destructive note clearing requires `cloverreports.note.clear-all`.\n'''
if "## Security hardening" not in readme:
    write("README.md", readme.rstrip() + security_section + "\n")

# Remove one-shot automation files from the final tree. They only exist to apply this patch safely in Actions.
for transient in (ROOT / "tools/security_hardening.py", ROOT / ".github/workflows/security-hardening.yml"):
    if transient.exists():
        transient.unlink()
