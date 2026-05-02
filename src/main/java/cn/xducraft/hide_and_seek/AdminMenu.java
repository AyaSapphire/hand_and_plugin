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
import java.util.List;

final class AdminMenu {
    enum Page {
        HOME
    }

    private final Hide_and_seek plugin;

    AdminMenu(Hide_and_seek plugin) {
        this.plugin = plugin;
    }

    void open(Player player, Page page) {
        player.openInventory(buildHome());
    }

    boolean isAdminMenu(Inventory inventory) {
        return inventory.getHolder() instanceof Holder;
    }

    Page pageOf(Inventory inventory) {
        if (!(inventory.getHolder() instanceof Holder holder)) return null;
        return holder.page();
    }

    private Inventory buildHome() {
        Inventory inventory = Bukkit.createInventory(new Holder(Page.HOME), 9, Component.text("寻找者设置", NamedTextColor.GOLD));
        fillFrame(inventory);
        placeAdjuster(inventory, 2, namedItem(Material.PLAYER_HEAD, "寻找者人数", NamedTextColor.AQUA,
                List.of("当前值: " + plugin.configuredSeekerCount())));
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

    private record Holder(Page page) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
