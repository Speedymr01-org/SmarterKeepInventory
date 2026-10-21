package com.Speedymr01.attribution;

import org.bukkit.Material;
import org.bukkit.World;

/**
 * Material / world rules used by the trap attribution code. Pure lookups (no server state) so the
 * precedence-sensitive parts can be unit-tested without spinning up Bukkit.
 */
public final class TrapRules {

    private TrapRules() {
    }

    public static boolean isAnvil(Material material) {
        return material == Material.ANVIL
                || material == Material.CHIPPED_ANVIL
                || material == Material.DAMAGED_ANVIL;
    }

    /** All 16 bed colours; matched by name to avoid depending on the block registry. */
    public static boolean isBed(Material material) {
        return material.name().endsWith("_BED");
    }

    /**
     * Blocks whose explosion is credited to the placer: TNT anywhere, beds only in worlds where they
     * explode (Nether/End) and respawn anchors only outside the Nether.
     */
    public static boolean isCreditedExplosive(Material material, World.Environment environment) {
        if (material == Material.TNT) {
            return true;
        }
        if (environment == null) {
            return false;
        }
        if (isBed(material)) {
            return environment == World.Environment.NETHER || environment == World.Environment.THE_END;
        }
        if (material == Material.RESPAWN_ANCHOR) {
            return environment == World.Environment.NORMAL || environment == World.Environment.THE_END;
        }
        return false;
    }
}
