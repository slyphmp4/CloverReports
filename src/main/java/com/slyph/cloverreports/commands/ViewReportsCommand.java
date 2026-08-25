package com.slyph.cloverreports.commands;

import com.slyph.cloverreports.commands.filters.HistoryFilterParser;
import com.slyph.cloverreports.gui.ReportsGUI;
import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.utils.InputValidator;
import com.slyph.cloverreports.utils.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

public final class ViewReportsCommand implements CommandExecutor {

    private final ReportManager reportManager;

    public ViewReportsCommand(ReportManager reportManager) {
        this.reportManager = reportManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Messages.getChatArray("not-player"));
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("cloverreports.view")) {
            player.sendMessage(Messages.getChatArray("no-permission"));
            return true;
        }
        if (args.length == 0) {
            ReportsGUI.openMainGUI(player, reportManager);
            return true;
        }

        String mode = args[0].toLowerCase(Locale.ROOT);
        if (mode.equals("history")) {
            HistoryFilterParser.Result result = HistoryFilterParser.parse(args, 1, ZoneId.systemDefault());
            if (!result.isValid()) {
                player.sendMessage(Messages.getChatArray("history-filter-invalid", Map.of("%filter%", result.getInvalidToken())));
                return true;
            }
            ReportsGUI.openHistoryGUI(player, reportManager, result.getFilter(), result.getPage());
            return true;
        }
        if (mode.equals("player")) {
            if (args.length < 2 || args.length > 3 || !InputValidator.isValidPlayerName(args[1])) {
                player.sendMessage(Messages.getChatArray("viewreports-usage"));
                return true;
            }
            int page = args.length == 3 ? parsePage(args[2]) : 0;
            if (page < 0) {
                player.sendMessage(Messages.getChatArray("viewreports-usage"));
                return true;
            }
            ReportsGUI.openPlayerReportsGUI(player, reportManager, args[1], page);
            return true;
        }
        int page = parsePage(args[0]);
        if (page >= 0 && args.length == 1) {
            ReportsGUI.openMainGUI(player, reportManager, page);
            return true;
        }
        player.sendMessage(Messages.getChatArray("viewreports-usage"));
        return true;
    }

    private int parsePage(String value) {
        try {
            int page = Integer.parseInt(value);
            return page > 0 ? page - 1 : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
