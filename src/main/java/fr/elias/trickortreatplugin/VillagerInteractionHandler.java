package fr.elias.trickortreatplugin;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class VillagerInteractionHandler implements Listener {
    private final TrickOrTreatPlugin plugin;
    // Primary config (config.yml)
    private final FileConfiguration mainCfg;
    // Secondary config (hauntedmobs.yml) for optional extra knobs
    private final FileConfiguration hauntedCfg;
    private final LibsDisguisesHandler disguises; // null if LD disabled/not installed

    public VillagerInteractionHandler(TrickOrTreatPlugin plugin,
                                      LibsDisguisesHandler disguises,
                                      FileConfiguration hauntedMobsConfig) {
        this.plugin = plugin;
        this.disguises = disguises;
        this.mainCfg = plugin.getConfig();      // read libdisguise & rewards/cooldowns from config.yml
        this.hauntedCfg = hauntedMobsConfig;    // optional extras (villager-interaction / villager-trick)
    }

    @EventHandler
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        Entity e = event.getRightClicked();
        if (!(e instanceof Villager)) return;

        // Optional: cancel trade UI (prefer hauntedmobs.yml key; fallback to config.yml if you add it there)
        if (getBool("villager-interaction.cancel-trade", true)) {
            event.setCancelled(true);
        }

        // Optional: restrict to named villager
        String requiredName = getString("villager-interaction.only-named", "").trim();
        if (!requiredName.isEmpty()) {
            String custom = ((Villager) e).getCustomName();
            if (custom == null || !org.bukkit.ChatColor.stripColor(custom).equalsIgnoreCase(requiredName)) {
                return;
            }
        }

        Player player = event.getPlayer();

        // Optional: require empty hand
        if (getBool("villager-interaction.require-empty-hand", false)
                && player.getInventory().getItemInMainHand() != null
                && player.getInventory().getItemInMainHand().getType().isItem()) {
            sendConfigured(player, "villager-interaction.messages.require-empty-hand",
                    "§cEmpty your hand to trick-or-treat!");
            return;
        }

        // Cooldown (config.yml provides the group cooldowns)
        if (!player.hasPermission("trickortreat.bypass.cooldown")) {
            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();
            long cooldownMs = plugin.getCooldown(player) * 1000L;

            Long last = plugin.getCooldowns().get(id);
            if (last != null && now - last < cooldownMs) {
                long left = (cooldownMs - (now - last)) / 1000L;
                String msg = getString("villager-interaction.messages.cooldown",
                        "§cYou are on cooldown. Please wait %seconds% seconds.");
                player.sendMessage(msg.replace("%seconds%", String.valueOf(left)));
                return;
            }
            plugin.getCooldowns().put(id, now);
        }

        // Rewards (from config.yml via plugin.getTrickOrTreat)
        Map<String, Object> rootReward = plugin.getTrickOrTreat(player);
        if (rootReward == null || rootReward.isEmpty()) {
            sendConfigured(player, "villager-interaction.messages.no-reward", "§cNo rewards configured.");
            return;
        }

        triggerReward(player, rootReward);
    }

    @SuppressWarnings("unchecked")
    private void triggerReward(Player player, Map<String, Object> rewardRoot) {
        boolean isTreat = rollTreat();
        String branch = isTreat ? "treats" : "tricks";

        Object branchObj = rewardRoot.get(branch);

        // Accept both Map and ConfigurationSection
        Map<String, Object> selected = null;
        if (branchObj instanceof Map) {
            selected = (Map<String, Object>) branchObj;
        } else if (branchObj instanceof org.bukkit.configuration.ConfigurationSection) {
            selected = ((org.bukkit.configuration.ConfigurationSection) branchObj).getValues(false);
        }

        if (selected == null) {
            player.sendMessage("§cInvalid reward config (" + branch + ").");
            return;
        }

        // Command (console)
        String command = String.valueOf(selected.getOrDefault("command", ""));
        if (!command.isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }

        // Message
        String message = String.valueOf(selected.getOrDefault("message", ""));
        if (!message.isEmpty()) {
            player.sendMessage(message);
        }

        // Event: firework + sound
        Object eventObj = selected.get("event");
        if (eventObj instanceof Map) {
            Map<String, Object> evt = (Map<String, Object>) eventObj;

            if (Boolean.TRUE.equals(evt.get("firework"))) {
                player.getWorld().spawn(player.getLocation(), Firework.class);
            }

            Object soundName = evt.get("sound");
            if (soundName instanceof String) {
                try {
                    player.getWorld().playSound(
                            player.getLocation(),
                            Sound.valueOf(((String) soundName).toUpperCase()),
                            1.0F, 1.0F
                    );
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // LibsDisguises trick (unchanged)
        boolean libEnabled = mainCfg.getBoolean("libdisguise.enabled", false);
        if (!isTreat && disguises != null && libEnabled
                && mainCfg.getBoolean("libdisguise.villager-trick.enabled", true)) {
            String mob = mainCfg.getString("libdisguise.villager-trick.mob", "WITCH");
            int secs = Math.max(1, mainCfg.getInt("libdisguise.villager-trick.duration-seconds", 8));
            disguises.disguisePlayerTemporarily(player, mob, secs, plugin);
        }
    }


    private boolean rollTreat() {
        // Prefer a configurable chance if provided; default 50/50
        double treatChance = getDouble("villager-interaction.treat-chance", 0.5D);
        treatChance = Math.max(0D, Math.min(1D, treatChance));
        return ThreadLocalRandom.current().nextDouble() < treatChance;
    }

    private void sendConfigured(Player p, String path, String fallback) {
        String msg = getString(path, fallback);
        if (msg != null && !msg.isEmpty()) p.sendMessage(msg);
    }

    // ------- Config getters with fallback: hauntedmobs.yml first, then config.yml -------
    private boolean getBool(String path, boolean def) {
        if (hauntedCfg.isBoolean(path)) return hauntedCfg.getBoolean(path);
        if (mainCfg.isBoolean(path)) return mainCfg.getBoolean(path);
        return def;
    }
    private String getString(String path, String def) {
        if (hauntedCfg.isString(path)) return hauntedCfg.getString(path, def);
        if (mainCfg.isString(path)) return mainCfg.getString(path, def);
        return def;
    }
    private int getInt(String path, int def) {
        if (hauntedCfg.isInt(path)) return hauntedCfg.getInt(path, def);
        if (mainCfg.isInt(path)) return mainCfg.getInt(path, def);
        return def;
    }
    private double getDouble(String path, double def) {
        if (hauntedCfg.isDouble(path)) return hauntedCfg.getDouble(path, def);
        if (mainCfg.isDouble(path)) return mainCfg.getDouble(path, def);
        // isInt fallback (YAML ints)
        if (hauntedCfg.isInt(path)) return hauntedCfg.getInt(path);
        if (mainCfg.isInt(path)) return mainCfg.getInt(path);
        return def;
    }
}
