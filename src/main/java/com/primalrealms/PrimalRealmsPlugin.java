
package com.primalrealms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.UUID;

public final class PrimalRealmsPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Integer> energy = new HashMap<>();
    private final Map<UUID, String> affinities = new HashMap<>();
    private final Map<UUID, Set<UUID>> trustedPlayers = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> lastDamage = new HashMap<>();
    private final Random random = new Random();

    private File dataFile;
    private YamlConfiguration data;
    private NamespacedKey rerollKey;
    private NamespacedKey energyItemKey;

    private final String[] availableAffinities = {
        "Flora", "Abyss", "Sol", "Terra", "Tempest", "Umbra"
    };

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPlayerData();

        rerollKey = new NamespacedKey(this, "affinity_reroll");
        energyItemKey = new NamespacedKey(this, "primal_energy_item");

        Bukkit.getPluginManager().registerEvents(this, this);
        registerRerollRecipe();
        registerEnergyRecipe();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                applyPassive(player);
                showCooldownDisplay(player);
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

                    for (String trustedId : data.getStringList(key + ".trusted")) {
                        try {
                            trustedPlayers.computeIfAbsent(uuid, ignored -> new HashSet<>())
                                .add(UUID.fromString(trustedId));
                        } catch (IllegalArgumentException exception) {
                            getLogger().warning("Skipping invalid trusted UUID for " + key);
                        }
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

        for (Map.Entry<UUID, Set<UUID>> entry : trustedPlayers.entrySet()) {
            data.set(entry.getKey() + ".trusted", entry.getValue().stream()
                .map(UUID::toString).toList());
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
        // Always enforce the configured energy limits, including for any older saved data.
        int storedEnergy = energy.getOrDefault(
            uuid, getConfig().getInt("energy.starting-energy", 0)
        );
        return clampEnergy(storedEnergy);
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
        recipe.setIngredient('N', Material.HEAVY_CORE);

        Bukkit.addRecipe(recipe);
    }

    private ItemStack createEnergyItem() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.AQUA.toString() + ChatColor.BOLD + "Primal Energy");
        meta.setLore(Arrays.asList(
            ChatColor.GRAY + "Contains 1 Primal Energy.",
            ChatColor.YELLOW + "Right-click to absorb it."
        ));
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(
            energyItemKey, PersistentDataType.BYTE, (byte) 1
        );

        item.setItemMeta(meta);
        return item;
    }

    private void registerEnergyRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
            new NamespacedKey(this, "primal_energy_recipe"),
            createEnergyItem()
        );

        // Eight Netherite ingots surrounding a Nether Star.
        // This is intentionally much more expensive than the Affinity Reroll recipe.
        recipe.shape("NNN", "NSN", "NNN");
        recipe.setIngredient('N', Material.NETHERITE_INGOT);
        recipe.setIngredient('S', Material.NETHER_STAR);

        Bukkit.addRecipe(recipe);
    }

    private boolean isEnergyItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(
            energyItemKey, PersistentDataType.BYTE
        );
    }

    private void consumeOneFromMainHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - 1);
        }
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

        Player player = event.getPlayer();
        ItemStack clickedItem = event.getItem();

        if (isEnergyItem(clickedItem)) {
            event.setCancelled(true);
            int currentEnergy = getEnergy(player.getUniqueId());
            int maximumEnergy = getConfig().getInt("energy.maximum-energy", 5);

            if (currentEnergy >= maximumEnergy) {
                player.sendMessage(ChatColor.RED
                    + "Your Primal Energy is already at its maximum (" + maximumEnergy + ").");
                return;
            }

            consumeOneFromMainHand(player);
            changeEnergy(player.getUniqueId(), 1);
            player.sendMessage(ChatColor.AQUA + "You absorbed 1 Primal Energy!");
            return;
        }

        ItemMeta meta = clickedItem.getItemMeta();

        if (!meta.getPersistentDataContainer().has(
                rerollKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);

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

        consumeOneFromMainHand(player);

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

        // Players at minimum energy (-5 by default) do not lose any more on death.
        int minimumEnergy = getConfig().getInt("energy.minimum-energy", -5);
        if (getEnergy(victim.getUniqueId()) > minimumEnergy) {
            changeEnergy(victim.getUniqueId(),
                -getConfig().getInt("energy.death-penalty", 1));
        }

        Player killer = victim.getKiller();

        if (killer != null) {
            int maximumEnergy = getConfig().getInt("energy.maximum-energy", 5);
            int killerEnergy = getEnergy(killer.getUniqueId());

            if (killerEnergy >= maximumEnergy) {
                // A player already at maximum energy cannot absorb the kill reward,
                // so leave a physical Energy item for them to collect or share.
                killer.getWorld().dropItemNaturally(
                    killer.getLocation(), createEnergyItem());
                killer.sendMessage(ChatColor.AQUA
                    + "You're already at maximum Primal Energy! An Energy item has dropped at your feet.");
            } else {
                changeEnergy(killer.getUniqueId(),
                    getConfig().getInt("energy.kill-reward", 1));
            }
        }
    }

    private void applyPassive(Player player) {
        String affinity = affinities.get(player.getUniqueId());
        if (affinity == null) return;

        switch (affinity.toLowerCase()) {
            case "flora" -> {
                long lastHit = lastDamage.getOrDefault(
                    player.getUniqueId(), 0L);

                // Flora's passive regeneration begins 3 seconds after taking damage.
                if (System.currentTimeMillis() - lastHit >= 3000) {
                    giveEffect(player, PotionEffectType.REGENERATION, 40, 0);
                }
            }
            case "abyss" -> {
                if (player.isInWater()) {
                    giveEffect(player, PotionEffectType.SPEED, 40, 0);
                }
            }
            case "sol" -> {
                // Sol permanently grants Fire Resistance while this affinity is active.
                giveEffect(player, PotionEffectType.FIRE_RESISTANCE, 40, 0);
            }
            case "terra" -> {
                if (player.isOnGround()) {
                    giveEffect(player, PotionEffectType.RESISTANCE, 40, 0);
                }
            }
            case "tempest" -> {
                if (player.isSprinting()) {
                    giveEffect(player, PotionEffectType.SPEED, 40, 0);
                }
            }
            case "umbra" -> {
                // Health Boost I adds two hearts while Umbra is the player's affinity.
                giveEffect(player, PotionEffectType.HEALTH_BOOST, 40, 0);
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

    // Active-ability energy scaling only: -5 = 0.5x, 0 = 1.0x, +5 = 1.5x.
    // Cooldowns and passive effects do not use this multiplier.
    private double getEnergyMultiplier(Player player) {
        int currentEnergy = getEnergy(player.getUniqueId());
        return Math.max(0.5, Math.min(1.5, 1.0 + (currentEnergy * 0.10)));
    }

    private double scalePower(Player player, double baseValue) {
        return baseValue * getEnergyMultiplier(player);
    }

    private int scaleDuration(Player player, int baseTicks) {
        return Math.max(1, (int) Math.round(baseTicks * getEnergyMultiplier(player)));
    }

    private double scaleRange(Player player, double baseRange) {
        return Math.max(1.0, baseRange * getEnergyMultiplier(player));
    }

    private boolean isTrusted(Player caster, Player target) {
        if (caster.getUniqueId().equals(target.getUniqueId())) return false;
        return trustedPlayers.getOrDefault(caster.getUniqueId(), Set.of())
            .contains(target.getUniqueId());
    }

    private boolean canAffect(Player caster, Entity target) {
        return !(target instanceof Player targetPlayer)
            || !isTrusted(caster, targetPlayer);
    }

    private void trustPlayer(Player player, String name, boolean trust) {
        if (name.equalsIgnoreCase(player.getName())) {
            player.sendMessage(ChatColor.RED + "You cannot trust or untrust yourself.");
            return;
        }

        Player onlineTarget = Bukkit.getPlayerExact(name);
        if (onlineTarget == null && Bukkit.getOfflinePlayerIfCached(name) == null) {
            player.sendMessage(ChatColor.RED
                + "That player has never joined or their name could not be found.");
            return;
        }

        UUID targetId = onlineTarget != null
            ? onlineTarget.getUniqueId()
            : Bukkit.getOfflinePlayerIfCached(name).getUniqueId();
        Set<UUID> trusted = trustedPlayers.computeIfAbsent(
            player.getUniqueId(), ignored -> new HashSet<>());

        if (trust) {
            if (!trusted.add(targetId)) {
                player.sendMessage(ChatColor.YELLOW + name + " is already trusted.");
            } else {
                player.sendMessage(ChatColor.GREEN + "You now trust " + name
                    + ". Your abilities will not target them.");
            }
        } else {
            if (!trusted.remove(targetId)) {
                player.sendMessage(ChatColor.YELLOW + name + " is not on your trusted list.");
            } else {
                player.sendMessage(ChatColor.GREEN + "You no longer trust " + name + ".");
            }
        }
        savePlayerData();
    }

    private LivingEntity findTarget(Player caster, double range) {
        LivingEntity best = null;
        double bestDistance = range * range;
        Vector look = caster.getEyeLocation().getDirection().normalize();

        for (Entity entity : caster.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof LivingEntity target)
                    || target.equals(caster) || !canAffect(caster, target)) {
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

    private void showCooldownDisplay(Player player) {
        String affinity = affinities.get(player.getUniqueId());
        if (affinity == null) return;

        long now = System.currentTimeMillis();
        String first = getCooldownLabel(player, affinity, 1, now);
        String second = getCooldownLabel(player, affinity, 2, now);

        player.sendActionBar(
            Component.text("Ability 1: ", NamedTextColor.GRAY)
                .append(first.equals("READY")
                    ? Component.text(first, NamedTextColor.GREEN)
                    : Component.text(first, NamedTextColor.GOLD))
                .append(Component.text("  |  Ability 2: ", NamedTextColor.GRAY))
                .append(second.equals("READY")
                    ? Component.text(second, NamedTextColor.GREEN)
                    : Component.text(second, NamedTextColor.GOLD))
        );
    }

    private String getCooldownLabel(Player player, String affinity,
                                    int number, long now) {
        String key = player.getUniqueId() + ":"
            + affinity.toLowerCase() + ":" + number;
        long readyAt = cooldowns.getOrDefault(key, 0L);
        if (now >= readyAt) return "READY";
        return ((readyAt - now + 999) / 1000) + "s";
    }

    private boolean hasDragonEgg(Player player) {
        return player.getInventory().contains(Material.DRAGON_EGG);
    }

    // Lightweight particle helpers for ability visuals. These are cosmetic only.
    private void particleBurst(Location location, Particle particle, int count,
                               double offsetX, double offsetY, double offsetZ, double speed) {
        if (location == null || location.getWorld() == null) return;
        location.getWorld().spawnParticle(particle, location, count,
            offsetX, offsetY, offsetZ, speed);
    }

    private void particleRing(Location center, Particle particle, double radius, int points, double yOffset) {
        if (center == null || center.getWorld() == null) return;
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2.0 * i) / points;
            Location point = center.clone().add(Math.cos(angle) * radius, yOffset, Math.sin(angle) * radius);
            center.getWorld().spawnParticle(particle, point, 2, 0.08, 0.08, 0.08, 0.01);
        }
    }

    private void particleLine(Location start, Location end, Particle particle, int points) {
        if (start == null || end == null || start.getWorld() == null
                || !start.getWorld().equals(end.getWorld())) return;
        Vector delta = end.toVector().subtract(start.toVector());
        for (int i = 0; i <= points; i++) {
            Location point = start.clone().add(delta.clone().multiply((double) i / points));
            start.getWorld().spawnParticle(particle, point, 2, 0.04, 0.04, 0.04, 0.01);
        }
    }

    private void activateAbility(Player player, int number) {
        String affinity = affinities.get(player.getUniqueId());

        if (affinity == null) {
            player.sendMessage(ChatColor.RED
                + "You don't have an affinity yet.");
            return;
        }

        String key = player.getUniqueId() + ":"
            + affinity.toLowerCase() + ":" + number;

        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(key, 0L);

        if (now < readyAt) {
            long seconds = (readyAt - now + 999) / 1000;
            player.sendMessage(ChatColor.RED
                + "That ability is on cooldown for " + seconds + "s.");
            return;
        }

        // Ability-specific cooldowns, in milliseconds.
        long cooldown;
        switch (affinity.toLowerCase()) {
            case "flora" -> cooldown = (number == 1) ? 180_000L : 270_000L;
            case "abyss" -> cooldown = (number == 1) ? 120_000L : 180_000L;
            case "sol" -> cooldown = (number == 1) ? 300_000L : 240_000L;
            case "terra" -> cooldown = (number == 1) ? 120_000L : 240_000L;
            case "tempest" -> cooldown = (number == 1) ? 220_000L : 135_000L;
            case "umbra" -> cooldown = (number == 1) ? 160_000L : 130_000L;
            default -> cooldown = 120_000L;
        }

        // The Dragon Egg perk halves active-ability cooldowns for its holder only.
        if (hasDragonEgg(player)) {
            cooldown /= 2;
        }

        boolean success = false;

        switch (affinity.toLowerCase()) {
            case "flora" -> {
                if (number == 1) {
                    LivingEntity target = findTarget(player, scaleRange(player, 12));

                    if (target == null) {
                        player.sendMessage(ChatColor.RED
                            + "Look at a nearby living target to snare it.");
                        return;
                    }

                    if (target instanceof Player victim) {
                        giveEffect(victim, PotionEffectType.SLOWNESS, scaleDuration(player, 200), 1);
                        victim.sendMessage(ChatColor.GREEN
                            + "Vines have snared you!");
                    } else {
                        target.addPotionEffect(new PotionEffect(
                            PotionEffectType.SLOWNESS, scaleDuration(player, 200), 1));
                    }

                    particleBurst(target.getLocation().add(0, 1, 0), Particle.HAPPY_VILLAGER, 24, 0.7, 0.8, 0.7, 0.08);
                    particleBurst(target.getLocation(), Particle.COMPOSTER, 30, 0.6, 0.15, 0.6, 0.04);
                    particleRing(target.getLocation(), Particle.HAPPY_VILLAGER, 1.1, 16, 0.1);
                    player.sendMessage(ChatColor.GREEN + "Vine Snare!");
                    success = true;
                } else {
                    player.setHealth(Math.min(
                        player.getMaxHealth(), player.getHealth() + scalePower(player, 3.0)));
                    // Regeneration III for 5 seconds.
                    giveEffect(player, PotionEffectType.REGENERATION, scaleDuration(player, 100), 2);

                    // Floral Bloom only heals nearby players who the caster trusts.
                    Set<UUID> trusted = trustedPlayers.getOrDefault(
                        player.getUniqueId(), Set.of());
                    for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                        if (entity instanceof Player ally
                                && !ally.equals(player)
                                && trusted.contains(ally.getUniqueId())) {
                            ally.setHealth(Math.min(
                                ally.getMaxHealth(), ally.getHealth() + scalePower(player, 3.0)));
                            giveEffect(ally, PotionEffectType.REGENERATION, scaleDuration(player, 100), 2);
                            ally.sendMessage(ChatColor.GREEN
                                + "Floral Bloom healed you!");
                        }
                    }

                    particleRing(player.getLocation(), Particle.HAPPY_VILLAGER, 2.2, 28, 0.2);
                    particleBurst(player.getLocation().add(0, 1, 0), Particle.COMPOSTER, 35, 1.4, 0.8, 1.4, 0.05);
                    particleBurst(player.getLocation().add(0, 1, 0), Particle.END_ROD, 12, 0.8, 0.8, 0.8, 0.03);
                    player.sendMessage(ChatColor.GREEN + "Floral Bloom!");
                    success = true;
                }
            }

            case "abyss" -> {
                if (number == 1) {
                    LivingEntity target = findTarget(player, scaleRange(player, 12));

                    if (target == null) {
                        player.sendMessage(ChatColor.RED
                            + "Look at a nearby living target to use Abyssal Grasp.");
                        return;
                    }

                    Vector pull = player.getLocation().toVector()
                        .subtract(target.getLocation().toVector());

                    if (pull.lengthSquared() > 0) pull.normalize();

                    pull.multiply(scalePower(player, 1.25));
                    pull.setY(scalePower(player, 0.35));
                    target.setVelocity(pull);

                    if (target instanceof Player victim) {
                        victim.sendMessage(ChatColor.DARK_AQUA
                            + "The Abyss pulls you!");
                    }

                    particleBurst(target.getLocation().add(0, 1, 0), Particle.PORTAL, 38, 0.45, 0.7, 0.45, 0.45);
                    particleBurst(target.getLocation(), Particle.SOUL, 18, 0.45, 0.2, 0.45, 0.04);
                    particleLine(target.getLocation().add(0, 1, 0), player.getLocation().add(0, 1, 0), Particle.PORTAL, 18);
                    player.sendMessage(ChatColor.DARK_AQUA + "Abyssal Grasp!");
                    success = true;
                } else {
                    Vector dash = player.getLocation().getDirection()
                        .normalize().multiply(scalePower(player, 2.0));
                    dash.setY(scalePower(player, 0.4));
                    player.setVelocity(dash);

                    giveEffect(player, PotionEffectType.RESISTANCE, scaleDuration(player, 300), 0);
                    particleBurst(player.getLocation(), Particle.CLOUD, 36, 1.0, 0.35, 1.0, 0.08);
                    particleRing(player.getLocation(), Particle.SOUL, 2.0, 24, 0.2);
                    particleBurst(player.getLocation().add(0, 1, 0), Particle.BUBBLE, 18, 0.7, 0.7, 0.7, 0.05);
                    player.sendMessage(ChatColor.DARK_AQUA + "Wave Surge!");
                    success = true;
                }
            }

            case "sol" -> {
                if (number == 1) {
                    LivingEntity target = findTarget(player, scaleRange(player, 16));

                    if (target == null) {
                        player.sendMessage(ChatColor.RED
                            + "Look at a nearby living target to use Solar Lance.");
                        return;
                    }

                    // Calibrated for roughly 10 damage against a full Protection III diamond set at 0 energy.
                    target.damage(scalePower(player, 35.0), player);
                    // Extremely long fire duration; water can still extinguish it.
                    target.setFireTicks(Integer.MAX_VALUE);

                    particleLine(player.getEyeLocation(), target.getLocation().add(0, 1, 0), Particle.END_ROD, 24);
                    particleBurst(target.getLocation().add(0, 1, 0), Particle.FLAME, 28, 0.5, 0.7, 0.5, 0.06);
                    particleBurst(target.getLocation(), Particle.LAVA, 10, 0.35, 0.2, 0.35, 0.02);
                    player.sendMessage(ChatColor.GOLD + "Solar Lance!");
                    success = true;
                } else {
                    int hit = 0;

                    for (Entity entity : player.getNearbyEntities(5, 3, 5)) {
                        if (entity instanceof LivingEntity target
                                && !target.equals(player) && canAffect(player, target)) {
                            // Calibrated for roughly 4 damage against a full Protection III diamond set at 0 energy.
                            target.damage(scalePower(player, 19.5), player);
                            target.addPotionEffect(new PotionEffect(
                                PotionEffectType.GLOWING, scaleDuration(player, 600), 0));
                            hit++;
                        }
                    }

                    particleRing(player.getLocation(), Particle.FLAME, 2.6, 32, 0.2);
                    particleBurst(player.getLocation().add(0, 1, 0), Particle.END_ROD, 36, 2.0, 1.0, 2.0, 0.08);
                    particleBurst(player.getLocation().add(0, 1, 0), Particle.GLOW, 20, 1.6, 0.8, 1.6, 0.03);
                    player.sendMessage(ChatColor.GOLD
                        + "Radiant Burst! Targets hit: " + hit);
                    success = true;
                }
            }

            case "terra" -> {
                if (number == 1) {
                    int hit = 0;

                    for (Entity entity : player.getNearbyEntities(4, 3, 4)) {
                        if (entity instanceof LivingEntity target
                                && !target.equals(player) && canAffect(player, target)) {
                            // Calibrated for roughly 6 damage against a full Protection III diamond set at 0 energy.
                            target.damage(scalePower(player, 25.5), player);

                            Vector knockback = target.getLocation().toVector()
                                .subtract(player.getLocation().toVector());

                            if (knockback.lengthSquared() > 0) {
                                knockback.normalize().multiply(scalePower(player, 0.8));
                            }

                            knockback.setY(scalePower(player, 0.35));
                            target.setVelocity(knockback);
                            hit++;
                        }
                    }

                    particleRing(player.getLocation(), Particle.CRIT, 3.0, 32, 0.1);
                    particleBurst(player.getLocation(), Particle.CLOUD, 34, 2.0, 0.25, 2.0, 0.08);
                    particleBurst(player.getLocation().add(0, 0.6, 0), Particle.CRIT, 22, 1.5, 0.35, 1.5, 0.05);
                    player.sendMessage(ChatColor.GRAY
                        + "Seismic Strike! Targets hit: " + hit);
                    success = true;
                } else {
                    giveEffect(player, PotionEffectType.RESISTANCE, scaleDuration(player, 600), 1);
                    giveEffect(player, PotionEffectType.ABSORPTION, scaleDuration(player, 600), 1);
                    particleRing(player.getLocation(), Particle.CRIT, 1.7, 24, 0.15);
                    particleBurst(player.getLocation().add(0, 1, 0), Particle.CLOUD, 22, 0.8, 0.9, 0.8, 0.03);
                    particleBurst(player.getLocation().add(0, 1, 0), Particle.TOTEM_OF_UNDYING, 12, 0.55, 0.7, 0.55, 0.04);
                    player.sendMessage(ChatColor.GRAY + "Earth Guards!");
                    success = true;
                }
            }

            case "tempest" -> {
                if (number == 1) {
                    LivingEntity target = findTarget(player, scaleRange(player, 12));

                    if (target == null) {
                        player.sendMessage(ChatColor.RED
                            + "Look at a nearby living target to use Lightning Bolt.");
                        return;
                    }

                    // Three spaced strikes; visual lightning avoids fire and environmental damage.
                    final LivingEntity lightningTarget = target;
                    for (int strike = 0; strike < 3; strike++) {
                        long delay = strike * 8L;
                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            if (lightningTarget.isValid() && !lightningTarget.isDead()) {
                                lightningTarget.getWorld().strikeLightningEffect(
                                    lightningTarget.getLocation());
                                particleBurst(lightningTarget.getLocation().add(0, 1, 0),
                                    Particle.ELECTRIC_SPARK, 24, 0.6, 0.9, 0.6, 0.12);
                                // Each strike is calibrated for roughly 3 damage against a full Protection III diamond set at 0 energy.
                                lightningTarget.damage(scalePower(player, 16.0), player);
                            }
                        }, delay);
                    }
                    player.sendMessage(ChatColor.AQUA
                        + "Lightning Bolt unleashed: 3 strikes!");
                    success = true;
                } else {
                    Vector direction = player.getLocation().getDirection()
                        .normalize().multiply(scalePower(player, 2.2));

                    direction.setY(scalePower(player, 0.45));
                    player.setVelocity(direction);
                    particleBurst(player.getLocation(), Particle.CLOUD, 28, 0.8, 0.35, 0.8, 0.08);
                    particleRing(player.getLocation(), Particle.ELECTRIC_SPARK, 1.5, 20, 0.35);
                    player.sendMessage(ChatColor.AQUA + "Windstep activated!");
                    success = true;
                }
            }

            case "umbra" -> {
                if (number == 1) {
                    Vector direction = player.getLocation()
                        .getDirection().normalize();

                    Location origin = player.getLocation();
                    Location destination = null;

                    for (double distance = scaleRange(player, 8); distance >= 1; distance -= 1) {
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
                    particleBurst(origin.add(0, 1, 0), Particle.PORTAL, 30, 0.45, 0.8, 0.45, 0.3);
                    particleBurst(destination.clone().add(0, 1, 0), Particle.PORTAL, 42, 0.55, 0.9, 0.55, 0.35);
                    particleBurst(destination, Particle.SOUL, 18, 0.5, 0.2, 0.5, 0.04);
                    player.sendMessage(ChatColor.DARK_PURPLE + "Shadowstep!");
                    success = true;
                } else {
                    int hit = 0;

                    for (Entity entity : player.getNearbyEntities(scaleRange(player, 8), scaleRange(player, 5), scaleRange(player, 8))) {
                        if (entity instanceof LivingEntity target
                                && !target.equals(player) && canAffect(player, target)) {
                            target.addPotionEffect(new PotionEffect(
                                PotionEffectType.WEAKNESS, scaleDuration(player, 160), 0));
                            target.addPotionEffect(new PotionEffect(
                                PotionEffectType.BLINDNESS, scaleDuration(player, 100), 0));
                            hit++;
                        }
                    }

                    particleRing(player.getLocation(), Particle.SOUL, 3.0, 32, 0.15);
                    particleBurst(player.getLocation().add(0, 1, 0), Particle.PORTAL, 44, 2.0, 1.0, 2.0, 0.35);
                    particleBurst(player.getLocation().add(0, 1, 0), Particle.SOUL, 24, 1.5, 0.8, 1.5, 0.04);
                    player.sendMessage(ChatColor.DARK_PURPLE
                        + "Eclipse! Targets affected: " + hit);
                    success = true;
                }
            }

            default -> player.sendMessage(ChatColor.RED
                + "That affinity isn't recognised.");
        }

        if (success) {
            cooldowns.put(key, now + cooldown);
            int currentEnergy = getEnergy(player.getUniqueId());
            double multiplier = getEnergyMultiplier(player);
            String percent = Math.round(multiplier * 100) + "%";
            player.sendMessage(ChatColor.GRAY + "Energy scaling: "
                + ChatColor.AQUA + currentEnergy + " "
                + ChatColor.GRAY + "(" + percent + " power)");
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

            if (args.length == 1 && args[0].equalsIgnoreCase("withdraw")) {
                int currentEnergy = getEnergy(player.getUniqueId());
                if (currentEnergy < 1) {
                    player.sendMessage(ChatColor.RED
                        + "You need at least 1 Primal Energy to withdraw an Energy item.");
                    return true;
                }

                ItemStack energyItem = createEnergyItem();
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(energyItem);
                if (!leftovers.isEmpty()) {
                    player.sendMessage(ChatColor.RED
                        + "Your inventory is full. Make room before withdrawing Energy.");
                    return true;
                }

                changeEnergy(player.getUniqueId(), -1);
                player.sendMessage(ChatColor.AQUA
                    + "You withdrew 1 Primal Energy into an Energy item.");
                return true;
            }

            if (args.length > 0) {
                player.sendMessage(ChatColor.YELLOW + "Usage: /energy or /energy withdraw");
                return true;
            }

            player.sendMessage(ChatColor.GOLD + "Your Primal Energy: "
                + ChatColor.YELLOW + getEnergy(player.getUniqueId()));
            player.sendMessage(ChatColor.GRAY + "Use /energy withdraw to turn 1 Energy into an item.");
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

        if (command.getName().equalsIgnoreCase("trust")
                || command.getName().equalsIgnoreCase("untrust")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in-game.");
                return true;
            }
            if (args.length != 1 || args[0].isBlank()) {
                player.sendMessage(ChatColor.YELLOW + "Usage: /"
                    + command.getName().toLowerCase() + " <player>");
                return true;
            }
            trustPlayer(player, args[0], command.getName().equalsIgnoreCase("trust"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("primal")) {
            sender.sendMessage(ChatColor.GREEN + "PrimalRealms commands:");
            sender.sendMessage(ChatColor.YELLOW + "/energy"
                + ChatColor.GRAY + " - View your Energy");
            sender.sendMessage(ChatColor.YELLOW + "/ability 1 or /ability 2"
                + ChatColor.GRAY + " - Use your active abilities");
            sender.sendMessage(ChatColor.YELLOW + "/trust <player>"
                + ChatColor.GRAY + " - Protect a player from your abilities");
            sender.sendMessage(ChatColor.YELLOW + "/untrust <player>"
                + ChatColor.GRAY + " - Remove that protection");
            return true;
        }

        return false;
    }
}
