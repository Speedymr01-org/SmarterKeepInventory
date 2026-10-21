package com.Speedymr01;

import com.Speedymr01.attribution.EffectAttributionTracker;
import com.Speedymr01.attribution.KillAttributionService;
import com.Speedymr01.attribution.TrapManager;
import com.Speedymr01.command.KeepInvCommand;
import com.Speedymr01.command.TestCommand;
import com.Speedymr01.manager.KeepInvManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SmarterKeepInventory extends JavaPlugin {

    private DataManager dataManager;
    private TrapManager trapManager;
    private EffectAttributionTracker effectAttributionTracker;
    private KillAttributionService killAttributionService;
    private KeepInvManager keepInvManager;
    private KeepInvCommand keepInvCommand;
    private TestCommand testCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();

        this.dataManager = new DataManager(this);
        this.trapManager = new TrapManager(this);
        this.effectAttributionTracker = new EffectAttributionTracker(this, trapManager);
        this.killAttributionService = new KillAttributionService(this, trapManager, effectAttributionTracker);
        this.keepInvManager = new KeepInvManager(this, killAttributionService);
        this.keepInvCommand = new KeepInvCommand(this);
        this.testCommand = new TestCommand();

        getServer().getPluginManager().registerEvents(keepInvManager, this);
        getServer().getPluginManager().registerEvents(trapManager, this);
        getServer().getPluginManager().registerEvents(effectAttributionTracker, this);

        getCommand("keepinv").setExecutor(keepInvCommand);
        getCommand("keepinv").setTabCompleter(keepInvCommand);
        getCommand("kitest").setExecutor(testCommand);
        getCommand("kitest").setTabCompleter(testCommand);

        getLogger().info("SmarterKeepInventory enabled!");
    }

    /**
     * Merges the config v2 defaults (all gravity blocks) into older configs.
     * Only ever adds entries — user choices are never removed.
     */
    private void migrateConfig() {
        if (getConfig().getInt("config-version", 1) >= 2) {
            return;
        }
        List<String> tracked = getConfig().getStringList("traps.tracked-blocks");
        List<String> defaults = new ArrayList<>();
        try (InputStream bundledFile = getResource("config.yml")) {
            if (bundledFile != null) {
                YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundledFile, StandardCharsets.UTF_8));
                defaults.addAll(bundled.getStringList("traps.tracked-blocks"));
            }
        } catch (IOException e) {
            getLogger().warning("Could not read bundled config.yml for migration: " + e.getMessage());
        }
        List<String> merged = ConfigMerge.mergeDistinct(tracked, defaults);
        List<String> added = merged.subList(tracked.size(), merged.size());
        getConfig().set("traps.tracked-blocks", merged);
        if (!getConfig().isSet("debug")) {
            getConfig().set("debug", false);
        }
        getConfig().set("config-version", 2);
        saveConfig();
        if (!added.isEmpty()) {
            getLogger().info("Updated config to v2: added tracked blocks " + String.join(", ", added));
        }
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
