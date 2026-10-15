
package com.primalrealms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class PrimalRealmsPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Integer> energy = new HashMap<>();
    private final Map<UUID, String> affinities = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Random random = new Random();

    private File dataFile;
    private YamlConfiguration data;
    private NamespacedKey rerollKey;

    private final String[] availableAffinities = {
        "Flora", "Abyss", "Sol", "Terra", "Tempest", "Umbra"
    };

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPlayerData();

        rerollKey = new NamespacedKey(this, "affinity_reroll");

        Bukkit.getPluginManager().registerEvents(this, this);
        registerRerollRecipe();

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
                    energy.put(uuid, clampEnergy(data.getInt(
                        key + ".energy",
                        getConfig().getInt("energy.starting-energy", 0)
                    )));

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

    private ItemStack createRerollItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.LIGHT_PURPLE.toString()
            + ChatColor.BOLD + "Affinity Reroll");

        meta.setLore(java.util.Arrays.asList(
            ChatColor.GRAY + "Right-click to change your affinity.",
            ChatColor.YELLOW + "A new power awaits..."
        ));

        meta.addEnchant(
            org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true
        );

        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(
            rerollKey, PersistentDataType.BYTE, (byte) 1
        );

        item.setItemMeta(meta);
        return item;
    }

    private void registerRerollRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
            new NamespacedKey(this, "affinity_reroll_recipe"),
            createRerollItem()
        );

        recipe.shape("DGD", "GNG", "DGD");
        recipe.setIngredient('D', Material.DIAMOND_BLOCK);
        recipe.setIngredient('G', Material.GOLD_BLOCK);
        recipe.setIngredient('N', Material.NETHER_STAR);

        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        assignRandomAffinity(event.getPlayer());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getItem() == null || !event.getItem().hasItemMeta()) return;
        if (!event.getAction().isRightClick()) return;

        ItemMeta meta = event.getItem().getItemMeta();

        if (!meta.getPersistentDataContainer().has(
            rerollKey, PersistentDataType.BYTE
        )) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String current = affinities.get(uuid);

        if (current == null) {
            assignRandomAffinity(player);
            current = affinities.get(uuid);
        }

        String next;
        do {
            next = availableAffinities[
                random.nextInt(availableAffinities.length)
            ];
        } while (next.equalsIgnoreCase(current));

        affinities.put(uuid, next);
        savePlayerData();

        ItemStack held = event.getItem();
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - 1);
        }

        player.sendMessage(ChatColor.LIGHT_PURPLE
            + "Your affinity has changed!");
        player.sendMessage(ChatColor.GRAY + current + ChatColor.WHITE
            + " -> " + ChatColor.GOLD + next);
        player.sendMessage(ChatColor.GRAY
            + "Your Energy has not changed.");
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

    private void activateAbility(Player player, int number) {
        UUID uuid = player.getUniqueId();
        String affinity = affinities.get(uuid);

        if (affinity == null) {
            player.sendMessage(ChatColor.RED + "You don't have an affinity yet.");
            return;
        }

        if (!affinity.equalsIgnoreCase("Tempest")) {
            player.sendMessage(ChatColor.YELLOW
                + affinity + " abilities haven't been implemented yet.");
            return;
        }

        String key = uuid + ":tempest:" + number;
        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(key, 0L);

        if (now < readyAt) {
            long seconds = (readyAt - now + 999) / 1000;
            player.sendMessage(ChatColor.RED
                + "That ability is on cooldown for " + seconds + "s.");
            return;
        }

        if (number == 1) {
            Player target = null;
            for (org.bukkit.entity.Entity entity :
                    player.getNearbyEntities(12, 12, 12)) {
                if (entity instanceof Player nearby
                        && !nearby.equals(player)
                        && player.hasLineOfSight(nearby)
                        && player.getLocation().distanceSquared(
                            nearby.getLocation()) <= 144) {
                    Vector direction = nearby.getEyeLocation().toVector()
                        .subtract(player.getEyeLocation().toVector());

                    if (direction.lengthSquared() > 0) {
                        direction.normalize();
                    }

                    if (player.getEyeLocation().getDirection()
                            .normalize().dot(direction) > 0.97) {
                        target = nearby;
                        break;
                    }
                }
            }

            if (target == null) {
                player.sendMessage(ChatColor.RED
                    + "Look directly at a nearby player to use Lightning Bolt.");
                return;
            }

            target.getWorld().strikeLightning(target.getLocation());
            cooldowns.put(key, now + 15000);
            player.sendMessage(ChatColor.AQUA + "Lightning Bolt unleashed!");
        } else if (number == 2) {
            Vector direction = player.getLocation().getDirection().normalize();
            direction.multiply(1.4);
            direction.setY(0.45);

            player.setVelocity(direction);
            cooldowns.put(key, now + 10000);
            player.sendMessage(ChatColor.AQUA + "Windstep activated!");
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

            player.sendMessage(ChatColor.GOLD + "Your Primal Energy: "
                + ChatColor.YELLOW + getEnergy(player.getUniqueId()));
            return true;
        }

        if (command.getName().equalsIgnoreCase("ability")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in-game.");
                return true;
            }

            if (args.length != 1
                    || (!args[0].equals("1") && !args[0].equals("2"))) {
                player.sendMessage(ChatColor.YELLOW
                    + "Usage: /ability <1|2>");
                return true;
            }

            activateAbility(player, Integer.parseInt(args[0]));
            return true;
        }

        if (command.getName().equalsIgnoreCase("primal")) {
            sender.sendMessage(ChatColor.GREEN + "PrimalRealms commands:");
            sender.sendMessage(ChatColor.YELLOW + "/energy"
                + ChatColor.GRAY + " - View your Energy");
            sender.sendMessage(ChatColor.YELLOW + "/ability 1 or /ability 2"
                + ChatColor.GRAY + " - Use your active abilities");
            return true;
        }

        return false;
    }
}
