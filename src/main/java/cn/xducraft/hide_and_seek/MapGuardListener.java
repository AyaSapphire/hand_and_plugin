package cn.xducraft.hide_and_seek;

import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

final class MapGuardListener implements Listener {
    private final MapGuardService mapGuard;

    MapGuardListener(MapGuardService mapGuard) {
        this.mapGuard = mapGuard;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!shouldBlock(event.getPlayer().getGameMode())) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.PHYSICAL) return;

        if (event.getClickedBlock() == null) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!shouldBlock(event.getPlayer().getGameMode())) return;
        if (!isProtectedHanging(event.getRightClicked())) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (!shouldBlock(event.getPlayer().getGameMode())) return;
        if (!isProtectedHanging(event.getRightClicked())) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!isProtectedHanging(event.getEntity())) return;
        if (!(event.getDamager() instanceof org.bukkit.entity.Player player)) return;
        if (!shouldBlock(player.getGameMode())) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof org.bukkit.entity.Player player)) return;
        if (!isProtectedHanging(event.getEntity())) return;
        if (!shouldBlock(player.getGameMode())) return;
        event.setCancelled(true);
    }

    private boolean shouldBlock(GameMode mode) {
        return mapGuard.isEnabled() && mode != GameMode.CREATIVE;
    }

    private boolean isProtectedHanging(Entity entity) {
        return entity instanceof Painting
                || entity instanceof ItemFrame
                || entity instanceof GlowItemFrame;
    }
}
