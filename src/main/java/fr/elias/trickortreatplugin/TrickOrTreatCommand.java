package fr.elias.trickortreatplugin;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class TrickOrTreatCommand implements CommandExecutor, TabCompleter {
    private final TrickOrTreatPlugin plugin;

    public TrickOrTreatCommand(TrickOrTreatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.GOLD + "TrickOrTreat commands:");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " pumpkins" + ChatColor.GRAY + " — show your pumpkin progress");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " horseman" + ChatColor.GRAY + " — spawn the Headless Horseman (admin)");
            return true;
        }

        // /tt pumpkins
        if (args[0].equalsIgnoreCase("pumpkins")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Players only.");
                return true;
            }
            Player p = (Player) sender;

            int total = plugin.getPumpkinHandler().getTotal(p.getUniqueId());
            String display = plugin.getPumpkinHandler().getDisplayProgress(p.getUniqueId()); // e.g. "4/10" or "50/30"

            if (plugin.getPumpkinHandler().getTotalLevels() > 1) {
                int lvl = plugin.getPumpkinHandler().getCurrentLevelIndex(p.getUniqueId()) + 1; // 1-based
                int lvls = plugin.getPumpkinHandler().getTotalLevels();
                p.sendMessage(ChatColor.GREEN + "Pumpkins: " + ChatColor.AQUA + display
                        + ChatColor.GRAY + "  (Level " + lvl + "/" + lvls + ", total " + total + ")");
            } else {
                p.sendMessage(ChatColor.GREEN + "Pumpkins: " + ChatColor.AQUA + display
                        + ChatColor.GRAY + "  (total " + total + ")");
            }
            return true;
        }

        // /tt horseman
        if (args[0].equalsIgnoreCase("horseman")) {
            if (!sender.hasPermission("trickortreat.horseman")) {
                sender.sendMessage(ChatColor.RED + "You lack permission: trickortreat.horseman");
                return true;
            }

            if (sender instanceof Player) {
                Player p = (Player) sender;
                plugin.getBossSpawnManager().spawnHeadlessHorseman(p); // respects per-player cooldown & spawn-chance
                sender.sendMessage(ChatColor.YELLOW + "Spawn attempt made at configured location (or your position if not set).");
            } else {
                // Console spawns at configured center and bypasses player cooldown
                boolean ok = plugin.getBossSpawnManager().spawnHeadlessHorsemanAtConfiguredCenter();
                if (ok) {
                    sender.sendMessage(ChatColor.GREEN + "Headless Horseman spawn attempted at configured center.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Failed to spawn. Check boss-mobs.headless-horseman.spawn-location/world.");
                }
            }
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Try /" + label + " help");
        return true;
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            if ("pumpkins".startsWith(prefix)) out.add("pumpkins");
            if ("help".startsWith(prefix)) out.add("help");
            if (sender.hasPermission("trickortreat.horseman") && "horseman".startsWith(prefix)) {
                out.add("horseman");
            }
        }
        return out;
    }

}
