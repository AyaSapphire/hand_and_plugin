package cn.xducraft.hide_and_seek;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Hide_and_seek extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, GamePlayer> players = new HashMap<>();
    private final List<Decoy> decoys = new ArrayList<>();
    private NamespacedKey abilityKey;
    private NamespacedKey noDropKey;
    private NamespacedKey attackKey;
    private GameSettings settings;
    private BukkitTask gameTask;
    private BossBar bossBar;
    private GamePhase phase = GamePhase.IDLE;
    private Location arenaSpawn;
    private int remainingTicks;

    @Override
    public void onEnable() {
        abilityKey = new NamespacedKey(this, "ability");
        noDropKey = new NamespacedKey(this, "no_drop");
        attackKey = new NamespacedKey(this, "attack_bullet");
        saveDefaultConfig();
        ensureConfigDefaults();
        settings = loadSettings();
        loadSpawn();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("has"), "Command has is missing from plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("has"), "Command has is missing from plugin.yml").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        stopGame(false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        if (!sender.hasPermission("hide_and_seek.admin")) {
            sender.sendMessage("你没有权限控制躲猫猫。");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> startGame(sender);
            case "stop" -> {
                stopGame(true);
                sender.sendMessage("已停止躲猫猫。");
            }
            case "setspawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("只有玩家可以设置出生点。");
                    return true;
                }
                arenaSpawn = player.getLocation();
                saveSpawn(arenaSpawn);
                sender.sendMessage("已设置躲猫猫出生点。");
            }
            case "status" -> sendStatus(sender);
            case "reload" -> reloadGameConfig(sender);
            case "settings", "config" -> handleSettingsCommand(sender, args);
            case "blacklist" -> handleBlacklistCommand(sender, args);
            case "help" -> sendHelp(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length > 1 && (args[0].equalsIgnoreCase("settings") || args[0].equalsIgnoreCase("config"))) {
            return tabCompleteSettings(args);
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("blacklist")) {
            return tabCompleteBlacklist(args);
        }
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("help", "start", "stop", "setspawn", "status", "reload", "settings", "blacklist").stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("/has start - 开始一局躲猫猫");
        sender.sendMessage("/has stop - 停止当前游戏并清理实体");
        sender.sendMessage("/has setspawn - 使用你当前位置作为竞技场出生点");
        sender.sendMessage("/has status - 查看当前游戏和关键设置");
        sender.sendMessage("/has reload - 重载 config.yml");
        sender.sendMessage("/has settings list|get|set|reset - 查看和调整玩法设置");
        sender.sendMessage("/has blacklist list|add|remove - 查看和调整伪装黑名单");
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("状态: " + phase + ", 玩家: " + players.size() + ", 剩余 tick: " + remainingTicks);
        sender.sendMessage("设置: 时长 " + settings.durationTicks() + " ticks, seeker " + settings.seekerCount()
                + ", HP/MP " + settings.maxHp() + "/" + settings.maxMp()
                + ", 攻击伤害 " + settings.damagePerHit());
    }

    private void reloadGameConfig(CommandSender sender) {
        reloadConfig();
        ensureConfigDefaults();
        settings = loadSettings();
        loadSpawn();
        sender.sendMessage("已重载躲猫猫配置。正在运行的游戏会从下一次相关逻辑开始使用新设置。");
    }

    private void ensureConfigDefaults() {
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    private void handleSettingsCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("/has settings <list|get|set|reset|reload>");
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> listSettings(sender);
            case "get" -> {
                if (args.length < 3) {
                    sender.sendMessage("/has settings get <path>");
                    return;
                }
                String path = args[2];
                Object value = getConfig().get(path);
                sender.sendMessage(path + " = " + (value == null ? "<未设置>" : value));
            }
            case "set" -> {
                if (args.length < 4) {
                    sender.sendMessage("/has settings set <path> <value>");
                    return;
                }
                setConfigValue(sender, args[2], String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
            }
            case "reset" -> {
                if (args.length < 3) {
                    sender.sendMessage("/has settings reset <path>");
                    return;
                }
                resetConfigValue(sender, args[2]);
            }
            case "reload" -> reloadGameConfig(sender);
            default -> sender.sendMessage("/has settings <list|get|set|reset|reload>");
        }
    }

    private void handleBlacklistCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("/has blacklist <list|add|remove|reload>");
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> listDisguiseBlacklist(sender);
            case "add" -> {
                if (args.length < 3) {
                    sender.sendMessage("/has blacklist add <minecraft:block|#minecraft:tag>");
                    return;
                }
                addDisguiseBlacklistEntry(sender, args[2]);
            }
            case "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage("/has blacklist remove <minecraft:block|#minecraft:tag>");
                    return;
                }
                removeDisguiseBlacklistEntry(sender, args[2]);
            }
            case "reload" -> reloadGameConfig(sender);
            default -> sender.sendMessage("/has blacklist <list|add|remove|reload>");
        }
    }

    private void listDisguiseBlacklist(CommandSender sender) {
        List<String> entries = getConfig().getStringList("abilities.disguise.blacklist");
        sender.sendMessage("伪装黑名单配置项: " + entries.size() + "，展开后方块数: " + settings.disguiseBlacklist().size());
        entries.stream().sorted().forEach(entry -> sender.sendMessage("- " + entry));
    }

    private void addDisguiseBlacklistEntry(CommandSender sender, String rawEntry) {
        String entry = normalizeBlockListEntry(rawEntry);
        if (!isValidBlockListEntry(entry)) {
            sender.sendMessage("无法识别方块或标签: " + rawEntry);
            return;
        }

        List<String> entries = new ArrayList<>(getConfig().getStringList("abilities.disguise.blacklist"));
        if (entries.stream().anyMatch(existing -> existing.equalsIgnoreCase(entry))) {
            sender.sendMessage("该项已经在伪装黑名单中: " + entry);
            return;
        }
        entries.add(entry);
        entries.sort(String::compareToIgnoreCase);
        getConfig().set("abilities.disguise.blacklist", entries);
        saveConfig();
        settings = loadSettings();
        sender.sendMessage("已加入伪装黑名单: " + entry);
    }

    private void removeDisguiseBlacklistEntry(CommandSender sender, String rawEntry) {
        String entry = normalizeBlockListEntry(rawEntry);
        List<String> entries = new ArrayList<>(getConfig().getStringList("abilities.disguise.blacklist"));
        boolean removed = entries.removeIf(existing -> existing.equalsIgnoreCase(entry));
        if (!removed) {
            sender.sendMessage("伪装黑名单中没有该项: " + entry);
            return;
        }
        getConfig().set("abilities.disguise.blacklist", entries);
        saveConfig();
        settings = loadSettings();
        sender.sendMessage("已移出伪装黑名单: " + entry);
    }

    private void listSettings(CommandSender sender) {
        Map<String, Object> values = collectScalarConfigValues(getConfig());
        if (values.isEmpty()) {
            sender.sendMessage("当前没有可直接调整的标量设置。");
            return;
        }
        sender.sendMessage("可调整设置：");
        values.forEach((path, value) -> sender.sendMessage("- " + path + " = " + value));
    }

    private void setConfigValue(CommandSender sender, String path, String rawValue) {
        Object template = getConfig().get(path);
        if (template == null && getConfig().getDefaults() != null) {
            template = getConfig().getDefaults().get(path);
        }
        if (template instanceof ConfigurationSection || template instanceof List<?>) {
            sender.sendMessage("该路径不是可直接设置的单个数值: " + path);
            return;
        }

        Object value = parseConfigValue(template, rawValue);
        if (value == null) {
            sender.sendMessage("无法解析数值: " + rawValue);
            return;
        }

        getConfig().set(path, value);
        saveConfig();
        settings = loadSettings();
        loadSpawn();
        sender.sendMessage("已设置 " + path + " = " + value);
    }

    private void resetConfigValue(CommandSender sender, String path) {
        if (getConfig().getDefaults() == null || !getConfig().getDefaults().contains(path)) {
            sender.sendMessage("默认配置中不存在该路径: " + path);
            return;
        }
        Object value = getConfig().getDefaults().get(path);
        getConfig().set(path, value);
        saveConfig();
        settings = loadSettings();
        loadSpawn();
        sender.sendMessage("已重置 " + path + " = " + value);
    }

    private Object parseConfigValue(Object template, String rawValue) {
        if (template instanceof Boolean) {
            if (rawValue.equalsIgnoreCase("true") || rawValue.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(rawValue);
            }
            return null;
        }
        if (template instanceof Integer) {
            try {
                return Integer.parseInt(rawValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (template instanceof Long) {
            try {
                return Long.parseLong(rawValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (template instanceof Float || template instanceof Double) {
            try {
                return Double.parseDouble(rawValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (template == null) {
            return rawValue;
        }
        return rawValue;
    }

    private List<String> tabCompleteSettings(String[] args) {
        if (args.length == 2) {
            return filterPrefix(List.of("list", "get", "set", "reset", "reload"), args[1]);
        }
        if (args.length == 3 && List.of("get", "set", "reset").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filterPrefix(new ArrayList<>(collectScalarConfigValues(getConfig()).keySet()), args[2]);
        }
        return List.of();
    }

    private List<String> tabCompleteBlacklist(String[] args) {
        if (args.length == 2) {
            return filterPrefix(List.of("list", "add", "remove", "reload"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            return filterPrefix(getConfig().getStringList("abilities.disguise.blacklist"), args[2]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("add")) {
            return filterPrefix(List.of(
                    "minecraft:water",
                    "minecraft:lava",
                    "minecraft:bedrock",
                    "minecraft:command_block",
                    "#minecraft:banners",
                    "#minecraft:doors",
                    "#minecraft:heads",
                    "#minecraft:tall_flowers",
                    "#minecraft:all_signs"
            ), args[2]);
        }
        return List.of();
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .sorted()
                .toList();
    }

    private Map<String, Object> collectScalarConfigValues(ConfigurationSection section) {
        Map<String, Object> values = new LinkedHashMap<>();
        collectScalarConfigValues(section, "", values);
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
    }

    private String normalizeBlockListEntry(String rawEntry) {
        String entry = rawEntry.trim().toLowerCase(Locale.ROOT);
        boolean tagEntry = entry.startsWith("#");
        if (tagEntry) entry = entry.substring(1);
        if (!entry.contains(":")) entry = "minecraft:" + entry;
        return tagEntry ? "#" + entry : entry;
    }

    private boolean isValidBlockListEntry(String entry) {
        if (entry.startsWith("#")) {
            return resolveBlockTag(entry.substring(1)) != null;
        }
        Material material = Material.matchMaterial(entry);
        return material != null && material.isBlock();
    }

    private void collectScalarConfigValues(ConfigurationSection section, String prefix, Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof ConfigurationSection child) {
                collectScalarConfigValues(child, path, values);
            } else if (!(entry.getValue() instanceof List<?>)) {
                values.put(path, entry.getValue());
            }
        }
    }

    private void startGame(CommandSender sender) {
        if (phase == GamePhase.RUNNING) {
            sender.sendMessage("游戏已经在运行。");
            return;
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            sender.sendMessage("没有在线玩家。");
            return;
        }

        stopGame(false);
        Location spawn = getArenaSpawn();
        remainingTicks = settings.durationTicks();
        phase = GamePhase.RUNNING;
        bossBar = Bukkit.createBossBar("躲猫猫 10:00", BarColor.GREEN, BarStyle.SEGMENTED_20);
        bossBar.setProgress(1.0);

        Collections.shuffle(online);
        int seekerCount = online.size() == 1 ? 1 : Math.min(settings.seekerCount(), online.size() - 1);
        for (int i = 0; i < online.size(); i++) {
            Player player = online.get(i);
            Role role = i < seekerCount ? Role.SEEKER : Role.HIDER;
            GamePlayer state = new GamePlayer(player.getUniqueId(), role, settings.maxHp(), settings.maxMp());
            players.put(player.getUniqueId(), state);
            setupPlayer(player, state, spawn);
            bossBar.addPlayer(player);
        }

        setupWorldBorder(spawn);
        Bukkit.broadcast(Component.text("躲猫猫开始！前 30 秒寻找者等待，躲藏者快藏好。"));
        gameTask = Bukkit.getScheduler().runTaskTimer(this, this::tickGame, 1L, 1L);
    }

    private void setupPlayer(Player player, GamePlayer state, Location spawn) {
        player.teleport(spawn);
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.setHealth(getMaxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(20);

        if (state.role == Role.HIDER) {
            spawnDisguiseDisplay(player, state);
            giveHiderLoadout(player);
            player.sendTitle("躲藏者", "伪装成方块并坚持到倒计时结束", 10, 60, 10);
        } else {
            giveSeekerLoadout(player);
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 620, 0, false, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 620, 10, false, false, false));
            player.sendTitle("寻找者", "30 秒后出发，找出所有躲藏者", 10, 60, 10);
        }
    }

    private void tickGame() {
        if (phase != GamePhase.RUNNING) return;
        remainingTicks--;
        updateBossBar();

        if (remainingTicks == settings.seekerReleaseAt()) {
            Bukkit.broadcast(Component.text("寻找者已释放！"));
            playersWithRole(Role.SEEKER).forEach(player -> {
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 1f);
            });
        }

        updateBorder();
        tickPlayers();
        tickDecoys();
        checkWin();
    }

    private void tickPlayers() {
        for (GamePlayer state : players.values()) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player == null) continue;
            state.hp = Math.min(settings.maxHp(), state.hp + settings.hpRegenPerTick());
            state.mp = Math.min(settings.maxMp(), state.mp + settings.mpRegenPerTick());
            ensureLoadout(player, state.role);
            if (state.role == Role.HIDER) tickDisguise(player, state);
            player.setFoodLevel(20);
            player.setSaturation(20);
            player.sendActionBar(Component.text(roleName(state.role) + "  HP " + state.hp + "/" + settings.maxHp() + "  MP " + state.mp + "/" + settings.maxMp()));
        }
    }

    private void tickDisguise(Player player, GamePlayer state) {
        if (state.display == null || state.display.isDead()) {
            spawnDisguiseDisplay(player, state);
        }

        Location loc = player.getLocation().clone();
        float visualYaw = state.rotationLocked ? state.lockedYaw : snapYaw(loc.getYaw());
        loc.setPitch(0f);
        loc.setYaw(0f);
        if (!state.rotationLocked) state.lockedYaw = visualYaw;
        state.display.teleport(loc);
        if (needsTransformUpdate(state, visualYaw)) {
            applyDisguiseTransform(state.display, visualYaw);
            state.visualYaw = visualYaw;
        }

        if (state.disguised) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, false));
        } else {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    private void tickDecoys() {
        Iterator<Decoy> iterator = decoys.iterator();
        while (iterator.hasNext()) {
            Decoy decoy = iterator.next();
            decoy.remainingTicks--;
            if (decoy.remainingTicks <= 0 || decoy.hp <= 0 || decoy.display == null || decoy.display.isDead()) {
                if (decoy.display != null) decoy.display.remove();
                iterator.remove();
            }
        }
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        int seconds = Math.max(0, remainingTicks / 20);
        bossBar.setTitle("躲猫猫 " + seconds / 60 + ":" + String.format("%02d", seconds % 60));
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, remainingTicks / (double) settings.durationTicks())));
    }

    private void updateBorder() {
        for (BorderStage stage : settings.borderStages()) {
            if (remainingTicks == stage.remainingTicks()) shrinkBorder(stage.size(), stage.seconds());
        }
    }

    private void shrinkBorder(double size, long seconds) {
        WorldBorder border = getArenaSpawn().getWorld().getWorldBorder();
        border.setSize(size, seconds);
        Bukkit.broadcast(Component.text("世界边界正在缩小。"));
    }

    private void checkWin() {
        if (phase != GamePhase.RUNNING) return;
        long hiders = players.values().stream().filter(state -> state.role == Role.HIDER).count();
        if (hiders == 0) {
            endGame("寻找者胜利！");
        } else if (remainingTicks <= 0) {
            endGame("躲藏者胜利！");
        }
    }

    private void endGame(String message) {
        Bukkit.broadcast(Component.text(message));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(message, "", 10, 70, 20);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        stopGame(false);
    }

    private void stopGame(boolean announce) {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        for (GamePlayer state : players.values()) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null) {
                cleanupPlayer(player, state);
            } else if (state.display != null) {
                state.display.remove();
                state.display = null;
            }
        }
        for (Decoy decoy : decoys) {
            if (decoy.display != null) decoy.display.remove();
        }

        decoys.clear();
        players.clear();
        World world = getArenaSpawn().getWorld();
        if (world != null) world.getWorldBorder().setSize(settings.borderResetSize());
        phase = GamePhase.IDLE;
        remainingTicks = 0;
        if (announce) Bukkit.broadcast(Component.text("躲猫猫已停止。"));
    }

    private void setupWorldBorder(Location center) {
        WorldBorder border = center.getWorld().getWorldBorder();
        border.setCenter(center);
        border.setSize(settings.borderInitialSize());
        border.setDamageBuffer(settings.borderDamageBuffer());
    }

    private void spawnDisguiseDisplay(Player player, GamePlayer state) {
        BlockData data = state.disguiseData == null ? Bukkit.createBlockData(Material.AIR) : state.disguiseData;
        state.display = player.getWorld().spawn(player.getLocation(), BlockDisplay.class, display -> {
            display.setBlock(data);
            display.setPersistent(false);
            display.setTeleportDuration(1);
            display.setRotation(0f, 0f);
            applyDisguiseTransform(display, state.lockedYaw);
            state.visualYaw = state.lockedYaw;
        });
    }

    private float snapYaw(float yaw) {
        double snapDegrees = settings.disguiseRotationSnapDegrees();
        if (snapDegrees <= 0) return normalizeYaw(yaw);
        return normalizeYaw((float) (Math.round(yaw / snapDegrees) * snapDegrees));
    }

    private float normalizeYaw(float yaw) {
        float normalized = yaw % 360f;
        if (normalized <= -180f) normalized += 360f;
        if (normalized > 180f) normalized -= 360f;
        return normalized;
    }

    private boolean needsTransformUpdate(GamePlayer state, float yaw) {
        return state.display == null || Math.abs(normalizeYaw(yaw - state.visualYaw)) > 0.01f;
    }

    private void applyDisguiseTransform(BlockDisplay display, float yaw) {
        float radians = (float) Math.toRadians(-yaw);
        Vector3f translation = new Vector3f(0.5f, 0f, 0.5f).rotateY(radians).negate();
        translation.y = 0.001f;

        display.setRotation(0f, 0f);
        display.setTransformation(new Transformation(
                translation,
                new Quaternionf(new AxisAngle4f(radians, 0f, 1f, 0f)),
                new Vector3f(1f, 1f, 1f),
                new Quaternionf()
        ));
    }

    private void giveHiderLoadout(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.setItem(0, abilityItem("disguise", "伪装", List.of("右键目标方块进行伪装")));
        inv.setItem(1, abilityItem("release", "解除伪装", List.of("右键恢复原形")));
        inv.setItem(2, abilityItem("rotation_lock", "旋转锁定", List.of("右键切换伪装旋转锁定")));
        inv.setItem(3, abilityItem("decoy", "诱饵", List.of("消耗 MP 放置一个伪装诱饵")));
        inv.setItem(4, abilityItem("fly_hider", "躲藏者跳跃", List.of("解除伪装并向视线方向位移")));
    }

    private void giveSeekerLoadout(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.setItem(0, abilityItem("attack_bullet", "攻击弹", List.of("命中躲藏者造成伤害")));
        inv.setItem(1, abilityItem("scan", "扫描", List.of("提示附近是否存在躲藏者")));
        inv.setItem(2, abilityItem("fly_seeker", "寻找者跳跃", List.of("向视线方向位移")));
    }

    private void ensureLoadout(Player player, Role role) {
        if (role == Role.HIDER) {
            ensureAbility(player, 0, "disguise", "伪装", List.of("右键目标方块进行伪装"));
            ensureAbility(player, 1, "release", "解除伪装", List.of("右键恢复原形"));
            ensureAbility(player, 2, "rotation_lock", "旋转锁定", List.of("右键切换伪装旋转锁定"));
            ensureAbility(player, 3, "decoy", "诱饵", List.of("消耗 MP 放置一个伪装诱饵"));
            ensureAbility(player, 4, "fly_hider", "躲藏者跳跃", List.of("解除伪装并向视线方向位移"));
        } else {
            ensureAbility(player, 0, "attack_bullet", "攻击弹", List.of("命中躲藏者造成伤害"));
            ensureAbility(player, 1, "scan", "扫描", List.of("提示附近是否存在躲藏者"));
            ensureAbility(player, 2, "fly_seeker", "寻找者跳跃", List.of("向视线方向位移"));
        }
    }

    private void ensureAbility(Player player, int slot, String ability, String name, List<String> lore) {
        ItemStack current = player.getInventory().getItem(slot);
        if (!ability.equals(getAbility(current))) {
            player.getInventory().setItem(slot, abilityItem(ability, name, lore));
        }
    }

    private ItemStack abilityItem(String ability, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.CARROT_ON_A_STICK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(lore.stream().map(Component::text).toList());
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(abilityKey, PersistentDataType.STRING, ability);
        pdc.set(noDropKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (phase != GamePhase.RUNNING) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) {
            if (hasNoDrop(event.getItem())) event.setCancelled(true);
            return;
        }
        Player player = event.getPlayer();
        GamePlayer state = players.get(player.getUniqueId());
        if (state == null) return;
        String ability = getAbility(event.getItem());
        if (ability == null) return;
        event.setCancelled(true);

        switch (ability) {
            case "disguise" -> useDisguise(player, state);
            case "release" -> releaseDisguise(player, state);
            case "rotation_lock" -> toggleRotationLock(player, state);
            case "decoy" -> useDecoy(player, state);
            case "fly_hider" -> useFly(player, state, Role.HIDER, settings.hiderFlyMp(), settings.hiderFlyPower(), settings.hiderFlyMinYBoost());
            case "attack_bullet" -> useAttackBullet(player, state);
            case "scan" -> useScan(player, state);
            case "fly_seeker" -> useFly(player, state, Role.SEEKER, settings.seekerFlyMp(), settings.seekerFlyPower(), settings.seekerFlyMinYBoost());
            default -> {
            }
        }
    }

    private void useDisguise(Player player, GamePlayer state) {
        if (state.role != Role.HIDER) return;
        Block target = player.getTargetBlockExact(settings.disguiseRange(), FluidCollisionMode.NEVER);
        if (target == null || target.getType().isAir() || !target.getType().isBlock() || isBlockedDisguise(target.getType())) {
            fail(player, "不能伪装成这个方块。");
            return;
        }

        state.disguiseData = target.getBlockData();
        state.disguised = true;
        if (state.display != null) state.display.setBlock(state.disguiseData);
        player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, state.disguiseData);
        player.playSound(player.getLocation(), Sound.BLOCK_GRASS_PLACE, 1f, 1.2f);
    }

    private boolean isBlockedDisguise(Material material) {
        return settings.disguiseBlacklist().contains(material);
    }

    private void releaseDisguise(Player player, GamePlayer state) {
        if (state.role != Role.HIDER) return;
        state.disguised = false;
        state.disguiseData = Bukkit.createBlockData(Material.AIR);
        if (state.display != null) state.display.setBlock(state.disguiseData);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.1f);
    }

    private void toggleRotationLock(Player player, GamePlayer state) {
        if (state.role != Role.HIDER) return;
        state.rotationLocked = !state.rotationLocked;
        state.lockedYaw = normalizeYaw(player.getLocation().getYaw());
        if (state.display != null) {
            applyDisguiseTransform(state.display, state.lockedYaw);
            state.visualYaw = state.lockedYaw;
        }
        player.sendMessage(state.rotationLocked ? "已锁定伪装旋转。" : "已解除旋转锁定。");
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.3f);
    }

    private void useDecoy(Player player, GamePlayer state) {
        if (state.role != Role.HIDER) return;
        if (!state.disguised || state.disguiseData == null || state.disguiseData.getMaterial().isAir()) {
            fail(player, "需要先伪装才能放置诱饵。");
            return;
        }
        if (!consumeMp(player, state, settings.decoyMp())) return;

        Vector forward = player.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 0.001) forward = new Vector(1, 0, 0);
        Location loc = player.getLocation().add(forward.normalize().multiply(2.0));
        loc.setY(player.getLocation().getY());
        loc.setYaw(0f);
        loc.setPitch(0f);

        BlockDisplay display = player.getWorld().spawn(loc, BlockDisplay.class, spawned -> {
            spawned.setBlock(state.disguiseData);
            spawned.setPersistent(false);
            applyDisguiseTransform(spawned, state.lockedYaw);
        });
        decoys.add(new Decoy(player.getUniqueId(), display, settings.decoyHp(), settings.decoyLifetimeTicks()));
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 0.8f);
    }

    private void useFly(Player player, GamePlayer state, Role requiredRole, int cost, double power, double minYBoost) {
        if (state.role != requiredRole) return;
        if (!consumeMp(player, state, cost)) return;
        if (requiredRole == Role.HIDER) releaseDisguise(player, state);
        Vector velocity = player.getEyeLocation().getDirection().normalize().multiply(power);
        velocity.setY(Math.max(velocity.getY(), minYBoost));
        player.setVelocity(velocity);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 18, 0.25, 0.15, 0.25, 0.02);
        player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1f, 1f);
    }

    private void useAttackBullet(Player player, GamePlayer state) {
        if (state.role != Role.SEEKER) return;
        if (!consumeMp(player, state, settings.attackBulletMp())) return;
        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(settings.attackBulletSpeed()));
        snowball.getPersistentDataContainer().set(attackKey, PersistentDataType.BYTE, (byte) 1);
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 1.4f);
    }

    private void useScan(Player player, GamePlayer state) {
        if (state.role != Role.SEEKER) return;
        if (!consumeMp(player, state, settings.scanMp())) return;
        player.getWorld().spawnParticle(Particle.SONIC_BOOM, player.getLocation().add(0, 1, 0), 1);
        player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.4f);
        boolean found = playersWithRole(Role.HIDER).stream()
                .anyMatch(hider -> hider.getWorld().equals(player.getWorld()) && hider.getLocation().distance(player.getLocation()) <= settings.scanRadius());
        Bukkit.getScheduler().runTaskLater(this, () -> player.sendMessage(found ? "扫描范围内发现躲藏者。" : "扫描范围内没有发现躲藏者。"), settings.scanResultDelayTicks());
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!projectile.getPersistentDataContainer().has(attackKey, PersistentDataType.BYTE)) return;
        Set<UUID> damagedPlayers = new HashSet<>();
        if (event.getHitEntity() instanceof Player player && damagedPlayers.add(player.getUniqueId())) {
            damageIfHider(player);
        }
        double hitRadius = settings.attackBulletHitRadius();
        for (Entity nearby : projectile.getNearbyEntities(hitRadius, hitRadius, hitRadius)) {
            if (nearby instanceof Player player && damagedPlayers.add(player.getUniqueId())) damageIfHider(player);
        }
        damageNearbyDecoy(projectile.getLocation(), settings.damagePerHit());
        projectile.getWorld().spawnParticle(Particle.CRIT, projectile.getLocation(), 16, 0.2, 0.2, 0.2, 0.05);
        projectile.remove();
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        GamePlayer state = players.get(player.getUniqueId());
        if (state == null) return;
        event.setCancelled(true);
        if (state.role == Role.HIDER && !(event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Snowball)) {
            damageHider(player, state, settings.damagePerHit());
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Snowball snowball
                && snowball.getPersistentDataContainer().has(attackKey, PersistentDataType.BYTE)
                && event.getEntity() instanceof Player player) {
            event.setCancelled(true);
        }
    }

    private void damageIfHider(Player player) {
        GamePlayer target = players.get(player.getUniqueId());
        if (target != null && target.role == Role.HIDER) damageHider(player, target, settings.damagePerHit());
    }

    private void damageHider(Player player, GamePlayer state, int damage) {
        state.hp = Math.max(0, state.hp - damage);
        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_HURT, 1f, 1.2f);
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 20, 0.35, 0.55, 0.35, 0.05);
        if (state.hp <= 0) eliminateHider(player, state);
    }

    private void damageNearbyDecoy(Location location, int damage) {
        for (Decoy decoy : decoys) {
            if (decoy.display != null && decoy.display.getWorld().equals(location.getWorld())
                    && decoy.display.getLocation().distance(location) <= settings.attackBulletHitRadius()) {
                decoy.hp -= damage;
            }
        }
    }

    private void eliminateHider(Player player, GamePlayer state) {
        releaseDisguise(player, state);
        if (state.display != null) {
            state.display.remove();
            state.display = null;
        }
        state.role = Role.SEEKER;
        state.hp = settings.maxHp();
        state.mp = settings.maxMp();
        player.teleport(getArenaSpawn());
        giveSeekerLoadout(player);
        player.sendTitle("你被发现了", "现在加入寻找者", 10, 60, 10);
        Bukkit.broadcast(Component.text(player.getName() + " 已转为寻找者。"));
        checkWin();
    }

    private boolean consumeMp(Player player, GamePlayer state, int amount) {
        if (state.mp < amount) {
            fail(player, "MP 不足。");
            return false;
        }
        state.mp -= amount;
        return true;
    }

    private void fail(Player player, String message) {
        player.sendMessage(message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.7f);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (hasNoDrop(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (hasNoDrop(event.getCurrentItem()) || hasNoDrop(event.getCursor())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (hasNoDrop(event.getOldCursor()) || hasNoDrop(event.getCursor())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (hasNoDrop(event.getMainHandItem()) || hasNoDrop(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (phase != GamePhase.RUNNING) return;
        GamePlayer state = players.get(event.getPlayer().getUniqueId());
        if (state != null) ensureLoadout(event.getPlayer(), state.role);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (phase != GamePhase.RUNNING || remainingTicks <= settings.seekerReleaseAt()) return;
        GamePlayer state = players.get(event.getPlayer().getUniqueId());
        if (state == null || state.role != Role.SEEKER) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        GamePlayer state = players.get(event.getPlayer().getUniqueId());
        if (bossBar != null && state != null) {
            bossBar.addPlayer(event.getPlayer());
            ensureLoadout(event.getPlayer(), state.role);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        GamePlayer state = players.remove(event.getPlayer().getUniqueId());
        if (state == null) return;
        if (bossBar != null) bossBar.removePlayer(event.getPlayer());
        cleanupPlayer(event.getPlayer(), state);
        if (phase == GamePhase.RUNNING) {
            Bukkit.broadcast(Component.text(event.getPlayer().getName() + " 已离开本局躲猫猫。"));
            if (players.isEmpty()) {
                stopGame(false);
            } else {
                checkWin();
            }
        }
    }

    private void cleanupPlayer(Player player, GamePlayer state) {
        if (state.display != null) {
            state.display.remove();
            state.display = null;
        }
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        removeAbilityItems(player.getInventory());
    }

    private void removeAbilityItems(PlayerInventory inventory) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (getAbility(inventory.getItem(i)) != null) inventory.setItem(i, null);
        }
        if (getAbility(inventory.getItemInOffHand()) != null) inventory.setItemInOffHand(null);
    }

    private String getAbility(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
    }

    private boolean hasNoDrop(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(noDropKey, PersistentDataType.BYTE);
    }

    private List<Player> playersWithRole(Role role) {
        return players.values().stream()
                .filter(state -> state.role == role)
                .map(state -> Bukkit.getPlayer(state.uuid))
                .filter(Objects::nonNull)
                .toList();
    }

    private String roleName(Role role) {
        return role == Role.HIDER ? "躲藏者" : "寻找者";
    }

    private double getMaxHealth(Player player) {
        return Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue();
    }

    private Location getArenaSpawn() {
        if (arenaSpawn != null) return arenaSpawn.clone();
        World world = Bukkit.getWorlds().getFirst();
        return world.getSpawnLocation();
    }

    private void loadSpawn() {
        if (!getConfig().contains("arena.world")) return;
        World world = Bukkit.getWorld(getConfig().getString("arena.world", ""));
        if (world == null) return;
        arenaSpawn = new Location(
                world,
                getConfig().getDouble("arena.x"),
                getConfig().getDouble("arena.y"),
                getConfig().getDouble("arena.z"),
                (float) getConfig().getDouble("arena.yaw"),
                (float) getConfig().getDouble("arena.pitch")
        );
    }

    private void saveSpawn(Location location) {
        getConfig().set("arena.world", location.getWorld().getName());
        getConfig().set("arena.x", location.getX());
        getConfig().set("arena.y", location.getY());
        getConfig().set("arena.z", location.getZ());
        getConfig().set("arena.yaw", location.getYaw());
        getConfig().set("arena.pitch", location.getPitch());
        saveConfig();
    }

    private GameSettings loadSettings() {
        int durationTicks = positiveInt("game.durationTicks");
        int seekerReleaseDelayTicks = nonNegativeInt("game.seekerReleaseDelayTicks");
        return new GameSettings(
                durationTicks,
                Math.max(1, getConfig().getInt("game.seekerCount")),
                Math.max(0, durationTicks - seekerReleaseDelayTicks),
                positiveInt("player.hp"),
                positiveInt("player.mp"),
                nonNegativeInt("player.hpRegenPerTick"),
                nonNegativeInt("player.mpRegenPerTick"),
                positiveInt("player.damagePerHit"),
                positiveInt("abilities.disguise.range"),
                clampedDouble("abilities.disguise.rotationSnapDegrees", 0.0, 360.0),
                loadDisguiseBlacklist(),
                positiveInt("abilities.decoy.hp"),
                nonNegativeInt("abilities.decoy.mp"),
                positiveInt("abilities.decoy.lifetimeTicks"),
                nonNegativeInt("abilities.flyHider.mp"),
                positiveDouble("abilities.flyHider.power"),
                nonNegativeDouble("abilities.flyHider.minYBoost"),
                nonNegativeInt("abilities.flySeeker.mp"),
                positiveDouble("abilities.flySeeker.power"),
                nonNegativeDouble("abilities.flySeeker.minYBoost"),
                nonNegativeInt("abilities.attackBullet.mp"),
                positiveDouble("abilities.attackBullet.speed"),
                positiveDouble("abilities.attackBullet.hitRadius"),
                nonNegativeInt("abilities.scan.mp"),
                positiveDouble("abilities.scan.radius"),
                nonNegativeLong("abilities.scan.resultDelayTicks"),
                positiveDouble("worldBorder.initialSize"),
                positiveDouble("worldBorder.resetSize"),
                nonNegativeDouble("worldBorder.damageBuffer"),
                loadBorderStages()
        );
    }

    private List<BorderStage> loadBorderStages() {
        List<BorderStage> stages = new ArrayList<>();
        for (Map<?, ?> map : getConfig().getMapList("worldBorder.stages")) {
            Object remainingTicks = map.get("remainingTicks");
            Object size = map.get("size");
            Object seconds = map.get("seconds");
            if (remainingTicks instanceof Number tickNumber && size instanceof Number sizeNumber) {
                long secondsValue = seconds instanceof Number secondsNumber ? Math.max(0L, secondsNumber.longValue()) : 3L;
                stages.add(new BorderStage(Math.max(0, tickNumber.intValue()), Math.max(1.0, sizeNumber.doubleValue()), secondsValue));
            }
        }
        stages.sort(Comparator.comparingInt(BorderStage::remainingTicks).reversed());
        return List.copyOf(stages);
    }

    private Set<Material> loadDisguiseBlacklist() {
        Set<Material> blacklist = new HashSet<>();
        blacklist.addAll(Set.of(
                Material.AIR,
                Material.CAVE_AIR,
                Material.VOID_AIR,
                Material.BARRIER,
                Material.STRUCTURE_VOID
        ));

        for (String rawEntry : getConfig().getStringList("abilities.disguise.blacklist")) {
            String entry = normalizeBlockListEntry(rawEntry);
            if (entry.startsWith("#")) {
                Tag<Material> tag = resolveBlockTag(entry.substring(1));
                if (tag == null) {
                    getLogger().warning("Unknown disguise blacklist block tag: " + entry);
                    continue;
                }
                tag.getValues().stream()
                        .filter(Material::isBlock)
                        .forEach(blacklist::add);
                continue;
            }

            Material material = Material.matchMaterial(entry);
            if (material == null || !material.isBlock()) {
                getLogger().warning("Unknown disguise blacklist block: " + entry);
                continue;
            }
            blacklist.add(material);
        }
        return Set.copyOf(blacklist);
    }

    private Tag<Material> resolveBlockTag(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        if (namespacedKey == null) return null;
        return Bukkit.getTag(Tag.REGISTRY_BLOCKS, namespacedKey, Material.class);
    }

    private int positiveInt(String path) {
        return Math.max(1, getConfig().getInt(path));
    }

    private int nonNegativeInt(String path) {
        return Math.max(0, getConfig().getInt(path));
    }

    private int clampedInt(String path, int min, int max) {
        return Math.max(min, Math.min(max, getConfig().getInt(path)));
    }

    private double clampedDouble(String path, double min, double max) {
        return Math.max(min, Math.min(max, getConfig().getDouble(path)));
    }

    private long nonNegativeLong(String path) {
        return Math.max(0L, getConfig().getLong(path));
    }

    private double positiveDouble(String path) {
        return Math.max(0.001, getConfig().getDouble(path));
    }

    private double nonNegativeDouble(String path) {
        return Math.max(0.0, getConfig().getDouble(path));
    }

    private record GameSettings(
            int durationTicks,
            int seekerCount,
            int seekerReleaseAt,
            int maxHp,
            int maxMp,
            int hpRegenPerTick,
            int mpRegenPerTick,
            int damagePerHit,
            int disguiseRange,
            double disguiseRotationSnapDegrees,
            Set<Material> disguiseBlacklist,
            int decoyHp,
            int decoyMp,
            int decoyLifetimeTicks,
            int hiderFlyMp,
            double hiderFlyPower,
            double hiderFlyMinYBoost,
            int seekerFlyMp,
            double seekerFlyPower,
            double seekerFlyMinYBoost,
            int attackBulletMp,
            double attackBulletSpeed,
            double attackBulletHitRadius,
            int scanMp,
            double scanRadius,
            long scanResultDelayTicks,
            double borderInitialSize,
            double borderResetSize,
            double borderDamageBuffer,
            List<BorderStage> borderStages
    ) {
    }

    private record BorderStage(int remainingTicks, double size, long seconds) {
    }

    private enum GamePhase {
        IDLE,
        RUNNING
    }

    private enum Role {
        HIDER,
        SEEKER
    }

    private static final class GamePlayer {
        private final UUID uuid;
        private Role role;
        private int hp;
        private int mp;
        private boolean disguised;
        private boolean rotationLocked;
        private float lockedYaw;
        private float visualYaw;
        private BlockData disguiseData;
        private BlockDisplay display;

        private GamePlayer(UUID uuid, Role role, int hp, int mp) {
            this.uuid = uuid;
            this.role = role;
            this.hp = hp;
            this.mp = mp;
            this.disguiseData = Bukkit.createBlockData(Material.AIR);
        }
    }

    private static final class Decoy {
        private final UUID owner;
        private final BlockDisplay display;
        private int hp;
        private int remainingTicks;

        private Decoy(UUID owner, BlockDisplay display, int hp, int remainingTicks) {
            this.owner = owner;
            this.display = display;
            this.hp = hp;
            this.remainingTicks = remainingTicks;
        }
    }
}
