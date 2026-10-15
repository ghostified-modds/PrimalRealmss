
package com.primalrealms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class PrimalRealmsPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Integer> energy = new HashMap<>();
    private final Map<UUID, String> affinities = new HashMap<>();
    private final Random random = new Random();

    private File dataFile;
    private YamlConfiguration data;

    private final String[] availableAffinities = {
        "Flora", "Abyss", "Sol", "Terra", "Tempest", "Umbra"
    };

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPlayerData();

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("PrimalRealms has awakened!");
    }

    @Override
    public void onDisable() {
        savePlayerData();
        getLogger().info("PrimalRealms has been disabled.");
    }

    private void loadPlayerData() {
        dataFile = new File(getDataFolder(), "players.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        for (String key : data.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);

                if (data.isInt(key)) {
                    energy.put(uuid, clampEnergy(data.getInt(key)));
                } else {
                    energy.put(uuid, clampEnergy(data.getInt(key + ".energy",
                        getConfig().getInt("energy.starting-energy", 0))));

                    String affinity = data.getString(key + ".affinity");

                    if (affinity != null && isValidAffinity(affinity)) {
                        affinities.put(uuid, affinity);
                    }
                }
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Skipping invalid player UUID: " + key);
            }
        }
    }

    private void savePlayerData() {
        if (dataFile == null || data == null) {
            return;
        }

        for (Map.Entry<UUID, Integer> entry : energy.entrySet()) {
            data.set(entry.getKey() + ".energy", entry.getValue());
        }

        for (Map.Entry<UUID, String> entry : affinities.entrySet()) {
            data.set(entry.getKey() + ".affinity", entry.getValue());
        }

        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save player data!");
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
        savePlayerData();

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

    private boolean isValidAffinity(String affinity) {
        for (String available : availableAffinities) {
            if (available.equalsIgnoreCase(affinity)) {
                return true;
            }
        }

        return false;
    }

    private void assignRandomAffinity(Player player) {
        UUID uuid = player.getUniqueId();

        if (affinities.containsKey(uuid)) {
            return;
        }

        String affinity = availableAffinities[
            random.nextInt(availableAffinities.length)
        ];

        affinities.put(uuid, affinity);
        savePlayerData();

        player.sendMessage(ChatColor.LIGHT_PURPLE + "Your affinity is "
            + ChatColor.GOLD + affinity + ChatColor.LIGHT_PURPLE + "!");

        player.sendMessage(ChatColor.GRAY
            + "Your journey with PrimalRealms begins now.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        assignRandomAffinity(event.getPlayer());
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

            player.sendMessage(
                ChatColor.GOLD + "Your Primal Energy: "
                + ChatColor.YELLOW + getEnergy(player.getUniqueId())
            );

            return true;
        }

        if (command.getName().equalsIgnoreCase("primal")) {
            sender.sendMessage(ChatColor.GREEN + "PrimalRealms commands:");
            sender.sendMessage(ChatColor.YELLOW + "/energy"
                + ChatColor.GRAY + " - View your Energy");
            return true;
        }

        return false;
    }
}
