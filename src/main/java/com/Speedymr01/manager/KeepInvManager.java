package com.Speedymr01.manager;

import com.Speedymr01.SmarterKeepInventory;
import com.Speedymr01.attribution.KillAttributionService;
import com.Speedymr01.attribution.KillAttributionService.KillCredit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KeepInvManager implements Listener {

    private static final int COUNTDOWN_SECONDS = 5;
    private static final double MOVEMENT_THRESHOLD_SQ = 1.0;

    private final SmarterKeepInventory plugin;
    private final KillAttributionService attributionService;
    private final Map<UUID, PendingToggle> pendingToggles = new ConcurrentHashMap<>();
    private final Map<UUID, Component> pendingVictimMessages = new ConcurrentHashMap<>();

    public KeepInvManager(SmarterKeepInventory plugin, KillAttributionService attributionService) {
        this.plugin = plugin;
        this.attributionService = attributionService;
    }

    public boolean isEnabled(UUID playerId) {
        return plugin.getDataManager().isKeepInvEnabled(playerId);
    }

    public void setEnabled(UUID playerId, boolean enabled) {
        cancelPendingToggle(playerId);
        plugin.getDataManager().setKeepInvEnabled(playerId, enabled);
    }

    public void startEnableCountdown(Player player) {
        UUID uuid = player.getUniqueId();
        cancelPendingToggle(player);
        Location startLocation = player.getLocation().clone();
        PendingToggle data = new PendingToggle(uuid, startLocation, COUNTDOWN_SECONDS);
        pendingToggles.put(uuid, data);
        BukkitTask task = new CountdownTask(uuid).runTaskTimer(plugin, 0L, 20L);
        data.task = task;
    }

    public void cancelPendingToggle(Player player) {
        cancelPendingToggle(player.getUniqueId());
    }

    public void cancelAll() {
        for (PendingToggle data : pendingToggles.values()) {
            if (data.task != null) {
                data.task.cancel();
            }
        }
        pendingToggles.clear();
    }

    private void cancelPendingToggle(UUID uuid) {
        PendingToggle existing = pendingToggles.remove(uuid);
        if (existing != null && existing.task != null) {
            existing.task.cancel();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelPendingToggle(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        // Trap / indirect attribution (cause-first). Credited kills never keep inventory.
        KillCredit credit = attributionService.resolveKill(player);
        if (credit != null) {
            deliverTrapKillMessages(credit, player);
            return;
        }

        Player killer = player.getKiller();
        if (killer != null) return;

        UUID uuid = player.getUniqueId();
        if (isEnabled(uuid)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setDroppedExp(0);
            player.sendMessage(Component.text("Your items were kept (keep inventory is enabled).", NamedTextColor.GREEN));
        }
    }

    private void deliverTrapKillMessages(KillCredit credit, Player victim) {
        String path = "messages.kills." + credit.source().configKey() + ".";
        String creditedName = resolveName(credit.credited());
        String victimName = victim.getName();

        String killerText = plugin.getConfig().getString(path + "killer", "");
        if (!killerText.isEmpty()) {
            Player killer = Bukkit.getPlayer(credit.credited());
            if (killer != null && killer.isOnline()) {
                killer.sendMessage(Component.text(
                        format(killerText, creditedName, victimName, credit.effectLabel()), NamedTextColor.RED));
            }
        }

        String victimText = plugin.getConfig().getString(path + "victim", "");
        if (!victimText.isEmpty()) {
            pendingVictimMessages.put(victim.getUniqueId(), Component.text(
                    format(victimText, creditedName, victimName, credit.effectLabel()), NamedTextColor.RED));
        }
    }

    private String format(String template, String creditedName, String victimName, String effectLabel) {
        return template
                .replace("%player%", creditedName)
                .replace("%victim%", victimName)
                .replace("%effect%", effectLabel != null ? effectLabel : "potion");
    }

    private String resolveName(UUID playerId) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
        String name = offline.getName();
        return name != null ? name : "Unknown";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Component message = pendingVictimMessages.remove(event.getPlayer().getUniqueId());
        if (message != null) {
            event.getPlayer().sendMessage(message);
        }
    }

    private class CountdownTask extends BukkitRunnable {
        private final UUID playerUuid;

        CountdownTask(UUID playerUuid) {
            this.playerUuid = playerUuid;
        }

        @Override
        public void run() {
            if (!plugin.isEnabled()) {
                pendingToggles.remove(playerUuid);
                this.cancel();
                return;
            }

            PendingToggle data = pendingToggles.get(playerUuid);
            if (data == null) {
                this.cancel();
                return;
            }

            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                pendingToggles.remove(playerUuid);
                this.cancel();
                return;
            }

            Location current = player.getLocation();
            if (!current.getWorld().equals(data.startLocation.getWorld())) {
                cancelAndNotify(player, "Keep inventory cancelled - you moved between worlds.");
                return;
            }

            if (current.distanceSquared(data.startLocation) > MOVEMENT_THRESHOLD_SQ) {
                cancelAndNotify(player, "Keep inventory cancelled - you moved.");
                return;
            }

            data.secondsLeft--;

            if (data.secondsLeft <= 0) {
                pendingToggles.remove(playerUuid);
                this.cancel();
                setEnabled(playerUuid, true);
                player.sendMessage(Component.text("Keep inventory enabled! Your items will be kept on death.", NamedTextColor.GREEN));
                player.sendActionBar(Component.text("Keep inventory enabled!", NamedTextColor.GREEN));
                return;
            }

            player.sendActionBar(Component.text("Enabling keep inventory in ", NamedTextColor.YELLOW)
                    .append(Component.text(data.secondsLeft, NamedTextColor.WHITE))
                    .append(Component.text("s... Stand still!", NamedTextColor.YELLOW)));
        }

        private void cancelAndNotify(Player player, String message) {
            pendingToggles.remove(playerUuid);
            this.cancel();
            player.sendMessage(Component.text(message, NamedTextColor.RED));
            player.sendActionBar(Component.text(message, NamedTextColor.RED));
        }
    }

    private static class PendingToggle {
        final UUID playerUuid;
        final Location startLocation;
        int secondsLeft;
        BukkitTask task;

        PendingToggle(UUID playerUuid, Location startLocation, int secondsLeft) {
            this.playerUuid = playerUuid;
            this.startLocation = startLocation;
            this.secondsLeft = secondsLeft;
        }
    }
}
