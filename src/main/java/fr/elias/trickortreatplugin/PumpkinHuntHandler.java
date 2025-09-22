package fr.elias.trickortreatplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class PumpkinHuntHandler implements Listener {
    private final TrickOrTreatPlugin plugin;
    private final FileConfiguration phCfg; // pumpkinhunt.yml

    // progress persistence (now structured)
    private final File progressFile;
    private final FileConfiguration progressCfg;
    private final Map<UUID, PlayerProgress> progress = new HashMap<>();

    // pumpkin source persistence
    private final File blocksFile;
    private final FileConfiguration blocksCfg;
    private final Set<String> placed = new HashSet<>();
    private final Set<String> grown  = new HashSet<>();

    public PumpkinHuntHandler(TrickOrTreatPlugin plugin, FileConfiguration pumpkinHuntConfig) {
        this.plugin = plugin;
        this.phCfg = pumpkinHuntConfig;

        // progress file
        this.progressFile = new File(plugin.getDataFolder(), "pumpkinprogress.yml");
        ensureFile(progressFile);
        this.progressCfg = YamlConfiguration.loadConfiguration(progressFile);
        loadProgress();

        // block source file
        this.blocksFile = new File(plugin.getDataFolder(), "pumpkinblocks.yml");
        ensureFile(blocksFile);
        this.blocksCfg = YamlConfiguration.loadConfiguration(blocksFile);
        loadBlockMarkers();
    }

    // ===== Events to mark source =====
    @EventHandler
    public void onPumpkinPlaced(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.PUMPKIN) return;
        String key = locKey(event.getBlockPlaced().getLocation());
        placed.add(key);
        grown.remove(key);
        saveBlocks();
    }

    @EventHandler
    public void onPumpkinGrown(BlockGrowEvent event) {
        if (event.getNewState().getType() != Material.PUMPKIN) return;
        String key = locKey(event.getBlock().getLocation());
        grown.add(key);
        placed.remove(key);
        saveBlocks();
    }

    @EventHandler
    @SuppressWarnings("unchecked")
    public void onPumpkinBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.PUMPKIN) return;

        Location loc = event.getBlock().getLocation();
        String key = locKey(loc);
        Source src = classify(key);

        boolean allowPlaced  = phCfg.getBoolean("rules.count-player-placed", false);
        boolean allowGrown   = phCfg.getBoolean("rules.count-grown", true);
        boolean allowNatural = phCfg.getBoolean("rules.count-natural", true);

        if ((src == Source.PLACED  && !allowPlaced) ||
                (src == Source.GROWN   && !allowGrown) ||
                (src == Source.NATURAL && !allowNatural)) {
            placed.remove(key);
            grown.remove(key);
            saveBlocks();
            return;
        }

        Player player = event.getPlayer();

        // Optional cooldown reuse (comment/remove if not wanted here)
        long cdSec = Math.max(0, plugin.getCooldown(player));
        if (cdSec > 0 && !player.hasPermission("trickortreat.cooldown.bypass")) {
            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = plugin.getCooldowns().get(id);
            long cdMs = cdSec * 1000L;
            if (last != null && now - last < cdMs) {
                long left = (cdMs - (now - last)) / 1000L;
                player.sendMessage("§cYou are on cooldown. Please wait " + left + " seconds.");
                placed.remove(key);
                grown.remove(key);
                saveBlocks();
                return;
            }
            plugin.getCooldowns().put(id, now);
        }

        // Always give a random small reward per break (as before)
        List<?> rewardList = phCfg.getList("pumpkin-rewards");
        if (rewardList != null && !rewardList.isEmpty()) {
            Object picked = rewardList.get(ThreadLocalRandom.current().nextInt(rewardList.size()));
            Map<String, Object> reward = toFlatMap(picked);
            if (reward != null) runReward(player, reward);
        }

        // Update totals & level rewards
        PlayerProgress pp = progress.computeIfAbsent(player.getUniqueId(), k -> new PlayerProgress());
        pp.totalBroken++;
        applyLeveling(player, pp); // may award per-level / big-win
        saveProgress();

        // remove marker after break
        placed.remove(key);
        grown.remove(key);
        saveBlocks();
    }

    // ===== Level logic =====
    private void applyLeveling(Player player, PlayerProgress pp) {
        if (!phCfg.getBoolean("levels.enabled", false)) {
            // legacy single-level behavior
            int target = Math.max(1, phCfg.getInt("total-pumpkins", 10));
            // award once when crossing target (simulate 'levels' with one level)
            if (pp.levelsAwarded == 0 && pp.totalBroken >= target) {
                doPerLevelWin(player);
                pp.levelsAwarded = 1;
                // also trigger big-win for legacy 'win-event'
                doBigWinLegacy(player);
                pp.bigWinAwarded = true;
            }
            return;
        }

        int count = Math.max(1, phCfg.getInt("levels.count", 1));
        int[] targets = buildTargets(count);

        // compute how many thresholds the total has crossed
        int completedLevels = thresholdsCrossed(pp.totalBroken, targets);

        // Award any newly crossed levels (one-time)
        while (pp.levelsAwarded < Math.min(completedLevels, count)) {
            doPerLevelWin(player);
            pp.levelsAwarded++;
        }

        // Big win once after all levels reached
        if (!pp.bigWinAwarded && completedLevels >= count) {
            doBigWin(player);
            pp.bigWinAwarded = true;
        }
    }

    private int[] buildTargets(int count) {
        List<Integer> list = phCfg.getIntegerList("levels.targets");
        int fallback = Math.max(1, phCfg.getInt("total-pumpkins", 10));
        int[] t = new int[count];
        for (int i = 0; i < count; i++) {
            t[i] = (i < list.size() && list.get(i) != null && list.get(i) > 0) ? list.get(i) : fallback;
        }
        return t;
    }

    private int thresholdsCrossed(int total, int[] targets) {
        int crossed = 0;
        int sum = 0;
        for (int target : targets) {
            sum += target;
            if (total >= sum) crossed++;
            else break;
        }
        return crossed;
    }

    private void doPerLevelWin(Player player) {
        ConfigurationSection sec = phCfg.getConfigurationSection("levels.per-level-win");
        if (sec == null) return;
        runReward(player, sec.getValues(false));
    }

    private void doBigWin(Player player) {
        ConfigurationSection sec = phCfg.getConfigurationSection("levels.big-win");
        if (sec == null) return;
        runReward(player, sec.getValues(false));
    }

    private void doBigWinLegacy(Player player) {
        // used when levels.enabled = false
        ConfigurationSection sec = phCfg.getConfigurationSection("win-event");
        if (sec == null) return;
        runReward(player, sec.getValues(false));
    }

    // ===== Public API for command/placeholder =====

    /** total pumpkins broken by the player (keeps increasing forever) */
    public int getTotal(UUID uuid) {
        PlayerProgress pp = progress.get(uuid);
        return (pp == null) ? 0 : pp.totalBroken;
    }

    /** current level index (0-based) – capped at last level when finished */
    public int getCurrentLevelIndex(UUID uuid) {
        if (!phCfg.getBoolean("levels.enabled", false)) {
            // legacy: 0 or 1 (finished)
            int total = getTotal(uuid);
            int target = Math.max(1, phCfg.getInt("total-pumpkins", 10));
            return (total >= target) ? 0 : 0; // always 0 in legacy mode
        }
        int count = Math.max(1, phCfg.getInt("levels.count", 1));
        int[] targets = buildTargets(count);
        int crossed = thresholdsCrossed(getTotal(uuid), targets);
        // current level is min(crossed, count - 1)
        return Math.min(crossed, count - 1);
    }

    /** how many levels configured (returns 1 when levels disabled) */
    public int getTotalLevels() {
        if (!phCfg.getBoolean("levels.enabled", false)) return 1;
        return Math.max(1, phCfg.getInt("levels.count", 1));
    }

    /** target for current (or last) level, used to build X/Y in UI */
    public int getCurrentLevelTarget(UUID uuid) {
        if (!phCfg.getBoolean("levels.enabled", false)) {
            return Math.max(1, phCfg.getInt("total-pumpkins", 10));
        }
        int count = getTotalLevels();
        int[] targets = buildTargets(count);
        int idx = getCurrentLevelIndex(uuid);
        return targets[Math.max(0, Math.min(idx, targets.length - 1))];
    }

    /** Returns X/Y string as requested:
     *  - While progressing: (current level progress)/(current level target)
     *  - After finishing all levels: total/(last level target) e.g. 50/30
     */
    public String getDisplayProgress(UUID uuid) {
        int total = getTotal(uuid);
        if (!phCfg.getBoolean("levels.enabled", false)) {
            int target = Math.max(1, phCfg.getInt("total-pumpkins", 10));
            return total + "/" + target;
        }

        int count = getTotalLevels();
        int[] targets = buildTargets(count);

        int crossed = thresholdsCrossed(total, targets);
        if (crossed >= count) {
            // finished: show total / lastTarget
            int lastTarget = targets[count - 1];
            return total + "/" + lastTarget;
        } else {
            // show progress within current level
            int sumPrev = 0;
            for (int i = 0; i < crossed; i++) sumPrev += targets[i];
            int curTarget = targets[crossed];
            int curProgress = Math.max(0, total - sumPrev);
            return curProgress + "/" + curTarget;
        }
    }

    // ===== persistence types =====

    private static class PlayerProgress {
        int totalBroken = 0;
        int levelsAwarded = 0; // number of per-level wins already granted
        boolean bigWinAwarded = false;
    }

    // ===== helpers (unchanged from your previous handler) =====

    private enum Source { PLACED, GROWN, NATURAL }

    private Source classify(String key) {
        if (placed.contains(key)) return Source.PLACED;
        if (grown.contains(key))  return Source.GROWN;
        return Source.NATURAL;
    }

    private String locKey(Location loc) {
        World w = loc.getWorld();
        return (w == null ? "world" : w.getName()) + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private void ensureFile(File f) {
        try {
            if (!f.exists()) {
                f.getParentFile().mkdirs();
                f.createNewFile();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create file: " + f.getName() + " - " + e.getMessage());
        }
    }

    private void loadBlockMarkers() {
        try {
            List<String> p = blocksCfg.getStringList("placed");
            List<String> g = blocksCfg.getStringList("grown");
            if (p != null) placed.addAll(p);
            if (g != null) grown.addAll(g);
            plugin.getLogger().info("Loaded pumpkin markers: placed=" + placed.size() + ", grown=" + grown.size());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load pumpkin block markers: " + e.getMessage());
        }
    }

    private void saveBlocks() {
        try {
            blocksCfg.set("placed", new ArrayList<>(placed));
            blocksCfg.set("grown", new ArrayList<>(grown));
            blocksCfg.save(blocksFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save pumpkin block markers: " + e.getMessage());
        }
    }

    private void loadProgress() {
        try {
            for (String key : progressCfg.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    // Back-compat: allow int OR section
                    if (progressCfg.isInt(key)) {
                        PlayerProgress pp = new PlayerProgress();
                        pp.totalBroken = progressCfg.getInt(key);
                        progress.put(uuid, pp);
                    } else if (progressCfg.isConfigurationSection(key)) {
                        ConfigurationSection s = progressCfg.getConfigurationSection(key);
                        PlayerProgress pp = new PlayerProgress();
                        pp.totalBroken = s.getInt("total", 0);
                        pp.levelsAwarded = s.getInt("awarded", 0);
                        pp.bigWinAwarded = s.getBoolean("bigwin", false);
                        progress.put(uuid, pp);
                    }
                } catch (IllegalArgumentException ignored) { }
            }
            plugin.getLogger().info("Loaded pumpkin progress for " + progress.size() + " player(s).");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load pumpkin progress: " + e.getMessage());
        }
    }

    public void saveProgress() {
        try {
            // clear then rewrite
            for (String k : new HashSet<>(progressCfg.getKeys(false))) {
                progressCfg.set(k, null);
            }
            for (Map.Entry<UUID, PlayerProgress> e : progress.entrySet()) {
                PlayerProgress pp = e.getValue();
                String key = e.getKey().toString();
                progressCfg.set(key + ".total", pp.totalBroken);
                progressCfg.set(key + ".awarded", pp.levelsAwarded);
                progressCfg.set(key + ".bigwin", pp.bigWinAwarded);
            }
            progressCfg.save(progressFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save pumpkin progress: " + e.getMessage());
        }
    }

    public void saveState() {
        saveProgress();
        saveBlocks();
    }

    // reward helpers (same as before)
    private Map<String, Object> toFlatMap(Object obj) {
        if (obj instanceof Map) return (Map<String, Object>) obj;
        if (obj instanceof ConfigurationSection) return ((ConfigurationSection) obj).getValues(false);
        return null;
    }

    private void runReward(Player player, Map<String, Object> data) {
        if (data == null) return;

        String command = String.valueOf(data.getOrDefault("command", ""));
        if (!command.isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }

        String message = String.valueOf(data.getOrDefault("message", ""));
        if (!message.isEmpty()) player.sendMessage(message);

        Object eventObj = data.get("event");
        Map<String, Object> evt = null;
        if (eventObj instanceof Map) {
            evt = ((Map<?, ?>) eventObj).entrySet().stream()
                    .collect(Collectors.toMap(e -> String.valueOf(e.getKey()), e -> (Object) e.getValue()));
        } else if (eventObj instanceof ConfigurationSection) {
            evt = ((ConfigurationSection) eventObj).getValues(false);
        }

        if (evt != null) {
            Object sound = evt.get("sound");
            if (sound instanceof String s) playSoundSmart(player, s);
            if (Boolean.TRUE.equals(evt.get("firework"))) {
                player.getWorld().spawn(player.getLocation(), Firework.class);
            }
        }
    }

    private void playSoundSmart(Player player, String key) {
        if (key == null || key.isEmpty()) return;
        try {
            Sound s = Sound.valueOf(key.toUpperCase(Locale.ROOT));
            player.getWorld().playSound(player.getLocation(), s, 1.0F, 1.0F);
            return;
        } catch (IllegalArgumentException ignored) { }
        String candidate = key;
        int colon = candidate.indexOf(':');
        if (colon >= 0 && colon + 1 < candidate.length()) candidate = candidate.substring(colon + 1);
        candidate = candidate.replace('.', '_').toUpperCase(Locale.ROOT);
        try {
            Sound s2 = Sound.valueOf(candidate);
            player.getWorld().playSound(player.getLocation(), s2, 1.0F, 1.0F);
        } catch (IllegalArgumentException ignored) { }
    }
}
