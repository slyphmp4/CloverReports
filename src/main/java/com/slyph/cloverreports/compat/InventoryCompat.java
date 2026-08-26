package com.slyph.cloverreports.compat;

import com.slyph.cloverreports.utils.ChatUtil;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class InventoryCompat {

    private InventoryCompat() {
    }

    public static Inventory create(InventoryHolder holder, int size, String title) {
        if (requiresLegacyTitle(Bukkit.getServer().getName())) {
            return createWithLegacyTitle(holder, size, title);
        }
        Inventory inventory = Bukkit.createInventory(holder, size, ChatUtil.component(title));
        return inventory == null ? createWithLegacyTitle(holder, size, title) : inventory;
    }

    static boolean requiresLegacyTitle(String serverName) {
        return serverName != null && serverName.equalsIgnoreCase("Cardboard");
    }

    @SuppressWarnings("deprecation")
    private static Inventory createWithLegacyTitle(InventoryHolder holder, int size, String title) {
        return Bukkit.createInventory(holder, size, title);
    }
}
