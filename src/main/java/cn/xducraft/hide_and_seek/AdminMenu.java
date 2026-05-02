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
        HOME,
        BORDER
    }

    private final Hide_and_seek plugin;

    AdminMenu(Hide_and_seek plugin) {
        this.plugin = plugin;
    }

    void open(Player player, Page page) {
        player.openInventory(switch (page) {
            case HOME -> buildHome();
            case BORDER -> buildBorder();
        });
    }

    boolean isAdminMenu(Inventory inventory) {
        return inventory.getHolder() instanceof Holder;
    }

    Page pageOf(Inventory inventory) {
        if (!(inventory.getHolder() instanceof Holder holder)) return null;
        return holder.page();
    }

    private Inventory buildHome() {
        Inventory inventory = Bukkit.createInventory(new Holder(Page.HOME), 54, Component.text("管理员菜单", NamedTextColor.GOLD));
        fillFrame(inventory);

        placeAdjuster(inventory, 10, namedItem(Material.PLAYER_HEAD, "寻找者数量", NamedTextColor.AQUA,
                List.of("当前值: " + plugin.configuredSeekerCount())));

        inventory.setItem(20, namedItem(Material.LIME_WOOL, "开始游戏", NamedTextColor.GREEN,
                List.of("开始一局新的躲猫猫")));
        inventory.setItem(22, namedItem(Material.RED_WOOL, "停止游戏", NamedTextColor.RED,
                List.of("停止当前对局并清理实体")));
        inventory.setItem(24, namedItem(Material.RESPAWN_ANCHOR, "设置出生点", NamedTextColor.YELLOW,
                List.of("将当前位置保存为小游戏出生点")));

        inventory.setItem(31, namedItem(Material.ORANGE_STAINED_GLASS, "边界工具", NamedTextColor.GOLD,
                List.of("设置初始/最终角点并预览范围")));
        inventory.setItem(33, namedItem(Material.REPEATER, "重载配置", NamedTextColor.GREEN,
                List.of("从 config.yml 重新读取配置")));

        inventory.setItem(45, namedItem(Material.PAPER, "当前阶段", NamedTextColor.WHITE,
                List.of(plugin.currentPhaseLabel())));
        inventory.setItem(47, namedItem(Material.CLOCK, "剩余时间", NamedTextColor.WHITE,
                List.of(plugin.remainingSeconds() + " 秒")));
        inventory.setItem(49, namedItem(Material.RESPAWN_ANCHOR, "小游戏出生点", NamedTextColor.WHITE,
                List.of(plugin.arenaSpawnSummary())));
        inventory.setItem(51, namedItem(Material.RED_STAINED_GLASS, "边界概览", NamedTextColor.WHITE,
                List.of(plugin.borderStatusSummary())));
        return inventory;
    }

    private Inventory buildBorder() {
        Inventory inventory = Bukkit.createInventory(new Holder(Page.BORDER), 54, Component.text("管理员菜单 - 边界工具", NamedTextColor.GOLD));
        fillFrame(inventory);
        inventory.setItem(20, namedItem(Material.RED_CONCRETE, "设为初始边界角点", NamedTextColor.RED,
                List.of("使用当前位置与出生点构造初始矩形")));
        inventory.setItem(22, namedItem(Material.ORANGE_CONCRETE, "设为最终边界角点", NamedTextColor.GOLD,
                List.of("使用当前位置与出生点构造最终矩形")));
        inventory.setItem(24, namedItem(Material.SPYGLASS, "预览边界", NamedTextColor.AQUA,
                List.of("在世界中短暂显示当前初始与最终边界")));
        inventory.setItem(49, namedItem(Material.ARROW, "返回主页", NamedTextColor.WHITE,
                List.of("返回管理员首页")));
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
