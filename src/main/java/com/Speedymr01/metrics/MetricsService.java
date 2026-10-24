package com.Speedymr01.metrics;

import com.Speedymr01.SmarterKeepInventory;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

/**
 * bStats integration.
 *
 * <p>Custom chart suppliers are evaluated asynchronously by bStats, so they only
 * read config values that are already loaded in memory (this plugin has no reload
 * command that could swap the configuration concurrently).</p>
 */
public class MetricsService {

    /** Plugin id from <a href="https://bstats.org/what-is-my-plugin-id">bstats.org</a>. */
    private static final int PLUGIN_ID = 34907;

    private final SmarterKeepInventory plugin;
    private Metrics metrics;

    public MetricsService(SmarterKeepInventory plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("metrics-enabled", true)) {
            plugin.getLogger().info("bStats metrics are disabled in config.yml.");
            return;
        }
        if (isTestEnvironment()) {
            // Unit tests run against unrelocated classes, which bStats rejects; skip telemetry.
            return;
        }
        try {
            this.metrics = new Metrics(plugin, PLUGIN_ID);
            registerCharts(metrics);
        } catch (Exception e) {
            // Telemetry must never prevent the plugin from enabling.
            this.metrics = null;
            plugin.getLogger().warning("Could not start bStats metrics: " + e.getMessage());
        }
    }

    private static boolean isTestEnvironment() {
        try {
            Class.forName("org.mockbukkit.mockbukkit.MockBukkit", false, MetricsService.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private void registerCharts(Metrics metrics) {
        metrics.addCustomChart(new SimplePie("tracked_trap_blocks", this::trackedBlockBucket));
    }

    private String trackedBlockBucket() {
        int count = plugin.getConfig().getStringList("traps.tracked-blocks").size();
        if (count == 0) {
            return "none";
        }
        if (count <= 5) {
            return "1-5";
        }
        if (count <= 10) {
            return "6-10";
        }
        if (count <= 20) {
            return "11-20";
        }
        return "21+";
    }

    public void shutdown() {
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
    }
}
