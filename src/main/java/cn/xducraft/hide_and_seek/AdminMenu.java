package cn.xducraft.hide_and_seek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AdminMenu {
    enum Page {
        HOME,
        PRESETS
    }

    private final Hide_and_seek plugin;

    AdminMenu(Hide_and_seek plugin) {
        this.plugin = plugin;
    }

    void open(Player player, Page page) {
        player.openInventory(page == Page.PRESETS ? buildPresets() : buildHome());
    }

    boolean isAdminMenu(Inventory inventory) {
        return inventory.getHolder() instanceof Holder;
    }

    Page pageOf(Inventory inventory) {
        if (!(inventory.getHolder() instanceof Holder holder)) return null;
        return holder.page();
    }

    String presetIdAt(Inventory inventory, int slot) {
        if (!(inventory.getHolder() instanceof Holder holder)) return null;
        return holder.presetSlots().get(slot);
    }

    private Inventory buildHome() {
        Inventory inventory = Bukkit.createInventory(new Holder(Page.HOME), 9, Component.text("寻找者设置", NamedTextColor.GOLD));
        fillFrame(inventory);
        placeAdjuster(inventory, 2, namedItem(Material.PLAYER_HEAD, "寻找者人数", NamedTextColor.AQUA,
                List.of("当前值: " + plugin.configuredSeekerCount())));
        return inventory;
    }

    private Inventory buildPresets() {
        Map<Integer, String> presetSlots = new HashMap<>();
        Inventory inventory = Bukkit.createInventory(new Holder(Page.PRESETS, presetSlots), 54, Component.text("预设列表", NamedTextColor.LIGHT_PURPLE));
        fillFrame(inventory);

        List<Hide_and_seek.PresetMenuEntry> entries = plugin.presetMenuEntries();
        int slot = 0;
        for (Hide_and_seek.PresetMenuEntry entry : entries) {
            if (slot >= 45) break;
            inventory.setItem(slot, presetItem(entry));
            presetSlots.put(slot, entry.id());
            slot++;
        }

        inventory.setItem(45, namedItem(Material.BOOKSHELF, "当前编辑预设 #" + plugin.currentPresetLabel(), NamedTextColor.LIGHT_PURPLE,
                List.of("左键切换  中键删除  右键启停")));
        inventory.setItem(47, namedItem(Material.LIME_DYE, "新增预设", NamedTextColor.GREEN,
                List.of("复制当前编辑预设", "并自动切换到新预设")));
        return inventory;
    }

    private void placeAdjuster(Inventory inventory, int startSlot, ItemStack center) {
        inventory.setItem(startSlot, actionButton(Material.RED_STAINED_GLASS_PANE, "<<", NamedTextColor.RED, "减少 2"));
        inventory.setItem(startSlot + 1, actionButton(Material.ORANGE_STAINED_GLASS_PANE, "<", NamedTextColor.GOLD, "减少 1"));
        inventory.setItem(startSlot + 2, center);
        inventory.setItem(startSlot + 3, actionButton(Material.LIME_STAINED_GLASS_PANE, ">", NamedTextColor.GREEN, "增加 1"));
        inventory.setItem(startSlot + 4, actionButton(Material.GREEN_STAINED_GLASS_PANE, ">>", NamedTextColor.GREEN, "增加 2"));
    }

    private ItemStack actionButton(Material material, String label, NamedTextColor color, String description) {
        return namedItem(material, label, color, List.of(description));
    }

    private ItemStack presetItem(Hide_and_seek.PresetMenuEntry entry) {
        Material material = entry.selected()
                ? Material.PINK_WOOL
                : entry.enabled() ? Material.LIME_WOOL : Material.RED_WOOL;
        List<String> lore = new ArrayList<>();
        lore.add(entry.selected() ? "当前正在编辑" : entry.enabled() ? "当前启用中" : "当前禁用中");
        lore.add("世界: " + entry.worldName());
        lore.add("初始边界: " + entry.initialWidth() + " x " + entry.initialDepth());
        lore.add("最终边界: " + entry.finalWidth() + " x " + entry.finalDepth());
        lore.add("左键切换到该预设");
        lore.add("中键删除该预设");
        lore.add("右键切换启用状态");
        return namedItem(material, "预设 #" + entry.id(), entry.selected() ? NamedTextColor.LIGHT_PURPLE
                : entry.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED, lore);
    }

    private ItemStack namedItem(Material material, String name, NamedTextColor color, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(Component.text(line, NamedTextColor.GRAY));
            }
            meta.lore(lore);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private void fillFrame(Inventory inventory) {
        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY, List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private record Holder(Page page, Map<Integer, String> presetSlots) implements InventoryHolder {
        private Holder(Page page) {
            this(page, Map.of());
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
