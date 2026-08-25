package com.slyph.cloverreports.reasons;

import com.slyph.cloverreports.CloverReports;
import com.slyph.cloverreports.utils.ChatUtil;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReportReasons {

    private static final List<ReportReason> FALLBACK_REASONS = List.of(
            new ReportReason("griefing", "Гриферство", "&cГриферство", List.of()),
            new ReportReason("cheats", "Читы", "&cЧиты", List.of()),
            new ReportReason("spam", "Спам", "&eСпам", List.of()),
            new ReportReason("insults", "Оскорбления", "&6Оскорбления", List.of())
    );
    private static File reasonsFile;
    private static FileConfiguration reasonsConfig;
    private static volatile List<ReportReason> cachedReasons;
    private static volatile Map<String, ReportReason> cachedReasonsByName;
    private static volatile Map<String, ReportReason> cachedReasonsByKey;

    private ReportReasons() {
    }

    public static void load(CloverReports plugin) {
        reasonsFile = new File(plugin.getDataFolder(), "reasons.yml");
        if (!reasonsFile.exists()) {
            plugin.saveResource("reasons.yml", false);
        }

        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(reasonsFile);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Не удалось загрузить reasons.yml: " + exception.getMessage());
            throw new IllegalStateException("Invalid reasons.yml", exception);
        }
        reasonsConfig = loaded;
        try (InputStream inputStream = plugin.getResource("reasons.yml")) {
            if (inputStream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                reasonsConfig.setDefaults(defaults);
                reasonsConfig.options().copyDefaults(false);
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Ошибка загрузки reasons.yml: " + exception.getMessage());
        }

        migrateOldReasons(plugin);
        rebuildCache();
    }

    public static List<ReportReason> getReasons() {
        List<ReportReason> reasons = cachedReasons;
        if (reasons != null) {
            return reasons;
        }
        getConfig();
        rebuildCache();
        return cachedReasons;
    }

    private static void rebuildCache() {
        FileConfiguration config = getConfig();
        Object value = config.get("reasons");
        List<ReportReason> reasons;
        if (value instanceof List<?>) {
            reasons = getLegacyReasons((List<?>) value);
        } else {
            ConfigurationSection section = config.getConfigurationSection("reasons");
            reasons = new ArrayList<>();
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    ConfigurationSection reasonSection = section.getConfigurationSection(key);
                    if (reasonSection == null) {
                        continue;
                    }
                    String name = reasonSection.getString("name", key);
                    String display = reasonSection.getString("display", name);
                    List<String> punishmentCommands = reasonSection.getStringList("punishment.commands");
                    reasons.add(new ReportReason(key, name, display, punishmentCommands));
                }
            }
        }

        if (reasons.isEmpty()) {
            reasons = FALLBACK_REASONS;
        }
        List<ReportReason> immutableReasons = List.copyOf(reasons);
        Map<String, ReportReason> byName = new LinkedHashMap<>();
        Map<String, ReportReason> byKey = new LinkedHashMap<>();
        for (ReportReason reason : immutableReasons) {
            byName.put(reason.getName().toLowerCase(Locale.ROOT), reason);
            byKey.put(reason.getKey().toLowerCase(Locale.ROOT), reason);
        }
        cachedReasons = immutableReasons;
        cachedReasonsByName = Map.copyOf(byName);
        cachedReasonsByKey = Map.copyOf(byKey);
    }

    public static String getDisplay(String reasonName) {
        getReasons();
        ReportReason reason = cachedReasonsByName.get(reasonName.toLowerCase(Locale.ROOT));
        if (reason != null) {
            return ChatUtil.color(reason.getDisplay());
        }
        return ChatUtil.color(ChatUtil.escapeUserText(reasonName));
    }

    public static List<String> getNames() {
        List<String> names = new ArrayList<>();
        for (ReportReason reason : getReasons()) {
            names.add(reason.getName());
        }
        return names;
    }

    public static List<String> getPunishmentCommands(String reasonName) {
        getReasons();
        ReportReason reason = cachedReasonsByName.get(reasonName.toLowerCase(Locale.ROOT));
        return reason == null ? List.of() : reason.getPunishmentCommands();
    }

    public static ReportReason findByKey(String key) {
        if (key == null) {
            return null;
        }
        getReasons();
        return cachedReasonsByKey.get(key.toLowerCase(Locale.ROOT));
    }

    public static ReportReason findByName(String name) {
        if (name == null) {
            return null;
        }
        getReasons();
        return cachedReasonsByName.get(name.toLowerCase(Locale.ROOT));
    }

    private static List<ReportReason> getLegacyReasons(List<?> values) {
        List<ReportReason> reasons = new ArrayList<>();
        for (Object value : values) {
            String name = String.valueOf(value);
            reasons.add(new ReportReason(name, name));
        }
        return reasons.isEmpty() ? FALLBACK_REASONS : reasons;
    }

    private static FileConfiguration getConfig() {
        if (reasonsConfig == null) {
            load(CloverReports.getInstance());
        }
        return reasonsConfig;
    }

    private static void migrateOldReasons(CloverReports plugin) {
        Object value = plugin.getConfig().get("report.reasons");
        if (value == null) {
            return;
        }

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("report.reasons");
        if (section != null) {
            copySection(section, reasonsConfig, "reasons");
        } else if (value instanceof List<?>) {
            int index = 1;
            for (Object entry : (List<?>) value) {
                String name = String.valueOf(entry);
                String path = "reasons.reason-" + index;
                reasonsConfig.set(path + ".name", name);
                reasonsConfig.set(path + ".display", name);
                index++;
            }
        }

        save();
        plugin.getConfig().set("report.reasons", null);
        plugin.saveConfig();
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

    private static void save() {
        File temporary = new File(reasonsFile.getParentFile(), reasonsFile.getName() + ".tmp");
        try {
            reasonsConfig.save(temporary);
            try {
                Files.move(temporary.toPath(), reasonsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), reasonsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
            }
            throw new IllegalStateException("Cannot save reasons.yml", exception);
        }
    }
}
