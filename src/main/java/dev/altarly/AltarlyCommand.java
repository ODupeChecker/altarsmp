package dev.altarly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

public final class AltarlyCommand implements CommandExecutor, TabCompleter {
    private final AltarlyPlugin plugin;
    private final WeaponManager weaponManager;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();
    private volatile int purgeRate = 50;
    private volatile boolean purgeRunning = false;

    public AltarlyCommand(AltarlyPlugin plugin, WeaponManager weaponManager) {
        this.plugin = plugin;
        this.weaponManager = weaponManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("removeitem")) {
            return handleRemoveItem(sender);
        }
        if (command.getName().equalsIgnoreCase("removeitemrate")) {
            return handleRate(sender, args);
        }

        FileConfiguration cfg = plugin.getConfig();

        if (args.length == 0) {
            sender.sendMessage(color("&e/altarly reload &7- Reload config"));
            sender.sendMessage(color("&e/altarly legs &7- Open legendary chest"));
            return true;
        }

        if (args[0].equalsIgnoreCase(cfg.getString("CURSED_BLADE.COMMANDS.RELOAD.ALIAS", "reload"))) {
            if (!sender.hasPermission("altarly.admin")) {
                sender.sendMessage(color(cfg.getString("CURSED_BLADE.MESSAGES.NO_PERMISSION", "&cYou do not have permission.")));
                return true;
            }

            plugin.reloadConfig();
            sender.sendMessage(color(cfg.getString("CURSED_BLADE.MESSAGES.RELOAD_SUCCESS", "&aAltarly config reloaded.")));
            return true;
        }

        if (args[0].equalsIgnoreCase(cfg.getString("CURSED_BLADE.COMMANDS.LEGS.ALIAS", "legs"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color(cfg.getString("CURSED_BLADE.MESSAGES.PLAYER_ONLY", "&cOnly players can use this command.")));
                return true;
            }
            if (!player.isOp()) {
                player.sendMessage(color(cfg.getString("CURSED_BLADE.MESSAGES.NO_PERMISSION", "&cOnly operators can use this command.")));
                return true;
            }

            String title = cfg.getString("CURSED_BLADE.COMMANDS.LEGS.INVENTORY_TITLE", "Legendary Weapons");
            Inventory chest = Bukkit.createInventory(null, InventoryType.CHEST, Component.text(title));
            int cursedSlot = Math.max(0, Math.min(26, cfg.getInt("CURSED_BLADE.COMMANDS.LEGS.GIVE_ITEM_SLOT", 0)));
            int enderSlot = Math.max(0, Math.min(26, cfg.getInt("ENDER_BLADE.COMMANDS.LEGS.GIVE_ITEM_SLOT", 1)));
            int tridentSlot = Math.max(0, Math.min(26, cfg.getInt("POSEIDONS_TRIDENT.COMMANDS.LEGS.GIVE_ITEM_SLOT", 2)));
            chest.setItem(cursedSlot, weaponManager.createCursedBlade());
            chest.setItem(enderSlot, weaponManager.createEnderBlade());
            chest.setItem(tridentSlot, weaponManager.createPoseidonsTrident());
            player.openInventory(chest);
            return true;
        }

        sender.sendMessage(color("&cUnknown subcommand."));
        return true;
    }

    private boolean handleRate(CommandSender sender, String[] args) {
        if (!isAuthorized(sender)) return true;
        if (args.length != 1) {
            sender.sendMessage(color("&cUsage: /removeitemrate <number_per_second>"));
            return true;
        }
        try {
            int rate = Integer.parseInt(args[0]);
            if (rate < 1 || rate > 5000) {
                sender.sendMessage(color("&cRate must be 1-5000."));
                return true;
            }
            purgeRate = rate;
            sender.sendMessage(color("&aremoveitem rate set to &e" + rate + "&a entries/sec."));
        } catch (NumberFormatException ex) {
            sender.sendMessage(color("&cInvalid number."));
        }
        return true;
    }

    private boolean handleRemoveItem(CommandSender sender) {
        if (!(sender instanceof Player player) || !isAuthorized(sender)) return true;
        if (purgeRunning) {
            sender.sendMessage(color("&cA purge is already running."));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage(color("&cHold an item first."));
            return true;
        }
        ItemMeta meta = held.getItemMeta();
        Integer customModelData = (meta != null && meta.hasCustomModelData()) ? meta.getCustomModelData() : null;
        String itemName = (meta != null && meta.hasDisplayName())
                ? PlainTextComponentSerializer.plainText().serialize(meta.displayName())
                : held.getType().name();
        String typeToken = "minecraft:" + held.getType().getKey().getKey().toLowerCase(Locale.ROOT);
        String cmdToken = customModelData == null ? null : "custom_model_data\":\"" + customModelData;

        Component alert = color("&4⚠ &cTHE ITEM &6(" + held.getType().name() + ") &cIS BEING PURGED &4⚠");
        Bukkit.getServer().broadcast(alert);

        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(plugin.getDataFolder().getParentFile().getAbsolutePath(), "EnderChest", "data"));
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            roots.add(world.getWorldFolder().toPath().resolve("playerdata"));
        }

        ConcurrentLinkedQueue<Path> queue = new ConcurrentLinkedQueue<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(queue::add);
            } catch (IOException ignored) {
            }
        }

        purgeRunning = true;
        new BukkitRunnable() {
            int filesChanged = 0;
            int itemsRemoved = 0;
            @Override
            public void run() {
                int perTick = Math.max(1, purgeRate / 20);
                for (int i = 0; i < perTick; i++) {
                    Path file = queue.poll();
                    if (file == null) {
                        purgeRunning = false;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage(color("&aPurge completed. &e" + itemsRemoved + "&a matches removed in &e" + filesChanged + "&a files."));
                            player.sendMessage(color("&cALL PEICES PURGED"));
                            Bukkit.broadcast(color("&6" + itemsRemoved + " &c\"" + itemName + "\" &6purged"));
                        });
                        cancel();
                        return;
                    }
                    try {
                        byte[] bytes = Files.readAllBytes(file);
                        String text = new String(bytes, StandardCharsets.ISO_8859_1);
                        int before = countMatches(text, typeToken, cmdToken);
                        if (before > 0) {
                            String replaced = text.replace(typeToken, "__PURGED__" + typeToken);
                            Files.write(file, replaced.getBytes(StandardCharsets.ISO_8859_1));
                            filesChanged++;
                            itemsRemoved += before;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }.runTaskTimerAsynchronously(plugin, 1L, 1L);

        sender.sendMessage(color("&aStarted purge at &e" + purgeRate + "&a entries/sec."));
        return true;
    }

    private int countMatches(String text, String typeToken, String cmdToken) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(typeToken, idx)) != -1) {
            int end = Math.min(text.length(), idx + 250);
            String window = text.substring(idx, end);
            if (cmdToken == null || window.contains(cmdToken)) count++;
            idx += typeToken.length();
        }
        return count;
    }

    private boolean isAuthorized(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use this command."));
            return false;
        }
        if (!player.getName().equalsIgnoreCase("laststandzzz")) {
            player.sendMessage(color("&cYou are not allowed to use this command."));
            return false;
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            String reload = plugin.getConfig().getString("CURSED_BLADE.COMMANDS.RELOAD.ALIAS", "reload");
            String legs = plugin.getConfig().getString("CURSED_BLADE.COMMANDS.LEGS.ALIAS", "legs");
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
