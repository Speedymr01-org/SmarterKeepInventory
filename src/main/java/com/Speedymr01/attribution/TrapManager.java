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
import org.bukkit.entity.Player;
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
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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

    /** A placed block whose explosion is credited to the placer (TNT, beds, respawn anchors). */
    private record ExplosiveBlock(UUID owner, Material material) {
    }

    /** A recent explosion caused by a credited entity; used to inherit ownership through TNT chains. */
    private record RecentExplosion(UUID worldId, double x, double y, double z, double radius, UUID owner,
                                   int expiresAt) {
    }

    /** How many ticks a pending dispense stays matchable. */
    private static final int PENDING_DISPENSE_TICKS = 2;
    /** Max squared distance between a spawned entity and the dispenser block center. */
    private static final double DISPENSE_MATCH_RADIUS_SQ = 9.0;
    /** Max distance from the victim to a consumed bed/anchor block that is still credited. */
    private static final double EXPLOSION_CREDIT_RADIUS = 12.0;
    /** Ticks during which a chained TNT can inherit ownership from a recent explosion. */
    private static final int CHAIN_INHERIT_TICKS = 10;
    /** Interval (ticks) for dropping explosive-block records whose block no longer exists. */
    private static final long EXPLOSIVE_PRUNE_INTERVAL_TICKS = 6000L;

    private final SmarterKeepInventory plugin;
    private final NamespacedKey ownerKey;

    private final Map<BlockKey, UUID> blockOwners = new ConcurrentHashMap<>();
    private final Map<BlockKey, UUID> dispenserOwners = new ConcurrentHashMap<>();
    private final Map<BlockKey, ExplosiveBlock> explosiveBlocks = new ConcurrentHashMap<>();
    private final List<RecentExplosion> recentExplosions = new ArrayList<>();
    private final Set<Material> trackedBlocks = ConcurrentHashMap.newKeySet();
    private final Deque<PendingDispense> pendingDispenses = new ConcurrentLinkedDeque<>();

    private final boolean trackDispensers;
    private final boolean persistenceEnabled;
    private final boolean debug;
    private final long autosaveTicks;
    private final File trapsFile;
    private BukkitTask autosaveTask;
    private BukkitTask explosivePruneTask;

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
        this.explosivePruneTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::pruneExplosiveBlocks,
                EXPLOSIVE_PRUNE_INTERVAL_TICKS, EXPLOSIVE_PRUNE_INTERVAL_TICKS);
    }

    // ------------------------------------------------------------------
    // Lookups used by KillAttributionService / EffectAttributionTracker
    // ------------------------------------------------------------------

    public UUID getBlockOwner(Block block) {
        return blockOwners.get(BlockKey.of(block));
    }

    /**
     * Finds the placer of an explosive block (bed / respawn anchor) near {@code location} that has
     * already been consumed — i.e. the block that just exploded. Bed and respawn-anchor explosions
     * deal damage <em>before</em> any explosion event fires, so the placer can only be recovered
     * from the ownership map at death time. Only blocks that are now air qualify, so a decorative
     * bed that is still standing is never credited.
     */
    public UUID getExplosionBlockOwner(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        UUID worldId = world.getUID();
        UUID best = null;
        double bestDistSq = EXPLOSION_CREDIT_RADIUS * EXPLOSION_CREDIT_RADIUS;
        for (Map.Entry<BlockKey, ExplosiveBlock> entry : explosiveBlocks.entrySet()) {
            BlockKey key = entry.getKey();
            if (!key.worldId().equals(worldId) || entry.getValue().material() == Material.TNT) {
                continue; // TNT explosions are entity explosions, handled by the stamped entity
            }
            if (!world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
                continue;
            }
            if (world.getBlockAt(key.x(), key.y(), key.z()).getType() != Material.AIR) {
                continue; // still standing: it did not explode
            }
            double dx = key.x() + 0.5 - location.getX();
            double dy = key.y() + 0.5 - location.getY();
            double dz = key.z() + 0.5 - location.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                best = entry.getValue().owner();
                bestDistSq = distSq;
            }
        }
        return best;
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
        trackExplosiveBlock(block, event.getPlayer());
    }

    /**
     * Records the placer of blocks whose explosion is credited: TNT anywhere, beds only in worlds
     * where they explode (Nether/End) and respawn anchors only outside the Nether. World gating
     * keeps the ownership map (which is persisted) from recording every decorative bed on the server.
     */
    private void trackExplosiveBlock(Block block, Player player) {
        Material type = block.getType();
        if (!TrapRules.isCreditedExplosive(type, block.getWorld().getEnvironment())) {
            return;
        }
        explosiveBlocks.put(BlockKey.of(block),
                new ExplosiveBlock(player.getUniqueId(), type));
        debug("placed explosive block " + type + " at " + BlockKey.of(block)
                + " for " + player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        BlockKey key = BlockKey.of(event.getBlock());
        blockOwners.remove(key);
        dispenserOwners.remove(key);
        explosiveBlocks.remove(key);
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
        if (entity instanceof Projectile projectile) {
            if (trackDispensers) {
                handleProjectileSpawn(projectile);
            }
            return;
        }
        if (entity instanceof TNTPrimed tnt) {
            // Independent of dispenser tracking: placed TNT and chains are always credited.
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
        Location loc = tnt.getLocation();
        int now = Bukkit.getCurrentTick();
        UUID blockOwner = takeTntBlockOwner(loc);
        if (getOwnerFromPDC(tnt) != null) {
            return; // already stamped (inherited from the explosion that primed it)
        }
        UUID owner = blockOwner;
        if (owner == null) {
            // Primed by an entity: a chained TNT (stamped), a player's flint & steel, or a projectile.
            Entity source = tnt.getSource();
            if (source != null) {
                owner = getOwnerFromPDC(source);
                if (owner == null && source instanceof Player player) {
                    owner = player.getUniqueId();
                }
            }
        }
        if (owner == null) {
            owner = matchRecentExplosion(loc, now);
        }
        if (owner == null) {
            owner = matchPendingDispense(loc, tnt);
        }
        if (owner == null) {
            debug("TNT primed at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                    + " -> no recorded owner");
            return;
        }
        stampOwner(tnt, owner);
        debug("TNT primed at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                + " -> owner " + owner);
    }

    /** Removes and returns the placer of the TNT block at the primed entity's position. */
    private UUID takeTntBlockOwner(Location loc) {
        BlockKey key = BlockKey.of(loc);
        ExplosiveBlock record = explosiveBlocks.get(key);
        if (record == null || record.material() != Material.TNT) {
            return null;
        }
        explosiveBlocks.remove(key);
        return record.owner();
    }

    /**
     * Remembers who caused an explosion so TNT primed by it (chain reactions) inherits the owner.
     * Chained TNT is spawned while the explosion finalizes, right after this event fires.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        UUID owner = getOwnerFromPDC(event.getEntity());
        if (owner == null) {
            return;
        }
        Location origin = event.getLocation();
        double radius = 0.0;
        for (Block block : event.blockList()) {
            if (!block.getWorld().equals(origin.getWorld())) {
                continue;
            }
            double dx = block.getX() + 0.5 - origin.getX();
            double dy = block.getY() + 0.5 - origin.getY();
            double dz = block.getZ() + 0.5 - origin.getZ();
            radius = Math.max(radius, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        int now = Bukkit.getCurrentTick();
        recentExplosions.add(new RecentExplosion(origin.getWorld().getUID(), origin.getX(), origin.getY(),
                origin.getZ(), radius + 2.0, owner, now + CHAIN_INHERIT_TICKS));
        pruneRecentExplosions(now);
        debug("explosion by owner " + owner + " at " + origin.getBlockX() + "," + origin.getBlockY()
                + "," + origin.getBlockZ());
    }

    private UUID matchRecentExplosion(Location loc, int now) {
        pruneRecentExplosions(now);
        UUID worldId = loc.getWorld().getUID();
        UUID best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (RecentExplosion record : recentExplosions) {
            if (!record.worldId().equals(worldId)) {
                continue;
            }
            double dx = loc.getX() - record.x();
            double dy = loc.getY() - record.y();
            double dz = loc.getZ() - record.z();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= record.radius() * record.radius() && distSq < bestDistSq) {
                best = record.owner();
                bestDistSq = distSq;
            }
        }
        return best;
    }

    private void pruneRecentExplosions(int now) {
        recentExplosions.removeIf(record -> record.expiresAt() < now);
    }

    /** Drops explosive-block records whose block is gone (destroyed by something other than a player). */
    private void pruneExplosiveBlocks() {
        for (Map.Entry<BlockKey, ExplosiveBlock> entry : explosiveBlocks.entrySet()) {
            BlockKey key = entry.getKey();
            World world = Bukkit.getWorld(key.worldId());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
                continue;
            }
            if (world.getBlockAt(key.x(), key.y(), key.z()).getType() == Material.AIR) {
                explosiveBlocks.remove(key, entry.getValue());
            }
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
        loadExplosives(yaml);
    }

    private void loadExplosives(YamlConfiguration yaml) {
        if (!yaml.isConfigurationSection("explosives")) {
            return;
        }
        for (String key : yaml.getConfigurationSection("explosives").getKeys(false)) {
            String value = yaml.getString("explosives." + key);
            BlockKey parsed = parseKey(key);
            if (parsed == null || value == null) {
                continue;
            }
            int split = value.lastIndexOf(';');
            if (split <= 0) {
                continue;
            }
            UUID owner = parseUuid(value.substring(0, split));
            Material material = Material.matchMaterial(value.substring(split + 1));
            if (owner == null || material == null) {
                plugin.getLogger().warning("Skipping invalid traps.yml entry: explosives." + key);
                continue;
            }
            explosiveBlocks.put(parsed, new ExplosiveBlock(owner, material));
        }
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
        for (Map.Entry<BlockKey, ExplosiveBlock> entry : explosiveBlocks.entrySet()) {
            ExplosiveBlock record = entry.getValue();
            yaml.set("explosives." + keyString(entry.getKey()),
                    record.owner() + ";" + record.material().name());
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
        if (explosivePruneTask != null) {
            explosivePruneTask.cancel();
            explosivePruneTask = null;
        }
        saveTraps();
    }
}
