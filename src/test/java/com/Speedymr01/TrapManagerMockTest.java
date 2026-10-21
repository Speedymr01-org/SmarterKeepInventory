package com.Speedymr01;

import com.Speedymr01.attribution.TrapManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event-level tests for {@link TrapManager} using MockBukkit: placement bookkeeping, the
 * falling-block ownership transfer, dispenser correlation and explosive-block credit.
 *
 * <p>MockBukkit emulates the Bukkit API, not vanilla event ordering, so these tests pin down our
 * handlers (given an event, we do the right thing) rather than reproducing real game sequences.</p>
 */
class TrapManagerMockTest {

    private ServerMock server;
    private SmarterKeepInventory plugin;
    private TrapManager traps;
    private WorldMock world;
    private PlayerMock placer;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(SmarterKeepInventory.class);
        traps = plugin.getTrapManager();
        world = server.addSimpleWorld("test_world");
        placer = server.addPlayer("TrapPlacer");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private BlockMock place(PlayerMock player, int x, int y, int z, Material material) {
        BlockMock block = world.getBlockAt(x, y, z);
        block.setType(material);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), world.getBlockAt(x, y - 1, z),
                new ItemStack(material), player, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return block;
    }

    private void breakBlock(PlayerMock player, Block block) {
        server.getPluginManager().callEvent(new BlockBreakEvent(block, player));
    }

    /** MockBukkit worlds start with no loaded chunks; the explosive lookup skips unloaded chunks. */
    private void loadChunkAt(Block block) {
        block.getWorld().loadChunk(block.getX() >> 4, block.getZ() >> 4);
    }

    private BlockMock placeInWorld(WorldMock targetWorld, Material material, int x, int y, int z) {
        BlockMock block = targetWorld.getBlockAt(x, y, z);
        block.setType(material);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), targetWorld.getBlockAt(x, y - 1, z),
                new ItemStack(material), placer, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return block;
    }

    // ------------------------------------------------------------------
    // Plugin lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("plugin enables and exposes the trap manager")
    void pluginLoads() {
        assertTrue(plugin.isEnabled());
        assertNotNull(traps);
    }

    // ------------------------------------------------------------------
    // Block placement / removal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a placed tracked block is credited to the placer")
    void tracksPlacedTrackedBlock() {
        BlockMock anvil = place(placer, 0, 64, 0, Material.ANVIL);
        assertEquals(placer.getUniqueId(), traps.getBlockOwner(anvil));
    }

    @Test
    @DisplayName("blocks outside the tracked list are ignored")
    void ignoresUntrackedBlocks() {
        BlockMock stone = place(placer, 0, 64, 0, Material.STONE);
        assertNull(traps.getBlockOwner(stone));
    }

    @Test
    @DisplayName("breaking a tracked block removes the record")
    void breakingRemovesRecord() {
        BlockMock sand = place(placer, 0, 64, 0, Material.SAND);
        assertNotNull(traps.getBlockOwner(sand));
        breakBlock(placer, sand);
        assertNull(traps.getBlockOwner(sand));
    }

    // ------------------------------------------------------------------
    // Falling blocks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fall start transfers ownership from the block to the falling entity")
    void fallStartStampsEntity() {
        BlockMock source = place(placer, 5, 70, 5, Material.ANVIL);
        // Spawn the entity elsewhere so the spawn handler cannot claim the source block.
        FallingBlock falling = world.spawnFallingBlock(new Location(world, 40, 70, 40),
                Material.ANVIL.createBlockData());
        assertNull(traps.getOwnerFromPDC(falling));

        server.getPluginManager().callEvent(new EntityChangeBlockEvent(falling, source,
                Material.AIR.createBlockData()));

        assertEquals(placer.getUniqueId(), traps.getOwnerFromPDC(falling));
        assertNull(traps.getBlockOwner(source), "the source block record is consumed");
    }

    @Test
    @DisplayName("the spawn event also transfers ownership when it fires first")
    void spawnStampsEntity() {
        place(placer, 5, 70, 5, Material.SAND);
        FallingBlock falling = world.spawnFallingBlock(new Location(world, 5, 70, 5),
                Material.SAND.createBlockData());
        assertEquals(placer.getUniqueId(), traps.getOwnerFromPDC(falling));
    }

    @Test
    @DisplayName("landing re-records ownership on the block it lands on")
    void landingRecordsDestination() {
        FallingBlock falling = world.spawnFallingBlock(new Location(world, 40, 70, 40),
                Material.SAND.createBlockData());
        traps.stampOwner(falling, placer.getUniqueId());
        BlockMock target = world.getBlockAt(8, 60, 8);

        server.getPluginManager().callEvent(new EntityChangeBlockEvent(falling, target,
                Material.SAND.createBlockData()));

        assertEquals(placer.getUniqueId(), traps.getBlockOwner(target));
    }

    @Test
    @DisplayName("an unowned falling block does not record anything on landing")
    void unownedLandingIsIgnored() {
        FallingBlock falling = world.spawnFallingBlock(new Location(world, 40, 70, 40),
                Material.SAND.createBlockData());
        BlockMock target = world.getBlockAt(8, 60, 8);

        server.getPluginManager().callEvent(new EntityChangeBlockEvent(falling, target,
                Material.GRAVEL.createBlockData()));

        assertNull(traps.getBlockOwner(target));
    }

    // ------------------------------------------------------------------
    // Dispensers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a placed dispenser is recorded and forgets its owner when broken")
    void tracksDispenser() {
        BlockMock dispenser = place(placer, 2, 64, 2, Material.DISPENSER);
        assertEquals(placer.getUniqueId(), traps.getDispenserOwner(dispenser));
        breakBlock(placer, dispenser);
        assertNull(traps.getDispenserOwner(dispenser));
    }

    @Test
    @DisplayName("a projectile spawned by a tracked dispenser is stamped with its owner")
    void stampsDispensedProjectile() {
        BlockMock dispenser = place(placer, 2, 64, 2, Material.DISPENSER);
        server.getPluginManager().callEvent(new BlockDispenseEvent(dispenser,
                new ItemStack(Material.ARROW), new Vector(1, 0, 0)));

        Arrow arrow = world.spawn(dispenser.getLocation().add(0.5, 0.5, 0.5), Arrow.class);

        assertEquals(placer.getUniqueId(), traps.getOwnerFromPDC(arrow));
    }

    @Test
    @DisplayName("projectiles from untracked dispensers are not stamped")
    void doesNotStampUntrackedDispenser() {
        BlockMock dispenser = place(placer, 2, 64, 2, Material.DISPENSER);
        breakBlock(placer, dispenser); // owner forgotten
        server.getPluginManager().callEvent(new BlockDispenseEvent(dispenser,
                new ItemStack(Material.ARROW), new Vector(1, 0, 0)));

        Arrow arrow = world.spawn(dispenser.getLocation().add(0.5, 0.5, 0.5), Arrow.class);

        assertNull(traps.getOwnerFromPDC(arrow));
    }

    // ------------------------------------------------------------------
    // TNT
    // ------------------------------------------------------------------

    @Test
    @DisplayName("primed TNT inherits the placer of the TNT block")
    void tntInheritsBlockPlacer() {
        BlockMock tntBlock = place(placer, 12, 64, 12, Material.TNT);
        TNTPrimed tnt = world.spawn(tntBlock.getLocation().add(0.5, 0, 0.5), TNTPrimed.class);

        assertEquals(placer.getUniqueId(), traps.getOwnerFromPDC(tnt));
    }

    @Test
    @DisplayName("TNT primed away from any placed block inherits the priming player")
    void tntInheritsPrimingPlayer() {
        UUID otherId = server.addPlayer("Igniter").getUniqueId();
        PlayerMock igniter = (PlayerMock) server.getPlayer(otherId);

        TNTPrimed tnt = world.spawn(new Location(world, 30, 64, 30), TNTPrimed.class,
                primed -> primed.setSource(igniter));

        assertEquals(otherId, traps.getOwnerFromPDC(tnt));
    }

    @Test
    @DisplayName("TNT with no known source stays unstamped")
    void unownedTntIsNotStamped() {
        TNTPrimed tnt = world.spawn(new Location(world, 30, 64, 30), TNTPrimed.class);
        assertNull(traps.getOwnerFromPDC(tnt));
    }

    // ------------------------------------------------------------------
    // Explosive blocks (beds / respawn anchors)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a consumed respawn anchor is credited in the overworld")
    void consumedAnchorIsCredited() {
        BlockMock anchor = place(placer, 20, 64, 20, Material.RESPAWN_ANCHOR);
        loadChunkAt(anchor);
        anchor.setType(Material.AIR); // the explosion consumed it
        assertEquals(placer.getUniqueId(), traps.getExplosionBlockOwner(anchor.getLocation()));
    }

    @Test
    @DisplayName("a standing respawn anchor is never credited")
    void standingAnchorIsNotCredited() {
        BlockMock anchor = place(placer, 20, 64, 20, Material.RESPAWN_ANCHOR);
        loadChunkAt(anchor);
        assertNull(traps.getExplosionBlockOwner(anchor.getLocation()));
    }

    @Test
    @DisplayName("beds are not tracked in the overworld (they cannot explode there)")
    void bedsAreIgnoredInOverworld() {
        BlockMock bed = place(placer, 24, 64, 24, Material.RED_BED);
        loadChunkAt(bed);
        bed.setType(Material.AIR);
        assertNull(traps.getExplosionBlockOwner(bed.getLocation()));
    }

    @Test
    @DisplayName("beds are tracked in the nether")
    void bedsAreTrackedInNether() {
        WorldMock nether = server.addSimpleWorld("test_nether");
        nether.setEnvironment(World.Environment.NETHER);
        BlockMock bed = placeInWorld(nether, Material.RED_BED, 4, 64, 4);
        loadChunkAt(bed);
        bed.setType(Material.AIR);
        assertEquals(placer.getUniqueId(), traps.getExplosionBlockOwner(bed.getLocation()));
    }

    @Test
    @DisplayName("breaking an explosive block discards its record")
    void breakingExplosiveBlockRemovesRecord() {
        BlockMock anchor = place(placer, 20, 64, 20, Material.RESPAWN_ANCHOR);
        loadChunkAt(anchor);
        breakBlock(placer, anchor);
        anchor.setType(Material.AIR);
        assertNull(traps.getExplosionBlockOwner(anchor.getLocation()));
    }
}
