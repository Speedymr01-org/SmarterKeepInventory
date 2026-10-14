package com.Speedymr01;

import com.Speedymr01.command.KeepInvCommand;
import com.Speedymr01.manager.KeepInvManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

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

        getCommand("keepinv").setExecutor(keepInvCommand);
        getCommand("keepinv").setTabCompleter(keepInvCommand);

        getLogger().info("SmarterKeepInventory enabled!");
    }

    @Override
    public void onDisable() {
        if (keepInvManager != null) {
            keepInvManager.cancelAll();
        }
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public KeepInvManager getKeepInvManager() {
        return keepInvManager;
    }
}