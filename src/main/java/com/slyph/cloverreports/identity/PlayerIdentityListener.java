package com.slyph.cloverreports.identity;

import com.slyph.cloverreports.CloverReports;
import com.slyph.cloverreports.managers.ReportManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public final class PlayerIdentityListener implements Listener {

    private final CloverReports plugin;
    private final ReportManager reportManager;

    public PlayerIdentityListener(CloverReports plugin, ReportManager reportManager) {
        this.plugin = plugin;
        this.reportManager = reportManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        register(event.getPlayer());
    }

    public void register(Player player) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> reportManager.registerIdentity(playerId, playerName));
    }
}
