package cn.xducraft.hide_and_seek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AdminController implements Listener {
    private static final int MENU_SLOT = 8;

    private final Hide_and_seek plugin;
    private final AdminMenu menu;
    private final NamespacedKey adminItemKey;
    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> previewTasks = new ConcurrentHashMap<>();

    AdminController(Hide_and_seek plugin) {
        this.plugin = plugin;
        this.menu = new AdminMenu(plugin);
        this.adminItemKey = new NamespacedKey(plugin, "admin_menu");
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
            ensureMenuItem(player);
        }
        menu.open(player, AdminMenu.Page.HOME);
    }

    void startGameAsPlayer(Player player) {
        cancelPreview(player.getUniqueId());
        if (admins.remove(player.getUniqueId())) {
            removeMenuItem(player);
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
        if (!isAdminItem(event.getItem())) return;
        event.setCancelled(true);
        if (!isAdmin(event.getPlayer().getUniqueId())) enterAdminMode(event.getPlayer(), true);
        menu.open(event.getPlayer(), AdminMenu.Page.HOME);
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
            handleMenuClick(player, menu.pageOf(top), event.getRawSlot());
            return;
        }

        if (isAdminItem(event.getCurrentItem()) || isAdminItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    private void handleMenuClick(Player player, AdminMenu.Page page, int slot) {
        switch (page) {
            case HOME -> handleHomeClick(player, slot);
            case BORDER -> handleBorderClick(player, slot);
        }
    }

    private void handleHomeClick(Player player, int slot) {
        switch (slot) {
            case 10 -> plugin.adjustConfiguredSeekerCount(-2);
            case 11 -> plugin.adjustConfiguredSeekerCount(-1);
            case 13 -> plugin.adjustConfiguredSeekerCount(1);
            case 14 -> plugin.adjustConfiguredSeekerCount(2);
            case 20 -> {
                player.closeInventory();
                startGameAsPlayer(player);
                return;
            }
            case 22 -> {
                plugin.stopGameFromAdmin();
                feedback(player, "已停止当前对局。", NamedTextColor.RED);
            }
            case 24 -> {
                plugin.setArenaSpawnFromAdmin(player);
                feedback(player, "已将当前位置设为小游戏出生点。", NamedTextColor.YELLOW);
            }
            case 31 -> {
                menu.open(player, AdminMenu.Page.BORDER);
                click(player);
                return;
            }
            case 33 -> {
                plugin.reloadGameConfigFromAdmin();
                feedback(player, "已重载配置。", NamedTextColor.GREEN);
            }
            default -> {
                return;
            }
        }
        menu.open(player, AdminMenu.Page.HOME);
    }

    private void handleBorderClick(Player player, int slot) {
        switch (slot) {
            case 20 -> {
                if (!plugin.canUseBorderCorner(player.getLocation())) {
                    feedback(player, "请先回到小游戏出生点所在世界。", NamedTextColor.RED);
                } else {
                    plugin.setConfiguredBorderInitialFromCorner(player.getLocation());
                    feedback(player, "已使用当前位置设置初始边界角点。", NamedTextColor.YELLOW);
                }
            }
            case 22 -> {
                if (!plugin.canUseBorderCorner(player.getLocation())) {
                    feedback(player, "请先回到小游戏出生点所在世界。", NamedTextColor.RED);
                } else {
                    plugin.setConfiguredBorderFinalFromCorner(player.getLocation());
                    feedback(player, "已使用当前位置设置最终边界角点。", NamedTextColor.GOLD);
                }
            }
            case 24 -> {
                startPreview(player);
                feedback(player, "已开始预览边界，持续 10 秒。", NamedTextColor.AQUA);
            }
            case 49 -> {
                menu.open(player, AdminMenu.Page.HOME);
                click(player);
                return;
            }
            default -> {
                return;
            }
        }
        menu.open(player, AdminMenu.Page.BORDER);
    }

    private void enterAdminMode(Player player, boolean notify) {
        boolean newlyAdded = admins.add(player.getUniqueId());
        plugin.removePlayerFromGameForAdmin(player);
        ensureMenuItem(player);
        if (notify && newlyAdded) {
            player.sendMessage(Component.text("已成为管理员。你不会加入或干扰当前对局。", NamedTextColor.GOLD));
        }
    }

    private void settleAdminIntoSpectator(Player player, boolean notify) {
        if (!admins.remove(player.getUniqueId())) return;
        cancelPreview(player.getUniqueId());
        removeMenuItem(player);
        plugin.placePlayerIntoWaitingState(player);
        if (notify) {
            player.sendMessage(Component.text("已离开管理员模式。本局中你将以旁观者等待下一局。", NamedTextColor.YELLOW));
        }
    }

    private void ensureMenuItem(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(MENU_SLOT, createMenuItem());
    }

    private void removeMenuItem(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isAdminItem(inventory.getItem(slot))) inventory.setItem(slot, null);
        }
        if (isAdminItem(inventory.getItemInOffHand())) inventory.setItemInOffHand(null);
    }

    private void startPreview(Player player) {
        cancelPreview(player.getUniqueId());
        BukkitTask task = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (!player.isOnline() || !isAdmin(player.getUniqueId())) {
                    cancelPreview(player.getUniqueId());
                    cancel();
                    return;
                }
                plugin.renderConfiguredBorderPreview(player);
                ticks++;
                if (ticks >= 200) {
                    cancelPreview(player.getUniqueId());
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
        previewTasks.put(player.getUniqueId(), task);
    }

    private void cancelPreview(UUID uuid) {
        BukkitTask task = previewTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private void feedback(Player player, String message, NamedTextColor color) {
        click(player);
        player.sendMessage(Component.text(message, color));
    }

    private void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.1f);
    }

    private ItemStack createMenuItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("管理员菜单", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("右键打开管理员菜单", NamedTextColor.GRAY),
                Component.text("创造模式下会自动保持主持身份", NamedTextColor.DARK_GRAY)
        ));
        meta.getPersistentDataContainer().set(adminItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isAdminItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(adminItemKey, PersistentDataType.BYTE);
    }
}
