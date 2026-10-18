package com.Speedymr01;

import com.Speedymr01.attribution.EffectAttributionTracker;
import com.Speedymr01.attribution.KillAttributionService;
import com.Speedymr01.attribution.TrapManager;
import com.Speedymr01.command.KeepInvCommand;
import com.Speedymr01.manager.KeepInvManager;
import org.bukkit.plugin.java.JavaPlugin;

public class SmarterKeepInventory extends JavaPlugin {

    private DataManager dataManager;
    private TrapManager trapManager;
    private EffectAttributionTracker effectAttributionTracker;
    private KillAttributionService killAttributionService;
    private KeepInvManager keepInvManager;
    private KeepInvCommand keepInvCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.dataManager = new DataManager(this);
        this.trapManager = new TrapManager(this);
        this.effectAttributionTracker = new EffectAttributionTracker(this, trapManager);
        this.killAttributionService = new KillAttributionService(this, trapManager, effectAttributionTracker);
        this.keepInvManager = new KeepInvManager(this, killAttributionService);
        this.keepInvCommand = new KeepInvCommand(this);

        getServer().getPluginManager().registerEvents(keepInvManager, this);
        getServer().getPluginManager().registerEvents(trapManager, this);
        getServer().getPluginManager().registerEvents(effectAttributionTracker, this);

        getCommand("keepinv").setExecutor(keepInvCommand);
        getCommand("keepinv").setTabCompleter(keepInvCommand);

        getLogger().info("SmarterKeepInventory enabled!");
    }

    @Override
    public void onDisable() {
        if (keepInvManager != null) {
            keepInvManager.cancelAll();
        }
        if (effectAttributionTracker != null) {
            effectAttributionTracker.shutdown();
        }
        if (trapManager != null) {
            trapManager.shutdown();
        }
        if (dataManager != null) {
            dataManager.save();
        }
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public KeepInvManager getKeepInvManager() {
        return keepInvManager;
    }

    public TrapManager getTrapManager() {
        return trapManager;
    }

    public KillAttributionService getKillAttributionService() {
        return killAttributionService;
    }
}
