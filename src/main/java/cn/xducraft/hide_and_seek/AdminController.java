package cn.xducraft.hide_and_seek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AdminController implements Listener {
    private static final int MENU_SLOT = 8;

    private final Hide_and_seek plugin;
    private final AdminMenu menu;
    private final NamespacedKey adminItemKey;
    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();

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
                exitAdminMode(event.getPlayer(), true);
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
            case CONTROL -> handleControlClick(player, slot);
            case BORDER -> handleBorderClick(player, slot);
        }
    }

    private void handleHomeClick(Player player, int slot) {
        switch (slot) {
            case 10 -> plugin.adjustConfiguredSeekerCount(-2);
            case 11 -> plugin.adjustConfiguredSeekerCount(-1);
            case 13 -> plugin.adjustConfiguredSeekerCount(1);
            case 14 -> plugin.adjustConfiguredSeekerCount(2);
            case 19 -> plugin.adjustConfiguredSeekerReleaseDelayTicks(-600);
            case 20 -> plugin.adjustConfiguredSeekerReleaseDelayTicks(-100);
            case 22 -> plugin.adjustConfiguredSeekerReleaseDelayTicks(100);
            case 23 -> plugin.adjustConfiguredSeekerReleaseDelayTicks(600);
            case 28 -> plugin.adjustConfiguredDurationTicks(-1200);
            case 29 -> plugin.adjustConfiguredDurationTicks(-600);
            case 31 -> plugin.adjustConfiguredDurationTicks(600);
            case 32 -> plugin.adjustConfiguredDurationTicks(1200);
            case 36 -> plugin.startGameFromAdmin(player);
            case 38 -> plugin.stopGameFromAdmin();
            case 40 -> {
                menu.open(player, AdminMenu.Page.CONTROL);
                return;
            }
            case 42 -> {
                menu.open(player, AdminMenu.Page.BORDER);
                return;
            }
            case 44 -> {
                manualExitAdminMode(player);
                return;
            }
            default -> {
                return;
            }
        }
        menu.open(player, AdminMenu.Page.HOME);
    }

    private void handleControlClick(Player player, int slot) {
        switch (slot) {
            case 11 -> plugin.forceSeekerWin();
            case 13 -> plugin.forceHiderWin();
            case 15 -> plugin.setArenaSpawnFromAdmin(player);
            case 31 -> plugin.reloadGameConfigFromAdmin();
            case 49 -> {
                menu.open(player, AdminMenu.Page.HOME);
                return;
            }
            default -> {
                return;
            }
        }
        menu.open(player, AdminMenu.Page.CONTROL);
    }

    private void handleBorderClick(Player player, int slot) {
        switch (slot) {
            case 10 -> plugin.adjustConfiguredBorderInitialWidth(-20.0);
            case 11 -> plugin.adjustConfiguredBorderInitialWidth(-5.0);
            case 13 -> plugin.adjustConfiguredBorderInitialWidth(5.0);
            case 14 -> plugin.adjustConfiguredBorderInitialWidth(20.0);
            case 19 -> plugin.adjustConfiguredBorderInitialDepth(-20.0);
            case 20 -> plugin.adjustConfiguredBorderInitialDepth(-5.0);
            case 22 -> plugin.adjustConfiguredBorderInitialDepth(5.0);
            case 23 -> plugin.adjustConfiguredBorderInitialDepth(20.0);
            case 28 -> plugin.adjustConfiguredBorderFinalWidth(-20.0);
            case 29 -> plugin.adjustConfiguredBorderFinalWidth(-5.0);
            case 31 -> plugin.adjustConfiguredBorderFinalWidth(5.0);
            case 32 -> plugin.adjustConfiguredBorderFinalWidth(20.0);
            case 37 -> plugin.adjustConfiguredBorderFinalDepth(-20.0);
            case 38 -> plugin.adjustConfiguredBorderFinalDepth(-5.0);
            case 40 -> plugin.adjustConfiguredBorderFinalDepth(5.0);
            case 41 -> plugin.adjustConfiguredBorderFinalDepth(20.0);
            case 49 -> {
                menu.open(player, AdminMenu.Page.HOME);
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

    private void exitAdminMode(Player player, boolean notify) {
        if (!admins.remove(player.getUniqueId())) return;
        removeMenuItem(player);
        if (plugin.isGameRunning()) {
            plugin.placePlayerIntoWaitingState(player);
            if (notify) {
                player.sendMessage(Component.text("已退出管理员模式。本局中你将以旁观者等待下一局。", NamedTextColor.YELLOW));
            }
            return;
        }
        plugin.applyIdleStateForOrdinaryPlayer(player);
        if (notify) {
            player.sendMessage(Component.text("已退出管理员模式。", NamedTextColor.GREEN));
        }
    }

    private void manualExitAdminMode(Player player) {
        player.closeInventory();
        if (!admins.remove(player.getUniqueId())) return;
        removeMenuItem(player);
        if (plugin.isGameRunning()) {
            player.setGameMode(GameMode.SPECTATOR);
            plugin.placePlayerIntoWaitingState(player);
            player.sendMessage(Component.text("已退出管理员模式。本局中你将以旁观者等待下一局。", NamedTextColor.YELLOW));
            return;
        }
        player.setGameMode(GameMode.ADVENTURE);
        plugin.applyIdleStateForOrdinaryPlayer(player);
        player.sendMessage(Component.text("已退出管理员模式。", NamedTextColor.GREEN));
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
