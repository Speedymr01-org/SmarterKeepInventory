package com.Speedymr01.attribution;

import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrapRulesTest {

    @Test
    @DisplayName("all anvil variants count as anvils")
    void anvils() {
        assertTrue(TrapRules.isAnvil(Material.ANVIL));
        assertTrue(TrapRules.isAnvil(Material.CHIPPED_ANVIL));
        assertTrue(TrapRules.isAnvil(Material.DAMAGED_ANVIL));
    }

    @Test
    @DisplayName("other fallable blocks are not anvils")
    void nonAnvils() {
        assertFalse(TrapRules.isAnvil(Material.SAND));
        assertFalse(TrapRules.isAnvil(Material.POINTED_DRIPSTONE));
        assertFalse(TrapRules.isAnvil(Material.STONE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WHITE_BED", "RED_BED", "LIGHT_BLUE_BED", "BLACK_BED"})
    @DisplayName("every bed colour is detected")
    void beds(String name) {
        assertTrue(TrapRules.isBed(Material.valueOf(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEDROCK", "SAND", "RESPAWN_ANCHOR", "STONE"})
    @DisplayName("similar names are not beds")
    void nonBeds(String name) {
        assertFalse(TrapRules.isBed(Material.valueOf(name)));
    }

    @ParameterizedTest
    @EnumSource(World.Environment.class)
    @DisplayName("TNT is credited in every environment")
    void tntEverywhere(World.Environment environment) {
        assertTrue(TrapRules.isCreditedExplosive(Material.TNT, environment));
    }

    @Test
    @DisplayName("beds are only tracked where they explode")
    void bedEnvironments() {
        assertTrue(TrapRules.isCreditedExplosive(Material.RED_BED, World.Environment.NETHER));
        assertTrue(TrapRules.isCreditedExplosive(Material.RED_BED, World.Environment.THE_END));
        assertFalse(TrapRules.isCreditedExplosive(Material.RED_BED, World.Environment.NORMAL));
    }

    @Test
    @DisplayName("respawn anchors are tracked outside the Nether")
    void anchorEnvironments() {
        assertTrue(TrapRules.isCreditedExplosive(Material.RESPAWN_ANCHOR, World.Environment.NORMAL));
        assertTrue(TrapRules.isCreditedExplosive(Material.RESPAWN_ANCHOR, World.Environment.THE_END));
        assertFalse(TrapRules.isCreditedExplosive(Material.RESPAWN_ANCHOR, World.Environment.NETHER));
    }

    @Test
    @DisplayName("regular blocks and unknown environments are never credited")
    void nonExplosives() {
        assertFalse(TrapRules.isCreditedExplosive(Material.SAND, World.Environment.NORMAL));
        assertFalse(TrapRules.isCreditedExplosive(Material.STONE, World.Environment.NETHER));
        assertFalse(TrapRules.isCreditedExplosive(Material.RED_BED, null));
        assertFalse(TrapRules.isCreditedExplosive(Material.RESPAWN_ANCHOR, null));
        // TNT does not need the environment
        assertTrue(TrapRules.isCreditedExplosive(Material.TNT, null));
    }
}
