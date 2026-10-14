package com.Speedymr01.command;

import com.Speedymr01.SmarterKeepInventory;
import com.Speedymr01.manager.KeepInvManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.UUID;

public class KeepInvCommand {

    private final SmarterKeepInventory plugin;
    private final KeepInvManager keepInvManager;

    public KeepInvCommand(SmarterKeepInventory plugin) {
        this.plugin = plugin;
        this.keepInvManager = plugin.getKeepInvManager();
    }

    public void showStatus(Player player, String cmdLabel, UUID uuid) {
        boolean enabled = keepInvManager.isEnabled(uuid);
        player.sendMessage(Component.text("=== Keep Inventory ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Status: ", NamedTextColor.GRAY)
                .append(Component.text(enabled ? "Enabled" : "Disabled",
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
        player.sendMessage(Component.text(cmdLabel + " on", NamedTextColor.YELLOW)
                .append(Component.text(" - Enable keep inventory (stand still for 5s)", NamedTextColor.GRAY)));
        player.sendMessage(Component.text(cmdLabel + " off", NamedTextColor.YELLOW)
                .append(Component.text(" - Disable keep inventory (instant)", NamedTextColor.GRAY)));
    }

    public void handleOn(Player player, UUID uuid) {
        if (keepInvManager.isEnabled(uuid)) {
            player.sendMessage(Component.text("Keep inventory is already enabled.", NamedTextColor.GRAY));
            return;
        }
        keepInvManager.startEnableCountdown(player);
    }

    public void handleOff(Player player, UUID uuid) {
        if (!keepInvManager.isEnabled(uuid)) {
            player.sendMessage(Component.text("Keep inventory is already disabled.", NamedTextColor.GRAY));
            return;
        }
        keepInvManager.cancelPendingToggle(player);
        keepInvManager.setEnabled(uuid, false);
        player.sendMessage(Component.text("Keep inventory disabled! Your items will drop on death.", NamedTextColor.GREEN));
    }
}