package fr.elias.trickortreatplugin;

import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.DisguiseType;
import me.libraryaddict.disguise.disguisetypes.MobDisguise;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Witch;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;


public class LibsDisguisesHandler {
    private final Logger logger;

    public LibsDisguisesHandler(Logger logger) {
        this.logger = logger;
    }

    /* ===========================
       Player disguise (immediate)
       =========================== */
    public void disguisePlayerAsMob(Player player, String mobType) {
        try {
            Class<?> disguiseTypeClass = Class.forName("me.libraryaddict.disguise.disguisetypes.DisguiseType");

            @SuppressWarnings("unchecked")
            Enum<?> disguiseType = Enum.valueOf((Class<Enum>) disguiseTypeClass, mobType.toUpperCase(Locale.ROOT));

            Method isMobMethod = disguiseTypeClass.getMethod("isMob");
            if (!(Boolean) isMobMethod.invoke(disguiseType)) {
                throw new IllegalArgumentException("Invalid mob type");
            }

            Class<?> mobDisguiseClass = Class.forName("me.libraryaddict.disguise.disguisetypes.MobDisguise");
            Object disguise = mobDisguiseClass.getConstructor(disguiseTypeClass).newInstance(disguiseType);
            DisguiseAPI.disguiseToAll(player, (MobDisguise) disguise);

            player.sendMessage(ChatColor.GREEN + "You are now disguised as a " + mobType.toLowerCase(Locale.ROOT) + "!");
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Failed to disguise as " + mobType.toLowerCase(Locale.ROOT) + ".");
            e.printStackTrace();
        }
    }

    /* =====================================
       Player disguise with auto-undisguise
       ===================================== */
    public void disguisePlayerTemporarily(Player player, String mobType, int durationSeconds, Plugin plugin) {
        disguisePlayerAsMob(player, mobType);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try { undisguisePlayer(player); } catch (Exception ignored) {}
        }, Math.max(1, durationSeconds) * 20L);
    }

    /* =======================================
       Generic entity disguise (immediate)
       ======================================= */
    @SuppressWarnings("unused")
    public void disguiseEntity(Entity entity, String mobType) {
        try {
            MobDisguise disguise = new MobDisguise(DisguiseType.valueOf(mobType.toUpperCase(Locale.ROOT)));
            DisguiseAPI.disguiseToAll(entity, disguise);
        } catch (Exception e) {
            logger.severe("Failed to disguise entity as " + mobType + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /* ==================================================
       Generic entity disguise with auto-undisguise
       ================================================== */
    @SuppressWarnings("unused")
    public void disguiseEntityTemporarily(Entity entity, String mobType, int durationSeconds, Plugin plugin) {
        try {
            MobDisguise disguise = new MobDisguise(DisguiseType.valueOf(mobType.toUpperCase(Locale.ROOT)));
            DisguiseAPI.disguiseToAll(entity, disguise);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try { DisguiseAPI.undisguiseToAll(entity); } catch (Exception ignored) {}
            }, Math.max(1, durationSeconds) * 20L);
        } catch (Exception e) {
            logger.warning("Failed to disguise entity as " + mobType + ": " + e.getMessage());
        }
    }

    public boolean undisguisePlayer(Player player) {
        try {
            if (!DisguiseAPI.isDisguised(player)) {
                player.sendMessage(ChatColor.RED + "You are not disguised.");
                return false;
            }
            DisguiseAPI.undisguiseToAll(player);
            player.sendMessage(ChatColor.GREEN + "Your disguise has been removed.");
            return true;
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Error while removing disguise.");
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("unused")
    public String getDisguiseTypes() throws Exception {
        StringBuilder disguiseList = new StringBuilder();
        Class<?> disguiseTypeClass = Class.forName("me.libraryaddict.disguise.disguisetypes.DisguiseType");
        Object[] disguiseTypes = disguiseTypeClass.getEnumConstants();

        for (Object type : disguiseTypes) {
            Method isMobMethod = disguiseTypeClass.getMethod("isMob");
            if ((Boolean) isMobMethod.invoke(type)) {
                disguiseList.append(type.toString().toLowerCase(Locale.ROOT)).append(", ");
            }
        }
        if (disguiseList.length() > 0) disguiseList.setLength(disguiseList.length() - 2);
        return disguiseList.toString();
    }

    /* ==========================================================
       Legacy sample: disguise certain mob spawns (optional)
       ========================================================== */
    @SuppressWarnings({"unused", "rawtypes"})
    public void disguiseMobIfNecessary(Entity entity, Map mobConfig) {
        if (entity instanceof Creeper || entity instanceof Witch) {
            try {
                MobDisguise disguise = new MobDisguise(DisguiseType.valueOf(entity.getType().name().toUpperCase(Locale.ROOT)));
                DisguiseAPI.disguiseToAll(entity, disguise);
            } catch (Exception e) {
                logger.severe("Error applying disguise to " + entity.getType().name() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @SuppressWarnings("unused")
    public void applyHelmet(Entity entity, Material helmetMaterial) {
        if (helmetMaterial == null || !(entity instanceof LivingEntity living)) return;
        EntityEquipment eq = living.getEquipment();
        if (eq != null) {
            eq.setHelmet(new ItemStack(helmetMaterial));
        }
    }
}
