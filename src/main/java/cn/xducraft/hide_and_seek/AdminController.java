package cn.xducraft.hide_and_seek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
    private static final long LEGEND_COOLDOWN_MS = 5000L;

    private final Hide_and_seek plugin;
    private final AdminMenu menu;
    private final NamespacedKey adminToolKey;
    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> previewTasks = new ConcurrentHashMap<>();
    private final Map<UUID, CornerSelection> initialCornerSelections = new ConcurrentHashMap<>();
    private final Map<UUID, CornerSelection> finalCornerSelections = new ConcurrentHashMap<>();
    private final Map<UUID, Long> legendShownAt = new ConcurrentHashMap<>();

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
        refreshAllAdminTools();
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
        legendShownAt.remove(event.getPlayer().getUniqueId());
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

        Action action = event.getAction();
        boolean leftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!leftClick && !rightClick) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!isAdmin(player.getUniqueId())) {
            enterAdminMode(player, true);
        }
        handleToolUse(player, tool, leftClick);
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
            handleMenuClick(player, event.getRawSlot());
            return;
        }

        if (isAdminItem(event.getCurrentItem()) || isAdminItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    private void handleMenuClick(Player player, int slot) {
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

    private void handleToolUse(Player player, AdminTool tool, boolean leftClick) {
        switch (tool) {
            case PRESET_SELECTOR -> handlePresetSelection(player, leftClick ? -1 : 1);
            case PRESET_TOGGLE -> handlePresetToggle(player);
            case PRESET_CREATE -> handlePresetCreate(player);
            case PRESET_DELETE -> handlePresetDelete(player);
            case START_OR_STOP_GAME -> handleStartOrStop(player);
            case SET_SPAWN -> {
                plugin.setArenaSpawnFromAdmin(player);
                startPreview(player);
                refreshAllAdminTools();
                feedback(player, "已设置当前预设 #" + plugin.currentPresetLabel() + " 的出生点。", NamedTextColor.YELLOW);
                maybeSendPreviewLegend(player);
            }
            case SET_INITIAL_CORNER -> handleCornerSelection(player, true);
            case SET_FINAL_CORNER -> handleCornerSelection(player, false);
            case PREVIEW_BORDERS -> {
                startPreview(player);
                feedback(player, "已预览当前预设 #" + plugin.currentPresetLabel() + " 的边界。", NamedTextColor.AQUA);
                maybeSendPreviewLegend(player);
            }
        }
    }

    private void handlePresetSelection(Player player, int delta) {
        if (plugin.isGameRunning()) {
            feedback(player, "游戏进行中时不能切换编辑预设。", NamedTextColor.RED);
            return;
        }
        if (!plugin.cyclePreset(delta)) {
            feedback(player, "当前只有这一个预设。", NamedTextColor.GRAY);
            return;
        }
        startPreview(player);
        refreshAllAdminTools();
        feedback(player, "已切换到预设 #" + plugin.currentPresetLabel() + "。", NamedTextColor.LIGHT_PURPLE);
        maybeSendPreviewLegend(player);
    }

    private void handlePresetToggle(Player player) {
        if (plugin.isGameRunning()) {
            feedback(player, "游戏进行中时不能修改预设启用状态。", NamedTextColor.RED);
            return;
        }
        Hide_and_seek.TogglePresetResult result = plugin.togglePresetEnabled(plugin.currentPresetLabel());
        refreshAllAdminTools();
        feedback(player, result.message(), result.changed() ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private void handlePresetCreate(Player player) {
        if (plugin.isGameRunning()) {
            feedback(player, "游戏进行中时不能新增预设。", NamedTextColor.RED);
            return;
        }
        String created = plugin.createNextPreset();
        if (created == null) {
            feedback(player, "新增预设失败。", NamedTextColor.RED);
            return;
        }
        startPreview(player);
        refreshAllAdminTools();
        feedback(player, "已新增预设 #" + created + "。", NamedTextColor.GREEN);
        maybeSendPreviewLegend(player);
    }

    private void handlePresetDelete(Player player) {
        if (plugin.isGameRunning()) {
            feedback(player, "游戏进行中时不能删除预设。", NamedTextColor.RED);
            return;
        }
        if (plugin.presetKeys().size() <= 1) {
            feedback(player, "至少要保留一个预设。", NamedTextColor.RED);
            return;
        }
        String deleting = plugin.currentPresetLabel();
        String deleted = plugin.deletePreset(deleting);
        if (deleted == null) {
            feedback(player, "删除预设失败。", NamedTextColor.RED);
            return;
        }
        startPreview(player);
        refreshAllAdminTools();
        feedback(player, "已删除预设 #" + deleted + "，当前为 #" + plugin.currentPresetLabel() + "。", NamedTextColor.RED);
        maybeSendPreviewLegend(player);
    }

    private void handleStartOrStop(Player player) {
        if (plugin.isGameRunning()) {
            plugin.stopGameFromAdmin();
            refreshAllAdminTools();
            feedback(player, "已停止当前对局。", NamedTextColor.RED);
            return;
        }
        player.closeInventory();
        startGameAsPlayer(player);
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

    private void refreshAllAdminTools() {
        for (UUID uuid : admins) {
            Player admin = plugin.getServer().getPlayer(uuid);
            if (admin != null && admin.isOnline()) {
                ensureAdminTools(admin);
            }
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
        if (!plugin.canUseBorderCorner(player.getLocation())) {
            feedback(player, "请先回到当前预设出生点所在世界。", NamedTextColor.RED);
            return;
        }

        Map<UUID, CornerSelection> selections = initial ? initialCornerSelections : finalCornerSelections;
        String presetKey = plugin.currentPresetLabel();
        CornerSelection existing = selections.get(player.getUniqueId());
        if (existing == null
                || !existing.presetKey().equals(presetKey)
                || !existing.location().getWorld().equals(player.getWorld())) {
            selections.put(player.getUniqueId(), new CornerSelection(presetKey, player.getLocation().clone()));
            feedback(
                    player,
                    "已记" + (initial ? "初始" : "最终") + "角点 1/2，到对角后再右键。",
                    initial ? NamedTextColor.RED : NamedTextColor.GREEN
            );
            maybeSendPreviewLegend(player);
            return;
        }

        selections.remove(player.getUniqueId());
        Hide_and_seek.BorderSelectionResult result = initial
                ? plugin.setConfiguredBorderInitialFromCorners(existing.location(), player.getLocation())
                : plugin.setConfiguredBorderFinalFromCorners(existing.location(), player.getLocation());
        if (!result.completed()) {
            feedback(player, result.message(), result.color());
            return;
        }

        startPreview(player);
        refreshAllAdminTools();
        feedback(player, result.message(), result.color());
        if (result.warningMessage() != null) {
            player.sendMessage(Component.text(result.warningMessage(), NamedTextColor.YELLOW));
        }
        maybeSendPreviewLegend(player);
    }

    private void feedback(Player player, String message, NamedTextColor color) {
        click(player);
        player.sendMessage(Component.text(message, color));
    }

    private void maybeSendPreviewLegend(Player player) {
        long now = System.currentTimeMillis();
        Long lastShown = legendShownAt.get(player.getUniqueId());
        if (lastShown != null && now - lastShown < LEGEND_COOLDOWN_MS) return;
        legendShownAt.put(player.getUniqueId(), now);
        player.sendMessage(
                Component.text("粒子: ", NamedTextColor.GRAY)
                        .append(Component.text("红", NamedTextColor.RED))
                        .append(Component.text("=初始边界  ", NamedTextColor.GRAY))
                        .append(Component.text("绿", NamedTextColor.GREEN))
                        .append(Component.text("=最终边界  ", NamedTextColor.GRAY))
                        .append(Component.text("蓝", NamedTextColor.AQUA))
                        .append(Component.text("=出生点", NamedTextColor.GRAY))
        );
    }

    private void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.1f);
    }

    private ItemStack createToolItem(AdminTool tool) {
        ItemStack item = new ItemStack(tool.material(this));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(tool.displayName(this), tool.color(this)));
        List<Component> lore = new ArrayList<>();
        for (String line : tool.lore(this)) {
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
        PRESET_SELECTOR(0),
        PRESET_TOGGLE(1),
        PRESET_CREATE(2),
        PRESET_DELETE(3),
        START_OR_STOP_GAME(4),
        SET_SPAWN(5),
        SET_INITIAL_CORNER(6),
        SET_FINAL_CORNER(7),
        PREVIEW_BORDERS(8);

        private final int slot;

        AdminTool(int slot) {
            this.slot = slot;
        }

        int slot() {
            return slot;
        }

        Material material(AdminController controller) {
            return switch (this) {
                case PRESET_SELECTOR -> Material.PINK_WOOL;
                case PRESET_TOGGLE -> controller.plugin.currentPresetEnabled() ? Material.LIME_WOOL : Material.RED_WOOL;
                case PRESET_CREATE -> Material.LIME_DYE;
                case PRESET_DELETE -> Material.RED_DYE;
                case START_OR_STOP_GAME -> controller.plugin.isGameRunning() ? Material.RED_WOOL : Material.LIME_WOOL;
                case SET_SPAWN -> Material.RECOVERY_COMPASS;
                case SET_INITIAL_CORNER -> Material.RED_DYE;
                case SET_FINAL_CORNER -> Material.LIME_DYE;
                case PREVIEW_BORDERS -> Material.SPYGLASS;
            };
        }

        String displayName(AdminController controller) {
            return switch (this) {
                case PRESET_SELECTOR -> "当前预设 #" + controller.plugin.currentPresetLabel();
                case PRESET_TOGGLE -> controller.plugin.currentPresetEnabled() ? "当前预设已启用" : "当前预设已禁用";
                case PRESET_CREATE -> "新增预设";
                case PRESET_DELETE -> "删除当前预设";
                case START_OR_STOP_GAME -> controller.plugin.isGameRunning() ? "结束游戏" : "开始游戏";
                case SET_SPAWN -> "设置当前预设出生点";
                case SET_INITIAL_CORNER -> "设置当前预设初始边界";
                case SET_FINAL_CORNER -> "设置当前预设最终边界";
                case PREVIEW_BORDERS -> "预览当前预设边界";
            };
        }

        NamedTextColor color(AdminController controller) {
            return switch (this) {
                case PRESET_SELECTOR -> NamedTextColor.LIGHT_PURPLE;
                case PRESET_TOGGLE -> controller.plugin.currentPresetEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED;
                case PRESET_CREATE -> NamedTextColor.GREEN;
                case PRESET_DELETE -> NamedTextColor.RED;
                case START_OR_STOP_GAME -> controller.plugin.isGameRunning() ? NamedTextColor.RED : NamedTextColor.GREEN;
                case SET_SPAWN -> NamedTextColor.YELLOW;
                case SET_INITIAL_CORNER -> NamedTextColor.RED;
                case SET_FINAL_CORNER -> NamedTextColor.GREEN;
                case PREVIEW_BORDERS -> NamedTextColor.AQUA;
            };
        }

        List<String> lore(AdminController controller) {
            return switch (this) {
                case PRESET_SELECTOR -> List.of(
                        "左键上一套，右键下一套",
                        "正在编辑的预设固定为粉红色羊毛"
                );
                case PRESET_TOGGLE -> List.of(
                        "右键切换当前预设启用状态",
                        controller.plugin.currentPresetEnabled() ? "当前会参与随机选图" : "当前不会参与随机选图"
                );
                case PRESET_CREATE -> List.of("复制当前预设并自动切换到新预设");
                case PRESET_DELETE -> List.of("删除当前编辑预设", "至少保留 1 个预设");
                case START_OR_STOP_GAME -> List.of(
                        controller.plugin.isGameRunning() ? "右键立即结束当前对局" : "右键后离开管理员模式并加入本局"
                );
                case SET_SPAWN -> List.of("将当前位置设为当前预设的出生点");
                case SET_INITIAL_CORNER -> List.of("第一次记录角点，第二次完成初始矩形");
                case SET_FINAL_CORNER -> List.of("第一次记录角点，第二次完成最终矩形");
                case PREVIEW_BORDERS -> List.of("显示出生点与初始/最终边界预览");
            };
        }

        boolean triggersPreviewAssist() {
            return this == PRESET_SELECTOR
                    || this == SET_SPAWN
                    || this == SET_INITIAL_CORNER
                    || this == SET_FINAL_CORNER
                    || this == PREVIEW_BORDERS;
        }
    }

    private record CornerSelection(String presetKey, Location location) {
    }
}
