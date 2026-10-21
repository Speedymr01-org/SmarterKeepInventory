package com.Speedymr01.attribution;

import com.Speedymr01.SmarterKeepInventory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Tracks ownership of player-placed trap blocks (gravity blocks) and dispensers,
 * stamps owner PDC onto launched projectiles, and persists the data to traps.yml.
 * All event handling is on the main thread; only file I/O runs async.
 */
public class TrapManager implements Listener {

    /** Immutable block coordinate key. */
    public record BlockKey(UUID worldId, int x, int y, int z) {
        public static BlockKey of(Location loc) {
            return new BlockKey(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }

        public static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record PendingDispense(int tick, BlockKey dispenser, UUID owner, Material item) {
    }

    /** How many ticks a pending dispense stays matchable. */
    private static final int PENDING_DISPENSE_TICKS = 2;
    /** Max squared distance between a spawned entity and the dispenser block center. */
    private static final double DISPENSE_MATCH_RADIUS_SQ = 9.0;

    private final SmarterKeepInventory plugin;
    private final NamespacedKey ownerKey;

    private final Map<BlockKey, UUID> blockOwners = new ConcurrentHashMap<>();
    private final Map<BlockKey, UUID> dispenserOwners = new ConcurrentHashMap<>();
    private final Set<Material> trackedBlocks = ConcurrentHashMap.newKeySet();
    private final Deque<PendingDispense> pendingDispenses = new ConcurrentLinkedDeque<>();

    private final boolean trackDispensers;
    private final boolean persistenceEnabled;
    private final boolean debug;
    private final long autosaveTicks;
    private final File trapsFile;
    private BukkitTask autosaveTask;

    public TrapManager(SmarterKeepInventory plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "trap-owner");
        this.trapsFile = new File(plugin.getDataFolder(), "traps.yml");

        for (String raw : plugin.getConfig().getStringList("traps.tracked-blocks")) {
            Material material = Material.matchMaterial(raw);
            if (material == null) {
                plugin.getLogger().warning("Unknown material in traps.tracked-blocks: " + raw);
            } else {
                trackedBlocks.add(material);
            }
        }
        this.trackDispensers = plugin.getConfig().getBoolean("traps.track-dispensers", true);
        this.persistenceEnabled = plugin.getConfig().getBoolean("traps.persistence-enabled", true);
        this.debug = plugin.getConfig().getBoolean("debug", false);
        long seconds = Math.max(5L, plugin.getConfig().getLong("traps.autosave-interval-seconds", 300L));
        this.autosaveTicks = seconds * 20L;

        if (persistenceEnabled) {
            loadTraps();
            this.autosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::saveTraps, autosaveTicks, autosaveTicks);
        }
    }

    // ------------------------------------------------------------------
    // Lookups used by KillAttributionService / EffectAttributionTracker
    // ------------------------------------------------------------------

    public UUID getBlockOwner(Block block) {
        return blockOwners.get(BlockKey.of(block));
    }

    public UUID getDispenserOwner(Block block) {
        return dispenserOwners.get(BlockKey.of(block));
    }

    /** Reads the trap-owner PDC, or null if absent/invalid. */
    public UUID getOwnerFromPDC(Entity entity) {
        String value = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void stampOwner(Entity entity, UUID owner) {
        entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
    }

    // ------------------------------------------------------------------
    // Placement / breaking
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Material type = block.getType();
        if (trackedBlocks.contains(type)) {
            blockOwners.put(BlockKey.of(block), event.getPlayer().getUniqueId());
            debug("placed tracked block " + type + " at " + BlockKey.of(block)
                    + " for " + event.getPlayer().getName());
        }
        if (trackDispensers && type == Material.DISPENSER) {
            dispenserOwners.put(BlockKey.of(block), event.getPlayer().getUniqueId());
            debug("placed dispenser at " + BlockKey.of(block) + " for " + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        BlockKey key = BlockKey.of(event.getBlock());
        blockOwners.remove(key);
        dispenserOwners.remove(key);
    }

    // ------------------------------------------------------------------
    // Falling blocks: transfer ownership to the entity, re-record on landing
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof FallingBlock fallingBlock) {
            handleFallingBlockSpawn(fallingBlock);
            return;
        }
        if (!trackDispensers) {
            return;
        }
        if (entity instanceof Projectile projectile) {
            handleProjectileSpawn(projectile);
        } else if (entity instanceof TNTPrimed tnt) {
            handleTntSpawn(tnt);
        }
    }

    private void handleFallingBlockSpawn(FallingBlock fallingBlock) {
        if (getOwnerFromPDC(fallingBlock) != null) {
            return; // already transferred by the fall-start EntityChangeBlockEvent
        }
        // The spawn position is exactly the block's coordinates (double coords, integral Y),
        // so the primary key is authoritative — no tolerance scanning (it could wrongly
        // match an adjacent tracked block and lose its ownership).
        Location loc = fallingBlock.getLocation();
        BlockKey primary = BlockKey.of(loc);
        UUID owner = blockOwners.remove(primary);
        if (owner != null) {
            stampOwner(fallingBlock, owner);
            debug("spawn at " + primary + " -> stamped owner " + owner);
        } else {
            debug("spawn at " + primary + " -> no recorded owner");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fallingBlock)) {
            return;
        }
        if (event.getTo() == Material.AIR) {
            // Fires twice for gravity blocks: when the block starts falling (source -> air) and
            // when a falling block destroys a block on landing. Only the first is the origin.
            if (getOwnerFromPDC(fallingBlock) != null) {
                return; // already transferred by the spawn event
            }
            Block source = event.getBlock();
            if (source.getType() != fallingBlock.getBlockData().getMaterial()) {
                return; // a block destroyed by the falling entity, not its origin
            }
            UUID owner = blockOwners.remove(BlockKey.of(source));
            if (owner != null) {
                stampOwner(fallingBlock, owner);
                debug("fall start at " + BlockKey.of(source) + " -> stamped owner " + owner);
            } else {
                debug("fall start at " + BlockKey.of(source) + " -> no recorded owner");
            }
            return;
        }
        UUID owner = getOwnerFromPDC(fallingBlock);
        if (owner == null) {
            debug("landed " + event.getTo() + " at " + BlockKey.of(event.getBlock())
                    + " -> unstamped, not recorded");
            return;
        }
        blockOwners.put(BlockKey.of(event.getBlock()), owner);
        debug("landed " + event.getTo() + " at " + BlockKey.of(event.getBlock()) + " -> owner " + owner);
    }

    // ------------------------------------------------------------------
    // Dispensers: pending dispense correlation + projectile stamping
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (!trackDispensers) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType() != Material.DISPENSER) {
            return;
        }
        UUID owner = dispenserOwners.get(BlockKey.of(block));
        if (owner == null) {
            return;
        }
        Material item = event.getItem().getType();
        if (!isCorrelatable(item)) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        prunePendingDispenses(now);
        pendingDispenses.addLast(new PendingDispense(now, BlockKey.of(block), owner, item));
    }

    private void handleProjectileSpawn(Projectile projectile) {
        ProjectileSource shooter = projectile.getShooter();
        if (shooter != null) {
            // Source already known (player or dispenser); don't correlate.
            if (shooter instanceof BlockProjectileSource blockSource) {
                UUID owner = dispenserOwners.get(BlockKey.of(blockSource.getBlock()));
                if (owner != null) {
                    stampOwner(projectile, owner);
                }
            }
            return;
        }
        UUID owner = matchPendingDispense(projectile.getLocation(), projectile);
        if (owner != null) {
            stampOwner(projectile, owner);
        }
    }

    private void handleTntSpawn(TNTPrimed tnt) {
        UUID owner = matchPendingDispense(tnt.getLocation(), tnt);
        if (owner != null) {
            stampOwner(tnt, owner);
        }
    }

    private void prunePendingDispenses(int now) {
        while (!pendingDispenses.isEmpty() && now - pendingDispenses.peekFirst().tick() > 5) {
            pendingDispenses.pollFirst();
        }
    }

    private UUID matchPendingDispense(Location spawnLoc, Entity spawned) {
        int now = Bukkit.getCurrentTick();
        prunePendingDispenses(now);
        UUID worldId = spawnLoc.getWorld().getUID();
        PendingDispense best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (PendingDispense pending : pendingDispenses) {
            if (now - pending.tick() > PENDING_DISPENSE_TICKS) {
                continue;
            }
            if (!pending.dispenser().worldId().equals(worldId)) {
                continue;
            }
            if (!isCompatible(pending.item(), spawned)) {
                continue;
            }
            double dx = spawnLoc.getX() - (pending.dispenser().x() + 0.5);
            double dy = spawnLoc.getY() - (pending.dispenser().y() + 0.5);
            double dz = spawnLoc.getZ() - (pending.dispenser().z() + 0.5);
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= DISPENSE_MATCH_RADIUS_SQ && distSq < bestDistSq) {
                best = pending;
                bestDistSq = distSq;
            }
        }
        if (best != null) {
            pendingDispenses.remove(best);
            return best.owner();
        }
        return null;
    }

    private boolean isCorrelatable(Material item) {
        return switch (item) {
            case SPLASH_POTION, LINGERING_POTION, ARROW, SPECTRAL_ARROW, TIPPED_ARROW,
                 FIRE_CHARGE, FIREWORK_ROCKET, TNT -> true;
            default -> false;
        };
    }

    private boolean isCompatible(Material item, Entity spawned) {
        return switch (item) {
            case SPLASH_POTION, LINGERING_POTION -> spawned instanceof ThrownPotion;
            case ARROW, SPECTRAL_ARROW, TIPPED_ARROW -> spawned instanceof AbstractArrow;
            case FIRE_CHARGE -> spawned instanceof SmallFireball;
            case FIREWORK_ROCKET -> spawned instanceof Firework;
            case TNT -> spawned instanceof TNTPrimed;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void loadTraps() {
        if (!trapsFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(trapsFile);
        loadSection(yaml, "traps", blockOwners);
        loadSection(yaml, "dispensers", dispenserOwners);
    }

    private void loadSection(YamlConfiguration yaml, String section, Map<BlockKey, UUID> target) {
        if (!yaml.isConfigurationSection(section)) {
            return;
        }
        for (String key : yaml.getConfigurationSection(section).getKeys(false)) {
            String value = yaml.getString(section + "." + key);
            BlockKey parsed = parseKey(key);
            UUID owner = parseUuid(value);
            if (parsed == null || owner == null) {
                plugin.getLogger().warning("Skipping invalid traps.yml entry: " + section + "." + key);
                continue;
            }
            target.put(parsed, owner);
        }
    }

    private BlockKey parseKey(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) {
            return null;
        }
        UUID worldId = resolveWorldId(parts[0]);
        if (worldId == null) {
            return null;
        }
        try {
            return new BlockKey(worldId, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private UUID resolveWorldId(String worldToken) {
        try {
            UUID id = UUID.fromString(worldToken);
            return Bukkit.getWorld(id) != null ? id : null;
        } catch (IllegalArgumentException ignored) {
            World world = Bukkit.getWorld(worldToken);
            return world != null ? world.getUID() : null;
        }
    }

    private UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String keyString(BlockKey key) {
        World world = Bukkit.getWorld(key.worldId());
        String worldToken = world != null ? world.getName() : key.worldId().toString();
        return worldToken + ";" + key.x() + ";" + key.y() + ";" + key.z();
    }

    private YamlConfiguration buildYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<BlockKey, UUID> entry : blockOwners.entrySet()) {
            yaml.set("traps." + keyString(entry.getKey()), entry.getValue().toString());
        }
        for (Map.Entry<BlockKey, UUID> entry : dispenserOwners.entrySet()) {
            yaml.set("dispensers." + keyString(entry.getKey()), entry.getValue().toString());
        }
        return yaml;
    }

    private void saveTraps() {
        if (!persistenceEnabled) {
            return;
        }
        try {
            buildYaml().save(trapsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save traps.yml: " + e.getMessage());
        }
    }

    private void debug(String message) {
        if (debug) {
            plugin.getLogger().info("[traps] " + message);
        }
    }

    /** Called from onDisable: cancels autosave and writes a final synchronous save. */
    public void shutdown() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        saveTraps();
    }
}
