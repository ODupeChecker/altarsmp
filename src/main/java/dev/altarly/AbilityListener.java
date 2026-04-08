package dev.altarly;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AbilityListener implements Listener {

    private final JavaPlugin plugin;
    private final WeaponManager weaponManager;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, Long> slashCooldowns = new HashMap<>();
    private final Map<UUID, Long> flamethrowerCooldowns = new HashMap<>();

    public AbilityListener(JavaPlugin plugin, WeaponManager weaponManager) {
        this.plugin = plugin;
        this.weaponManager = weaponManager;
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!weaponManager.isLegendaryWeapon(mainHand)) {
            return;
        }

        event.setCancelled(true);
        boolean sneaking = player.isSneaking();
        if (sneaking && plugin.getConfig().getBoolean("CURSED_BLADE.ABILITIES.CURSED_FLAMETHROWER.ENABLED", true)) {
            castFlamethrower(player);
        } else if (!sneaking && plugin.getConfig().getBoolean("CURSED_BLADE.ABILITIES.RUINED_SLASH.ENABLED", true)) {
            castRuinedSlash(player);
        }
    }

    private void castRuinedSlash(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        String root = "CURSED_BLADE.ABILITIES.RUINED_SLASH";

        long cooldownMillis = cfg.getLong(root + ".COOLDOWN_MILLIS", 5500L);
        if (isOnCooldown(player.getUniqueId(), slashCooldowns, cooldownMillis)) {
            sendMessage(player, cfg.getString("CURSED_BLADE.MESSAGES.SLASH_COOLDOWN", "&cRuined Slash is on cooldown."));
            return;
        }

        slashCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        showCooldownBar(player, cfg.getString(root + ".ABILITY_NAME", "Ruined Slash"), cooldownMillis, BossBar.Color.PURPLE);

        int totalBlocks = cfg.getInt(root + ".DISTANCE_BLOCKS", 7);
        double startBlocks = cfg.getDouble(root + ".START_BLOCKS", 1.0);
        double hitRadius = cfg.getDouble(root + ".HIT_RADIUS", 0.5);
        double damage = cfg.getDouble(root + ".TRUE_DAMAGE", 5.0);
        String worldName = cfg.getString(root + ".MM_WORLD", player.getWorld().getName());
        String mobId = cfg.getString(root + ".MM_MOB", "TOWER_SKELETON_SLASH_FX:1");

        Location origin = player.getLocation();
        Vector forward = origin.getDirection().setY(0).normalize();
        if (forward.lengthSquared() == 0) {
            forward = new Vector(0, 0, 1);
        }

        Map<UUID, Player> hits = new HashMap<>();
        for (int step = 0; step < totalBlocks; step++) {
            double distance = startBlocks + step;
            Location point = origin.clone().add(forward.clone().multiply(distance));
            Location block = point.getBlock().getLocation();
            dispatchMythicSpawn(worldName, mobId, block.getBlockX(), block.getBlockY(), block.getBlockZ(), origin.getYaw(), origin.getPitch());

            for (Entity entity : point.getWorld().getNearbyEntities(point, hitRadius, hitRadius, hitRadius)) {
                if (!(entity instanceof Player target) || target.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                hits.putIfAbsent(target.getUniqueId(), target);
            }
        }

        playSound(player, cfg.getString(root + ".SOUND", "littleroom_towerskeleton:sword_hit"), 1.0f, 1.0f);
        for (Player target : hits.values()) {
            applyTrueDamage(player, target, damage);
        }
    }

    private void castFlamethrower(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        String root = "CURSED_BLADE.ABILITIES.CURSED_FLAMETHROWER";

        long cooldownMillis = cfg.getLong(root + ".COOLDOWN_MILLIS", 12000L);
        if (isOnCooldown(player.getUniqueId(), flamethrowerCooldowns, cooldownMillis)) {
            sendMessage(player, cfg.getString("CURSED_BLADE.MESSAGES.FLAMETHROWER_COOLDOWN", "&cCursed Flamethrower is on cooldown."));
            return;
        }

        flamethrowerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        showCooldownBar(player, cfg.getString(root + ".ABILITY_NAME", "Cursed Flamethrower"), cooldownMillis, BossBar.Color.RED);

        int durationTicks = cfg.getInt(root + ".DURATION_TICKS", 120);
        long pulseTicks = cfg.getLong(root + ".PULSE_INTERVAL_TICKS", 24L);
        long soundTicks = cfg.getLong(root + ".SOUND_INTERVAL_TICKS", 20L);
        double maxDistance = cfg.getDouble(root + ".LENGTH_BLOCKS", 3.0);
        double startBlocks = cfg.getDouble(root + ".START_BLOCKS", 1.0);
        double step = cfg.getDouble(root + ".STEP_BLOCKS", 0.5);
        double hitRadius = cfg.getDouble(root + ".HIT_RADIUS", 0.8);
        double tickDamage = cfg.getDouble(root + ".TRUE_DAMAGE_PER_PULSE", 1.2);
        String worldName = cfg.getString(root + ".MM_WORLD", player.getWorld().getName());
        String mobId = cfg.getString(root + ".MM_MOB", "TOWERSKELETON_flamethrower_fx:1");

        new BukkitRunnable() {
            int livedTicks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || livedTicks >= durationTicks) {
                    cancel();
                    return;
                }

                Location eye = player.getEyeLocation();
                Vector direction = eye.getDirection().normalize();

                Map<UUID, Player> pulseHits = new HashMap<>();
                for (double dist = startBlocks; dist <= maxDistance + 0.0001; dist += step) {
                    Location point = eye.clone().add(direction.clone().multiply(dist));
                    dispatchMythicSpawn(worldName, mobId, point.getBlockX(), point.getBlockY(), point.getBlockZ(), eye.getYaw(), eye.getPitch());

                    for (Entity entity : point.getWorld().getNearbyEntities(point, hitRadius, hitRadius, hitRadius)) {
                        if (!(entity instanceof Player target) || target.getUniqueId().equals(player.getUniqueId())) {
                            continue;
                        }
                        pulseHits.putIfAbsent(target.getUniqueId(), target);
                    }
                }

                for (Player target : pulseHits.values()) {
                    applyTrueDamage(player, target, tickDamage);
                }

                livedTicks += pulseTicks;
            }
        }.runTaskTimer(plugin, 0L, pulseTicks);

        new BukkitRunnable() {
            int livedTicks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || livedTicks >= durationTicks) {
                    cancel();
                    return;
                }

                playSound(player, cfg.getString(root + ".LOOP_SOUND", "littleroom_towerskeleton:shield_flamethrower_loop"), 1.0f, 1.0f);
                livedTicks += (int) soundTicks;
            }
        }.runTaskTimer(plugin, 0L, soundTicks);
    }


    private void dispatchMythicSpawn(String worldName, String mobId, int x, int y, int z, float yaw, float pitch) {
        String command = "mm mobs spawn " + mobId + " 1 " + worldName + "," + x + "," + y + "," + z + "," + yaw + "," + pitch;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void playSound(Player player, String soundKey, float volume, float pitch) {
        player.playSound(Sound.sound(Key.key(soundKey), Sound.Source.PLAYER, volume, pitch));
    }

    private void applyTrueDamage(Player source, LivingEntity target, double amount) {
        double health = target.getHealth();
        target.setNoDamageTicks(0);
        target.damage(0.0001, source);
        target.setHealth(Math.max(0.0, health - amount));
    }

    private void showCooldownBar(Player player, String abilityName, long cooldownMillis, BossBar.Color color) {
        BossBar bar = BossBar.bossBar(
                Component.text(abilityName),
                1.0f,
                color,
                BossBar.Overlay.PROGRESS
        );
        player.showBossBar(bar);

        long startedAt = System.currentTimeMillis();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                long elapsed = System.currentTimeMillis() - startedAt;
                float progress = Math.max(0f, 1f - (float) elapsed / cooldownMillis);
                bar.progress(progress);

                if (elapsed >= cooldownMillis) {
                    player.hideBossBar(bar);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void sendMessage(Player player, String message) {
        String prefix = plugin.getConfig().getString("CURSED_BLADE.MESSAGES.PREFIX", "");
        player.sendMessage(serializer.deserialize(prefix + message));
    }

    private boolean isOnCooldown(UUID uuid, Map<UUID, Long> cooldowns, long cooldownMillis) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(uuid);
        return last != null && (now - last) < cooldownMillis;
    }
}
