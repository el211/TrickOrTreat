package fr.elias.trickortreatplugin;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class LoginDisguiseListener implements Listener {
    private final TrickOrTreatPlugin plugin;
    private final FileConfiguration cfg;           // reads config.yml
    private final LibsDisguisesHandler disguises;  // null if LD disabled/not installed
    private final Random random = new Random();

    /** Use this 2-arg constructor and register with: new LoginDisguiseListener(this, disguises) */
    public LoginDisguiseListener(TrickOrTreatPlugin plugin, LibsDisguisesHandler disguises) {
        this.plugin = plugin;
        this.disguises = disguises;
        this.cfg = plugin.getConfig(); // <- pulls libdisguise.login.* from config.yml
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // toggles
        if (disguises == null) return;
        if (!cfg.getBoolean("libdisguise.enabled", false)) return;
        if (!cfg.getBoolean("libdisguise.login.enabled", false)) return;

        Player p = event.getPlayer();

        // choose mob per mode
        String mob = selectMobFor(p);
        if (mob == null || mob.isEmpty()) return;

        int delay = Math.max(0, cfg.getInt("libdisguise.login.delay-ticks", 5));
        boolean persistent = cfg.getBoolean("libdisguise.login.persistent", true);
        int duration = Math.max(1, cfg.getInt("libdisguise.login.duration-seconds", 600));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (persistent) {
                disguises.disguisePlayerAsMob(p, mob);
            } else {
                disguises.disguisePlayerTemporarily(p, mob, duration, plugin);
            }
        }, delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (disguises == null) return;
        if (!cfg.getBoolean("libdisguise.enabled", false)) return;
        if (!cfg.getBoolean("libdisguise.login.enabled", false)) return;
        if (!cfg.getBoolean("libdisguise.login.undisguise-on-quit", true)) return;

        try {
            disguises.undisguisePlayer(event.getPlayer());
        } catch (Exception ignored) {}
    }

    private String selectMobFor(Player p) {
        String mode = cfg.getString("libdisguise.login.mode", "default")
                .toLowerCase(Locale.ROOT);

        switch (mode) {
            case "random": {
                List<String> pool = cfg.getStringList("libdisguise.login.random-pool");
                if (pool == null || pool.isEmpty()) {
                    return cfg.getString("libdisguise.login.default-mob", "ZOMBIE");
                }
                return pool.get(random.nextInt(pool.size()));
            }
            case "group": {
                String mobByGroup = mobFromLuckPermsGroup(p);
                if (mobByGroup != null) return mobByGroup;
                return cfg.getString("libdisguise.login.default-mob", "ZOMBIE");
            }
            case "permission": {
                String mobByPerm = mobFromPermissions(p);
                if (mobByPerm != null) return mobByPerm;
                return cfg.getString("libdisguise.login.default-mob", "ZOMBIE");
            }
            case "default":
            default:
                return cfg.getString("libdisguise.login.default-mob", "ZOMBIE");
        }
    }

    private String mobFromLuckPermsGroup(Player p) {
        try {
            LuckPerms lp = LuckPermsProvider.get();
            User user = lp.getUserManager().getUser(p.getUniqueId());
            String group = (user != null && user.getPrimaryGroup() != null) ? user.getPrimaryGroup() : "default";
            String path = "libdisguise.login.groups." + group;
            return cfg.getString(path, cfg.getString("libdisguise.login.groups.default", null));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String mobFromPermissions(Player p) {
        Object listObj = cfg.get("libdisguise.login.permissions");
        if (!(listObj instanceof List<?> l)) return null;

        for (Object o : l) {
            if (o instanceof Map<?, ?> m) {
                Object node = m.get("node");
                Object mob = m.get("mob");
                if (node instanceof String n && mob instanceof String s) {
                    if (p.hasPermission(n)) return s;
                }
            }
        }
        return null;
    }
}
