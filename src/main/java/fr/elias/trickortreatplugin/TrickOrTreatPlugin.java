package fr.elias.trickortreatplugin;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public class TrickOrTreatPlugin extends JavaPlugin {
    private static TrickOrTreatPlugin instance;
    private BossSpawnManager bossSpawnManager;
    private FileConfiguration hauntedMobsConfig;
    private FileConfiguration pumpkinHuntConfig;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private LibsDisguisesHandler disguises;
    private PumpkinHuntHandler pumpkinHandler;
    private int autoTaskId = -1;
    private BossEventListener bossListener;
    private MobSpawnHandler mobHandler;
    private VillagerInteractionHandler villagerHandler;
    private LoginDisguiseListener loginListener;

    @Override
    public void onEnable() {
        instance = this;

        // Ensure default files exist, then load
        saveDefaultConfig();
        saveResourceIfMissing("hauntedmobs.yml");
        saveResourceIfMissing("pumpkinhunt.yml");

        hauntedMobsConfig = load("hauntedmobs.yml");
        pumpkinHuntConfig = load("pumpkinhunt.yml");

        // Boss manager
        bossSpawnManager = new BossSpawnManager(
                this,
                hauntedMobsConfig.getConfigurationSection("boss-mobs.headless-horseman"),
                hauntedMobsConfig
        );

        // LibsDisguises integration (from config.yml)
        boolean ldEnabled = getConfig().getBoolean("libdisguise.enabled", false);
        boolean ldPresent = getServer().getPluginManager().getPlugin("LibsDisguises") != null;
        if (ldEnabled && ldPresent) {
            disguises = new LibsDisguisesHandler(getLogger());
            getLogger().info("LibsDisguises integration enabled (config.yml).");
        } else {
            disguises = null;
            if (ldEnabled && !ldPresent) {
                getLogger().warning("libdisguise.enabled = true in config.yml, but LibsDisguises is not installed.");
            }
        }

        // === Auto-spawn scheduler (store task id for reload) ===
        ConfigurationSection bossSec = hauntedMobsConfig.getConfigurationSection("boss-mobs.headless-horseman");
        if (bossSec != null) {
            ConfigurationSection auto = bossSec.getConfigurationSection("auto");
            if (auto != null && auto.getBoolean("enabled", false)) {
                long intervalSec = Math.max(5, auto.getLong("interval-seconds", 60));
                autoTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(
                        this,
                        bossSpawnManager::tryAutoSpawn,
                        20L * 5,               // initial delay 5s
                        20L * intervalSec      // repeat every N seconds
                );
                getLogger().info("Headless Horseman auto-spawn enabled (" + intervalSec + "s interval).");
            }
        }

        // === Register listeners (store references so we can unregister on reload) ===
        PluginManager pm = getServer().getPluginManager();

        pumpkinHandler   = new PumpkinHuntHandler(this, pumpkinHuntConfig);
        bossListener     = new BossEventListener(bossSpawnManager);
        mobHandler       = new MobSpawnHandler(this, hauntedMobsConfig, disguises);
        villagerHandler  = new VillagerInteractionHandler(this, disguises, hauntedMobsConfig);
        loginListener    = new LoginDisguiseListener(this, disguises);

        pm.registerEvents(pumpkinHandler, this);
        pm.registerEvents(bossListener, this);
        pm.registerEvents(mobHandler, this);
        pm.registerEvents(villagerHandler, this);
        pm.registerEvents(loginListener, this);

        // Command
        if (getCommand("tt") != null) {
            TrickOrTreatCommand executor = new TrickOrTreatCommand(this);
            getCommand("tt").setExecutor(executor);
            getCommand("tt").setTabCompleter(executor);
        } else {
            getLogger().warning("Command 'tt' not found in plugin.yml");
        }

        // Placeholders
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new TrickOrTreatPlaceholders(this).register();
            getLogger().info("PlaceholderAPI detected – registered %trickortreat_*% placeholders.");
        } else {
            getLogger().info("PlaceholderAPI not found – skipping placeholders.");
        }

        getLogger().info("TrickOrTreatPlugin is enabled.");
    }


    @Override
    public void onDisable() {
        if (pumpkinHandler != null) {
            pumpkinHandler.saveState(); // saves progress + placed/grown markers
        }
        getLogger().info("TrickOrTreatPlugin is disabled.");
    }

    public BossSpawnManager getBossSpawnManager() { return bossSpawnManager; }

    private void saveResourceIfMissing(String name) {
        File f = new File(getDataFolder(), name);
        if (!f.exists()) saveResource(name, false);
    }
    public void reloadAll() {
        // Persist current state first
        try {
            if (pumpkinHandler != null) pumpkinHandler.saveState();
        } catch (Exception ignored) {}

        // Cancel scheduled auto task if running
        if (autoTaskId != -1) {
            getServer().getScheduler().cancelTask(autoTaskId);
            autoTaskId = -1;
        }

        // Unregister listeners safely
        try { org.bukkit.event.HandlerList.unregisterAll(pumpkinHandler); } catch (Throwable ignored) {}
        try { org.bukkit.event.HandlerList.unregisterAll(bossListener); }   catch (Throwable ignored) {}
        try { org.bukkit.event.HandlerList.unregisterAll(mobHandler); }     catch (Throwable ignored) {}
        try { org.bukkit.event.HandlerList.unregisterAll(villagerHandler);} catch (Throwable ignored) {}
        try { org.bukkit.event.HandlerList.unregisterAll(loginListener); }  catch (Throwable ignored) {}

        // Reload YAML configs
        reloadConfig();
        hauntedMobsConfig = load("hauntedmobs.yml");
        pumpkinHuntConfig = load("pumpkinhunt.yml");

        // Rebuild Boss manager with fresh config section
        ConfigurationSection bossSec = hauntedMobsConfig.getConfigurationSection("boss-mobs.headless-horseman");
        bossSpawnManager = new BossSpawnManager(
                this,
                bossSec,
                hauntedMobsConfig
        );

        // Rebuild LibsDisguises handler per new config
        boolean ldEnabled = getConfig().getBoolean("libdisguise.enabled", false);
        boolean ldPresent = getServer().getPluginManager().getPlugin("LibsDisguises") != null;
        if (ldEnabled && ldPresent) {
            disguises = new LibsDisguisesHandler(getLogger());
        } else {
            disguises = null;
        }

        // Reschedule auto-spawn if configured
        if (bossSec != null) {
            ConfigurationSection auto = bossSec.getConfigurationSection("auto");
            if (auto != null && auto.getBoolean("enabled", false)) {
                long intervalSec = Math.max(5, auto.getLong("interval-seconds", 60));
                autoTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(
                        this,
                        bossSpawnManager::tryAutoSpawn,
                        20L * 5,
                        20L * intervalSec
                );
            }
        }

        // Re-register listeners bound to refreshed configs
        PluginManager pm = getServer().getPluginManager();

        pumpkinHandler   = new PumpkinHuntHandler(this, pumpkinHuntConfig);
        bossListener     = new BossEventListener(bossSpawnManager);
        mobHandler       = new MobSpawnHandler(this, hauntedMobsConfig, disguises);
        villagerHandler  = new VillagerInteractionHandler(this, disguises, hauntedMobsConfig);
        loginListener    = new LoginDisguiseListener(this, disguises);

        pm.registerEvents(pumpkinHandler, this);
        pm.registerEvents(bossListener, this);
        pm.registerEvents(mobHandler, this);
        pm.registerEvents(villagerHandler, this);
        pm.registerEvents(loginListener, this);
    }

    private FileConfiguration load(String fileName) {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), fileName));
    }

    public static TrickOrTreatPlugin getInstance() {
        return instance;
    }

    public PumpkinHuntHandler getPumpkinHandler() {
        return pumpkinHandler;
    }

    // ---- Helpers (LuckPerms-safe) ----
    public long getCooldown(Player player) {
        String group = safePrimaryGroup(player);
        FileConfiguration cfg = getConfig();
        if (cfg.isSet("custom-cooldowns." + group)) {
            return cfg.getLong("custom-cooldowns." + group);
        }
        if (cfg.isSet("custom-cooldowns.default")) {
            return cfg.getLong("custom-cooldowns.default");
        }
        return cfg.getLong("default-cooldown", 60);
    }

    public Map<String, Object> getTrickOrTreat(Player player) {
        FileConfiguration cfg = getConfig();
        boolean perGroup = cfg.getBoolean("reward-per-luckpermsgroups", true);
        String group = safePrimaryGroup(player);

        String base = "rewards.";
        String path = perGroup && cfg.isConfigurationSection(base + group) ? base + group : base + "default";
        if (!cfg.isConfigurationSection(path)) {
            return Collections.emptyMap();
        }
        return cfg.getConfigurationSection(path).getValues(false);
    }

    private String safePrimaryGroup(Player player) {
        try {
            LuckPerms lp = LuckPermsProvider.get();
            User u = lp.getUserManager().getUser(player.getUniqueId());
            return (u != null && u.getPrimaryGroup() != null) ? u.getPrimaryGroup() : "default";
        } catch (Throwable ignored) {
            return "default";
        }
    }

    public Map<UUID, Long> getCooldowns() {
        return cooldowns;
    }
}
