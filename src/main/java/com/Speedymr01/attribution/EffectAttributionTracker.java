package com.Speedymr01.attribution;

import com.Speedymr01.SmarterKeepInventory;
import org.bukkit.Bukkit;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tracks who is responsible for poison/wither effects (transient) and for ignitions
 * (who set a player on fire). All records are short-lived, main-thread only.
 */
public class EffectAttributionTracker implements Listener {

    /** Max ticks between staging a pending applier and the effect being committed. */
    private static final int PENDING_TOLERANCE_TICKS = 2;

    private static final class ApplierRecord {
        final UUID applier;
        final int expiresAt;

        ApplierRecord(UUID applier, int expiresAt) {
            this.applier = applier;
            this.expiresAt = expiresAt;
        }
    }

    private static final class PendingApply {
        final UUID applier;
        final int tick;

        PendingApply(UUID applier, int tick) {
            this.applier = applier;
            this.tick = tick;
        }
    }

    private static final class IgnitionRecord {
        final UUID source;
        final int expiresAt;

        IgnitionRecord(UUID source, int expiresAt) {
            this.source = source;
            this.expiresAt = expiresAt;
        }
    }

    private record PendingIgnition(UUID victim, UUID source, int dueTick, int beforeTicks, boolean requireIncrease) {
    }

    private final SmarterKeepInventory plugin;
    private final TrapManager trapManager;
    private final boolean indirectEnabled;
    private final boolean selfAttribution;
    private final boolean fireEnabled;
    private final int ignitionExpiryTicks;

    private final Map<UUID, Map<PotionEffectType, ApplierRecord>> appliers = new ConcurrentHashMap<>();
    private final Map<UUID, List<PendingApply>> pendingApplies = new ConcurrentHashMap<>();
    private final Map<UUID, IgnitionRecord> ignitions = new ConcurrentHashMap<>();
    private final Queue<PendingIgnition> pendingIgnitions = new ConcurrentLinkedQueue<>();

    private final BukkitTask ignitionTask;

    public EffectAttributionTracker(SmarterKeepInventory plugin, TrapManager trapManager) {
        this.plugin = plugin;
        this.trapManager = trapManager;
        this.indirectEnabled = plugin.getConfig().getBoolean("attribution.indirect.enabled", true);
        this.selfAttribution = plugin.getConfig().getBoolean("attribution.indirect.self-attribution", false);
        this.fireEnabled = plugin.getConfig().getBoolean("attribution.fire.enabled", true);
        long seconds = Math.max(1L, plugin.getConfig().getLong("attribution.fire.ignition-expiry-seconds", 10L));
        this.ignitionExpiryTicks = (int) (seconds * 20L);
        this.ignitionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processPendingIgnitions, 1L, 1L);
    }

    // ------------------------------------------------------------------
    // Lookups used by KillAttributionService
    // ------------------------------------------------------------------

    /** Applier of an active poison/wither effect on the victim, or null. */
    public UUID getApplier(UUID victimId, PotionEffectType type) {
        if (!indirectEnabled) {
            return null;
        }
        Map<PotionEffectType, ApplierRecord> records = appliers.get(victimId);
        if (records == null) {
            return null;
        }
        ApplierRecord record = records.get(type);
        if (record == null) {
            return null;
        }
        if (Bukkit.getCurrentTick() > record.expiresAt) {
            records.remove(type);
            return null;
        }
        if (!selfAttribution && record.applier.equals(victimId)) {
            return null;
        }
        return record.applier;
    }

    /** Player who last ignited the victim, or null. */
    public UUID getIgniter(UUID victimId) {
        if (!fireEnabled) {
            return null;
        }
        IgnitionRecord record = ignitions.get(victimId);
        if (record == null) {
            return null;
        }
        if (Bukkit.getCurrentTick() > record.expiresAt) {
            ignitions.remove(victimId);
            return null;
        }
        if (!selfAttribution && record.source.equals(victimId)) {
            return null;
        }
        return record.source;
    }

    // ------------------------------------------------------------------
    // Effect applier capture
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        UUID applier = resolvePotionApplier(potion);
        if (applier == null) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player player) {
                stagePending(player.getUniqueId(), applier, tick);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLingeringPotionSplash(LingeringPotionSplashEvent event) {
        // Lingering potions don't apply effects on splash; effects come from the cloud.
        // Copy the launch-stamped owner onto the cloud so cloud ticks stay attributed.
        AreaEffectCloud cloud = event.getAreaEffectCloud();
        if (cloud == null) {
            return;
        }
        UUID owner = resolvePotionApplier(event.getEntity());
        if (owner != null) {
            trapManager.stampOwner(cloud, owner);
        }
    }

    /**
     * Resolves the credited applier of a thrown potion: player thrower, owned dispenser
     * (via shooter block or stamped PDC), or null.
     */
    private UUID resolvePotionApplier(ThrownPotion potion) {
        ProjectileSource shooter = potion.getShooter();
        if (shooter instanceof Player player) {
            return player.getUniqueId();
        }
        if (shooter instanceof BlockProjectileSource blockSource) {
            UUID owner = trapManager.getDispenserOwner(blockSource.getBlock());
            if (owner != null) {
                if (trapManager.getOwnerFromPDC(potion) == null) {
                    trapManager.stampOwner(potion, owner);
                }
                return owner;
            }
            return null;
        }
        return trapManager.getOwnerFromPDC(potion);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAreaEffectCloudApply(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        UUID applier = null;
        ProjectileSource source = cloud.getSource();
        if (source instanceof Player player) {
            applier = player.getUniqueId();
        } else if (source instanceof BlockProjectileSource blockSource) {
            applier = trapManager.getDispenserOwner(blockSource.getBlock());
        }
        if (applier == null) {
            applier = trapManager.getOwnerFromPDC(cloud);
        }
        if (applier == null) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player player) {
                stagePending(player.getUniqueId(), applier, tick);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Entity damager = event.getDamager();

        // Tipped-arrow effect staging.
        if (damager instanceof AbstractArrow arrow) {
            UUID applier = resolveArrowApplier(arrow);
            if (applier != null) {
                stagePending(victim.getUniqueId(), applier, Bukkit.getCurrentTick());
            }
        }

        // Ignition capture: who set the victim on fire (checked next tick).
        UUID igniter = resolveIgniter(damager, victim);
        if (igniter != null) {
            // Fireballs may apply the fire before this event fires, so they only require
            // the victim to be burning next tick; other sources must add new burn ticks.
            pendingIgnitions.add(new PendingIgnition(
                    victim.getUniqueId(), igniter, Bukkit.getCurrentTick() + 1,
                    victim.getFireTicks(), !(damager instanceof SmallFireball)));
        }
    }

    private UUID resolveArrowApplier(AbstractArrow arrow) {
        ProjectileSource shooter = arrow.getShooter();
        if (shooter instanceof Player player) {
            return player.getUniqueId();
        }
        if (shooter instanceof BlockProjectileSource blockSource) {
            return trapManager.getDispenserOwner(blockSource.getBlock());
        }
        return trapManager.getOwnerFromPDC(arrow);
    }

    /**
     * Resolves the credited source of a damaging hit for ignition purposes:
     * player melee, player-shot projectile, or dispenser/device entity via PDC.
     */
    private UUID resolveIgniter(Entity damager, Player victim) {
        UUID credit = null;
        if (damager instanceof Player player) {
            credit = player.getUniqueId();
        } else if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            credit = shooter.getUniqueId();
        } else {
            credit = trapManager.getOwnerFromPDC(damager);
        }
        if (credit == null || credit.equals(victim.getUniqueId())) {
            return null;
        }
        return credit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        UUID victimId = victim.getUniqueId();
        PotionEffectType type = event.getModifiedType();
        if (type == null) {
            return;
        }

        switch (event.getAction()) {
            case ADDED, CHANGED -> {
                if (!indirectEnabled) {
                    return;
                }
                if (!type.equals(PotionEffectType.POISON) && !type.equals(PotionEffectType.WITHER)) {
                    return;
                }
                EntityPotionEffectEvent.Cause cause = event.getCause();
                if (cause != EntityPotionEffectEvent.Cause.POTION_SPLASH
                        && cause != EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD
                        && cause != EntityPotionEffectEvent.Cause.ARROW) {
                    // Drink/food/attack/rose/command/plugin/... -> no player applier.
                    removeApplier(victimId, type);
                    return;
                }
                UUID applier = consumePending(victimId, Bukkit.getCurrentTick());
                if (applier == null) {
                    removeApplier(victimId, type);
                    return;
                }
                int duration = event.getNewEffect() != null ? event.getNewEffect().getDuration() : 0;
                appliers.computeIfAbsent(victimId, ignored -> new ConcurrentHashMap<>())
                        .put(type, new ApplierRecord(applier, Bukkit.getCurrentTick() + Math.max(1, duration)));
            }
            case REMOVED, CLEARED -> removeApplier(victimId, type);
            default -> {
            }
        }
    }

    private void removeApplier(UUID victimId, PotionEffectType type) {
        Map<PotionEffectType, ApplierRecord> records = appliers.get(victimId);
        if (records != null) {
            records.remove(type);
            if (records.isEmpty()) {
                appliers.remove(victimId);
            }
        }
    }

    // ------------------------------------------------------------------
    // Pending staging + ignition ticker
    // ------------------------------------------------------------------

    private void stagePending(UUID victimId, UUID applier, int tick) {
        pendingApplies.computeIfAbsent(victimId, ignored -> new ArrayList<>())
                .add(new PendingApply(applier, tick));
    }

    private UUID consumePending(UUID victimId, int now) {
        List<PendingApply> list = pendingApplies.get(victimId);
        if (list == null) {
            return null;
        }
        UUID found = null;
        synchronized (list) {
            list.removeIf(pending -> now - pending.tick > PENDING_TOLERANCE_TICKS);
            for (PendingApply pending : list) {
                if (now - pending.tick <= PENDING_TOLERANCE_TICKS) {
                    found = pending.applier;
                }
            }
            // Do NOT clear: one splash can commit several effects (poison + wither).
            // Stale entries expire via the tolerance pruning above.
        }
        return found;
    }

    private void processPendingIgnitions() {
        if (pendingIgnitions.isEmpty()) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        Queue<PendingIgnition> deferred = new ConcurrentLinkedQueue<>();
        PendingIgnition pending;
        while ((pending = pendingIgnitions.poll()) != null) {
            if (pending.dueTick() > now) {
                deferred.add(pending);
                continue;
            }
            Player victim = Bukkit.getPlayer(pending.victim());
            if (victim == null || !victim.isOnline()) {
                continue;
            }
            int after = victim.getFireTicks();
            boolean ignited = after > 0
                    && (!pending.requireIncrease() || after > Math.max(pending.beforeTicks(), 0));
            if (ignited) {
                ignitions.put(pending.victim(), new IgnitionRecord(
                        pending.source(), now + ignitionExpiryTicks));
            }
        }
        pendingIgnitions.addAll(deferred);
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        appliers.remove(id);
        pendingApplies.remove(id);
        ignitions.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        appliers.remove(id);
        pendingApplies.remove(id);
        ignitions.remove(id);
    }

    /** Called from onDisable. */
    public void shutdown() {
        if (ignitionTask != null) {
            ignitionTask.cancel();
        }
        pendingIgnitions.clear();
    }
}
