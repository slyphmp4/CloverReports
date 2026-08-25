package com.slyph.cloverreports.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ReportTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cloverreports.report") || args.length != 1) {
            return Collections.emptyList();
        }
        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getName().equalsIgnoreCase(sender.getName()) && player.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                completions.add(player.getName());
            }
        }
        completions.sort(String.CASE_INSENSITIVE_ORDER);
        return completions;
    }
}
