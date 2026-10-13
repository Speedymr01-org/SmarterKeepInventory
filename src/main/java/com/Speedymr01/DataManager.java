package com.Speedymr01;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class DataManager {
    private final SmarterKeepInventory plugin;
    private final File dataFile;
    private final YamlConfiguration data;

    public DataManager(SmarterKeepInventory plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.data = new YamlConfiguration();
        load();
    }

    private void load() {
        if (!dataFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create data.yml: " + e.getMessage());
            }
            return;
        }
        try {
            data.load(dataFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not load data.yml: " + e.getMessage());
        }
    }

    public void save() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    public boolean isKeepInvEnabled(UUID playerId) {
        return data.getBoolean("keepinv." + playerId + ".enabled", false);
    }

    public void setKeepInvEnabled(UUID playerId, boolean enabled) {
        data.set("keepinv." + playerId + ".enabled", enabled);
        save();
    }
}
