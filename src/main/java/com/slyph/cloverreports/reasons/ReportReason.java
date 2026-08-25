package com.slyph.cloverreports.reasons;

import java.util.List;

public final class ReportReason {

    private final String key;
    private final String name;
    private final String display;
    private final List<String> punishmentCommands;

    public ReportReason(String name, String display) {
        this(name, name, display, List.of());
    }

    public ReportReason(String name, String display, List<String> punishmentCommands) {
        this(name, name, display, punishmentCommands);
    }

    public ReportReason(String key, String name, String display, List<String> punishmentCommands) {
        this.key = key;
        this.name = name;
        this.display = display;
        this.punishmentCommands = List.copyOf(punishmentCommands);
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getDisplay() {
        return display;
    }

    public List<String> getPunishmentCommands() {
        return punishmentCommands;
    }
}
