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
import java.util.Locale;

final class AdminMenu {
    enum Page {
        HOME,
        CONTROL,
        BORDER
    }

    private final Hide_and_seek plugin;

    AdminMenu(Hide_and_seek plugin) {
        this.plugin = plugin;
    }

    void open(Player player, Page page) {
        player.openInventory(switch (page) {
            case HOME -> buildHome();
            case CONTROL -> buildControl();
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
                List.of("当前值: " + plugin.configuredSeekerCount(), "小步进: 1", "大步进: 2")),
                "减少 2", "减少 1", "增加 1", "增加 2");
        placeAdjuster(inventory, 19, namedItem(Material.SPYGLASS, "寻找者释放倒计时", NamedTextColor.YELLOW,
                List.of("当前值: " + formatSeconds(plugin.configuredSeekerReleaseDelayTicks()), "小步进: 5 秒", "大步进: 30 秒")),
                "减少 30 秒", "减少 5 秒", "增加 5 秒", "增加 30 秒");
        placeAdjuster(inventory, 28, namedItem(Material.CLOCK, "游戏总时长", NamedTextColor.YELLOW,
                List.of("当前值: " + formatSeconds(plugin.configuredDurationTicks()), "小步进: 30 秒", "大步进: 60 秒")),
                "减少 60 秒", "减少 30 秒", "增加 30 秒", "增加 60 秒");

        inventory.setItem(36, namedItem(Material.LIME_WOOL, "开始游戏", NamedTextColor.GREEN,
                List.of("立即开始一局新的躲猫猫")));
        inventory.setItem(38, namedItem(Material.RED_WOOL, "停止游戏", NamedTextColor.RED,
                List.of("终止当前对局并清理实体")));
        inventory.setItem(40, namedItem(Material.COMPASS, "对局控制", NamedTextColor.GOLD,
                List.of("进入胜负结算、出生点和重载菜单")));
        inventory.setItem(42, namedItem(Material.ORANGE_STAINED_GLASS, "边界设置", NamedTextColor.GOLD,
                List.of("调整初始与最终边界尺寸")));
        inventory.setItem(44, namedItem(Material.IRON_DOOR, "退出管理员模式", NamedTextColor.RED,
                List.of("恢复普通玩家身份")));

        inventory.setItem(45, namedItem(Material.PAPER, "当前阶段", NamedTextColor.WHITE,
                List.of(plugin.currentPhaseLabel())));
        inventory.setItem(47, namedItem(Material.CLOCK, "剩余时间", NamedTextColor.WHITE,
                List.of(plugin.remainingSeconds() + " 秒")));
        inventory.setItem(49, namedItem(Material.PLAYER_HEAD, "人数统计", NamedTextColor.WHITE,
                List.of(
                        "躲藏者: " + plugin.currentHiderCount(),
                        "寻找者: " + plugin.currentSeekerCount(),
                        "管理员: " + plugin.currentAdminCount()
                )));
        inventory.setItem(51, namedItem(Material.RESPAWN_ANCHOR, "小游戏出生点", NamedTextColor.WHITE,
                List.of(plugin.arenaSpawnSummary())));
        inventory.setItem(53, namedItem(Material.RED_STAINED_GLASS, "边界概览", NamedTextColor.WHITE,
                List.of(plugin.borderStatusSummary())));
        return inventory;
    }

    private Inventory buildControl() {
        Inventory inventory = Bukkit.createInventory(new Holder(Page.CONTROL), 54, Component.text("管理员菜单 - 对局控制", NamedTextColor.GOLD));
        fillFrame(inventory);
        inventory.setItem(11, namedItem(Material.RED_BANNER, "寻找者胜利", NamedTextColor.RED,
                List.of("立即结束对局并判定寻找者获胜")));
        inventory.setItem(13, namedItem(Material.LIGHT_BLUE_BANNER, "躲藏者胜利", NamedTextColor.AQUA,
                List.of("立即结束对局并判定躲藏者获胜")));
        inventory.setItem(15, namedItem(Material.RESPAWN_ANCHOR, "设置出生点", NamedTextColor.YELLOW,
                List.of("将当前位置保存为小游戏出生点")));
        inventory.setItem(31, namedItem(Material.REPEATER, "重载配置", NamedTextColor.GREEN,
                List.of("从 config.yml 重新读取配置")));
        inventory.setItem(49, namedItem(Material.ARROW, "返回主页", NamedTextColor.WHITE,
                List.of("返回管理员首页")));
        return inventory;
    }

    private Inventory buildBorder() {
        Inventory inventory = Bukkit.createInventory(new Holder(Page.BORDER), 54, Component.text("管理员菜单 - 边界设置", NamedTextColor.GOLD));
        fillFrame(inventory);
        placeAdjuster(inventory, 10, namedItem(Material.RED_STAINED_GLASS, "初始边界宽度", NamedTextColor.GOLD,
                List.of("当前值: " + formatDimension(plugin.configuredBorderInitialWidth()), "小步进: 5", "大步进: 20")),
                "减少 20", "减少 5", "增加 5", "增加 20");
        placeAdjuster(inventory, 19, namedItem(Material.RED_STAINED_GLASS_PANE, "初始边界深度", NamedTextColor.GOLD,
                List.of("当前值: " + formatDimension(plugin.configuredBorderInitialDepth()), "小步进: 5", "大步进: 20")),
                "减少 20", "减少 5", "增加 5", "增加 20");
        placeAdjuster(inventory, 28, namedItem(Material.ORANGE_STAINED_GLASS, "最终边界宽度", NamedTextColor.GOLD,
                List.of("当前值: " + formatDimension(plugin.configuredBorderFinalWidth()), "小步进: 5", "大步进: 20")),
                "减少 20", "减少 5", "增加 5", "增加 20");
        placeAdjuster(inventory, 37, namedItem(Material.ORANGE_STAINED_GLASS_PANE, "最终边界深度", NamedTextColor.GOLD,
                List.of("当前值: " + formatDimension(plugin.configuredBorderFinalDepth()), "小步进: 5", "大步进: 20")),
                "减少 20", "减少 5", "增加 5", "增加 20");
        inventory.setItem(49, namedItem(Material.ARROW, "返回主页", NamedTextColor.WHITE,
                List.of("返回管理员首页")));
        return inventory;
    }

    private void placeAdjuster(Inventory inventory, int startSlot, ItemStack center, String bigMinus, String smallMinus, String smallPlus, String bigPlus) {
        inventory.setItem(startSlot, actionButton(Material.RED_STAINED_GLASS_PANE, "<<", NamedTextColor.RED, bigMinus));
        inventory.setItem(startSlot + 1, actionButton(Material.ORANGE_STAINED_GLASS_PANE, "<", NamedTextColor.GOLD, smallMinus));
        inventory.setItem(startSlot + 2, center);
        inventory.setItem(startSlot + 3, actionButton(Material.LIME_STAINED_GLASS_PANE, ">", NamedTextColor.GREEN, smallPlus));
        inventory.setItem(startSlot + 4, actionButton(Material.GREEN_STAINED_GLASS_PANE, ">>", NamedTextColor.GREEN, bigPlus));
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

    private String formatSeconds(int ticks) {
        return (ticks / 20) + " 秒";
    }

    private String formatDimension(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return Integer.toString((int) Math.rint(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record Holder(Page page) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
