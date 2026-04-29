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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Hide_and_seek extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int MAX_HP = 2000;
    private static final int MAX_MP = 2000;
    private static final int DAMAGE_PER_HIT = 400;
    private static final int GAME_TICKS = 12000;
    private static final int SEEKER_RELEASE_AT = 11400;
    private static final int SEEKER_COUNT = 3;
    private static final int DECOY_HP = 100;
    private static final int DECOY_MP = 1200;
    private static final int DECOY_LIFETIME = 600;
    private static final int HIDER_FLY_MP = 1600;
    private static final int SEEKER_FLY_MP = 300;
    private static final int ATTACK_MP = 60;
    private static final int SCAN_MP = 1300;
    private static final double SCAN_RADIUS = 10.0;

    private final Map<UUID, GamePlayer> players = new HashMap<>();
    private final List<Decoy> decoys = new ArrayList<>();
    private NamespacedKey abilityKey;
    private NamespacedKey noDropKey;
    private NamespacedKey attackKey;
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
            sender.sendMessage("/has <start|stop|setspawn|status>");
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
            case "status" -> sender.sendMessage("状态: " + phase + ", 玩家: " + players.size() + ", 剩余 tick: " + remainingTicks);
            default -> sender.sendMessage("/has <start|stop|setspawn|status>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("start", "stop", "setspawn", "status").stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
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
        remainingTicks = GAME_TICKS;
        phase = GamePhase.RUNNING;
        bossBar = Bukkit.createBossBar("躲猫猫 10:00", BarColor.GREEN, BarStyle.SEGMENTED_20);
        bossBar.setProgress(1.0);

        Collections.shuffle(online);
        int seekerCount = online.size() == 1 ? 1 : Math.min(SEEKER_COUNT, online.size() - 1);
        for (int i = 0; i < online.size(); i++) {
            Player player = online.get(i);
            Role role = i < seekerCount ? Role.SEEKER : Role.HIDER;
            GamePlayer state = new GamePlayer(player.getUniqueId(), role);
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

        if (remainingTicks == SEEKER_RELEASE_AT) {
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
            state.hp = Math.min(MAX_HP, state.hp + 5);
            state.mp = Math.min(MAX_MP, state.mp + 10);
            if (state.role == Role.HIDER) tickDisguise(player, state);
            player.setFoodLevel(20);
            player.setSaturation(20);
            player.sendActionBar(Component.text(roleName(state.role) + "  HP " + state.hp + "/" + MAX_HP + "  MP " + state.mp + "/" + MAX_MP));
        }
    }

    private void tickDisguise(Player player, GamePlayer state) {
        if (state.display == null || state.display.isDead()) {
            spawnDisguiseDisplay(player, state);
        }

        Location loc = player.getLocation().clone();
        float visualYaw = state.rotationLocked ? state.lockedYaw : loc.getYaw();
        loc.setPitch(0f);
        loc.setYaw(0f);
        if (!state.rotationLocked) state.lockedYaw = visualYaw;
        state.display.teleport(loc);
        applyDisguiseTransform(state.display, visualYaw);

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
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, remainingTicks / (double) GAME_TICKS)));
    }

    private void updateBorder() {
        if (remainingTicks == 10800) shrinkBorder(90);
        if (remainingTicks == 8400) shrinkBorder(75);
        if (remainingTicks == 6000) shrinkBorder(60);
        if (remainingTicks == 3600) shrinkBorder(40);
    }

    private void shrinkBorder(double size) {
        WorldBorder border = getArenaSpawn().getWorld().getWorldBorder();
        border.setSize(size, 3L);
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
            if (state.display != null) state.display.remove();
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.INVISIBILITY);
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                player.getInventory().remove(Material.CARROT_ON_A_STICK);
            }
        }
        for (Decoy decoy : decoys) {
            if (decoy.display != null) decoy.display.remove();
        }

        decoys.clear();
        players.clear();
        World world = getArenaSpawn().getWorld();
        if (world != null) world.getWorldBorder().setSize(100000.0);
        phase = GamePhase.IDLE;
        remainingTicks = 0;
        if (announce) Bukkit.broadcast(Component.text("躲猫猫已停止。"));
    }

    private void setupWorldBorder(Location center) {
        WorldBorder border = center.getWorld().getWorldBorder();
        border.setCenter(center);
        border.setSize(128.0);
        border.setDamageBuffer(0.0);
    }

    private void spawnDisguiseDisplay(Player player, GamePlayer state) {
        BlockData data = state.disguiseData == null ? Bukkit.createBlockData(Material.AIR) : state.disguiseData;
        state.display = player.getWorld().spawn(player.getLocation(), BlockDisplay.class, display -> {
            display.setBlock(data);
            display.setPersistent(false);
            display.setTeleportDuration(1);
            display.setRotation(0f, 0f);
            applyDisguiseTransform(display, state.lockedYaw);
        });
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
            case "fly_hider" -> useFly(player, state, Role.HIDER, HIDER_FLY_MP, 1.85);
            case "attack_bullet" -> useAttackBullet(player, state);
            case "scan" -> useScan(player, state);
            case "fly_seeker" -> useFly(player, state, Role.SEEKER, SEEKER_FLY_MP, 1.55);
            default -> {
            }
        }
    }

    private void useDisguise(Player player, GamePlayer state) {
        if (state.role != Role.HIDER) return;
        Block target = player.getTargetBlockExact(15, FluidCollisionMode.NEVER);
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
        return Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.BARRIER, Material.STRUCTURE_VOID,
                Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.JIGSAW,
                Material.STRUCTURE_BLOCK).contains(material);
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
        state.lockedYaw = player.getLocation().getYaw();
        player.sendMessage(state.rotationLocked ? "已锁定伪装旋转。" : "已解除旋转锁定。");
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.3f);
    }

    private void useDecoy(Player player, GamePlayer state) {
        if (state.role != Role.HIDER) return;
        if (!state.disguised || state.disguiseData == null || state.disguiseData.getMaterial().isAir()) {
            fail(player, "需要先伪装才能放置诱饵。");
            return;
        }
        if (!consumeMp(player, state, DECOY_MP)) return;

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
        decoys.add(new Decoy(player.getUniqueId(), display, DECOY_HP, DECOY_LIFETIME));
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 0.8f);
    }

    private void useFly(Player player, GamePlayer state, Role requiredRole, int cost, double power) {
        if (state.role != requiredRole) return;
        if (!consumeMp(player, state, cost)) return;
        if (requiredRole == Role.HIDER) releaseDisguise(player, state);
        Vector velocity = player.getEyeLocation().getDirection().normalize().multiply(power);
        velocity.setY(Math.max(velocity.getY(), 0.45));
        player.setVelocity(velocity);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 18, 0.25, 0.15, 0.25, 0.02);
        player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1f, 1f);
    }

    private void useAttackBullet(Player player, GamePlayer state) {
        if (state.role != Role.SEEKER) return;
        if (!consumeMp(player, state, ATTACK_MP)) return;
        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(2.2));
        snowball.getPersistentDataContainer().set(attackKey, PersistentDataType.BYTE, (byte) 1);
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 1.4f);
    }

    private void useScan(Player player, GamePlayer state) {
        if (state.role != Role.SEEKER) return;
        if (!consumeMp(player, state, SCAN_MP)) return;
        player.getWorld().spawnParticle(Particle.SONIC_BOOM, player.getLocation().add(0, 1, 0), 1);
        player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.4f);
        boolean found = playersWithRole(Role.HIDER).stream()
                .anyMatch(hider -> hider.getWorld().equals(player.getWorld()) && hider.getLocation().distance(player.getLocation()) <= SCAN_RADIUS);
        Bukkit.getScheduler().runTaskLater(this, () -> player.sendMessage(found ? "扫描范围内发现躲藏者。" : "扫描范围内没有发现躲藏者。"), 20L);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!projectile.getPersistentDataContainer().has(attackKey, PersistentDataType.BYTE)) return;
        if (event.getHitEntity() instanceof Player player) damageIfHider(player);
        for (Entity nearby : projectile.getNearbyEntities(1.75, 1.75, 1.75)) {
            if (nearby instanceof Player player) damageIfHider(player);
        }
        damageNearbyDecoy(projectile.getLocation(), DAMAGE_PER_HIT);
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
            damageHider(player, state, DAMAGE_PER_HIT);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Snowball snowball
                && snowball.getPersistentDataContainer().has(attackKey, PersistentDataType.BYTE)
                && event.getEntity() instanceof Player player) {
            event.setCancelled(true);
            damageIfHider(player);
        }
    }

    private void damageIfHider(Player player) {
        GamePlayer target = players.get(player.getUniqueId());
        if (target != null && target.role == Role.HIDER) damageHider(player, target, DAMAGE_PER_HIT);
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
                    && decoy.display.getLocation().distance(location) <= 1.75) {
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
        state.hp = MAX_HP;
        state.mp = MAX_MP;
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
    public void onMove(PlayerMoveEvent event) {
        if (phase != GamePhase.RUNNING || remainingTicks <= SEEKER_RELEASE_AT) return;
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
        if (bossBar != null) bossBar.addPlayer(event.getPlayer());
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
        private int hp = MAX_HP;
        private int mp = MAX_MP;
        private boolean disguised;
        private boolean rotationLocked;
        private float lockedYaw;
        private BlockData disguiseData;
        private BlockDisplay display;

        private GamePlayer(UUID uuid, Role role) {
            this.uuid = uuid;
            this.role = role;
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
