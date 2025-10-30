package fr.elias.trickortreatplugin;

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
        // Just forward; BossSpawnManager checks isCurrentBoss(...) and handles rewards safely.
        bossSpawnManager.onBossDeath(event);
    }
}
