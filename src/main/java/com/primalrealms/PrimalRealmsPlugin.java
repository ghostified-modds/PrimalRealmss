
package com.primalrealms;

import org.bukkit.plugin.java.JavaPlugin;

public final class PrimalRealmsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getLogger().info("PrimalRealms has awakened!");
    }

    @Override
    public void onDisable() {
        getLogger().info("PrimalRealms has been disabled.");
    }
}
