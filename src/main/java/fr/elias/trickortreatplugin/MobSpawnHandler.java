package fr.elias.trickortreatplugin;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

public class MobSpawnHandler implements Listener {
    private final TrickOrTreatPlugin plugin;
    private final FileConfiguration cfg;
    private final LibsDisguisesHandler disguises; // null when LD disabled/not installed
    private final Random random = new Random();

    public MobSpawnHandler(TrickOrTreatPlugin plugin, FileConfiguration hauntedMobsConfig, LibsDisguisesHandler disguises) {
        this.plugin = plugin;
        this.cfg = hauntedMobsConfig;
        this.disguises = disguises;
    }

    @EventHandler
    public void onMobSpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();

        if (entity instanceof Zombie) {
            handleSpooky((Monster) entity, "zombie");
            handleOptionalDisguise((Monster) entity, "zombie");
        } else if (entity instanceof Skeleton) {
            handleSpooky((Monster) entity, "skeleton");
            handleOptionalDisguise((Monster) entity, "skeleton");
        }

        // Jump-scare sound
        double chance = cfg.getDouble("jump-scares.ghost-mob.spawn-chance", 0.15);
        if (random.nextDouble() < chance) {
            String snd = cfg.getString("jump-scares.ghost-mob.sound", "ENTITY_GHAST_SCREAM");
            try {
                entity.getWorld().playSound(entity.getLocation(), Sound.valueOf(snd.toUpperCase()), 1.0f, 1.0f);
            } catch (IllegalArgumentException ignored) {}

            // Optional LD disguise for jump-scare (brief effect)
            if (disguises != null) {
                String as = cfg.getString("jump-scares.ghost-mob.disguise-as", null);
                int dur = Math.max(1, cfg.getInt("jump-scares.ghost-mob.disguise-duration-seconds", 3));
                if (as != null && !as.isEmpty()) {
                    disguises.disguiseEntityTemporarily(entity, as, dur, plugin);
                }
            }

            if (cfg.getBoolean("logging.jump-scares", false)) {
                plugin.getLogger().info("A ghost mob jump scare occurred!");
            }
        }
    }

    private void handleSpooky(Monster mob, String type) {
        double chance = cfg.getDouble("spooky-mobs." + type + ".spawn-chance", 0.0);
        if (random.nextDouble() >= chance) return;

        // Helmet (NPE-safe)
        String headMat = cfg.getString("spooky-mobs." + type + ".head", "CARVED_PUMPKIN");
        Material mat = Material.matchMaterial(headMat);
        if (mat != null) {
            EntityEquipment eq = mob.getEquipment();
            if (eq != null) {
                eq.setHelmet(new ItemStack(mat));
            }
        }

        // Sound
        String soundName = cfg.getString("spooky-mobs." + type + ".sound", "ENTITY_WITHER_SPAWN");
        try {
            mob.getWorld().playSound(mob.getLocation(), Sound.valueOf(soundName.toUpperCase()), 1.0F, 1.0F);
        } catch (IllegalArgumentException ignored) {}

        if (cfg.getBoolean("logging.spooky-mobs", false)) {
            plugin.getLogger().info("A spooky " + type + " has spawned with custom head and sound.");
        }
    }

    private void handleOptionalDisguise(Monster mob, String type) {
        if (disguises == null) return; // LD not enabled
        String pathBase = "spooky-mobs." + type + ".";

        String as = cfg.getString(pathBase + "disguise-as", null);
        if (as == null || as.isEmpty()) return;

        double chance = cfg.getDouble(pathBase + "disguise-chance", 0.25);
        if (random.nextDouble() >= chance) return;

        int dur = Math.max(1, cfg.getInt(pathBase + "disguise-duration-seconds", 30));
        disguises.disguiseEntityTemporarily(mob, as, dur, plugin);
    }
}
