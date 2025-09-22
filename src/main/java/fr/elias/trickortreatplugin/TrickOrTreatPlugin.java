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

    @Override
    public void onEnable() {
        instance = this;

        // Ensure default files exist, then load
        saveDefaultConfig();
        saveResourceIfMissing("hauntedmobs.yml");
        saveResourceIfMissing("pumpkinhunt.yml");

        hauntedMobsConfig = load("hauntedmobs.yml");
        pumpkinHuntConfig = load("pumpkinhunt.yml");

        // Initialize Boss Manager (uses hauntedmobs.yml for boss settings)
// Initialize Boss Manager (uses hauntedmobs.yml for boss settings)
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
// === Auto-spawn scheduler ===
        ConfigurationSection bossSec = hauntedMobsConfig.getConfigurationSection("boss-mobs.headless-horseman");
        if (bossSec != null) {
            ConfigurationSection auto = bossSec.getConfigurationSection("auto");
            if (auto != null && auto.getBoolean("enabled", false)) {
                long intervalSec = Math.max(5, auto.getLong("interval-seconds", 60));
                getServer().getScheduler().runTaskTimer(
                        this,
                        bossSpawnManager::tryAutoSpawn,
                        20L * 5,                 // initial delay 5s
                        20L * intervalSec        // repeat every N seconds
                );
                getLogger().info("Headless Horseman auto-spawn enabled (" + intervalSec + "s interval).");
            }
        }


        // Register listeners
        PluginManager pm = getServer().getPluginManager();

        // Create once and register once
        pumpkinHandler = new PumpkinHuntHandler(this, pumpkinHuntConfig);
        pm.registerEvents(pumpkinHandler, this);

        pm.registerEvents(new BossEventListener(bossSpawnManager), this);
        pm.registerEvents(new MobSpawnHandler(this, hauntedMobsConfig, disguises), this);
        pm.registerEvents(new VillagerInteractionHandler(this, disguises, hauntedMobsConfig), this);
        pm.registerEvents(new LoginDisguiseListener(this, disguises), this);

        if (getCommand("tt") != null) {
            TrickOrTreatCommand executor = new TrickOrTreatCommand(this);
            getCommand("tt").setExecutor(executor);
            getCommand("tt").setTabCompleter(executor);
        } else {
            getLogger().warning("Command 'tt' not found in plugin.yml");
        }

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
