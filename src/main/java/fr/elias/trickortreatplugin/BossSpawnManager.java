package fr.elias.trickortreatplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Headless Horseman boss manager.
 * - Single instance guard (with chunk force-load so we can reliably track him).
 * - Auto-spawn with time window & optional nearby player requirement.
 * - Option to spawn even with no players nearby (keeps chunk loaded until death).
 * - Minion waves.
 * - Rewards & cooldown after death.
 * - Safe cleanup on disable.
 */
public class BossSpawnManager {
    private static final String TAG_BOSS   = "TT_BOSS_HORSEMAN";
    private static final String TAG_MINION = "TT_BOSS_MINION";

    private final JavaPlugin plugin;
    private final FileConfiguration cfg;          // hauntedmobs.yml (full)
    private final ConfigurationSection bossCfg;   // boss-mobs.headless-horseman

    private final Random random = new Random();
    private final Map<UUID, Long> manualCooldowns = new HashMap<>(); // per-player

    // Single-instance tracking
    private UUID activeBossId = null;   // rider skeleton UUID (authoritative)
    private int  minionTaskId  = -1;

    // Auto-spawn cooldown (re-uses death cooldown period)
    private final long cooldownTimeMs;
    private long lastAutoSpawnMs = 0L;

    // Forced-chunk tracking while boss is alive
    private boolean bossChunkForced = false;
    private String  bossWorldName   = null;
    private int     bossChunkX      = 0;
    private int     bossChunkZ      = 0;

    // Track last player who damaged the *current* boss
    private UUID lastBossDamager = null;

    public BossSpawnManager(JavaPlugin plugin, ConfigurationSection bossConfig, FileConfiguration hauntedMobsConfig) {
        this.plugin = plugin;
        this.bossCfg = bossConfig;
        this.cfg = hauntedMobsConfig;
        this.cooldownTimeMs = bossConfig != null
                ? bossConfig.getLong("cooldown-of-spawn-after-death", 3600) * 1000L
                : 3600_000L;
        // Try to adopt an existing boss on startup (e.g., after /reload)
        adoptExistingBossIfAny();
    }

    public void noteBossDamagedBy(UUID playerId) {
        // only track while this boss is alive
        if (isBossAlive() && playerId != null) {
            lastBossDamager = playerId;
        }
    }

    private Player resolveKillerFallback(EntityDeathEvent event) {
        // 1) Vanilla killer if present
        Player k = event.getEntity().getKiller();
        if (k != null) return k;

        // 2) Our last-hit cache
        if (lastBossDamager != null) {
            Player p = Bukkit.getPlayer(lastBossDamager);
            if (p != null && p.isOnline()) return p;
        }

        // 3) Nearest player within 24 blocks as a last resort
        return getNearestPlayer(event.getEntity().getLocation(), 24.0);
    }

    /* =========================
       Manual/console spawns
       ========================= */

    public void spawnHeadlessHorseman(Player player) {
        if (bossCfg == null) {
            if (player != null) player.sendMessage(ChatColor.RED + "Boss config missing.");
            return;
        }
        if (isBossAlive()) {
            if (player != null) player.sendMessage(ChatColor.RED + "The Headless Horseman is already roaming!");
            return;
        }

        double chance = bossCfg.getDouble("spawn-chance", 1.0);
        if (chance < 1.0 && random.nextDouble() > chance) {
            if (player != null) player.sendMessage(ChatColor.GRAY + "The air feels cold... but nothing appears.");
            return;
        }

        if (player != null) {
            long now = System.currentTimeMillis();
            Long last = manualCooldowns.get(player.getUniqueId());
            if (last != null && now - last < cooldownTimeMs) {
                long left = (cooldownTimeMs - (now - last)) / 1000L;
                player.sendMessage(ChatColor.RED + "You must wait " + left + " seconds to spawn the Headless Horseman again!");
                return;
            }
            manualCooldowns.put(player.getUniqueId(), now);
        }

        Location loc = (player != null) ? getSpawnLocation(player) : getConfiguredCenterLocation();
        if (loc == null) {
            if (player != null) player.sendMessage(ChatColor.RED + "Invalid spawn location.");
            return;
        }
        doSpawnAt(loc, /*forceChunk*/ shouldForceChunkForAutoMode());
    }

    private String colorize(String s) {
        if (s == null) return null;
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public boolean spawnHeadlessHorsemanAtConfiguredCenter() {
        if (bossCfg == null) return false;
        if (isBossAlive()) return false;

        Location loc = getConfiguredCenterLocation();
        if (loc == null) return false;

        double chance = bossCfg.getDouble("spawn-chance", 1.0);
        if (chance < 1.0 && random.nextDouble() > chance) return false;

        doSpawnAt(loc, /*forceChunk*/ shouldForceChunkForAutoMode());
        return true;
    }

    /* =========================
       Auto-spawn loop
       ========================= */

    public void tryAutoSpawn() {
        if (bossCfg == null) return;
        if (isBossAlive()) return; // hard guard

        ConfigurationSection auto = bossCfg.getConfigurationSection("auto");
        if (auto == null || !auto.getBoolean("enabled", false)) return;

        Location center = getConfiguredCenterLocation();
        if (center == null || center.getWorld() == null) return;

        long from = auto.getLong("world-time.from", 0);
        long to   = auto.getLong("world-time.to",   23999);
        long time = center.getWorld().getTime();
        if (!isInTimeWindow(time, from, to)) return;

        boolean requireNearby = auto.getBoolean("require-player-nearby", true);
        if (requireNearby) {
            int radius = Math.max(1, auto.getInt("region-radius", 96));
            boolean anyNearby = Bukkit.getOnlinePlayers().stream()
                    .anyMatch(p -> p.getWorld().equals(center.getWorld())
                            && p.getLocation().distanceSquared(center) <= (radius * (double) radius));
            if (!anyNearby) return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAutoSpawnMs < cooldownTimeMs) return;

        // Ensure chunk loaded before spawning
        ensureChunkLoaded(center, shouldForceChunkForAutoMode());

        boolean ok = spawnHeadlessHorsemanAtConfiguredCenter();
        if (ok) {
            lastAutoSpawnMs = now;
        } else {
            // If spawn failed, and we forced the chunk, immediately unforce to avoid leaks
            if (shouldForceChunkForAutoMode()) {
                unforceBossChunkIfNeeded();
            }
        }
    }

    /* =========================
       Death, cleanup, adoption
       ========================= */

    public void onBossDeath(EntityDeathEvent event) {
        Entity dead = event.getEntity();

        // Only continue if the dead entity IS the tracked boss
        if (!isCurrentBoss(dead)) {
            return;
        }

        // Clear active boss + start cooldown + reset last hitter
        clearActiveBoss(true);

        // Broadcast message
        String msg = cfg.getString("boss-mobs.headless-horseman.reward.message",
                "The Headless Horseman has been slain!");
        Bukkit.broadcastMessage(ChatColor.GOLD + ChatColor.translateAlternateColorCodes('&', msg));

        // Reward commands to credited player (fallback finder)
        List<String> cmds = cfg.getStringList("boss-mobs.headless-horseman.reward.random-commands");
        Player credited = resolveKillerFallback(event);
        if (!cmds.isEmpty() && credited != null) {
            String cmd = cmds.get(new Random().nextInt(cmds.size()));
            String finalCmd = cmd.replace("%player%", credited.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
        }

        String rewardSound = cfg.getString("boss-mobs.headless-horseman.reward.sound", "");
        if (rewardSound != null && !rewardSound.isEmpty()) {
            playWorldSoundSafe(event.getEntity().getLocation(), rewardSound, 1.0f, 1.0f);
        }

        event.getDrops().clear();
    }

    /** Call from plugin.onDisable() */
    public void despawnIfAlive() {
        if (isBossAlive()) {
            Entity e = Bukkit.getEntity(activeBossId);
            if (e != null) e.remove();
        }
        // remove lingering tagged entities
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(TAG_BOSS) || e.getScoreboardTags().contains(TAG_MINION)) {
                    e.remove();
                }
            }
        }
        clearActiveBoss(false);
    }

    private void adoptExistingBossIfAny() {
        // If any skeleton in any world is tagged/name-matched, adopt it.
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(Skeleton.class)) {
                if (isHorsemanSkeleton(e)) {
                    activeBossId = e.getUniqueId();
                    // keep his chunk loaded if auto mode wants that
                    if (shouldForceChunkForAutoMode()) {
                        forceChunkOfLocation(e.getLocation());
                    }
                    return;
                }
            }
        }
    }

    /* =========================
       Internals
       ========================= */

    public boolean isCurrentBoss(Entity e) {
        if (e == null) return false;
        if (activeBossId == null) return false;
        return activeBossId.equals(e.getUniqueId());
    }

    private void clearActiveBoss(boolean onDeath) {
        // stop minion waves
        if (minionTaskId != -1) {
            Bukkit.getScheduler().cancelTask(minionTaskId);
            minionTaskId = -1;
        }
        // despawn remaining minions if configured
        boolean despawnMinions = bossCfg.getBoolean("minions.despawn-on-boss-death", true);
        if (despawnMinions) {
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    if (e.getScoreboardTags().contains(TAG_MINION)) e.remove();
                }
            }
        }
        // unforce boss chunk
        unforceBossChunkIfNeeded();

        // reset tracking
        activeBossId = null;
        lastBossDamager = null;

        if (onDeath) lastAutoSpawnMs = System.currentTimeMillis();
    }

    private boolean isBossAlive() {
        if (activeBossId == null) return false;
        Entity e = Bukkit.getEntity(activeBossId);
        return e != null && !e.isDead();
    }

    private boolean isHorsemanSkeleton(Entity e) {
        if (!(e instanceof Skeleton)) return false;

        // Check scoreboard tag first (this is the most reliable)
        if (e.getScoreboardTags().contains(TAG_BOSS)) {
            return true;
        }

        // Fallback: match custom name (stripped) to stripped configured name
        String rawConfigured = bossCfg.getString("display-name", "&cHeadless Horseman");
        String coloredConfigured = colorize(rawConfigured);
        String strippedConfigured = ChatColor.stripColor(coloredConfigured);

        String actualName = e.getCustomName();
        if (actualName != null) {
            String strippedActual = ChatColor.stripColor(actualName);
            if (strippedConfigured.equalsIgnoreCase(strippedActual)) {
                return true;
            }
        }

        return false;
    }

    private void doSpawnAt(Location loc, boolean forceChunk) {
        World w = loc.getWorld();
        if (w == null) return;

        if (forceChunk) {
            forceChunkOfLocation(loc);
        } else {
            // best-effort load
            ensureChunkLoaded(loc, false);
        }

        SkeletonHorse horse = (SkeletonHorse) w.spawnEntity(loc, EntityType.SKELETON_HORSE);
        Skeleton sk        = (Skeleton)      w.spawnEntity(loc, EntityType.SKELETON);
        horse.addPassenger(sk);

        horse.addScoreboardTag(TAG_BOSS);
        sk.addScoreboardTag(TAG_BOSS);

        // Boss display name from config
        String rawName = bossCfg.getString("display-name", "&cHeadless Horseman");
        String coloredName = colorize(rawName);

        // store on skeleton
        sk.setCustomName(coloredName);
        sk.setCustomNameVisible(true);

        double maxHp = bossCfg.getDouble("health", 150.0);
        if (sk.getAttribute(Attribute.MAX_HEALTH) != null) {
            sk.getAttribute(Attribute.MAX_HEALTH).setBaseValue(maxHp);
        }
        sk.setHealth(Math.min(maxHp, sk.getMaxHealth()));

        ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
        axe.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        axe.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
        if (sk.getEquipment() != null) {
            sk.getEquipment().setItemInMainHand(axe);
            sk.getEquipment().setHelmet(new ItemStack(Material.JACK_O_LANTERN));
        }
        sk.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 1, true, false, true));

        String spawnMsg   = bossCfg.getString(
                "message-on-spawn",
                "&aThe Headless Horseman rides again!"
        );
        String spawnSound = bossCfg.getString("sound", "entity_lightning_bolt_thunder");

        // colorize before broadcast
        Bukkit.broadcastMessage(colorize(spawnMsg));

        // play sound same as before
        playWorldSoundSafe(loc, spawnSound, 1.0f, 1.0f);

        activeBossId = sk.getUniqueId();
        startMinionWaves(sk);
    }

    private void startMinionWaves(Skeleton boss) {
        if (!bossCfg.getBoolean("minions.enabled", true)) return;

        final int period      = Math.max(20, bossCfg.getInt("minions.interval-ticks", 200));
        final int perWave     = Math.max(1,  bossCfg.getInt("minions.count-per-wave", 3));
        final int maxAlive    = Math.max(perWave, bossCfg.getInt("minions.max-alive", 10));
        final double tgtRad   = Math.max(8.0, bossCfg.getDouble("minions.target-radius", 24.0));
        final List<String> types = bossCfg.getStringList("minions.types");

        minionTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (boss.isDead() || boss.getWorld() == null || !isBossAlive()) {
                clearActiveBoss(false);
                return;
            }
            World w = boss.getWorld();

            int alive = 0;
            for (Entity e : w.getNearbyEntities(boss.getLocation(), 64, 64, 64)) {
                if (e.getScoreboardTags().contains(TAG_MINION)) alive++;
            }
            if (alive >= maxAlive) return;

            int toSpawn = Math.min(perWave, maxAlive - alive);
            for (int i = 0; i < toSpawn; i++) {
                Entity m = spawnOneMinion(w, boss.getLocation(), types);
                if (m == null) continue;
                m.addScoreboardTag(TAG_MINION);
                Player nearest = getNearestPlayer(m.getLocation(), tgtRad);
                if (nearest != null && m instanceof Monster mm) mm.setTarget(nearest);
            }
        }, period, period);
    }

    private Entity spawnOneMinion(World w, Location around, List<String> preferredTypes) {
        double r = 4 + random.nextDouble() * 6; // 4..10
        double a = random.nextDouble() * Math.PI * 2;
        Location at = around.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
        at.setY(around.getY());

        String pick = null;
        if (preferredTypes != null && !preferredTypes.isEmpty()) {
            pick = preferredTypes.get(random.nextInt(preferredTypes.size()));
        }
        if (pick != null) pick = pick.toUpperCase(Locale.ROOT);

        try {
            if ("BABY_ZOMBIE".equals(pick)) {
                Zombie z = (Zombie) w.spawnEntity(at, EntityType.ZOMBIE);
                z.setBaby(true); return z;
            }
            if ("BABY_HUSK".equals(pick)) {
                Zombie z = (Zombie) w.spawnEntity(at, EntityType.HUSK);
                z.setBaby(true); return z;
            }
            if (pick != null) {
                EntityType type = EntityType.valueOf(pick);
                return w.spawnEntity(at, type);
            }
        } catch (IllegalArgumentException ignored) {}

        // Fallback rotation
        switch (random.nextInt(3)) {
            case 0: {
                Zombie z = (Zombie) w.spawnEntity(at, EntityType.ZOMBIE);
                z.setBaby(true); return z;
            }
            case 1: return w.spawnEntity(at, EntityType.SILVERFISH);
            default: return w.spawnEntity(at, EntityType.CAVE_SPIDER);
        }
    }

    private Player getNearestPlayer(Location loc, double maxDist) {
        Player nearest = null;
        double best = maxDist * maxDist;
        for (Player p : loc.getWorld().getPlayers()) {
            double d2 = p.getLocation().distanceSquared(loc);
            if (d2 <= best) { best = d2; nearest = p; }
        }
        return nearest;
    }

    /* =========================
       Chunk helpers & config
       ========================= */

    private boolean shouldForceChunkForAutoMode() {
        ConfigurationSection auto = bossCfg.getConfigurationSection("auto");
        if (auto == null) return false;
        boolean requireNearby = auto.getBoolean("require-player-nearby", true);
        boolean forceFlag     = auto.getBoolean("force-load-chunk", false);
        // If you want spawning with no players near, we must force-load.
        return forceFlag || !requireNearby;
    }

    private void forceChunkOfLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        World w = loc.getWorld();
        w.getChunkAt(cx, cz).load(true);
        try { w.setChunkForceLoaded(cx, cz, true); } catch (Throwable ignored) {}
        bossChunkForced = true;
        bossWorldName = w.getName();
        bossChunkX = cx;
        bossChunkZ = cz;
    }

    private void unforceBossChunkIfNeeded() {
        if (!bossChunkForced) return;
        World w = Bukkit.getWorld(bossWorldName);
        if (w != null) {
            try { w.setChunkForceLoaded(bossChunkX, bossChunkZ, false); } catch (Throwable ignored) {}
        }
        bossChunkForced = false;
        bossWorldName = null;
        bossChunkX = bossChunkZ = 0;
    }

    private boolean ensureChunkLoaded(Location loc, boolean force) {
        if (loc == null || loc.getWorld() == null) return false;
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        World w = loc.getWorld();
        w.getChunkAt(cx, cz).load(true);
        if (force) {
            try { w.setChunkForceLoaded(cx, cz, true); } catch (Throwable ignored) {}
            bossChunkForced = true;
            bossWorldName = w.getName();
            bossChunkX = cx;
            bossChunkZ = cz;
        }
        return true;
    }

    private Location getConfiguredCenterLocation() {
        boolean defined = bossCfg.getBoolean("define-spawn-location", false);
        if (!defined) return null;
        String raw = bossCfg.getString("spawn-location", "world,0,64,0");
        String[] p = raw.split(",");
        if (p.length < 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            double x = Double.parseDouble(p[1]);
            double y = Double.parseDouble(p[2]);
            double z = Double.parseDouble(p[3]);
            return new Location(w, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Location getSpawnLocation(Player player) {
        Location c = getConfiguredCenterLocation();
        return (c != null) ? c : player.getLocation();
    }

    private boolean isInTimeWindow(long time, long from, long to) {
        time = ((time % 24000) + 24000) % 24000;
        from = ((from % 24000) + 24000) % 24000;
        to   = ((to % 24000) + 24000) % 24000;
        if (from <= to) return time >= from && time <= to;
        // wraps overnight (e.g., 23000..500)
        return time >= from || time <= to;
    }

    private void playWorldSoundSafe(Location loc, String lowerCaseKey, float vol, float pitch) {
        if (loc == null || lowerCaseKey == null || lowerCaseKey.isEmpty()) return;
        try {
            Sound s = Sound.valueOf(lowerCaseKey.toUpperCase(Locale.ROOT));
            loc.getWorld().playSound(loc, s, vol, pitch);
        } catch (IllegalArgumentException ignored) { }
    }
}
