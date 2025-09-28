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
        // /tt help or no args
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.GOLD + "TrickOrTreat commands:");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " pumpkins" + ChatColor.GRAY + " — show your pumpkin progress");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " horseman" + ChatColor.GRAY + " — spawn the Headless Horseman (admin)");
            if (sender.hasPermission("trickortreat.reload")) {
                sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload" + ChatColor.GRAY + " — reload all configs & handlers");
            }
            return true;
        }

        // /tt pumpkins
        if (args[0].equalsIgnoreCase("pumpkins")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Players only.");
                return true;
            }
            int total = plugin.getPumpkinHandler().getTotal(p.getUniqueId());
            String display = plugin.getPumpkinHandler().getDisplayProgress(p.getUniqueId());
            if (plugin.getPumpkinHandler().getTotalLevels() > 1) {
                int lvl = plugin.getPumpkinHandler().getCurrentLevelIndex(p.getUniqueId()) + 1;
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
            if (sender instanceof Player p) {
                plugin.getBossSpawnManager().spawnHeadlessHorseman(p);
                sender.sendMessage(ChatColor.YELLOW + "Spawn attempt made at configured location (or your position if not set).");
            } else {
                boolean ok = plugin.getBossSpawnManager().spawnHeadlessHorsemanAtConfiguredCenter();
                sender.sendMessage(ok
                        ? ChatColor.GREEN + "Headless Horseman spawn attempted at configured center."
                        : ChatColor.RED + "Failed to spawn. Check boss-mobs.headless-horseman.spawn-location/world.");
            }
            return true;
        }

        // /tt reload  ➜ calls TrickOrTreatPlugin#reloadAll()
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("trickortreat.reload")) {
                sender.sendMessage(ChatColor.RED + "You lack permission: trickortreat.reload");
                return true;
            }
            plugin.reloadAll();
            sender.sendMessage(ChatColor.GREEN + "TrickOrTreat reloaded.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Try /" + label + " help");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            if ("pumpkins".startsWith(p)) out.add("pumpkins");
            if ("help".startsWith(p)) out.add("help");
            if (sender.hasPermission("trickortreat.horseman") && "horseman".startsWith(p)) out.add("horseman");
            if (sender.hasPermission("trickortreat.reload") && "reload".startsWith(p)) out.add("reload");
        }
        return out;
    }
}
