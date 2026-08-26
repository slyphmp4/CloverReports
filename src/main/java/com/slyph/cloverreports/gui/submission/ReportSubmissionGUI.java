package com.slyph.cloverreports.gui.submission;

import com.slyph.cloverreports.compat.InventoryCompat;
import com.slyph.cloverreports.reasons.ReportReason;
import com.slyph.cloverreports.reasons.ReportReasons;
import com.slyph.cloverreports.utils.ChatUtil;
import com.slyph.cloverreports.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ReportSubmissionGUI {

    public static final int PREVIOUS_SLOT = 45;
    public static final int CANCEL_SLOT = 49;
    public static final int NEXT_SLOT = 53;
    public static final int SELECTED_REASON_SLOT = 11;
    public static final int EVIDENCE_SLOT = 13;
    public static final int SUBMIT_SLOT = 15;
    public static final int BACK_SLOT = 31;
    private static final int[] REASON_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    public void openReasonSelection(Player player, String targetName, UUID targetUuid) {
        openReasonSelection(player, targetName, targetUuid, 0, null);
    }

    public void openReasonSelection(Player player, String targetName, UUID targetUuid, int requestedPage, String evidenceUrl) {
        List<ReportReason> reasons = ReportReasons.getReasons();
        int totalPages = Math.max(1, (int) Math.ceil(reasons.size() / (double) REASON_SLOTS.length));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        Map<String, String> placeholders = placeholders(targetName, page, totalPages, null, evidenceUrl);
        ReportSubmissionHolder holder = new ReportSubmissionHolder(ReportSubmissionMenuType.REASON_SELECTION, targetName, targetUuid, page, totalPages, null, evidenceUrl);
        Inventory inventory = InventoryCompat.create(holder, 54, guiText("submission.reason-selection.title", "&0Жалоба на %player%", placeholders));
        holder.setInventory(inventory);

        fill(inventory, item(material("submission.filler.material", Material.GRAY_STAINED_GLASS_PANE), guiText("submission.filler.display", "&7", Map.of()), guiList("submission.filler.lore", List.of(), Map.of())));
        int start = page * REASON_SLOTS.length;
        int end = Math.min(reasons.size(), start + REASON_SLOTS.length);
        for (int index = start; index < end; index++) {
            ReportReason reason = reasons.get(index);
            int slot = REASON_SLOTS[index - start];
            Map<String, String> reasonPlaceholders = placeholders(targetName, page, totalPages, reason, evidenceUrl);
            inventory.setItem(slot, item(material("submission.reason-selection.reason.material", Material.PAPER), guiText("submission.reason-selection.reason.display", "&f%reason%", reasonPlaceholders), guiList("submission.reason-selection.reason.lore", List.of("&7", "&aНажмите, чтобы выбрать", "&7"), reasonPlaceholders)));
            holder.setReason(slot, reason);
        }

        if (page > 0) {
            Map<String, String> navigation = new HashMap<>(placeholders);
            navigation.put("%page%", String.valueOf(page));
            inventory.setItem(PREVIOUS_SLOT, item(material("submission.reason-selection.previous.material", Material.ARROW), guiText("submission.reason-selection.previous.display", "&f← Назад", navigation), guiList("submission.reason-selection.previous.lore", List.of("&7Страница %page%"), navigation)));
        }
        inventory.setItem(CANCEL_SLOT, item(material("submission.reason-selection.cancel.material", Material.BARRIER), guiText("submission.reason-selection.cancel.display", "&cОтмена", placeholders), guiList("submission.reason-selection.cancel.lore", List.of("&7Закрыть меню"), placeholders)));
        if (page + 1 < totalPages) {
            Map<String, String> navigation = new HashMap<>(placeholders);
            navigation.put("%page%", String.valueOf(page + 2));
            inventory.setItem(NEXT_SLOT, item(material("submission.reason-selection.next.material", Material.ARROW), guiText("submission.reason-selection.next.display", "&fВперёд →", navigation), guiList("submission.reason-selection.next.lore", List.of("&7Страница %page%"), navigation)));
        }
        player.openInventory(inventory);
    }

    public void openConfirmation(Player player, String targetName, UUID targetUuid, int reasonPage, ReportReason reason, String evidenceUrl) {
        Map<String, String> placeholders = placeholders(targetName, reasonPage, 1, reason, evidenceUrl);
        ReportSubmissionHolder holder = new ReportSubmissionHolder(ReportSubmissionMenuType.CONFIRMATION, targetName, targetUuid, reasonPage, 1, reason, evidenceUrl);
        Inventory inventory = InventoryCompat.create(holder, 36, guiText("submission.confirmation.title", "&0Подтверждение жалобы", placeholders));
        holder.setInventory(inventory);
        fill(inventory, item(material("submission.filler.material", Material.GRAY_STAINED_GLASS_PANE), guiText("submission.filler.display", "&7", Map.of()), guiList("submission.filler.lore", List.of(), Map.of())));

        inventory.setItem(SELECTED_REASON_SLOT, item(material("submission.confirmation.reason.material", Material.WRITABLE_BOOK), guiText("submission.confirmation.reason.display", "&fПричина: &e%reason%", placeholders), guiList("submission.confirmation.reason.lore", List.of("&7Вернитесь назад, чтобы сменить"), placeholders)));

        if (!player.hasPermission("cloverreports.report.evidence")) {
            inventory.setItem(EVIDENCE_SLOT, item(material("submission.confirmation.evidence-disabled.material", Material.BARRIER), guiText("submission.confirmation.evidence-disabled.display", "&cВложения недоступны", placeholders), guiList("submission.confirmation.evidence-disabled.lore", List.of("&7У вас нет права"), placeholders)));
        } else if (evidenceUrl == null) {
            inventory.setItem(EVIDENCE_SLOT, item(material("submission.confirmation.evidence-empty.material", Material.MAP), guiText("submission.confirmation.evidence-empty.display", "&bДобавить ссылку", placeholders), guiList("submission.confirmation.evidence-empty.lore", List.of("&7Нажмите и вставьте в чат", "&7ссылку на скриншот или видео"), placeholders)));
        } else {
            inventory.setItem(EVIDENCE_SLOT, item(material("submission.confirmation.evidence-set.material", Material.FILLED_MAP), guiText("submission.confirmation.evidence-set.display", "&aВложение добавлено", placeholders), guiListWithEvidence("submission.confirmation.evidence-set.lore", List.of("&f%evidence%", "&7", "&eЛКМ: &7заменить", "&cПКМ: &7удалить"), placeholders, evidenceUrl)));
        }

        inventory.setItem(SUBMIT_SLOT, item(material("submission.confirmation.submit.material", Material.LIME_DYE), guiText("submission.confirmation.submit.display", "&aОтправить жалобу", placeholders), guiList("submission.confirmation.submit.lore", List.of("&7Проверьте данные и нажмите"), placeholders)));
        inventory.setItem(BACK_SLOT, item(material("submission.confirmation.back.material", Material.ARROW), guiText("submission.confirmation.back.display", "&f← Выбрать другую причину", placeholders), guiList("submission.confirmation.back.lore", List.of("&7Вернуться к списку"), placeholders)));
        player.openInventory(inventory);
    }

    private Map<String, String> placeholders(String targetName, int page, int totalPages, ReportReason reason, String evidenceUrl) {
        Map<String, String> result = new HashMap<>();
        result.put("%player%", targetName);
        result.put("%page%", String.valueOf(page + 1));
        result.put("%pages%", String.valueOf(totalPages));
        result.put("%reason%", reason == null ? "" : reason.getDisplay());
        result.put("%reason_name%", reason == null ? "" : reason.getName());
        result.put("%evidence%", evidenceUrl == null ? "" : evidenceUrl);
        return result;
    }

    private void fill(Inventory inventory, ItemStack filler) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private ItemStack item(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(ChatUtil.component(displayName));
        List<String> visibleLore = lore.size() <= 50 ? lore : lore.subList(0, 50);
        meta.lore(ChatUtil.components(visibleLore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    private Material material(String path, Material fallback) {
        String configured = Messages.getGui(path);
        if (configured.equals("gui." + path)) {
            return fallback;
        }
        Material material = Material.matchMaterial(configured.trim().toUpperCase(Locale.ROOT));
        return material == null || material == Material.AIR ? fallback : material;
    }

    private String guiText(String path, String fallback, Map<String, String> placeholders) {
        String configured = Messages.getGui(path, placeholders);
        if (!configured.equals("gui." + path)) {
            return configured;
        }
        return ChatUtil.color(replace(fallback, placeholders));
    }

    private List<String> guiList(String path, List<String> fallback, Map<String, String> placeholders) {
        List<String> configured = Messages.getGuiList(path, placeholders);
        if (!(configured.size() == 1 && configured.get(0).equals("gui." + path))) {
            return configured;
        }
        List<String> result = new ArrayList<>(fallback.size());
        for (String line : fallback) {
            result.add(ChatUtil.color(replace(line, placeholders)));
        }
        return result;
    }

    private List<String> guiListWithEvidence(String path, List<String> fallback, Map<String, String> placeholders, String evidenceUrl) {
        String token = "{CLOVER_EVIDENCE_URL}";
        Map<String, String> safePlaceholders = new HashMap<>(placeholders);
        safePlaceholders.put("%evidence%", token);
        List<String> lines = guiList(path, fallback, safePlaceholders);
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(line.replace(token, evidenceUrl));
        }
        return result;
    }

    private String replace(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }
}
