package dev.altarly;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AbilityListener implements Listener {
    private static final String[] RUINSTEP_FLAME_FRAMES = {
            "⛿", "⛾", "⛽", "⛼", "⛻", "⛺", "⛹", "⛸", "🁉", "🁊", " "
    };

    private final JavaPlugin plugin;
    private final WeaponManager weaponManager;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, Long> slashCooldowns = new HashMap<>();
    private final Map<UUID, Long> ruinstepCooldowns = new HashMap<>();

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
        if (sneaking && plugin.getConfig().getBoolean("CURSED_BLADE.ABILITIES.RUINSTEP.ENABLED", true)) {
            castRuinstep(player);
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
        double damage = cfg.getDouble(root + ".TRUE_DAMAGE", 5.0);
        double hitRadius = cfg.getDouble(root + ".HIT_RADIUS", 0.65);
        int startOffset = cfg.getInt(root + ".START_OFFSET_BLOCKS", 1);
        String worldName = cfg.getString(root + ".MM_WORLD", player.getWorld().getName());
        String mobId = cfg.getString(root + ".MM_MOB", "TOWER_SKELETON_SLASH_FX:1");

        Location origin = player.getLocation();
        Vector forward = origin.getDirection().setY(0).normalize();
        if (forward.lengthSquared() == 0) {
            forward = new Vector(0, 0, 1);
        }

        for (int step = 0; step < totalBlocks; step++) {
            int distance = startOffset + step;
            Location point = origin.clone().add(forward.clone().multiply(distance));
            Location block = point.getBlock().getLocation();

            dispatchMythicSpawn(worldName, mobId, block.getBlockX(), block.getBlockY(), block.getBlockZ(), origin.getYaw(), origin.getPitch());
            point.getWorld().spawnParticle(Particle.SWEEP_ATTACK, point.clone().add(0, 1.1, 0), 1, 0, 0, 0, 0);
            point.getWorld().spawnParticle(Particle.CRIT, point.clone().add(0, 1.0, 0), 4, 0.15, 0.15, 0.15, 0.02);
            damageNearbyPlayers(player, point.clone().add(0, 1.0, 0), hitRadius, damage);
        }

        playSound(player, cfg.getString(root + ".SOUND", "littleroom_towerskeleton:sword_hit"), 1.0f, 1.15f);
        playSound(player, cfg.getString(root + ".SOUND_SECONDARY", "minecraft:entity.player.attack.sweep"), 0.85f, 1.2f);
    }

    private void castRuinstep(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        String root = "CURSED_BLADE.ABILITIES.RUINSTEP";

        long cooldownMillis = cfg.getLong(root + ".COOLDOWN_MILLIS", 12000L);
        if (isOnCooldown(player.getUniqueId(), ruinstepCooldowns, cooldownMillis)) {
            sendMessage(player, cfg.getString("CURSED_BLADE.MESSAGES.RUINSTEP_COOLDOWN", "&cRuinstep is on cooldown."));
            return;
        }

        ruinstepCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        showCooldownBar(player, cfg.getString(root + ".ABILITY_NAME", "Ruinstep"), cooldownMillis, BossBar.Color.RED);

        double dashDistance = cfg.getDouble(root + ".DASH_DISTANCE_BLOCKS", 8.0);
        double dashSpeed = cfg.getDouble(root + ".DASH_SPEED", 1.85);
        double flameSpacing = Math.max(0.1, cfg.getDouble(root + ".FLAME_SPACING_BLOCKS", 1.15));
        double hitRadius = cfg.getDouble(root + ".HIT_RADIUS", 0.75);
        double trueDamage = cfg.getDouble(root + ".TRUE_DAMAGE", 3.0);
        double dashLift = cfg.getDouble(root + ".DASH_LIFT_VELOCITY", 0.42);
        double trailOffset = cfg.getDouble(root + ".TRAIL_OFFSET_BLOCKS", 0.9);
        int trailTickInterval = Math.max(1, cfg.getInt(root + ".TRAIL_TICK_INTERVAL", 1));
        int maxAirTicks = Math.max(1, cfg.getInt(root + ".MAX_AIR_TICKS", 30));
        float flameScale = (float) cfg.getDouble(root + ".FLAME_SCALE", 4.0);

        Location start = player.getLocation();
        Vector horizontalDirection = start.getDirection().setY(0).normalize();
        if (horizontalDirection.lengthSquared() == 0) {
            horizontalDirection = new Vector(0, 0, 1);
        }
        final Vector dashDirection = horizontalDirection.clone();
        Vector dashVelocity = dashDirection.clone().multiply(dashSpeed).setY(dashLift);
        player.setVelocity(dashVelocity);

        int maxTicksFromDistance = Math.max(1, (int) Math.ceil(dashDistance / Math.max(0.01, dashSpeed)));
        int tickLimit = Math.min(maxAirTicks, maxTicksFromDistance + maxAirTicks);
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                Vector travelDirection = player.getVelocity().clone().setY(0);
                if (travelDirection.lengthSquared() < 0.0001) {
                    travelDirection = dashDirection.clone();
                } else {
                    travelDirection.normalize();
                }

                Location spawn = player.getLocation()
                        .add(travelDirection.clone().multiply(-Math.max(flameSpacing, trailOffset)))
                        .add(0, 0.15, 0);
                spawnRuinstepFlame(spawn, flameScale);
                damageNearbyPlayers(player, spawn.clone().add(0, 0.8, 0), hitRadius, trueDamage);

                ticks += trailTickInterval;
                if ((ticks > 0 && player.isOnGround()) || ticks >= tickLimit) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, trailTickInterval);

        playSound(player, cfg.getString(root + ".DASH_SOUND", "minecraft:entity.blaze.shoot"), 1.0f, 0.95f);
        playSound(player, cfg.getString(root + ".DASH_SOUND_SECONDARY", "minecraft:entity.player.attack.knockback"), 0.9f, 1.1f);
    }

    private void spawnRuinstepFlame(Location location, float scale) {
        if (location.getWorld() == null) {
            return;
        }

        TextDisplay display = (TextDisplay) location.getWorld().spawnEntity(location, EntityType.TEXT_DISPLAY);
        display.setText(RUINSTEP_FLAME_FRAMES[0]);
        display.setBillboard(Display.Billboard.CENTER);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setBrightness(new Display.Brightness(15, 15));
        display.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        ));

        new BukkitRunnable() {
            int frame = 1;

            @Override
            public void run() {
                if (!display.isValid()) {
                    cancel();
                    return;
                }

                if (frame >= RUINSTEP_FLAME_FRAMES.length) {
                    display.remove();
                    cancel();
                    return;
                }

                display.setText(RUINSTEP_FLAME_FRAMES[frame]);
                frame++;
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void damageNearbyPlayers(Player source, Location center, double radius, double amount) {
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof Player target) || target.getUniqueId().equals(source.getUniqueId())) {
                continue;
            }
            applyTrueDamage(source, target, amount);
        }
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
        double result = health - amount;
        if (result <= 0.0) {
            target.setNoDamageTicks(0);
            target.damage(1000.0, source);
            return;
        }

        target.setNoDamageTicks(0);
        target.damage(0.001, source);
        target.setHealth(result);
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
