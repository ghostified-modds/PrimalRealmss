
package com.primalrealms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PrimalRealmsPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Integer> energy = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadEnergyData();

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("PrimalRealms has awakened!");
    }

    @Override
    public void onDisable() {
        saveEnergyData();
        getLogger().info("PrimalRealms has been disabled.");
    }

    private void loadEnergyData() {
        dataFile = new File(getDataFolder(), "players.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        for (String key : data.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                int value = data.getInt(key);
                energy.put(uuid, clampEnergy(value));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Skipping invalid player UUID: " + key);
            }
        }
    }

    private void saveEnergyData() {
        if (dataFile == null || data == null) {
            return;
        }

        for (Map.Entry<UUID, Integer> entry : energy.entrySet()) {
            data.set(entry.getKey().toString(), entry.getValue());
        }

        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save player Energy data!");
            exception.printStackTrace();
        }
    }

    private int clampEnergy(int value) {
        int minimum = getConfig().getInt("energy.minimum-energy", -5);
        int maximum = getConfig().getInt("energy.maximum-energy", 5);
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int getEnergy(UUID uuid) {
        return energy.getOrDefault(
            uuid,
            getConfig().getInt("energy.starting-energy", 0)
        );
    }

    private void changeEnergy(UUID uuid, int amount) {
        int oldValue = getEnergy(uuid);
        int newValue = clampEnergy(oldValue + amount);

        energy.put(uuid, newValue);
        saveEnergyData();

        if (getConfig().getBoolean("settings.announce-energy-changes", true)) {
            Player player = Bukkit.getPlayer(uuid);

            if (player != null && oldValue != newValue) {
                String sign = newValue > oldValue ? "+" : "";
                player.sendMessage(
                    ChatColor.GOLD + "Primal Energy: "
                    + ChatColor.YELLOW + sign + (newValue - oldValue)
                    + ChatColor.GRAY + " (Total: "
                    + ChatColor.WHITE + newValue + ChatColor.GRAY + ")"
                );
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        changeEnergy(
            victim.getUniqueId(),
            -getConfig().getInt("energy.death-penalty", 1)
        );

        Player killer = victim.getKiller();

        if (killer != null) {
            changeEnergy(
                killer.getUniqueId(),
                getConfig().getInt("energy.kill-reward", 1)
            );
        }
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (command.getName().equalsIgnoreCase("energy")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in-game.");
                return true;
            }

            int currentEnergy = getEnergy(player.getUniqueId());

            player.sendMessage(
                ChatColor.GOLD + "Your Primal Energy: "
                + ChatColor.YELLOW + currentEnergy
            );
            return true;
        }

        if (command.getName().equalsIgnoreCase("primal")) {
            sender.sendMessage(
                ChatColor.GREEN + "PrimalRealms commands:"
            );
            sender.sendMessage(
                ChatColor.YELLOW + "/energy"
                + ChatColor.GRAY + " - View your Energy"
            );
            return true;
        }

        return false;
    }
}

