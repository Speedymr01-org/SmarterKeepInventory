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
        UUID victimId = victim.getUniqueId();
        EntityDamageEvent lastDamage = victim.getLastDamageCause();
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

        // 3. Poison/wither ticks -> effect applier record.
        if (cause == EntityDamageEvent.DamageCause.POISON || cause == EntityDamageEvent.DamageCause.WITHER) {
            PotionEffectType type = cause == EntityDamageEvent.DamageCause.POISON
                    ? PotionEffectType.POISON : PotionEffectType.WITHER;
            UUID applier = effectTracker.getApplier(victimId, type);
            if (applier != null) {
                String label = type.equals(PotionEffectType.POISON) ? "poison" : "wither";
                return new KillCredit(applier, KillSource.INDIRECT, label);
            }
        }

        // 4. Fire/burn deaths -> ignition record.
        if (cause == EntityDamageEvent.DamageCause.FIRE || cause == EntityDamageEvent.DamageCause.FIRE_TICK) {
            UUID igniter = effectTracker.getIgniter(victimId);
            if (igniter != null) {
                return new KillCredit(igniter, KillSource.FIRE, null);
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
            if (isAnvil(material)) {
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

    private boolean isAnvil(Material material) {
        return material == Material.ANVIL
                || material == Material.CHIPPED_ANVIL
                || material == Material.DAMAGED_ANVIL;
    }

    /** %effect% placeholder for INDIRECT kills from potion entities. */
    private String effectForDamager(Entity damager) {
        if (damager instanceof ThrownPotion potion) {
            boolean poison = false;
            boolean wither = false;
            boolean harming = false;
            for (PotionEffect effect : potion.getEffects()) {
                PotionEffectType type = effect.getType();
                if (type.equals(PotionEffectType.POISON)) {
                    poison = true;
                } else if (type.equals(PotionEffectType.WITHER)) {
                    wither = true;
                } else if (type.equals(PotionEffectType.INSTANT_DAMAGE)) {
                    harming = true;
                }
            }
            if (poison) {
                return "poison";
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
