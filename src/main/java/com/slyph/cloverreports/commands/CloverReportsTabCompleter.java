package com.slyph.cloverreports.commands;

import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.reasons.ReportReason;
import com.slyph.cloverreports.reasons.ReportReasons;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CloverReportsTabCompleter implements TabCompleter {

    private final ReportManager reportManager;

    public CloverReportsTabCompleter(ReportManager reportManager) {
        this.reportManager = reportManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            add(result, "reload", args[0], sender.hasPermission("cloverreports.reload"));
            add(result, "backup", args[0], sender.hasPermission("cloverreports.backup"));
            add(result, "note", args[0], sender.hasPermission("cloverreports.note"));
            add(result, "logs", args[0], sender.hasPermission("cloverreports.logs"));
            add(result, "export", args[0], sender.hasPermission("cloverreports.export"));
            return result;
        }
        if (args[0].equalsIgnoreCase("note") && sender.hasPermission("cloverreports.note")) {
            if (args.length == 2) {
                return reportManager.getReportedPlayers(ReportManager.STATUS_PENDING, args[1]);
            }
            if (args.length == 3 && sender.hasPermission("cloverreports.note.clear-all")
                    && "clear".startsWith(args[2].toLowerCase(Locale.ROOT))) {
                return List.of("clear");
            }
            return Collections.emptyList();
        }
        if (args[0].equalsIgnoreCase("logs") && sender.hasPermission("cloverreports.logs")) {
            return filterSuggestions(args, 1);
        }
        if (args[0].equalsIgnoreCase("export") && sender.hasPermission("cloverreports.export")) {
            if (args.length == 2) {
                List<String> result = new ArrayList<>();
                add(result, "csv", args[1], true);
                add(result, "json", args[1], true);
                return result;
            }
            return filterSuggestions(args, 2);
        }
        return Collections.emptyList();
    }

    private List<String> filterSuggestions(String[] args, int firstFilterIndex) {
        if (args.length <= firstFilterIndex) {
            return Collections.emptyList();
        }
        String input = args[args.length - 1];
        String lower = input.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        if (lower.startsWith("player=")) {
            addPlayers(result, "player=", input.substring(input.indexOf('=') + 1));
            return new ArrayList<>(result);
        }
        if (lower.startsWith("moderator=") || lower.startsWith("mod=")) {
            String prefix = lower.startsWith("mod=") ? "mod=" : "moderator=";
            String value = lower.substring(prefix.length());
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(value)) {
                    result.add(prefix + player.getName());
                }
            }
            return new ArrayList<>(result);
        }
        if (lower.startsWith("reason=")) {
            String value = lower.substring("reason=".length());
            for (ReportReason reason : ReportReasons.getReasons()) {
                if (reason.getKey().toLowerCase(Locale.ROOT).startsWith(value)) {
                    result.add("reason=" + reason.getKey());
                }
            }
            return new ArrayList<>(result);
        }
        if (lower.startsWith("action=")) {
            for (String action : List.of("closed", "punished", "note", "note-clear", "teleport", "open-actions", "select-ban-reason")) {
                add(result, "action=" + action, input, true);
            }
            return new ArrayList<>(result);
        }
        if (lower.startsWith("from=")) {
            add(result, "from=" + LocalDate.now(), input, true);
            return new ArrayList<>(result);
        }
        if (lower.startsWith("to=")) {
            add(result, "to=" + LocalDate.now(), input, true);
            return new ArrayList<>(result);
        }
        if (lower.startsWith("page=")) {
            add(result, "page=1", input, true);
            return new ArrayList<>(result);
        }

        Set<String> used = new LinkedHashSet<>();
        for (int index = firstFilterIndex; index < args.length - 1; index++) {
            int separator = args[index].indexOf('=');
            if (separator > 0) {
                used.add(args[index].substring(0, separator).toLowerCase(Locale.ROOT));
            }
        }
        for (String key : List.of("player", "moderator", "reason", "action", "from", "to", "page")) {
            if (!used.contains(key)) {
                add(result, key + "=", input, true);
            }
        }
        if (args.length == firstFilterIndex + 1) {
            addPlayers(result, "", input);
        }
        return new ArrayList<>(result);
    }

    private void addPlayers(Set<String> result, String prefix, String input) {
        for (String player : reportManager.getReportedPlayers(ReportManager.STATUS_PENDING, input)) {
            result.add(prefix + player);
        }
        for (String player : reportManager.getReportedPlayers(ReportManager.STATUS_RESOLVED, input)) {
            result.add(prefix + player);
        }
    }

    private void add(java.util.Collection<String> result, String value, String input, boolean allowed) {
        if (allowed && value.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))) {
            result.add(value);
        }
    }
}
