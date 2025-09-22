package fr.elias.trickortreatplugin;

import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public class BossEventListener implements Listener {
    private final BossSpawnManager bossSpawnManager;

    public BossEventListener(BossSpawnManager bossSpawnManager) {
        this.bossSpawnManager = bossSpawnManager;
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        Entity e = event.getEntity();
        if (e instanceof Skeleton && e.getCustomName() != null) {
            String name = ChatColor.stripColor(e.getCustomName());
            if ("Headless Horseman".equalsIgnoreCase(name)) {
                bossSpawnManager.onBossDeath(event);
            }
        }
    }
}
