package com.Speedymr01.attribution;

import com.Speedymr01.SmarterKeepInventory;
import org.bukkit.Material;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

/**
 * Single resolution point for trap/indirect kill attribution.
 * Cause-first: the lethal cause's own record (PDC owner, block owner, effect applier,
 * igniter) wins; {@code getKiller()} remains the fallback handled by the caller.
 */
public class KillAttributionService {

    /** Source categories, matching the messages.kills.* config keys. */
    public enum KillSource {
        DRIPSTONE("dripstone"),
        ANVIL("anvil"),
        SUFFOCATION("suffocation"),
        INDIRECT("indirect"),
        ARROW("arrow"),
        FIRE("fire"),
        EXPLOSION("explosion"),
        GENERIC("generic");

        private final String configKey;

        KillSource(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return configKey;
        }
    }

    /** A resolved new-attribution kill. {@code effectLabel} is only set for INDIRECT. */
    public record KillCredit(UUID credited, KillSource source, String effectLabel) {
    }

    private final SmarterKeepInventory plugin;
    private final TrapManager trapManager;
    private final EffectAttributionTracker effectTracker;

    public KillAttributionService(SmarterKeepInventory plugin, TrapManager trapManager,
                                  EffectAttributionTracker effectTracker) {
        this.plugin = plugin;
        this.trapManager = trapManager;
        this.effectTracker = effectTracker;
    }

    /**
     * Resolves a trap/indirect attribution for a death, or null when the death has no
     * new attribution (in which case the caller keeps the existing getKiller() behavior).
     */
    public KillCredit resolveKill(Player victim) {
        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        KillCredit credit = resolve(victim, lastDamage);
        if (plugin.getConfig().getBoolean("debug", false)) {
            String damager = lastDamage instanceof EntityDamageByEntityEvent byEntity
                    ? byEntity.getDamager().getType().name() : "-";
            plugin.getLogger().info("[attribution] " + victim.getName()
                    + " cause=" + (lastDamage == null ? "UNKNOWN" : lastDamage.getCause())
                    + " damager=" + damager
                    + " -> " + (credit == null ? "no attribution (fallback to killer/KI)"
                    : credit.source().configKey() + " by " + credit.credited()));
        }
        return credit;
    }

    private KillCredit resolve(Player victim, EntityDamageEvent lastDamage) {
        UUID victimId = victim.getUniqueId();
        EntityDamageEvent.DamageCause cause = lastDamage != null ? lastDamage.getCause() : null;

        // 1. Lethal damage dealt by a trap-owner-stamped entity (falling block, dispenser projectile/TNT).
        if (lastDamage instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            UUID owner = trapManager.getOwnerFromPDC(damager);
            if (owner != null && !isSelf(owner, victimId)) {
                return new KillCredit(owner, categoryForDamager(damager), effectForDamager(damager));
            }
        }

        // 2. Suffocation inside owned blocks (head block first, then feet).
        if (cause == EntityDamageEvent.DamageCause.SUFFOCATION) {
            UUID owner = trapManager.getBlockOwner(victim.getEyeLocation().getBlock());
            if (owner == null) {
                owner = trapManager.getBlockOwner(victim.getLocation().getBlock());
            }
            if (owner != null && !isSelf(owner, victimId)) {
                return new KillCredit(owner, KillSource.SUFFOCATION, null);
            }
        }

        // 3. Wither ticks -> effect applier record. (Poison is never checked: it cannot kill —
        //    the victim is always left at 1 HP.)
        if (cause == EntityDamageEvent.DamageCause.WITHER) {
            UUID applier = effectTracker.getApplier(victimId, PotionEffectType.WITHER);
            if (applier != null) {
                return new KillCredit(applier, KillSource.INDIRECT, "wither");
            }
        }

        // 4. Fire/burn deaths -> ignition record.
        if (cause == EntityDamageEvent.DamageCause.FIRE || cause == EntityDamageEvent.DamageCause.FIRE_TICK) {
            UUID igniter = effectTracker.getIgniter(victimId);
            if (igniter != null) {
                return new KillCredit(igniter, KillSource.FIRE, null);
            }
        }

        // 5. Bed / respawn anchor explosions -> placer of the consumed block. Their damage is
        //    applied before any explosion event fires, so this is the only point where the
        //    responsibility can be recovered (TNT/entity explosions are covered by step 1).
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            UUID owner = trapManager.getExplosionBlockOwner(victim.getLocation());
            if (owner != null && !isSelf(owner, victimId)) {
                return new KillCredit(owner, KillSource.EXPLOSION, null);
            }
        }

        return null;
    }

    private boolean isSelf(UUID credit, UUID victimId) {
        return credit.equals(victimId)
                && !plugin.getConfig().getBoolean("attribution.indirect.self-attribution", false);
    }

    private KillSource categoryForDamager(Entity damager) {
        if (damager instanceof FallingBlock fallingBlock) {
            Material material = fallingBlock.getBlockData().getMaterial();
            if (material == Material.POINTED_DRIPSTONE) {
                return KillSource.DRIPSTONE;
            }
            if (TrapRules.isAnvil(material)) {
                return KillSource.ANVIL;
            }
            return KillSource.SUFFOCATION;
        }
        if (damager instanceof ThrownPotion) {
            return KillSource.INDIRECT;
        }
        if (damager instanceof AbstractArrow) {
            return KillSource.ARROW;
        }
        if (damager instanceof SmallFireball) {
            return KillSource.FIRE;
        }
        if (damager instanceof TNTPrimed) {
            return KillSource.EXPLOSION;
        }
        return KillSource.GENERIC;
    }

    /** %effect% placeholder for INDIRECT kills from potion entities. */
    private String effectForDamager(Entity damager) {
        if (damager instanceof ThrownPotion potion) {
            boolean wither = false;
            boolean harming = false;
            for (PotionEffect effect : potion.getEffects()) {
                PotionEffectType type = effect.getType();
                if (type.equals(PotionEffectType.WITHER)) {
                    wither = true;
                } else if (type.equals(PotionEffectType.INSTANT_DAMAGE)) {
                    harming = true;
                }
            }
            if (wither) {
                return "wither";
            }
            if (harming) {
                return "harming potion";
            }
        }
        return "potion";
    }
}
