package dev.altarly;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public final class BetterTeamsHook {
    private final JavaPlugin plugin;

    public BetterTeamsHook(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isFriendly(Player first, Player second) {
        if (first.getUniqueId().equals(second.getUniqueId())) {
            return true;
        }

        Plugin betterTeams = plugin.getServer().getPluginManager().getPlugin("BetterTeams");
        if (betterTeams == null || !betterTeams.isEnabled()) {
            return false;
        }

        try {
            Class<?> teamClass = Class.forName("com.booksaw.betterTeams.Team");
            Method getTeamMethod = teamClass.getMethod("getTeam", Player.class);
            Object firstTeam = getTeamMethod.invoke(null, first);
            Object secondTeam = getTeamMethod.invoke(null, second);

            if (firstTeam == null || secondTeam == null) {
                return false;
            }
            return firstTeam.equals(secondTeam);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("BetterTeams detected but API call failed: " + ex.getClass().getSimpleName());
            return false;
        }
    }
}
