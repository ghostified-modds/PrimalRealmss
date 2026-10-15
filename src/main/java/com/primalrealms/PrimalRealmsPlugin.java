
package com.primalrealms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class PrimalRealmsPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Integer> energy = new HashMap<>();
    private final Map<UUID, String> affinities = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> lastDamage = new HashMap<>();
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

        // Check automatic passives every second.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                applyPassive(player);
            }
        }, 20L, 20L);

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
        if (dataFile == null || data == null) return;

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
            uuid, getConfig().getInt("energy.starting-energy", 0)
        );
    }

    private void changeEnergy(UUID uuid, int amount) {
        int oldValue = getEnergy(uuid);
        int newValue = clampEnergy(oldValue + amount);

        energy.put(uuid, newValue);
        savePlayerData();

        if (getConfig().getBoolean(
                "settings.announce-energy-changes", true)) {
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
            if (available.equalsIgnoreCase(affinity)) return true;
        }
        return false;
    }

    private void assignRandomAffinity(Player player) {
        UUID uuid = player.getUniqueId();
        if (affinities.containsKey(uuid)) return;

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
        meta.setLore(Arrays.asList(
            ChatColor.GRAY + "Right-click to change your affinity.",
            ChatColor.YELLOW + "A new power awaits..."
        ));
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
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
                rerollKey, PersistentDataType.BYTE)) return;

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
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            lastDamage.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        changeEnergy(victim.getUniqueId(),
            -getConfig().getInt("energy.death-penalty", 1));

        Player killer = victim.getKiller();
        if (killer != null) {
            changeEnergy(killer.getUniqueId(),
                getConfig().getInt("energy.kill-reward", 1));
        }
    }

    // Automatic passive powers.
    private void applyPassive(Player player) {
        String affinity = affinities.get(player.getUniqueId());
        if (affinity == null) return;

        switch (affinity.toLowerCase()) {
            case "flora" -> {
                // Regeneration after 8 seconds without taking damage.
                long lastHit = lastDamage.getOrDefault(
                    player.getUniqueId(), 0L);
                if (System.currentTimeMillis() - lastHit >= 8000) {
                    giveEffect(player, PotionEffectType.REGENERATION, 40, 0);
                }
            }
            case "abyss" -> {
                // Extra protection while underwater.
                if (player.isInWater()) {
                    giveEffect(player, PotionEffectType.RESISTANCE, 40, 0);
                }
            }
            case "sol" -> {
                // A little extra speed outdoors in daylight.
                if (player.getWorld().isDayTime()
                        && player.getLocation().getBlock().getLightFromSky() >= 12
                        && player.getLocation().getBlock().getLightLevel() >= 12) {
                    giveEffect(player, PotionEffectType.SPEED, 40, 0);
                }
            }
            case "terra" -> {
                // Extra protection while standing on solid ground.
                if (player.isOnGround()) {
                    giveEffect(player, PotionEffectType.RESISTANCE, 40, 0);
                }
            }
            case "tempest" -> {
                // Faster movement while sprinting.
                if (player.isSprinting()) {
                    giveEffect(player, PotionEffectType.SPEED, 40, 0);
                }
            }
            case "umbra" -> {
                // Faster movement in dim areas.
                if (player.getLocation().getBlock().getLightLevel() <= 4) {
                    giveEffect(player, PotionEffectType.SPEED, 40, 0);
                }
            }
            default -> { }
        }
    }

    private void giveEffect(Player player, PotionEffectType type,
                            int duration, int amplifier) {
        player.addPotionEffect(new PotionEffect(
            type, duration, amplifier, true, false, true
        ));
    }

    // Find the player the caster is looking toward.
    private Player findTarget(Player caster, double range) {
        Player best = null;
        double bestDistance = range * range;
        Vector look = caster.getEyeLocation().getDirection().normalize();

        for (Entity entity : caster.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof Player target) || target.equals(caster)) {
                continue;
            }
            if (!caster.hasLineOfSight(target)) continue;

            Vector direction = target.getEyeLocation().toVector()
                .subtract(caster.getEyeLocation().toVector());
            double distance = direction.lengthSquared();
            if (distance <= 0 || distance > range * range) continue;

            direction.normalize();
            if (look.dot(direction) < 0.94) continue;

            if (distance < bestDistance) {
                best = target;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void activateAbility(Player player, int number) {
        String affinity = affinities.get(player.getUniqueId());

        if (affinity == null) {
            player.sendMessage(ChatColor.RED + "You don't have an affinity yet.");
            return;
        }

        String key = player.getUniqueId() + ":" + affinity.toLowerCase()
            + ":" + number;
        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(key, 0L);

        if (now < readyAt) {
            long seconds = (readyAt - now + 999) / 1000;
            player.sendMessage(ChatColor.RED
                + "That ability is on cooldown for " + seconds + "s.");
            return;
        }

        // Keep cooldowns fair and independent for each ability.
        long cooldown = 15000L;
        if (affinity.equalsIgnoreCase("Terra")
                && number == 2) cooldown = 20000L;
        if (affinity.equalsIgnoreCase("Umbra")
                && number == 2) cooldown = 20000L;

        boolean success = false;

        switch (affinity.toLowerCase()) {
            case "flora" -> {
                if (number == 1) {
                    Player target = findTarget(player, 12);
                    if (target == null) {
                        player.sendMessage(ChatColor.RED
                            + "Look at a nearby player to snare them.");
                        return;
                    }
                    giveEffect(target, PotionEffectType.SLOWNESS, 80, 1);
                    target.sendMessage(ChatColor.GREEN
                        + "Vines have snared you!");
                    player.sendMessage(ChatColor.GREEN + "Vine Snare!");
                    success = true;
                } else {
                    for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                        if (entity instanceof Player ally) {
                            ally.setHealth(Math.min(
                                ally.getMaxHealth(), ally.getHealth() + 4.0));
                            giveEffect(ally, PotionEffectType.REGENERATION, 60, 0);
                        }
                    }
                    player.setHealth(Math.min(
                        player.getMaxHealth(), player.getHealth() + 4.0));
                    giveEffect(player, PotionEffectType.REGENERATION, 60, 0);
                    player.sendMessage(ChatColor.GREEN + "Verdant Bloom!");
                    success = true;
                }
            }
            case "abyss" -> {
                if (number == 1) {
                    Player target = findTarget(player, 12);
                    if (target == null) {
                        player.sendMessage(ChatColor.RED
                            + "Look at a nearby player to use Abyssal Grasp.");
                        return;
                    }
                    Vector pull = player.getLocation().toVector()
                        .subtract(target.getLocation().toVector());
                    if (pull.lengthSquared() > 0) pull.normalize();
                    pull.multiply(1.25);
                    pull.setY(0.35);
                    target.setVelocity(pull);
                    target.sendMessage(ChatColor.DARK_AQUA
                        + "The Abyss pulls you!");
                    player.sendMessage(ChatColor.DARK_AQUA + "Abyssal Grasp!");
                    success = true;
                } else {
                    Vector dash = player.getLocation().getDirection().normalize()
                        .multiply(1.2);
                    dash.setY(0.4);
                    player.setVelocity(dash);
                    giveEffect(player, PotionEffectType.RESISTANCE, 40, 0);
                    player.sendMessage(ChatColor.DARK_AQUA + "Void Surge!");
                    success = true;
                }
            }
            case "sol" -> {
                if (number == 1) {
                    Player target = findTarget(player, 16);
                    if (target == null) {
                        player.sendMessage(ChatColor.RED
                            + "Look at a nearby player to use Solar Lance.");
                        return;
                    }
                    target.damage(5.0, player);
                    target.setFireTicks(Math.max(target.getFireTicks(), 40));
                    player.sendMessage(ChatColor.GOLD + "Solar Lance!");
                    success = true;
                } else {
                    int hit = 0;
                    for (Entity entity : player.getNearbyEntities(5, 3, 5)) {
                        if (entity instanceof Player target
                                && !target.equals(player)) {
                            target.damage(4.0, player);
                            giveEffect(target, PotionEffectType.GLOWING, 60, 0);
                            hit++;
                        }
                    }
                    player.sendMessage(ChatColor.GOLD
                        + "Radiant Burst! Enemies hit: " + hit);
                    success = true;
                }
            }
            case "terra" -> {
                if (number == 1) {
                    int hit = 0;
                    for (Entity entity : player.getNearbyEntities(4, 3, 4)) {
                        if (entity instanceof Player target
                                && !target.equals(player)) {
                            target.damage(4.0, player);
                            Vector knockback = target.getLocation().toVector()
                                .subtract(player.getLocation().toVector());
                            if (knockback.lengthSquared() > 0) {
                                knockback.normalize().multiply(0.8);
                            }
                            knockback.setY(0.35);
                            target.setVelocity(knockback);
                            hit++;
                        }
                    }
                    player.sendMessage(ChatColor.GRAY
                        + "Seismic Strike! Enemies hit: " + hit);
                    success = true;
                } else {
                    giveEffect(player, PotionEffectType.RESISTANCE, 100, 1);
                    giveEffect(player, PotionEffectType.ABSORPTION, 100, 1);
                    player.sendMessage(ChatColor.GRAY + "Earthguard!");
                    success = true;
                }
            }
            case "tempest" -> {
                if (number == 1) {
                    Player target = findTarget(player, 12);
                    if (target == null) {
                        player.sendMessage(ChatColor.RED
                            + "Look directly at a nearby player to use Lightning Bolt.");
                        return;
                    }
                    target.getWorld().strikeLightning(target.getLocation());
                    player.sendMessage(ChatColor.AQUA + "Lightning Bolt unleashed!");
                    success = true;
                } else {
                    Vector direction = player.getLocation().getDirection()
                        .normalize().multiply(1.4);
                    direction.setY(0.45);
                    player.setVelocity(direction);
                    player.sendMessage(ChatColor.AQUA + "Windstep activated!");
                    success = true;
                }
            }
            case "umbra" -> {
                if (number == 1) {
                    Vector direction = player.getLocation().getDirection()
                        .normalize();
                    Location origin = player.getLocation();
                    Location destination = null;

                    for (double distance = 5; distance >= 1; distance -= 1) {
                        Location candidate = origin.clone().add(
                            direction.clone().multiply(distance));
                        Block feet = candidate.getBlock();
                        Block head = candidate.clone().add(0, 1, 0).getBlock();
                        Block below = candidate.clone().subtract(0, 1, 0).getBlock();

                        if (feet.isPassable() && head.isPassable()
                                && !below.isPassable()) {
                            destination = candidate;
                            break;
                        }
                    }

                    if (destination == null) {
                        player.sendMessage(ChatColor.RED
                            + "There isn't a safe place to Shadowstep.");
                        return;
                    }

                    destination.setYaw(player.getLocation().getYaw());
                    destination.setPitch(player.getLocation().getPitch());
                    player.teleport(destination);
                    player.sendMessage(ChatColor.DARK_PURPLE + "Shadowstep!");
                    success = true;
                } else {
                    int hit = 0;
                    for (Entity entity : player.getNearbyEntities(5, 3, 5)) {
                        if (entity instanceof Player target
                                && !target.equals(player)) {
                            giveEffect(target, PotionEffectType.WEAKNESS, 60, 0);
                            giveEffect(target, PotionEffectType.BLINDNESS, 40, 0);
                            hit++;
                        }
                    }
                    player.sendMessage(ChatColor.DARK_PURPLE
                        + "Eclipse! Enemies affected: " + hit);
                    success = true;
                }
            }
            default -> player.sendMessage(ChatColor.RED
                + "That affinity isn't recognised.");
        }

        if (success) {
            cooldowns.put(key, now + cooldown);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
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
                player.sendMessage(ChatColor.YELLOW + "Usage: /ability <1|2>");
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
