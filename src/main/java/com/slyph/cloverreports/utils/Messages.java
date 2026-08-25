package com.slyph.cloverreports.utils;

import com.slyph.cloverreports.CloverReports;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuppressWarnings("deprecation")
public final class Messages {

    private static File messagesFile;
    private static File guiFile;
    private static volatile FileConfiguration messagesConfig;
    private static volatile FileConfiguration guiConfig;

    private Messages() {
    }

    public static synchronized void load(CloverReports plugin) {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        guiFile = new File(plugin.getDataFolder(), "gui.yml");
        messagesConfig = loadFile(plugin, messagesFile, "messages.yml");
        guiConfig = loadFile(plugin, guiFile, "gui.yml");
        migrateOldConfigSections(plugin);
        migrateGuiFromMessagesFile(plugin);
        migrateGuiNoteNumberFormat(plugin);
    }

    public static String get(String path) {
        return firstVisibleLine(getChatList(path), "messages." + path);
    }

    public static String get(String path, Map<String, String> placeholders) {
        return firstVisibleLine(getChatList(path, placeholders), "messages." + path);
    }

    public static String getPlain(String path) {
        return getRawString(getMessagesConfig(), "messages", path);
    }

    public static String getPlain(String path, Map<String, String> placeholders) {
        return applyPlaceholders(getRawString(getMessagesConfig(), "messages", path), placeholders);
    }

    public static List<String> getChatList(String path) {
        return ChatUtil.color(getRawList(getMessagesConfig(), "messages", path));
    }

    public static List<String> getChatList(String path, Map<String, String> placeholders) {
        return colorWithPlaceholders(getRawList(getMessagesConfig(), "messages", path), placeholders);
    }

    public static String[] getChatArray(String path) {
        return getChatList(path).toArray(new String[0]);
    }

    public static String[] getChatArray(String path, Map<String, String> placeholders) {
        return getChatList(path, placeholders).toArray(new String[0]);
    }

    public static String getGui(String path) {
        return ChatUtil.color(getRawString(getGuiConfig(), "gui", path));
    }

    public static String getGui(String path, Map<String, String> placeholders) {
        return ChatUtil.color(applyPlaceholders(getRawString(getGuiConfig(), "gui", path), placeholders));
    }

    public static List<String> getGuiList(String path) {
        return ChatUtil.color(getRawList(getGuiConfig(), "gui", path));
    }

    public static List<String> getGuiList(String path, Map<String, String> placeholders) {
        return colorWithPlaceholders(getRawList(getGuiConfig(), "gui", path), placeholders);
    }

    public static int getGuiInt(String path, int fallback) {
        return getGuiConfig().getInt("gui." + path, fallback);
    }

    private static String firstVisibleLine(List<String> lines, String fallback) {
        for (String line : lines) {
            String stripped = ChatColor.stripColor(line);
            if (stripped != null && !stripped.isBlank()) {
                return line;
            }
        }
        return lines.isEmpty() ? fallback : lines.get(0);
    }

    private static List<String> colorWithPlaceholders(List<String> lines, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(ChatUtil.color(applyPlaceholders(line, placeholders)));
        }
        return result;
    }

    private static String applyPlaceholders(String line, Map<String, String> placeholders) {
        String result = line;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private static FileConfiguration loadFile(CloverReports plugin, File file, String resourceName) {
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
        }

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Не удалось загрузить " + resourceName + ": " + exception.getMessage());
            throw new IllegalStateException("Invalid " + resourceName, exception);
        }

        try (InputStream inputStream = plugin.getResource(resourceName)) {
            if (inputStream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(false);
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Не удалось загрузить настройки по умолчанию " + resourceName + ": " + exception.getMessage());
            throw new IllegalStateException("Cannot load defaults for " + resourceName, exception);
        }
        return config;
    }

    private static String getRawString(FileConfiguration config, String section, String path) {
        String key = section + "." + path;
        Object value = config.get(key);
        return value instanceof String ? (String) value : key;
    }

    private static List<String> getRawList(FileConfiguration config, String section, String path) {
        String key = section + "." + path;
        Object value = config.get(key);
        List<String> result = new ArrayList<>();
        if (value instanceof List<?>) {
            for (Object entry : (List<?>) value) {
                result.add(String.valueOf(entry));
            }
            return result;
        }
        if (value instanceof String) {
            result.add((String) value);
            return result;
        }
        result.add(key);
        return result;
    }

    private static FileConfiguration getMessagesConfig() {
        FileConfiguration current = messagesConfig;
        if (current == null) {
            CloverReports plugin = CloverReports.getInstance();
            if (plugin == null) {
                throw new IllegalStateException("CloverReports is not enabled");
            }
            load(plugin);
            current = messagesConfig;
        }
        return current;
    }

    private static FileConfiguration getGuiConfig() {
        FileConfiguration current = guiConfig;
        if (current == null) {
            CloverReports plugin = CloverReports.getInstance();
            if (plugin == null) {
                throw new IllegalStateException("CloverReports is not enabled");
            }
            load(plugin);
            current = guiConfig;
        }
        return current;
    }

    private static void migrateOldConfigSections(CloverReports plugin) {
        boolean configChanged = false;
        ConfigurationSection messagesSection = plugin.getConfig().getConfigurationSection("messages");
        if (messagesSection != null) {
            copySection(messagesSection, messagesConfig, "messages");
            saveAtomic(messagesConfig, messagesFile);
            plugin.getConfig().set("messages", null);
            configChanged = true;
        }

        ConfigurationSection guiSection = plugin.getConfig().getConfigurationSection("gui");
        if (guiSection != null) {
            copySection(guiSection, guiConfig, "gui");
            saveAtomic(guiConfig, guiFile);
            plugin.getConfig().set("gui", null);
            configChanged = true;
        }

        String banReason = plugin.getConfig().getString("actions.ban-reason");
        if (banReason != null) {
            messagesConfig.set("messages.ban-reason", banReason);
            saveAtomic(messagesConfig, messagesFile);
            plugin.getConfig().set("actions.ban-reason", null);
            configChanged = true;
        }

        if (configChanged) {
            saveAtomic(plugin.getConfig(), new File(plugin.getDataFolder(), "config.yml"));
        }
    }

    private static void migrateGuiFromMessagesFile(CloverReports plugin) {
        ConfigurationSection section = messagesConfig.getConfigurationSection("gui");
        if (section == null) {
            return;
        }
        copySection(section, guiConfig, "gui");
        saveAtomic(guiConfig, guiFile);
        messagesConfig.set("gui", null);
        saveAtomic(messagesConfig, messagesFile);
        plugin.getLogger().info("GUI settings migrated to gui.yml.");
    }

    private static void migrateGuiNoteNumberFormat(CloverReports plugin) {
        String oldLine = "   &#BFA8FF%number%. &7%time% &8→ &#FFFFFF%raw_note% ";
        String newLine = "   &#BFA8FF%number% &7%time% &8→ &#FFFFFF%raw_note% ";
        boolean changed = migrateGuiListLine("report-head.note-entry-lines", oldLine, newLine);
        changed |= migrateGuiListLine("history-head.note-entry-lines", oldLine, newLine);
        changed |= normalizeGuiLeadingBlankLines("report-head.notes-prefix", 1);
        changed |= normalizeGuiLeadingBlankLines("history-head.notes-prefix", 1);
        changed |= removeGuiSingleBlankLine("report-head.notes-suffix");
        changed |= removeGuiSingleBlankLine("history-head.notes-suffix");
        if (changed) {
            saveAtomic(guiConfig, guiFile);
            plugin.getLogger().info("GUI note format migrated.");
        }
    }

    private static boolean normalizeGuiLeadingBlankLines(String path, int amount) {
        String key = "gui." + path;
        List<String> lines = new ArrayList<>(guiConfig.getStringList(key));
        int leadingBlanks = 0;
        while (leadingBlanks < lines.size() && lines.get(leadingBlanks).isBlank()) {
            leadingBlanks++;
        }
        if (leadingBlanks == amount) {
            return false;
        }
        while (leadingBlanks > amount) {
            lines.remove(0);
            leadingBlanks--;
        }
        for (int index = leadingBlanks; index < amount; index++) {
            lines.add(0, "");
        }
        guiConfig.set(key, lines);
        return true;
    }

    private static boolean removeGuiSingleBlankLine(String path) {
        String key = "gui." + path;
        List<String> lines = guiConfig.getStringList(key);
        if (lines.size() != 1 || !lines.get(0).isBlank()) {
            return false;
        }
        guiConfig.set(key, List.of());
        return true;
    }

    private static boolean migrateGuiListLine(String path, String oldLine, String newLine) {
        String key = "gui." + path;
        List<String> lines = new ArrayList<>(guiConfig.getStringList(key));
        boolean changed = false;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).equals(oldLine)) {
                lines.set(index, newLine);
                changed = true;
            }
        }
        if (changed) {
            guiConfig.set(key, lines);
        }
        return changed;
    }

    private static void copySection(ConfigurationSection source, FileConfiguration targetConfig, String targetPath) {
        for (String key : source.getKeys(false)) {
            String targetKey = targetPath + "." + key;
            ConfigurationSection nested = source.getConfigurationSection(key);
            if (nested != null) {
                copySection(nested, targetConfig, targetKey);
            } else {
                targetConfig.set(targetKey, source.get(key));
            }
        }
    }

    private static void saveAtomic(FileConfiguration config, File target) {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            config.save(temporary);
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
            }
            throw new IllegalStateException("Cannot save " + target.getName(), exception);
        }
    }
}
