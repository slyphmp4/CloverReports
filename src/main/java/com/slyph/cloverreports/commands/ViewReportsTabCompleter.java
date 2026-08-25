package com.slyph.cloverreports.commands;

import com.slyph.cloverreports.gui.ReportsGUI;
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

public final class ViewReportsTabCompleter implements TabCompleter {

    private final ReportManager reportManager;

    public ViewReportsTabCompleter(ReportManager reportManager) {
        this.reportManager = reportManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cloverreports.view")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            add(result, "history", args[0]);
            add(result, "player", args[0]);
            result.addAll(pageSuggestions(ReportManager.STATUS_PENDING, null, args[0]));
            return result;
        }
        if (args[0].equalsIgnoreCase("player")) {
            if (args.length == 2) {
                return reportManager.getReportedPlayers(ReportManager.STATUS_PENDING, args[1]);
            }
            if (args.length == 3) {
                return pageSuggestions(ReportManager.STATUS_PENDING, args[1], args[2]);
            }
            return Collections.emptyList();
        }
        if (!args[0].equalsIgnoreCase("history")) {
            return Collections.emptyList();
        }
        return historySuggestions(args);
    }

    private List<String> historySuggestions(String[] args) {
        String input = args[args.length - 1];
        String lower = input.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        if (lower.startsWith("player=")) {
            String value = input.substring(input.indexOf('=') + 1);
            for (String player : reportManager.getReportedPlayers(ReportManager.STATUS_RESOLVED, value)) {
                result.add("player=" + player);
            }
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
            add(result, "action=closed", input);
            add(result, "action=punished", input);
            return new ArrayList<>(result);
        }
        if (lower.startsWith("from=")) {
            add(result, "from=" + LocalDate.now(), input);
            return new ArrayList<>(result);
        }
        if (lower.startsWith("to=")) {
            add(result, "to=" + LocalDate.now(), input);
            return new ArrayList<>(result);
        }
        if (lower.startsWith("page=")) {
            add(result, "page=1", input);
            return new ArrayList<>(result);
        }
        Set<String> used = new LinkedHashSet<>();
        for (int index = 1; index < args.length - 1; index++) {
            int separator = args[index].indexOf('=');
            if (separator > 0) {
                used.add(args[index].substring(0, separator).toLowerCase(Locale.ROOT));
            }
        }
        addKey(result, used, "player", input);
        addKey(result, used, "moderator", input);
        addKey(result, used, "reason", input);
        addKey(result, used, "action", input);
        addKey(result, used, "from", input);
        addKey(result, used, "to", input);
        addKey(result, used, "page", input);
        if (args.length == 2) {
            result.addAll(reportManager.getReportedPlayers(ReportManager.STATUS_RESOLVED, input));
        }
        return new ArrayList<>(result);
    }

    private List<String> pageSuggestions(String status, String player, String input) {
        int count = reportManager.getReportedPlayerCount(status, player);
        int pages = Math.max(1, (int) Math.ceil(count / (double) ReportsGUI.getReportsPerPage()));
        List<String> result = new ArrayList<>();
        for (int page = 1; page <= Math.min(pages, 100); page++) {
            add(result, String.valueOf(page), input);
        }
        return result;
    }

    private void addKey(Set<String> result, Set<String> used, String key, String input) {
        if (!used.contains(key)) {
            add(result, key + "=", input);
        }
    }

    private void add(java.util.Collection<String> result, String value, String input) {
        if (value.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))) {
            result.add(value);
        }
    }
}
