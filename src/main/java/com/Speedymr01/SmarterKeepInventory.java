package com.Speedymr01;

import com.Speedymr01.command.KeepInvCommand;
import com.Speedymr01.manager.KeepInvManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class SmarterKeepInventory extends JavaPlugin {

    private DataManager dataManager;
    private KeepInvManager keepInvManager;
    private KeepInvCommand keepInvCommand;

    @Override
    public void onEnable() {
        this.dataManager = new DataManager(this);
        this.keepInvManager = new KeepInvManager(this);
        this.keepInvCommand = new KeepInvCommand(this);

        getServer().getPluginManager().registerEvents(keepInvManager, this);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            commands.registrar().register(
                buildKeepInvCommand(), 
                "Toggle keep inventory on death", 
                List.of("keepinv", "ki")
            );
        });

        getLogger().info("SmarterKeepInventory enabled!");
    }

    @Override
    public void onDisable() {
        if (keepInvManager != null) {
            keepInvManager.cancelAll();
        }
    }

    private LiteralCommandNode<CommandSourceStack> buildKeepInvCommand() {
        return Commands.literal("keepinv")
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) {
                        keepInvCommand.showStatus(player, "/keepinv", player.getUniqueId());
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("on")
                        .executes(ctx -> {
                            if (ctx.getSource().getSender() instanceof Player player) {
                                keepInvCommand.handleOn(player, player.getUniqueId());
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(ctx -> {
                            if (ctx.getSource().getSender() instanceof Player player) {
                                keepInvCommand.handleOff(player, player.getUniqueId());
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public KeepInvManager getKeepInvManager() {
        return keepInvManager;
    }
}