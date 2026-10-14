package com.Speedymr01.command;

import com.Speedymr01.SmarterKeepInventory;
import com.Speedymr01.manager.KeepInvManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class KeepInvCommand implements CommandExecutor, TabCompleter {

    private final SmarterKeepInventory plugin;
    private final KeepInvManager keepInvManager;

    public KeepInvCommand(SmarterKeepInventory plugin) {
        this.plugin = plugin;
        this.keepInvManager = plugin.getKeepInvManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        UUID uuid = player.getUniqueId();

        if (args.length == 0) {
            showStatus(player, label, uuid);
        } else if (args[0].equalsIgnoreCase("on")) {
            handleOn(player, uuid);
        } else if (args[0].equalsIgnoreCase("off")) {
            handleOff(player, uuid);
        } else {
            showStatus(player, label, uuid);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("on", "off");
        }
        return new ArrayList<>();
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