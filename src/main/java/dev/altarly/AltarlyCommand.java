package dev.altarly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class AltarlyCommand implements CommandExecutor, TabCompleter {
    private final AltarlyPlugin plugin;
    private final WeaponManager weaponManager;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

    public AltarlyCommand(AltarlyPlugin plugin, WeaponManager weaponManager) {
        this.plugin = plugin;
        this.weaponManager = weaponManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        FileConfiguration cfg = plugin.getConfig();

        if (args.length == 0) {
            sender.sendMessage(color("&e/altarly reload &7- Reload config"));
            sender.sendMessage(color("&e/altarly legs &7- Open legendary chest"));
            return true;
        }

        if (args[0].equalsIgnoreCase(cfg.getString("ALTARS.COMMANDS.RELOAD.ALIAS", "reload"))) {
            if (!sender.hasPermission("altarly.admin")) {
                sender.sendMessage(color(cfg.getString("ALTARS.MESSAGES.NO_PERMISSION", "&cYou do not have permission.")));
                return true;
            }

            plugin.reloadConfig();
            sender.sendMessage(color(cfg.getString("ALTARS.MESSAGES.RELOAD_SUCCESS", "&aAltarly config reloaded.")));
            return true;
        }

        if (args[0].equalsIgnoreCase(cfg.getString("ALTARS.COMMANDS.LEGS.ALIAS", "legs"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color(cfg.getString("ALTARS.MESSAGES.PLAYER_ONLY", "&cOnly players can use this command.")));
                return true;
            }
            if (!player.isOp()) {
                player.sendMessage(color(cfg.getString("ALTARS.MESSAGES.NO_PERMISSION", "&cOnly operators can use this command.")));
                return true;
            }

            String title = cfg.getString("ALTARS.COMMANDS.LEGS.INVENTORY_TITLE", "Legendary Weapons");
            Inventory chest = Bukkit.createInventory(null, InventoryType.CHEST, Component.text(title));
            int ruinedSlot = Math.max(0, Math.min(26, cfg.getInt("ALTARS.COMMANDS.LEGS.RUINED_BLADE_SLOT", 0)));
            int enderSlot = Math.max(0, Math.min(26, cfg.getInt("ALTARS.COMMANDS.LEGS.ENDER_BLADE_SLOT", 1)));
            chest.setItem(ruinedSlot, weaponManager.createWeapon(WeaponManager.LegendaryType.RUINED_BLADE));
            chest.setItem(enderSlot, weaponManager.createWeapon(WeaponManager.LegendaryType.ENDER_BLADE));
            player.openInventory(chest);
            return true;
        }

        sender.sendMessage(color("&cUnknown subcommand."));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            String reload = plugin.getConfig().getString("ALTARS.COMMANDS.RELOAD.ALIAS", "reload");
            String legs = plugin.getConfig().getString("ALTARS.COMMANDS.LEGS.ALIAS", "legs");
            if (reload.startsWith(args[0].toLowerCase())) options.add(reload);
            if (legs.startsWith(args[0].toLowerCase())) options.add(legs);
            return options;
        }
        return List.of();
    }

    private Component color(String message) {
        return serializer.deserialize(message);
    }
}
