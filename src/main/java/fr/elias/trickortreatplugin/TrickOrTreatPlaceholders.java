package fr.elias.trickortreatplugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class TrickOrTreatPlaceholders extends PlaceholderExpansion {
    private final TrickOrTreatPlugin plugin;

    public TrickOrTreatPlaceholders(TrickOrTreatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "trickortreat";
    }

    @Override
    public @NotNull String getAuthor() {
        return "YourName";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player p, @NotNull String params) {
        if (p == null) return "";
        switch (params.toLowerCase()) {
            case "pumpkins": // "X/Y"
                return plugin.getPumpkinHandler().getDisplayProgress(p.getUniqueId());
            case "pumpkins_total": // total ever broken
                return String.valueOf(plugin.getPumpkinHandler().getTotal(p.getUniqueId()));
            case "pumpkins_level": // "L/N"
                int lvl = plugin.getPumpkinHandler().getCurrentLevelIndex(p.getUniqueId()) + 1;
                int lvls = plugin.getPumpkinHandler().getTotalLevels();
                return lvl + "/" + lvls;
            default:
                return null;
        }
    }
}
