package cn.xducraft.hide_and_seek;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
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
import java.util.concurrent.ThreadLocalRandom;
import java.time.Duration;
import java.util.UUID;

public final class Hide_and_seek extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final Key SYSTEM_FONT = Key.key("minecraft", "system");
    private static final Key PLAYER_UI_FONT = Key.key("minecraft", "player_ui_1");
    private static final String SYSTEM_SEEKER_WIN = "\uE001";
    private static final String SYSTEM_HIDER_WIN = "\uE002";
    private static final String SYSTEM_WORLDBORDER_WARNING = "\uE003";
    private static final String SYSTEM_WORLDBORDER_SHRINK = "\uE004";
    private static final String SYSTEM_RELEASE_SEEKER = "\uE005";
    private static final String SYSTEM_START_SEEKER = "\uE006";
    private static final String SYSTEM_START_HIDER = "\uE007";
    private final Map<UUID, GamePlayer> players = new HashMap<>();
    private final List<Decoy> decoys = new ArrayList<>();
    private final Map<UUID, DecoyProjectile> decoyProjectiles = new HashMap<>();
    private final List<AttackBullet> attackBullets = new ArrayList<>();
    private final List<ScanEffect> scanEffects = new ArrayList<>();
    private final Set<UUID> initialSeekers = new HashSet<>();
    private final Set<UUID> waitingSpectators = new HashSet<>();
    private final Map<Integer, BorderRectangle> pendingBorders = new HashMap<>();
    private final Set<Integer> warnedBorderStages = new HashSet<>();
    private final Set<Integer> startedBorderStages = new HashSet<>();
    private NamespacedKey abilityKey;
    private NamespacedKey noDropKey;
    private NamespacedKey decoyProjectileKey;
    private GameSettings settings;
    private BukkitTask gameTask;
    private BossBar bossBar;
    private GamePhase phase = GamePhase.IDLE;
    private Location arenaSpawn;
    private BorderState borderState;
    private ItemDisplay jailCell;
    private int jailCellOpenTicks = -1;
    private int borderParticleTick;
    private int remainingTicks;
    private Boolean originalLocatorBar;

    @Override
    public void onEnable() {
        abilityKey = new NamespacedKey(this, "ability");
        noDropKey = new NamespacedKey(this, "no_drop");
        decoyProjectileKey = new NamespacedKey(this, "decoy_projectile");
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
            if (role == Role.SEEKER) initialSeekers.add(player.getUniqueId());
            GamePlayer state = new GamePlayer(player.getUniqueId(), role, settings.maxHp(), settings.maxMp());
            players.put(player.getUniqueId(), state);
            setupPlayer(player, state, spawn);
            bossBar.addPlayer(player);
        }
        disableLocatorBar(spawn.getWorld());

        setupWorldBorder(spawn);
        spawnJailCell(spawn);
        Bukkit.broadcast(Component.text("躲猫猫开始！前 30 秒寻找者等待，躲藏者快藏好。", NamedTextColor.GOLD));
        gameTask = Bukkit.getScheduler().runTaskTimer(this, this::tickGame, 1L, 1L);
    }

    private void setupPlayer(Player player, GamePlayer state, Location spawn) {
        player.teleport(spawn);
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.setHealth(getMaxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(20);
        joinScoreboardTeam(player, state.role);

        if (state.role == Role.HIDER) {
            spawnDisguiseDisplay(player, state);
            giveHiderLoadout(player);
            showTitle(player, systemGlyph(SYSTEM_START_HIDER), Component.empty(), 10, 60, 10);
        } else {
            giveSeekerLoadout(player);
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 620, 0, false, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 620, 10, false, false, false));
            showTitle(player, systemGlyph(SYSTEM_START_SEEKER), Component.empty(), 10, 60, 10);
        }
    }

    private void tickGame() {
        if (phase != GamePhase.RUNNING) return;
        remainingTicks--;
        updateBossBar();

        if (remainingTicks == settings.seekerReleaseAt()) {
            Bukkit.broadcast(Component.text("寻找者已释放！", NamedTextColor.RED));
            openJailCell();
            for (Player player : Bukkit.getOnlinePlayers()) {
                showTitle(player, systemGlyph(SYSTEM_RELEASE_SEEKER), Component.empty(), 5, 45, 10);
            }
            playersWithRole(Role.SEEKER).forEach(player -> {
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 1f);
            });
        }

        updateBorder();
        tickPlayers();
        tickDecoys();
        tickDecoyProjectiles();
        tickAttackBullets();
        tickScanEffects();
        tickJailCell();
        checkWin();
    }

    private void tickPlayers() {
        for (GamePlayer state : players.values()) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player == null) continue;
            state.hp = Math.min(settings.maxHp(), state.hp + settings.hpRegenPerTick());
            state.mp = Math.min(settings.maxMp(), state.mp + settings.mpRegenPerTick());
            tickFlyLock(player, state);
            tickAcceleratedAir(player, state);
            ensureLoadout(player, state.role);
            if (state.role == Role.HIDER) {
                tickDisguise(player, state);
                renderDisguiseTargetOutline(player);
            }
            player.setFoodLevel(20);
            player.setSaturation(20);
            player.sendActionBar(statusLine(state));
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
                continue;
            }
            tickDecoyMovement(decoy);
        }
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        int seconds = Math.max(0, remainingTicks / 20);
        long hiders = players.values().stream().filter(state -> state.role == Role.HIDER).count();
        bossBar.setTitle("躲猫猫 " + seconds / 60 + ":" + String.format("%02d", seconds % 60) + "  躲藏者 " + hiders);
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, remainingTicks / (double) settings.durationTicks())));
    }

    private void updateBorder() {
        for (BorderStage stage : settings.borderStages()) {
            int warnAt = stage.remainingTicks() + settings.borderWarningTicks();
            if (!warnedBorderStages.contains(stage.remainingTicks())
                    && remainingTicks <= warnAt
                    && remainingTicks > stage.remainingTicks()) {
                BorderRectangle next = prepareNextBorder(stage);
                warnedBorderStages.add(stage.remainingTicks());
                announceNextBorder(stage, next);
            }
            if (!startedBorderStages.contains(stage.remainingTicks()) && remainingTicks <= stage.remainingTicks()) {
                startedBorderStages.add(stage.remainingTicks());
                shrinkBorder(stage);
            }
        }
        tickBorderTransition();
        tickRectangularBorder();
    }

    private void shrinkBorder(BorderStage stage) {
        if (borderState == null) return;
        BorderRectangle next = prepareNextBorder(stage);
        borderState.beginMove(next, Math.max(1, stage.seconds() * 20L));
        Bukkit.broadcast(Component.text("世界边界开始缩小。跟随粒子返回安全区。", NamedTextColor.RED));
        for (Player player : Bukkit.getOnlinePlayers()) {
            showTitle(player, systemGlyph(SYSTEM_WORLDBORDER_SHRINK), Component.empty(), 5, 45, 10);
        }
    }

    private void checkWin() {
        if (phase != GamePhase.RUNNING) return;
        long hiders = players.values().stream().filter(state -> state.role == Role.HIDER).count();
        if (hiders == 0) {
            endGame(Role.SEEKER, "寻找者胜利！");
        } else if (remainingTicks <= 0) {
            endGame(Role.HIDER, "躲藏者胜利！");
        }
    }

    private void endGame(Role winner, String message) {
        Component chatTitle = Component.text(message, winner == Role.HIDER ? NamedTextColor.GREEN : NamedTextColor.RED);
        Component visualTitle = systemGlyph(winner == Role.HIDER ? SYSTEM_HIDER_WIN : SYSTEM_SEEKER_WIN);
        Bukkit.broadcast(chatTitle);
        broadcastResult(winner);
        for (Player player : Bukkit.getOnlinePlayers()) {
            showTitle(player, visualTitle, Component.empty(), 10, 70, 20);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        stopGame(false);
    }

    private void broadcastResult(Role winner) {
        Bukkit.broadcast(Component.text("[结果]", NamedTextColor.GOLD));
        Bukkit.broadcast(Component.text("获胜阵营：", NamedTextColor.WHITE)
                .append(winner == Role.SEEKER
                        ? Component.text("寻找者(初)", NamedTextColor.RED)
                        : Component.text("躲藏者", NamedTextColor.AQUA)));
        Bukkit.broadcast(Component.text(" ", NamedTextColor.WHITE).append(winner == Role.SEEKER
                ? playerList(initialSeekerPlayers(), NamedTextColor.RED)
                : playerList(playersWithRole(Role.HIDER), NamedTextColor.AQUA)));
        Bukkit.broadcast(Component.text("--------------------------------------", NamedTextColor.WHITE));
        Bukkit.broadcast(Component.text("<寻找者(初)>  ", NamedTextColor.RED)
                .append(playerList(initialSeekerPlayers(), NamedTextColor.RED)));
        Bukkit.broadcast(Component.text("<寻找者(增)>  ", NamedTextColor.RED)
                .append(playerList(joinedSeekerPlayers(), NamedTextColor.RED)));
        Bukkit.broadcast(Component.text("躲藏者>    ", NamedTextColor.AQUA)
                .append(playerList(playersWithRole(Role.HIDER), NamedTextColor.AQUA)));
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
        for (UUID projectileId : decoyProjectiles.keySet()) {
            Entity projectile = Bukkit.getEntity(projectileId);
            if (projectile != null) projectile.remove();
        }
        for (AttackBullet bullet : attackBullets) {
            if (bullet.display != null) bullet.display.remove();
        }
        for (ScanEffect scanEffect : scanEffects) {
            if (scanEffect.display != null) scanEffect.display.remove();
        }
        if (jailCell != null) {
            jailCell.remove();
            jailCell = null;
        }

        decoys.clear();
        decoyProjectiles.clear();
        attackBullets.clear();
        scanEffects.clear();
        initialSeekers.clear();
        pendingBorders.clear();
        warnedBorderStages.clear();
        startedBorderStages.clear();
        restoreLocatorBar();
        players.clear();
        cleanupWaitingSpectators();
        World world = getArenaSpawn().getWorld();
        if (world != null) world.getWorldBorder().changeSize(settings.borderResetSize(), 0L);
        borderState = null;
        jailCellOpenTicks = -1;
        phase = GamePhase.IDLE;
        remainingTicks = 0;
        if (announce) Bukkit.broadcast(Component.text("躲猫猫已停止。", NamedTextColor.YELLOW));
    }

    private void setupWorldBorder(Location center) {
        WorldBorder border = center.getWorld().getWorldBorder();
        border.setCenter(center);
        border.changeSize(settings.borderResetSize(), 0L);
        border.setDamageBuffer(settings.borderDamageBuffer());
        borderState = new BorderState(center.getX(), center.getZ(), settings.borderInitialWidth(), settings.borderInitialDepth());
        borderParticleTick = 0;
    }

    private void spawnJailCell(Location spawn) {
        if (jailCell != null) jailCell.remove();
        Location location = spawn.clone();
        location.setYaw(0f);
        location.setPitch(0f);
        jailCell = location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(resourcePackItem(Material.WHITE_DYE, new NamespacedKey("animated_java", "jail_cell/bone")));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            display.setPersistent(false);
            display.setTeleportDuration(2);
            display.setViewRange(128f);
            display.setTransformation(new Transformation(
                    new Vector3f(-0.5f, 0f, -0.5f),
                    new Quaternionf(),
                    new Vector3f(3.5f, 3.5f, 3.5f),
                    new Quaternionf()
            ));
        });
        jailCellOpenTicks = -1;
    }

    private void openJailCell() {
        if (jailCell == null || jailCell.isDead()) return;
        jailCellOpenTicks = 0;
    }

    private void tickJailCell() {
        if (jailCell == null || jailCellOpenTicks < 0) return;
        jailCellOpenTicks++;
        jailCell.teleport(jailCell.getLocation().add(0, 0.45, 0));
        if (jailCellOpenTicks >= 12) {
            jailCell.remove();
            jailCell = null;
            jailCellOpenTicks = -1;
        }
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
        meta.setItemModel(NamespacedKey.minecraft("ability/" + ability));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(abilityKey, PersistentDataType.STRING, ability);
        pdc.set(noDropKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resourcePackItem(Material material, NamespacedKey itemModel) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(itemModel);
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
        if (state.role == Role.SEEKER && remainingTicks > settings.seekerReleaseAt()) {
            fail(player, "寻找者尚未释放。");
            return;
        }

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

    private void renderDisguiseTargetOutline(Player player) {
        if (!"disguise".equals(getAbility(player.getInventory().getItemInMainHand()))) return;
        Block target = player.getTargetBlockExact(settings.disguiseRange(), FluidCollisionMode.NEVER);
        if (target == null || target.getType().isAir()) return;
        boolean valid = target.getType().isBlock() && !isBlockedDisguise(target.getType());
        Particle.DustOptions dust = valid
                ? new Particle.DustOptions(Color.WHITE, 0.8f)
                : new Particle.DustOptions(Color.RED, 0.8f);
        Location center = target.getLocation().add(0.5, 0.5, 0.5);
        double[] edges = {-0.5, 0.0, 0.5};
        for (double x : edges) {
            for (double z : edges) {
                spawnTargetParticle(player, center, x, -0.5, z, dust);
                spawnTargetParticle(player, center, x, 0.5, z, dust);
            }
        }
        for (double x : edges) {
            for (double y : edges) {
                spawnTargetParticle(player, center, x, y, -0.5, dust);
                spawnTargetParticle(player, center, x, y, 0.5, dust);
            }
        }
        for (double z : edges) {
            for (double y : edges) {
                spawnTargetParticle(player, center, -0.5, y, z, dust);
                spawnTargetParticle(player, center, 0.5, y, z, dust);
            }
        }
    }

    private void spawnTargetParticle(Player player, Location center, double x, double y, double z, Particle.DustOptions dust) {
        player.spawnParticle(Particle.DUST, center.clone().add(x, y, z), 1, 0, 0, 0, 0, dust);
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
        state.lockedYaw = snapYaw(player.getLocation().getYaw());
        if (state.display != null) {
            applyDisguiseTransform(state.display, state.lockedYaw);
            state.visualYaw = state.lockedYaw;
        }
        player.sendMessage(Component.text(state.rotationLocked ? "已锁定伪装旋转。" : "已解除旋转锁定。", NamedTextColor.AQUA));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.3f);
    }

    private void useDecoy(Player player, GamePlayer state) {
        if (state.role != Role.HIDER) return;
        if (!state.disguised || state.disguiseData == null || state.disguiseData.getMaterial().isAir()) {
            fail(player, "需要先伪装才能放置诱饵。");
            return;
        }
        if (!consumeMp(player, state, settings.decoyMp())) return;

        Snowball projectile = player.launchProjectile(Snowball.class);
        projectile.setItem(resourcePackItem(Material.SNOWBALL, NamespacedKey.minecraft(".empty")));
        projectile.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(settings.decoyThrowSpeed()));
        projectile.getPersistentDataContainer().set(decoyProjectileKey, PersistentDataType.BYTE, (byte) 1);
        decoyProjectiles.put(projectile.getUniqueId(), new DecoyProjectile(
                player.getUniqueId(),
                state.disguiseData,
                state.lockedYaw
        ));
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 0.8f);
    }

    private void useFly(Player player, GamePlayer state, Role requiredRole, int cost, double power, double minYBoost) {
        if (state.role != requiredRole) return;
        if (state.flyLocked) {
            fail(player, "跳跃尚未恢复。落地或进入水中后才能再次使用。");
            return;
        }
        if (!consumeMp(player, state, cost)) return;
        if (requiredRole == Role.HIDER) releaseDisguise(player, state);
        Vector velocity = player.getEyeLocation().getDirection().normalize().multiply(power);
        velocity.setY(Math.max(velocity.getY(), minYBoost));
        player.setVelocity(velocity);
        state.flyLocked = true;
        state.flyLockTicks = 0;
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 18, 0.25, 0.15, 0.25, 0.02);
        player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1f, 1f);
    }

    private void useAttackBullet(Player player, GamePlayer state) {
        if (state.role != Role.SEEKER) return;
        if (!consumeMp(player, state, settings.attackBulletMp())) return;
        Location start = player.getEyeLocation().add(player.getEyeLocation().getDirection().normalize().multiply(0.8));
        Vector velocity = player.getEyeLocation().getDirection().normalize().multiply(settings.attackBulletSpeed());
        ItemDisplay visual = player.getWorld().spawn(start, ItemDisplay.class, display -> {
            display.setItemStack(new ItemStack(Material.NETHERITE_BLOCK));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            display.setPersistent(false);
            display.setTeleportDuration(1);
            display.setViewRange(64f);
            display.setTransformation(new Transformation(
                    new Vector3f(0f, -0.15f, 0f),
                    new Quaternionf(),
                    new Vector3f(0.45f, 0.45f, 0.45f),
                    new Quaternionf()
            ));
        });
        attackBullets.add(new AttackBullet(player.getUniqueId(), visual, velocity));
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 1.4f);
    }

    private void useScan(Player player, GamePlayer state) {
        if (state.role != Role.SEEKER) return;
        if (!consumeMp(player, state, settings.scanMp())) return;
        Location location = player.getLocation().clone();
        ItemDisplay display = player.getWorld().spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(resourcePackItem(Material.WHITE_DYE, new NamespacedKey("animated_java", "scan_effect/scan_effect")));
            spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            spawned.setPersistent(false);
            spawned.setTeleportDuration(1);
            spawned.setViewRange(96f);
            spawned.setTransformation(new Transformation(
                    new Vector3f(-0.5f, 0f, -0.5f),
                    new Quaternionf(),
                    new Vector3f(1.0f, 1.0f, 1.0f),
                    new Quaternionf()
            ));
        });
        scanEffects.add(new ScanEffect(player.getUniqueId(), display, location));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.playSound(location, "minecraft:scan", SoundCategory.MASTER, 1f, 1f);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getPersistentDataContainer().has(decoyProjectileKey, PersistentDataType.BYTE)) {
            landDecoyProjectile(projectile);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        GamePlayer state = players.get(player.getUniqueId());
        if (state == null) return;
        event.setCancelled(true);
        damagePlayer(player, state, customDamageFromVanilla(event));
    }

    private int customDamageFromVanilla(EntityDamageEvent event) {
        return Math.max(settings.minVanillaDamage(), (int) Math.ceil(event.getFinalDamage() * settings.vanillaDamageScale()));
    }

    private void damagePlayer(Player player, GamePlayer state, int damage) {
        if (state.role == Role.HIDER) {
            damageHider(player, state, damage);
            return;
        }
        state.hp = Math.max(0, state.hp - damage);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.75f, 0.9f);
        player.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, player.getLocation().add(0, 1, 0), 8, 0.25, 0.45, 0.25, 0.02);
        if (state.hp <= 0) {
            state.hp = settings.maxHp();
            state.mp = Math.max(0, state.mp / 2);
            player.teleport(getArenaSpawn());
            showTitle(player, Component.text("倒下了", NamedTextColor.RED), Component.text("已返回出生点", NamedTextColor.GRAY), 5, 40, 10);
        }
    }

    private void damageHider(Player player, GamePlayer state, int damage) {
        state.hp = Math.max(0, state.hp - damage);
        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_HURT, 1f, 1.2f);
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 20, 0.35, 0.55, 0.35, 0.05);
        if (state.hp <= 0) eliminateHider(player, state);
    }

    private boolean damageNearbyHiders(Location location, UUID owner) {
        boolean hit = false;
        double hitRadius = settings.attackBulletHitRadius();
        for (Player player : playersWithRole(Role.HIDER)) {
            if (player.getUniqueId().equals(owner) || !player.getWorld().equals(location.getWorld())) continue;
            if (player.getLocation().add(0, 0.8, 0).distance(location) <= hitRadius) {
                GamePlayer target = players.get(player.getUniqueId());
                if (target != null) {
                    damageHider(player, target, settings.damagePerHit());
                    hit = true;
                    if (phase != GamePhase.RUNNING) return true;
                }
            }
        }
        return hit;
    }

    private boolean damageNearbyDecoy(Location location, int damage) {
        boolean hit = false;
        for (Decoy decoy : decoys) {
            if (decoy.display != null && decoy.display.getWorld().equals(location.getWorld())
                    && decoy.display.getLocation().distance(location) <= settings.attackBulletHitRadius()) {
                decoy.hp -= damage;
                hit = true;
            }
        }
        return hit;
    }

    private void tickDecoyProjectiles() {
        Iterator<Map.Entry<UUID, DecoyProjectile>> iterator = decoyProjectiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, DecoyProjectile> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Projectile projectile) || projectile.isDead()) {
                iterator.remove();
                continue;
            }
            DecoyProjectile decoyProjectile = entry.getValue();
            decoyProjectile.age++;
            if (decoyProjectile.age >= settings.decoyMaxFlightTicks()) {
                spawnDecoy(projectile.getLocation(), decoyProjectile);
                projectile.remove();
                iterator.remove();
            }
        }
    }

    private void tickAttackBullets() {
        Iterator<AttackBullet> iterator = attackBullets.iterator();
        while (iterator.hasNext()) {
            AttackBullet bullet = iterator.next();
            if (bullet.display == null || bullet.display.isDead()) {
                iterator.remove();
                continue;
            }
            bullet.age++;
            Location next = bullet.display.getLocation().add(bullet.velocity);
            boolean hit = bullet.age >= settings.attackBulletMaxFlightTicks()
                    || next.getBlock().getType().isSolid();
            if (!hit) {
                bullet.display.teleport(next);
                hit = damageNearbyHiders(next, bullet.owner) || damageNearbyDecoy(next, settings.damagePerHit());
                if (phase != GamePhase.RUNNING) return;
            }
            if (hit) {
                bullet.display.getWorld().spawnParticle(Particle.CRIT, bullet.display.getLocation(), 16, 0.2, 0.2, 0.2, 0.05);
                bullet.display.remove();
                iterator.remove();
            }
        }
    }

    private void tickScanEffects() {
        Iterator<ScanEffect> iterator = scanEffects.iterator();
        while (iterator.hasNext()) {
            ScanEffect scanEffect = iterator.next();
            if (scanEffect.display == null || scanEffect.display.isDead()) {
                iterator.remove();
                continue;
            }
            scanEffect.age++;
            double progress = Math.min(1.0, scanEffect.age / (double) settings.scanResultDelayTicks());
            double radius = Math.max(1.0, settings.scanRadius() * progress);
            if (scanEffect.age % 2 == 0) renderScanRing(scanEffect.origin, radius);
            if (!scanEffect.caught) {
                scanEffect.caught = playersWithRole(Role.HIDER).stream()
                        .anyMatch(hider -> hider.getWorld().equals(scanEffect.origin.getWorld())
                                && hider.getLocation().distance(scanEffect.origin) <= radius);
            }
            if (scanEffect.age >= settings.scanResultDelayTicks()) {
                Player owner = Bukkit.getPlayer(scanEffect.owner);
                if (owner != null) {
                    owner.sendMessage(scanEffect.caught
                            ? Component.text("扫描范围内发现躲藏者。", NamedTextColor.RED)
                            : Component.text("扫描范围内没有发现躲藏者。", NamedTextColor.GREEN));
                }
                scanEffect.display.remove();
                iterator.remove();
            }
        }
    }

    private void renderScanRing(Location origin, double radius) {
        World world = origin.getWorld();
        if (world == null) return;
        int points = Math.max(16, (int) Math.round(radius * 8.0));
        Particle.DustOptions dust = new Particle.DustOptions(Color.AQUA, 1.0f);
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0 * i / points;
            Location location = origin.clone().add(Math.cos(angle) * radius, 0.15, Math.sin(angle) * radius);
            world.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, dust);
        }
    }

    private void tickFlyLock(Player player, GamePlayer state) {
        if (!state.flyLocked) return;
        state.flyLockTicks++;
        boolean oldEnough = state.flyLockTicks >= settings.flyUnlockMinTicks();
        boolean fallbackExpired = state.flyLockTicks >= settings.flyUnlockFallbackTicks();
        boolean safeMedium = player.isInWater() || isInBubbleColumn(player) || player.isInLava() || player.isClimbing() || player.isInPowderedSnow();
        if ((oldEnough && (((Entity) player).isOnGround() || safeMedium)) || fallbackExpired) {
            state.flyLocked = false;
            state.flyLockTicks = 0;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.35f, 1.6f);
        }
    }

    private void tickAcceleratedAir(Player player, GamePlayer state) {
        if (!player.isUnderWater() || isInBubbleColumn(player)) {
            state.airDamageTicks = 0;
            return;
        }
        player.setRemainingAir(Math.max(0, player.getRemainingAir() - settings.airExtraDrainPerTick()));
        if (player.getRemainingAir() > 0) {
            state.airDamageTicks = 0;
            return;
        }
        state.airDamageTicks++;
        if (state.airDamageTicks >= settings.airDamageIntervalTicks()) {
            state.airDamageTicks = 0;
            damagePlayer(player, state, settings.airDamage());
        }
    }

    private void tickDecoyMovement(Decoy decoy) {
        if (decoy.display == null) return;
        if (decoy.moveTicksRemaining > 0) {
            Location loc = decoy.display.getLocation();
            Location next = loc.clone().add(decoy.moveDirection);
            if (canMoveDecoyTo(next)) {
                decoy.display.teleport(next);
                applyDisguiseTransform(decoy.display, decoy.yaw);
            } else {
                decoy.moveTicksRemaining = 0;
                decoy.nextMoveTicks = settings.decoyMoveIntervalTicks();
            }
            decoy.moveTicksRemaining--;
            return;
        }

        decoy.nextMoveTicks--;
        if (decoy.nextMoveTicks > 0) return;
        double radians = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
        decoy.moveDirection = new Vector(Math.cos(radians), 0.0, Math.sin(radians)).multiply(settings.decoyMoveSpeed());
        decoy.yaw = normalizeYaw((float) Math.toDegrees(Math.atan2(-decoy.moveDirection.getX(), decoy.moveDirection.getZ())));
        decoy.moveTicksRemaining = settings.decoyMoveDurationTicks();
        decoy.nextMoveTicks = settings.decoyMoveIntervalTicks();
    }

    private boolean canMoveDecoyTo(Location location) {
        Block feet = location.getBlock();
        Block head = location.clone().add(0, 1, 0).getBlock();
        return !feet.getType().isSolid() && !head.getType().isSolid();
    }

    private boolean isInBubbleColumn(Player player) {
        return player.getLocation().getBlock().getType() == Material.BUBBLE_COLUMN
                || player.getEyeLocation().getBlock().getType() == Material.BUBBLE_COLUMN;
    }

    private void landDecoyProjectile(Projectile projectile) {
        DecoyProjectile decoyProjectile = decoyProjectiles.remove(projectile.getUniqueId());
        if (decoyProjectile == null) return;
        spawnDecoy(projectile.getLocation(), decoyProjectile);
        projectile.remove();
    }

    private void spawnDecoy(Location location, DecoyProjectile decoyProjectile) {
        Location loc = location.clone();
        loc.setYaw(0f);
        loc.setPitch(0f);
        BlockDisplay display = loc.getWorld().spawn(loc, BlockDisplay.class, spawned -> {
            spawned.setBlock(decoyProjectile.blockData);
            spawned.setPersistent(false);
            applyDisguiseTransform(spawned, decoyProjectile.yaw);
        });
        decoys.add(new Decoy(decoyProjectile.owner, display, settings.decoyHp(), settings.decoyLifetimeTicks(), decoyProjectile.yaw, settings.decoyMoveDelayTicks()));
        loc.getWorld().spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.5, 0), 18, 0.25, 0.25, 0.25, decoyProjectile.blockData);
        loc.getWorld().playSound(loc, Sound.BLOCK_STONE_PLACE, 0.8f, 1.1f);
    }

    private void eliminateHider(Player player, GamePlayer state) {
        releaseDisguise(player, state);
        if (state.display != null) {
            state.display.remove();
            state.display = null;
        }
        long hiders = players.values().stream().filter(candidate -> candidate.role == Role.HIDER).count();
        if (hiders <= 1) {
            players.remove(player.getUniqueId());
            if (bossBar != null) bossBar.removePlayer(player);
            cleanupPlayer(player, state);
            endGame(Role.SEEKER, "寻找者胜利！");
            return;
        }
        state.role = Role.SEEKER;
        state.hp = settings.maxHp();
        state.mp = settings.maxMp();
        player.teleport(getArenaSpawn());
        joinScoreboardTeam(player, Role.SEEKER);
        giveSeekerLoadout(player);
        showTitle(player, Component.text("你被发现了", NamedTextColor.RED), Component.text("现在加入寻找者", NamedTextColor.GRAY), 10, 60, 10);
        Bukkit.broadcast(Component.text(player.getName() + " 已转为寻找者。", NamedTextColor.RED));
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
        player.sendMessage(Component.text(message, NamedTextColor.RED));
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
        } else if (phase == GamePhase.RUNNING) {
            setupWaitingSpectator(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (waitingSpectators.remove(event.getPlayer().getUniqueId())) {
            if (bossBar != null) bossBar.removePlayer(event.getPlayer());
            return;
        }
        GamePlayer state = players.remove(event.getPlayer().getUniqueId());
        if (state == null) return;
        if (bossBar != null) bossBar.removePlayer(event.getPlayer());
        cleanupPlayer(event.getPlayer(), state);
        if (phase == GamePhase.RUNNING) {
            Bukkit.broadcast(Component.text(event.getPlayer().getName() + " 已离开本局躲猫猫。", NamedTextColor.YELLOW));
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
        player.teleport(getArenaSpawn());
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        clearScoreboardTeam(player);
        removeAbilityItems(player.getInventory());
    }

    private void disableLocatorBar(World world) {
        if (world == null) return;
        if (originalLocatorBar == null) originalLocatorBar = world.getGameRuleValue(GameRules.LOCATOR_BAR);
        world.setGameRule(GameRules.LOCATOR_BAR, false);
    }

    private void restoreLocatorBar() {
        World world = getArenaSpawn().getWorld();
        if (world != null && originalLocatorBar != null) {
            world.setGameRule(GameRules.LOCATOR_BAR, originalLocatorBar);
        }
        originalLocatorBar = null;
    }

    private void removeAbilityItems(PlayerInventory inventory) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (getAbility(inventory.getItem(i)) != null) inventory.setItem(i, null);
        }
        if (getAbility(inventory.getItemInOffHand()) != null) inventory.setItemInOffHand(null);
    }

    private void setupWaitingSpectator(Player player) {
        waitingSpectators.add(player.getUniqueId());
        player.teleport(getArenaSpawn());
        player.setGameMode(GameMode.SPECTATOR);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        removeAbilityItems(player.getInventory());
        if (bossBar != null) bossBar.addPlayer(player);
        showTitle(player, Component.text("本局进行中", NamedTextColor.YELLOW), Component.text("你已进入旁观，下一局会自动加入", NamedTextColor.GRAY), 10, 70, 20);
    }

    private void cleanupWaitingSpectators() {
        for (UUID uuid : waitingSpectators) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            player.teleport(getArenaSpawn());
            player.setGameMode(GameMode.ADVENTURE);
            player.sendMessage(Component.text("本局躲猫猫已结束，下一局开始时你会加入游戏。", NamedTextColor.GREEN));
        }
        waitingSpectators.clear();
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

    private List<Player> initialSeekerPlayers() {
        return players.values().stream()
                .filter(state -> state.role == Role.SEEKER && initialSeekers.contains(state.uuid))
                .map(state -> Bukkit.getPlayer(state.uuid))
                .filter(Objects::nonNull)
                .toList();
    }

    private List<Player> joinedSeekerPlayers() {
        return players.values().stream()
                .filter(state -> state.role == Role.SEEKER && !initialSeekers.contains(state.uuid))
                .map(state -> Bukkit.getPlayer(state.uuid))
                .filter(Objects::nonNull)
                .toList();
    }

    private Component playerList(List<Player> listedPlayers, NamedTextColor color) {
        if (listedPlayers.isEmpty()) return Component.text("", color);
        return Component.text(String.join(", ", listedPlayers.stream().map(Player::getName).toList()), color);
    }

    private String roleName(Role role) {
        return role == Role.HIDER ? "躲藏者" : "寻找者";
    }

    private Component statusLine(GamePlayer state) {
        updateUiTrail(state);
        int hpText = Math.max(0, state.hp / Math.max(1, settings.maxHp() / 5));
        int mpText = Math.max(0, state.mp / Math.max(1, settings.maxMp() / 100));
        TextColor uiColor = TextColor.color(0x4e5c24);
        return Component.text("", uiColor).font(PLAYER_UI_FONT)
                .append(uiText("\uEF03\uE0A1\uEF04", uiColor))
                .append(uiText("\uEF03" + hpBarGlyph(state.hpOld, settings.maxHp()) + "\uEF04", uiColor))
                .append(uiText("\uEF03" + hpBarGlyph(state.hp, settings.maxHp()) + "\uEF04", NamedTextColor.WHITE))
                .append(uiText("\uEF03" + hpCapGlyph(state.hp, settings.maxHp()) + "\uEF04", NamedTextColor.WHITE))
                .append(uiText("\uEF07HP " + hpText + "/5\uEF08", NamedTextColor.WHITE))
                .append(uiText("\uEF01\uE001\uEF02", uiColor))
                .append(uiText("\uEF01" + mpBarGlyph(state.mpOld, settings.maxMp()) + "\uEF02", uiColor))
                .append(uiText("\uEF01" + mpBarGlyph(state.mp, settings.maxMp()) + "\uEF02", NamedTextColor.WHITE))
                .append(uiText("\uEF05MP " + paddedMp(mpText) + "/100\uEF06", NamedTextColor.WHITE));
    }

    private Component uiText(String text, TextColor color) {
        return Component.text(text).font(PLAYER_UI_FONT).color(color);
    }

    private void updateUiTrail(GamePlayer state) {
        if (state.hpOld <= 0) state.hpOld = state.hp;
        if (state.mpOld <= 0) state.mpOld = state.mp;
        state.hpOld = moveTowardWithDelay(state.hpOld, state.hp, 150, state, true);
        state.mpOld = moveTowardWithDelay(state.mpOld, state.mp, 150, state, false);
    }

    private int moveTowardWithDelay(int current, int target, int step, GamePlayer state, boolean hp) {
        if (current <= target) {
            if (hp) state.hpTrailDelayTicks = 0;
            else state.mpTrailDelayTicks = 0;
            return target;
        }
        int delayLimit = hp ? 5 : 15;
        if (hp) {
            if (state.hpTrailDelayTicks < delayLimit) {
                state.hpTrailDelayTicks++;
                return current;
            }
        } else if (state.mpTrailDelayTicks < delayLimit) {
            state.mpTrailDelayTicks++;
            return current;
        }
        return Math.max(target, current - step);
    }

    private String mpBarGlyph(int value, int max) {
        return barGlyph(value, max, 0xE001);
    }

    private String hpBarGlyph(int value, int max) {
        return barGlyph(value, max, 0xE0B1);
    }

    private String hpCapGlyph(int value, int max) {
        if (max <= 0 || value <= 0) return "\uE0B1";
        int fifth = Math.max(1, max / 5);
        int hpUnits = Math.max(0, Math.min(5, value / fifth));
        return switch (hpUnits) {
            case 5 -> "\uE0A6";
            case 4 -> "\uE0A5";
            case 3 -> "\uE0A4";
            case 2 -> "\uE0A3";
            case 1 -> "\uE0A2";
            default -> "\uE0B1";
        };
    }

    private String barGlyph(int value, int max, int baseCodepoint) {
        if (max <= 0) return String.valueOf((char) baseCodepoint);
        int index = Math.max(0, Math.min(79, (int) Math.ceil(value * 80.0 / max) - 1));
        return String.valueOf((char) (baseCodepoint + index));
    }

    private String paddedMp(int mpText) {
        if (mpText >= 100) return "100";
        if (mpText >= 10) return "  " + mpText;
        return "    " + mpText;
    }

    private Component systemGlyph(String glyph) {
        return Component.text(glyph, NamedTextColor.WHITE).font(SYSTEM_FONT);
    }

    private void showTitle(Player player, Component title, Component subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        player.showTitle(Title.title(
                title,
                subtitle,
                Title.Times.times(
                        Duration.ofMillis(fadeInTicks * 50L),
                        Duration.ofMillis(stayTicks * 50L),
                        Duration.ofMillis(fadeOutTicks * 50L)
                )
        ));
    }

    private void joinScoreboardTeam(Player player, Role role) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team hiderTeam = getOrCreateTeam(scoreboard, "hider", Component.text("[躲藏者] ", NamedTextColor.GREEN));
        Team seekerTeam = getOrCreateTeam(scoreboard, "seeker", Component.text("[寻找者] ", NamedTextColor.RED));
        hiderTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        hiderTeam.setCanSeeFriendlyInvisibles(false);
        seekerTeam.setCanSeeFriendlyInvisibles(false);
        hiderTeam.removeEntry(player.getName());
        seekerTeam.removeEntry(player.getName());
        (role == Role.HIDER ? hiderTeam : seekerTeam).addEntry(player.getName());
    }

    private Team getOrCreateTeam(Scoreboard scoreboard, String name, Component prefix) {
        Team team = scoreboard.getTeam(name);
        if (team == null) team = scoreboard.registerNewTeam(name);
        team.prefix(prefix);
        return team;
    }

    private void clearScoreboardTeam(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team hiderTeam = scoreboard.getTeam("hider");
        Team seekerTeam = scoreboard.getTeam("seeker");
        if (hiderTeam != null) hiderTeam.removeEntry(player.getName());
        if (seekerTeam != null) seekerTeam.removeEntry(player.getName());
    }

    private BorderRectangle prepareNextBorder(BorderStage stage) {
        return pendingBorders.computeIfAbsent(stage.remainingTicks(), ignored -> {
            BorderRectangle current = borderState == null
                    ? new BorderRectangle(getArenaSpawn().getX(), getArenaSpawn().getZ(), settings.borderInitialWidth(), settings.borderInitialDepth())
                    : borderState.current();
            double halfXRange = Math.max(0.0, (current.width() - stage.width()) / 2.0);
            double halfZRange = Math.max(0.0, (current.depth() - stage.depth()) / 2.0);
            double centerX = randomBetween(current.centerX() - halfXRange, current.centerX() + halfXRange);
            double centerZ = randomBetween(current.centerZ() - halfZRange, current.centerZ() + halfZRange);
            return new BorderRectangle(centerX, centerZ, stage.width(), stage.depth());
        });
    }

    private double randomBetween(double min, double max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    private void announceNextBorder(BorderStage stage, BorderRectangle next) {
        int seconds = Math.max(0, (remainingTicks - stage.remainingTicks()) / 20);
        Bukkit.broadcast(Component.text("下一次缩圈将在 " + seconds + " 秒后开始，绿色粒子标出了下一安全区。", NamedTextColor.YELLOW));
        for (Player player : Bukkit.getOnlinePlayers()) {
            showTitle(player, systemGlyph(SYSTEM_WORLDBORDER_WARNING), Component.empty(), 10, 70, 20);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.0f);
        }
    }

    private String describeBorder(BorderRectangle border) {
        return "X " + formatOneDecimal(border.minX()) + " 到 " + formatOneDecimal(border.maxX())
                + ", Z " + formatOneDecimal(border.minZ()) + " 到 " + formatOneDecimal(border.maxZ());
    }

    private String formatOneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void tickBorderTransition() {
        if (borderState == null) return;
        borderState.tick();
        applyVanillaBorder(borderState.current());
    }

    private void applyVanillaBorder(BorderRectangle rectangle) {
        World world = getArenaSpawn().getWorld();
        if (world == null) return;
        WorldBorder border = world.getWorldBorder();
        border.setCenter(rectangle.centerX(), rectangle.centerZ());
        border.changeSize(settings.borderResetSize(), 0L);
    }

    private void tickRectangularBorder() {
        if (borderState == null) return;
        BorderRectangle rectangle = borderState.current();
        renderBorderParticles(rectangle);
        for (GamePlayer state : players.values()) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player == null || !player.getWorld().equals(getArenaSpawn().getWorld())) continue;
            Location loc = player.getLocation();
            if (rectangle.contains(loc.getX(), loc.getZ())) {
                state.borderDamageTicks = 0;
                continue;
            }

            state.borderDamageTicks++;
            if (state.borderDamageTicks >= settings.borderOutsideDamageIntervalTicks()) {
                state.borderDamageTicks = 0;
                damageOutsideBorder(player, state);
            }
        }
    }

    private void damageOutsideBorder(Player player, GamePlayer state) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.55f, 0.8f);
        player.spawnParticle(Particle.DAMAGE_INDICATOR, player.getLocation().add(0, 1, 0), 6, 0.2, 0.4, 0.2, 0.02);
        player.sendActionBar(Component.text("你正在安全区外，返回红色粒子内。", NamedTextColor.RED));
        if (state.role == Role.HIDER) {
            damageHider(player, state, settings.borderOutsideDamage());
            return;
        }
        damagePlayer(player, state, settings.borderOutsideDamage());
    }

    private void renderBorderParticles(BorderRectangle current) {
        borderParticleTick++;
        if (borderParticleTick < settings.borderParticleIntervalTicks()) return;
        borderParticleTick = 0;
        BorderRectangle next = nextWarnedBorder();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(getArenaSpawn().getWorld())) continue;
            renderRectangleFor(player, current, new Particle.DustOptions(Color.RED, 1.7f));
            if (next != null) renderRectangleFor(player, next, new Particle.DustOptions(Color.LIME, 1.35f));
        }
    }

    private BorderRectangle nextWarnedBorder() {
        for (BorderStage stage : settings.borderStages()) {
            if (!startedBorderStages.contains(stage.remainingTicks())) {
                BorderRectangle pending = pendingBorders.get(stage.remainingTicks());
                if (pending != null) return pending;
            }
        }
        return null;
    }

    private void renderRectangleFor(Player player, BorderRectangle rectangle, Particle.DustOptions dust) {
        double spacing = Math.max(0.5, settings.borderParticleSpacing());
        double maxDistanceSquared = settings.borderParticleViewDistance() * settings.borderParticleViewDistance();
        double centerY = player.getLocation().getY() + 1.0;
        for (int yOffset = -settings.borderParticleVerticalHalfRange(); yOffset <= settings.borderParticleVerticalHalfRange(); yOffset++) {
            double y = centerY + yOffset;
            for (double x = rectangle.minX(); x <= rectangle.maxX(); x += spacing) {
                spawnBorderParticle(player, x, y, rectangle.minZ(), maxDistanceSquared, dust);
                spawnBorderParticle(player, x, y, rectangle.maxZ(), maxDistanceSquared, dust);
            }
            for (double z = rectangle.minZ(); z <= rectangle.maxZ(); z += spacing) {
                spawnBorderParticle(player, rectangle.minX(), y, z, maxDistanceSquared, dust);
                spawnBorderParticle(player, rectangle.maxX(), y, z, maxDistanceSquared, dust);
            }
        }
    }

    private void spawnBorderParticle(Player player, double x, double y, double z, double maxDistanceSquared, Particle.DustOptions dust) {
        Location location = new Location(player.getWorld(), x, y, z);
        if (location.distanceSquared(player.getLocation()) > maxDistanceSquared) return;
        player.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, dust);
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
                positiveDouble("player.vanillaDamageScale"),
                positiveInt("player.minVanillaDamage"),
                positiveInt("player.airExtraDrainPerTick"),
                positiveInt("player.airDamage"),
                positiveInt("player.airDamageIntervalTicks"),
                positiveInt("abilities.disguise.range"),
                clampedDouble("abilities.disguise.rotationSnapDegrees", 0.0, 360.0),
                loadDisguiseBlacklist(),
                positiveInt("abilities.decoy.hp"),
                nonNegativeInt("abilities.decoy.mp"),
                positiveInt("abilities.decoy.lifetimeTicks"),
                positiveDouble("abilities.decoy.throwSpeed"),
                positiveInt("abilities.decoy.maxFlightTicks"),
                nonNegativeInt("abilities.decoy.moveDelayTicks"),
                positiveInt("abilities.decoy.moveIntervalTicks"),
                positiveInt("abilities.decoy.moveDurationTicks"),
                nonNegativeDouble("abilities.decoy.moveSpeed"),
                nonNegativeInt("abilities.flyHider.mp"),
                positiveDouble("abilities.flyHider.power"),
                nonNegativeDouble("abilities.flyHider.minYBoost"),
                nonNegativeInt("abilities.flySeeker.mp"),
                positiveDouble("abilities.flySeeker.power"),
                nonNegativeDouble("abilities.flySeeker.minYBoost"),
                positiveInt("abilities.fly.unlockMinTicks"),
                positiveInt("abilities.fly.unlockFallbackTicks"),
                nonNegativeInt("abilities.attackBullet.mp"),
                positiveDouble("abilities.attackBullet.speed"),
                positiveDouble("abilities.attackBullet.hitRadius"),
                positiveInt("abilities.attackBullet.maxFlightTicks"),
                nonNegativeInt("abilities.scan.mp"),
                positiveDouble("abilities.scan.radius"),
                nonNegativeLong("abilities.scan.resultDelayTicks"),
                positiveDoubleWithFallback("worldBorder.initialWidth", "worldBorder.initialSize"),
                positiveDoubleWithFallback("worldBorder.initialDepth", "worldBorder.initialSize"),
                positiveDouble("worldBorder.resetSize"),
                nonNegativeDouble("worldBorder.damageBuffer"),
                nonNegativeInt("worldBorder.warningTicks"),
                positiveInt("worldBorder.outsideDamage"),
                positiveInt("worldBorder.outsideDamageIntervalTicks"),
                positiveInt("worldBorder.particleIntervalTicks"),
                positiveDouble("worldBorder.particleSpacing"),
                positiveDouble("worldBorder.particleViewDistance"),
                nonNegativeInt("worldBorder.particleVerticalHalfRange"),
                loadBorderStages()
        );
    }

    private List<BorderStage> loadBorderStages() {
        List<BorderStage> stages = new ArrayList<>();
        for (Map<?, ?> map : getConfig().getMapList("worldBorder.stages")) {
            Object remainingTicks = map.get("remainingTicks");
            Object size = map.get("size");
            Object width = map.get("width");
            Object depth = map.get("depth");
            Object seconds = map.get("seconds");
            if (remainingTicks instanceof Number tickNumber) {
                double fallbackSize = size instanceof Number sizeNumber ? sizeNumber.doubleValue() : 1.0;
                double widthValue = width instanceof Number widthNumber ? widthNumber.doubleValue() : fallbackSize;
                double depthValue = depth instanceof Number depthNumber ? depthNumber.doubleValue() : fallbackSize;
                long secondsValue = seconds instanceof Number secondsNumber ? Math.max(0L, secondsNumber.longValue()) : 3L;
                stages.add(new BorderStage(Math.max(0, tickNumber.intValue()), Math.max(1.0, widthValue), Math.max(1.0, depthValue), secondsValue));
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

    private double positiveDoubleWithFallback(String path, String fallbackPath) {
        if (getConfig().contains(path)) return positiveDouble(path);
        return positiveDouble(fallbackPath);
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
            double vanillaDamageScale,
            int minVanillaDamage,
            int airExtraDrainPerTick,
            int airDamage,
            int airDamageIntervalTicks,
            int disguiseRange,
            double disguiseRotationSnapDegrees,
            Set<Material> disguiseBlacklist,
            int decoyHp,
            int decoyMp,
            int decoyLifetimeTicks,
            double decoyThrowSpeed,
            int decoyMaxFlightTicks,
            int decoyMoveDelayTicks,
            int decoyMoveIntervalTicks,
            int decoyMoveDurationTicks,
            double decoyMoveSpeed,
            int hiderFlyMp,
            double hiderFlyPower,
            double hiderFlyMinYBoost,
            int seekerFlyMp,
            double seekerFlyPower,
            double seekerFlyMinYBoost,
            int flyUnlockMinTicks,
            int flyUnlockFallbackTicks,
            int attackBulletMp,
            double attackBulletSpeed,
            double attackBulletHitRadius,
            int attackBulletMaxFlightTicks,
            int scanMp,
            double scanRadius,
            long scanResultDelayTicks,
            double borderInitialWidth,
            double borderInitialDepth,
            double borderResetSize,
            double borderDamageBuffer,
            int borderWarningTicks,
            int borderOutsideDamage,
            int borderOutsideDamageIntervalTicks,
            int borderParticleIntervalTicks,
            double borderParticleSpacing,
            double borderParticleViewDistance,
            int borderParticleVerticalHalfRange,
            List<BorderStage> borderStages
    ) {
    }

    private record BorderStage(int remainingTicks, double width, double depth, long seconds) {
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
        private int hpOld;
        private int mpOld;
        private int hpTrailDelayTicks;
        private int mpTrailDelayTicks;
        private boolean disguised;
        private boolean rotationLocked;
        private float lockedYaw;
        private float visualYaw;
        private BlockData disguiseData;
        private BlockDisplay display;
        private boolean flyLocked;
        private int flyLockTicks;
        private int borderDamageTicks;
        private int airDamageTicks;
        private GamePlayer(UUID uuid, Role role, int hp, int mp) {
            this.uuid = uuid;
            this.role = role;
            this.hp = hp;
            this.mp = mp;
            this.hpOld = hp;
            this.mpOld = mp;
            this.disguiseData = Bukkit.createBlockData(Material.AIR);
        }
    }

    private static final class Decoy {
        private final UUID owner;
        private final BlockDisplay display;
        private int hp;
        private int remainingTicks;
        private float yaw;
        private int nextMoveTicks;
        private int moveTicksRemaining;
        private Vector moveDirection = new Vector(0, 0, 0);

        private Decoy(UUID owner, BlockDisplay display, int hp, int remainingTicks, float yaw, int nextMoveTicks) {
            this.owner = owner;
            this.display = display;
            this.hp = hp;
            this.remainingTicks = remainingTicks;
            this.yaw = yaw;
            this.nextMoveTicks = nextMoveTicks;
        }
    }

    private static final class DecoyProjectile {
        private final UUID owner;
        private final BlockData blockData;
        private final float yaw;
        private int age;

        private DecoyProjectile(UUID owner, BlockData blockData, float yaw) {
            this.owner = owner;
            this.blockData = blockData;
            this.yaw = yaw;
        }
    }

    private static final class AttackBullet {
        private final UUID owner;
        private final ItemDisplay display;
        private final Vector velocity;
        private int age;

        private AttackBullet(UUID owner, ItemDisplay display, Vector velocity) {
            this.owner = owner;
            this.display = display;
            this.velocity = velocity;
        }
    }

    private static final class ScanEffect {
        private final UUID owner;
        private final ItemDisplay display;
        private final Location origin;
        private int age;
        private boolean caught;

        private ScanEffect(UUID owner, ItemDisplay display, Location origin) {
            this.owner = owner;
            this.display = display;
            this.origin = origin;
        }
    }

    private record BorderRectangle(double centerX, double centerZ, double width, double depth) {
        private boolean contains(double x, double z) {
            return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
        }

        private double minX() {
            return centerX - width / 2.0;
        }

        private double maxX() {
            return centerX + width / 2.0;
        }

        private double minZ() {
            return centerZ - depth / 2.0;
        }

        private double maxZ() {
            return centerZ + depth / 2.0;
        }
    }

    private static final class BorderState {
        private BorderRectangle current;
        private BorderRectangle start;
        private BorderRectangle target;
        private long moveTicksRemaining;
        private long moveTotalTicks;

        private BorderState(double centerX, double centerZ, double width, double depth) {
            this.current = new BorderRectangle(centerX, centerZ, width, depth);
            this.start = current;
            this.target = current;
        }

        private BorderRectangle current() {
            return current;
        }

        private void beginMove(BorderRectangle target, long ticks) {
            this.start = current;
            this.target = target;
            this.moveTotalTicks = Math.max(1L, ticks);
            this.moveTicksRemaining = this.moveTotalTicks;
        }

        private void tick() {
            if (moveTicksRemaining <= 0) return;
            long elapsed = moveTotalTicks - moveTicksRemaining + 1;
            double progress = Math.max(0.0, Math.min(1.0, elapsed / (double) moveTotalTicks));
            current = new BorderRectangle(
                    lerp(start.centerX(), target.centerX(), progress),
                    lerp(start.centerZ(), target.centerZ(), progress),
                    lerp(start.width(), target.width(), progress),
                    lerp(start.depth(), target.depth(), progress)
            );
            moveTicksRemaining--;
            if (moveTicksRemaining <= 0) current = target;
        }

        private static double lerp(double start, double end, double progress) {
            return start + (end - start) * progress;
        }
    }
}
