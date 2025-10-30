package fr.elias.trickortreatplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

/**
 * Tracks the last player who damaged the current boss, so rewards still work
 * even when getKiller() is null (projectiles, TNT, environment, etc).
 */
public class BossCombatListener implements Listener {
    private final BossSpawnManager boss;

    public BossCombatListener(BossSpawnManager boss) {
        this.boss = boss;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossDamaged(EntityDamageByEntityEvent e) {
        final Entity victim = e.getEntity();
        if (!boss.isCurrentBoss(victim)) return; // Only care if the victim is the active boss

        Player hitter = resolvePlayerFromDamager(e.getDamager());
        if (hitter == null) {
            // Some damage types put the "damager" as a non-entity (e.g., explosion chain),
            // try to resolve additional known sources from victim's last damage cause if needed.
            // In most cases above resolver is enough.
            return;
        }

        UUID id = hitter.getUniqueId();
        if (Bukkit.getPlayer(id) != null) {
            boss.noteBossDamagedBy(id);
        }
    }

    /**
     * Attempt to resolve a Player who caused damage, from various damager types:
     * - Player (direct melee)
     * - Projectile (Arrow, Trident, ThrownPotion, etc.) -> shooter
     * - AreaEffectCloud (lingering potions) -> source (ProjectileSource)
     * - TNTPrimed -> source (Entity)
     * - Tamed pets (Wolf/Cat/etc.) -> owner (if player)
     */
    private Player resolvePlayerFromDamager(Entity damager) {
        if (damager == null) return null;

        // Direct player
        if (damager instanceof Player p) {
            return p;
        }

        // Projectiles (Arrow/Trident/ThrownPotion/Snowball/etc.)
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            // Shooter can be Player, LivingEntity, or BlockProjectileSource
            if (src instanceof Player p) return p;
            if (src instanceof Entity ent && ent instanceof Player p) return p;
            return null;
        }

        // Lingering/splash potion clouds
        if (damager instanceof AreaEffectCloud cloud) {
            ProjectileSource src = cloud.getSource(); // <-- this is a ProjectileSource
            if (src instanceof Player p) return p;
            if (src instanceof Entity ent && ent instanceof Player p) return p;
            return null;
        }

        // TNT (primed)
        if (damager instanceof TNTPrimed tnt) {
            Entity src = tnt.getSource(); // This can be a Player or other Entity
            if (src instanceof Player p) return p;
            return null;
        }

        // Tamed pets
        if (damager instanceof Tameable tame) {
            AnimalTamer owner = tame.getOwner();
            if (owner instanceof Player p) return p;
            return null;
        }

        // (Optional) Minecarts, Ender Crystals, etc. — usually not player-owned in vanilla.
        // Add more cases as your server needs.

        return null;
    }
}
