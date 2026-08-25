package com.slyph.cloverreports.commands;

import com.slyph.cloverreports.CloverReports;
import com.slyph.cloverreports.gui.submission.ReportSubmissionListener;
import com.slyph.cloverreports.utils.InputValidator;
import com.slyph.cloverreports.utils.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;

import java.util.Map;

public final class ReportCommand implements CommandExecutor {

    private final ReportSubmissionListener submissionListener;

    public ReportCommand(ReportSubmissionListener submissionListener) {
        this.submissionListener = submissionListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cloverreports.report")) {
            sender.sendMessage(Messages.getChatArray("no-permission"));
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(Messages.getChatArray("not-player"));
            return true;
        }
        Player player = (Player) sender;
        if (args.length != 1 || !InputValidator.isValidPlayerName(args[0])) {
            player.sendMessage(Messages.getChatArray("report-usage"));
            return true;
        }
        Player onlineTarget = Bukkit.getPlayerExact(args[0]);
        OfflinePlayer knownTarget = onlineTarget == null ? Bukkit.getOfflinePlayerIfCached(args[0]) : onlineTarget;
        String targetName = knownTarget == null || knownTarget.getName() == null ? args[0] : knownTarget.getName();
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(Messages.getChatArray("cannot-report-yourself"));
            return true;
        }
        if (CloverReports.getInstance().getConfig().getBoolean("report.online-only", false) && onlineTarget == null) {
            player.sendMessage(Messages.getChatArray("player-not-found", Map.of("%player%", targetName)));
            return true;
        }
        submissionListener.open(player, targetName, knownTarget == null ? null : knownTarget.getUniqueId());
        return true;
    }
}
