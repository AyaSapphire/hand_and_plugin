package cn.xducraft.hide_and_seek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AdminController implements Listener {
    private final Hide_and_seek plugin;
    private final AdminMenu menu;
    private final NamespacedKey adminToolKey;
    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> previewTasks = new ConcurrentHashMap<>();
    private final Map<UUID, CornerSelection> initialCornerSelections = new ConcurrentHashMap<>();
    private final Map<UUID, CornerSelection> finalCornerSelections = new ConcurrentHashMap<>();

    AdminController(Hide_and_seek plugin) {
        this.plugin = plugin;
        this.menu = new AdminMenu(plugin);
        this.adminToolKey = new NamespacedKey(plugin, "admin_tool");
    }

    boolean isAdmin(UUID uuid) {
        return admins.contains(uuid);
    }

    int adminCount() {
        return admins.size();
    }

    void openMenuCommand(Player player) {
        if (!isAdmin(player.getUniqueId())) {
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.setGameMode(GameMode.CREATIVE);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        enterAdminMode(player, true);
                        menu.open(player, AdminMenu.Page.HOME);
                    }
                }.runTask(plugin);
                return;
            }
            enterAdminMode(player, true);
        } else {
            ensureAdminTools(player);
        }
        menu.open(player, AdminMenu.Page.HOME);
    }

    void startGameAsPlayer(Player player) {
        cancelPreview(player.getUniqueId());
        if (admins.remove(player.getUniqueId())) {
            removeAdminTools(player);
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            player.setGameMode(GameMode.ADVENTURE);
        }
        plugin.applyIdleStateForOrdinaryPlayer(player);
        player.sendMessage(Component.text("你已离开管理员模式，并作为普通玩家加入本局。", NamedTextColor.GREEN));
        plugin.startGameFromAdmin(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                enterAdminMode(event.getPlayer(), false);
            }
        }.runTask(plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        admins.remove(event.getPlayer().getUniqueId());
        cancelPreview(event.getPlayer().getUniqueId());
        initialCornerSelections.remove(event.getPlayer().getUniqueId());
        finalCornerSelections.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.CREATIVE) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    enterAdminMode(event.getPlayer(), true);
                }
            }.runTask(plugin);
            return;
        }

        if (!isAdmin(event.getPlayer().getUniqueId())) return;
        if (event.getNewGameMode() != GameMode.ADVENTURE && event.getNewGameMode() != GameMode.SURVIVAL) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                settleAdminIntoSpectator(event.getPlayer(), true);
            }
        }.runTask(plugin);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        AdminTool tool = adminToolOf(event.getItem());
        if (tool == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!isAdmin(player.getUniqueId())) {
            enterAdminMode(player, true);
        }
        handleToolUse(player, tool);
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!isAdmin(event.getPlayer().getUniqueId())) return;
        ItemStack nextItem = event.getPlayer().getInventory().getItem(event.getNewSlot());
        AdminTool tool = adminToolOf(nextItem);
        if (tool == null || !tool.triggersPreviewAssist()) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!event.getPlayer().isOnline() || !isAdmin(event.getPlayer().getUniqueId())) return;
                startPreview(event.getPlayer());
            }
        }.runTask(plugin);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isAdminItem(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isAdminItem(event.getMainHandItem()) || isAdminItem(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (menu.isAdminMenu(top)) {
            event.setCancelled(true);
            if (event.getClickedInventory() == null || event.getRawSlot() >= top.getSize()) return;
            handleHomeClick(player, event.getRawSlot());
            return;
        }

        if (isAdminItem(event.getCurrentItem()) || isAdminItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    private void handleHomeClick(Player player, int slot) {
        switch (slot) {
            case 2 -> plugin.adjustConfiguredSeekerCount(-2);
            case 3 -> plugin.adjustConfiguredSeekerCount(-1);
            case 5 -> plugin.adjustConfiguredSeekerCount(1);
            case 6 -> plugin.adjustConfiguredSeekerCount(2);
            default -> {
                return;
            }
        }
        feedback(player, "寻找者人数已设为 " + plugin.configuredSeekerCount() + "。", NamedTextColor.AQUA);
        menu.open(player, AdminMenu.Page.HOME);
    }

    private void handleToolUse(Player player, AdminTool tool) {
        switch (tool) {
            case START_GAME -> {
                if (plugin.isGameRunning()) {
                    feedback(player, "当前已有对局进行中。", NamedTextColor.RED);
                    return;
                }
                player.closeInventory();
                startGameAsPlayer(player);
            }
            case STOP_GAME -> {
                if (!plugin.isGameRunning()) {
                    feedback(player, "当前没有正在进行的对局。", NamedTextColor.GRAY);
                    return;
                }
                plugin.stopGameFromAdmin();
                feedback(player, "已停止当前对局。", NamedTextColor.RED);
            }
            case SET_SPAWN -> {
                plugin.setArenaSpawnFromAdmin(player);
                startPreview(player);
                feedback(player, "已将当前位置设为小游戏出生点。", NamedTextColor.YELLOW);
                sendPreviewLegend(player);
            }
            case SET_INITIAL_CORNER -> {
                if (!plugin.canUseBorderCorner(player.getLocation())) {
                    feedback(player, "请先回到小游戏出生点所在世界。", NamedTextColor.RED);
                    return;
                }
                handleCornerSelection(player, true);
            }
            case SET_FINAL_CORNER -> {
                if (!plugin.canUseBorderCorner(player.getLocation())) {
                    feedback(player, "请先回到小游戏出生点所在世界。", NamedTextColor.RED);
                    return;
                }
                handleCornerSelection(player, false);
            }
            case PREVIEW_BORDERS -> {
                startPreview(player);
                feedback(player, "已开始预览边界，持续 10 秒。", NamedTextColor.AQUA);
                sendPreviewLegend(player);
            }
            case OPEN_MENU -> {
                click(player);
                menu.open(player, AdminMenu.Page.HOME);
            }
        }
    }

    private void enterAdminMode(Player player, boolean notify) {
        boolean newlyAdded = admins.add(player.getUniqueId());
        plugin.removePlayerFromGameForAdmin(player);
        ensureAdminTools(player);
        if (notify && newlyAdded) {
            player.sendMessage(Component.text("已成为管理员。你不会加入或干扰当前对局。", NamedTextColor.GOLD));
        }
    }

    private void settleAdminIntoSpectator(Player player, boolean notify) {
        if (!admins.remove(player.getUniqueId())) return;
        cancelPreview(player.getUniqueId());
        removeAdminTools(player);
        plugin.placePlayerIntoWaitingState(player);
        if (notify) {
            player.sendMessage(Component.text("已离开管理员模式。本局中你将以旁观者等待下一局。", NamedTextColor.YELLOW));
        }
    }

    private void ensureAdminTools(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (AdminTool tool : AdminTool.values()) {
            inventory.setItem(tool.slot(), createToolItem(tool));
        }
    }

    private void removeAdminTools(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isAdminItem(inventory.getItem(slot))) inventory.setItem(slot, null);
        }
        if (isAdminItem(inventory.getItemInOffHand())) inventory.setItemInOffHand(null);
    }

    private void startPreview(Player player) {
        cancelPreview(player.getUniqueId());
        BukkitTask task = new BukkitRunnable() {
            private int repeats;

            @Override
            public void run() {
                if (!player.isOnline() || !isAdmin(player.getUniqueId())) {
                    cancelPreview(player.getUniqueId());
                    cancel();
                    return;
                }
                plugin.renderConfiguredBorderPreview(player);
                repeats++;
                if (repeats >= 50) {
                    cancelPreview(player.getUniqueId());
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 4L);
        previewTasks.put(player.getUniqueId(), task);
    }

    private void cancelPreview(UUID uuid) {
        BukkitTask task = previewTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private void handleCornerSelection(Player player, boolean initial) {
        Map<UUID, CornerSelection> selections = initial ? initialCornerSelections : finalCornerSelections;
        String presetKey = plugin.currentPresetLabel();
        CornerSelection existing = selections.get(player.getUniqueId());
        if (existing == null || !existing.presetKey().equals(presetKey)
                || !existing.location().getWorld().equals(player.getWorld())) {
            selections.put(player.getUniqueId(), new CornerSelection(presetKey, player.getLocation().clone()));
            feedback(player,
                    "已记录" + (initial ? "初始" : "最终") + "边界第一个角点，请移动到对角后再次右键。",
                    initial ? NamedTextColor.YELLOW : NamedTextColor.GOLD);
            return;
        }

        selections.remove(player.getUniqueId());
        Hide_and_seek.BorderSelectionResult result = initial
                ? plugin.setConfiguredBorderInitialFromCorners(existing.location(), player.getLocation())
                : plugin.setConfiguredBorderFinalFromCorners(existing.location(), player.getLocation());
        if (result.completed()) {
            startPreview(player);
            feedback(player, result.message(), result.color());
            sendPreviewLegend(player);
            return;
        }
        feedback(player, result.message(), result.color());
    }

    private void feedback(Player player, String message, NamedTextColor color) {
        click(player);
        player.sendMessage(Component.text(message, color));
    }

    private void sendPreviewLegend(Player player) {
        player.sendMessage(
                Component.text("粒子说明: ", NamedTextColor.GRAY)
                        .append(Component.text("红", NamedTextColor.RED))
                        .append(Component.text(" = 初始边界  ", NamedTextColor.GRAY))
                        .append(Component.text("绿", NamedTextColor.GREEN))
                        .append(Component.text(" = 最终边界  ", NamedTextColor.GRAY))
                        .append(Component.text("蓝", NamedTextColor.AQUA))
                        .append(Component.text(" = 出生点", NamedTextColor.GRAY))
        );
    }

    private void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.1f);
    }

    private ItemStack createToolItem(AdminTool tool) {
        ItemStack item = new ItemStack(tool.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(tool.displayName(), tool.color()));
        List<Component> lore = new ArrayList<>();
        for (String line : tool.lore()) {
            lore.add(Component.text(line, NamedTextColor.GRAY));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(adminToolKey, PersistentDataType.STRING, tool.name());
        item.setItemMeta(meta);
        return item;
    }

    private boolean isAdminItem(ItemStack item) {
        return adminToolOf(item) != null;
    }

    private AdminTool adminToolOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(adminToolKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return AdminTool.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private enum AdminTool {
        START_GAME(2, Material.LIME_WOOL, "开始游戏", NamedTextColor.GREEN, List.of("右键后会离开管理员模式", "并作为普通玩家加入本局")),
        STOP_GAME(3, Material.RED_WOOL, "结束游戏", NamedTextColor.RED, List.of("右键立即结束当前对局")),
        SET_SPAWN(4, Material.RECOVERY_COMPASS, "设置出生点", NamedTextColor.YELLOW, List.of("右键将当前位置设为小游戏出生点")),
        SET_INITIAL_CORNER(5, Material.RED_DYE, "设置初始边界", NamedTextColor.RED, List.of("第一次右键记录第一个角点", "第二次右键完成初始矩形")),
        SET_FINAL_CORNER(6, Material.LIME_DYE, "设置最终边界", NamedTextColor.GOLD, List.of("第一次右键记录第一个角点", "第二次右键完成最终矩形")),
        PREVIEW_BORDERS(7, Material.SPYGLASS, "预览边界", NamedTextColor.AQUA, List.of("右键显示出生点光柱", "并预览初始与最终边界 10 秒")),
        OPEN_MENU(8, Material.NETHER_STAR, "寻找者设置", NamedTextColor.GOLD, List.of("右键打开单行设置栏", "只调整寻找者人数"));

        private final int slot;
        private final Material material;
        private final String displayName;
        private final NamedTextColor color;
        private final List<String> lore;

        AdminTool(int slot, Material material, String displayName, NamedTextColor color, List<String> lore) {
            this.slot = slot;
            this.material = material;
            this.displayName = displayName;
            this.color = color;
            this.lore = lore;
        }

        int slot() {
            return slot;
        }

        Material material() {
            return material;
        }

        String displayName() {
            return displayName;
        }

        NamedTextColor color() {
            return color;
        }

        List<String> lore() {
            return lore;
        }

        boolean triggersPreviewAssist() {
            return this == SET_SPAWN || this == SET_INITIAL_CORNER || this == SET_FINAL_CORNER || this == PREVIEW_BORDERS;
        }
    }

    private record CornerSelection(String presetKey, Location location) {
    }
}
