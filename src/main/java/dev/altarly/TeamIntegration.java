package dev.altarly;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

public final class TeamIntegration {
    private static final String BETTER_TEAMS_PLUGIN = "BetterTeams";

    private final Plugin plugin;

    public TeamIntegration() {
        this.plugin = Bukkit.getPluginManager().getPlugin(BETTER_TEAMS_PLUGIN);
    }

    public boolean isSameTeam(Player first, Player second) {
        if (first == null || second == null || first.getUniqueId().equals(second.getUniqueId())) {
            return false;
        }
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }

        try {
            Object firstTeam = resolveTeam(first);
            Object secondTeam = resolveTeam(second);
            return firstTeam != null && secondTeam != null && firstTeam.equals(secondTeam);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private Object resolveTeam(Player player) throws ReflectiveOperationException {
        Class<?> teamClass = Class.forName("com.booksaw.betterTeams.team.Team");
        UUID uuid = player.getUniqueId();

        try {
            Method getTeamUuid = teamClass.getMethod("getTeam", UUID.class);
            return getTeamUuid.invoke(null, uuid);
        } catch (NoSuchMethodException ignored) {
            Method getTeamPlayer = teamClass.getMethod("getTeam", Player.class);
            return getTeamPlayer.invoke(null, player);
        }
    }
}
