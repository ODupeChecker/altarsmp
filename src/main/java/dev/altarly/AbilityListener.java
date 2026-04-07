package dev.altarly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
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
import org.joml.Vector3f;

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
        } else if (!sneaking && plugin.getConfig().getBoolean("CURSED_BLADE.ABILITIES.SWORD_SLASH.ENABLED", true)) {
            castSwordSlash(player);
        }
    }

    private void castSwordSlash(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        String root = "CURSED_BLADE.ABILITIES.SWORD_SLASH";

        if (isOnCooldown(player.getUniqueId(), slashCooldowns, cfg.getLong(root + ".COOLDOWN_MILLIS", 5500L))) {
            sendMessage(player, cfg.getString("CURSED_BLADE.MESSAGES.SLASH_COOLDOWN", "&cSword Slash is on cooldown."));
            return;
        }

        slashCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        int slashCount = cfg.getInt(root + ".SLASH_COUNT", 5);
        double maxDistance = cfg.getDouble(root + ".MAX_DISTANCE", 10.0);
        double spacing = maxDistance / Math.max(1, slashCount);
        double damage = cfg.getDouble(root + ".DAMAGE", 5.0);
        double radius = cfg.getDouble(root + ".RADIUS", 1.7);
        double height = cfg.getDouble(root + ".HEIGHT", 2.0);
        long intervalTicks = cfg.getLong(root + ".INTERVAL_TICKS", 4L);
        int standLifeTicks = cfg.getInt(root + ".VISUAL.LIFETIME_TICKS", 20);
        int sweepCount = cfg.getInt(root + ".VISUAL.PARTICLE_SWEEP_COUNT", 1);
        int cloudCount = cfg.getInt(root + ".VISUAL.PARTICLE_CLOUD_COUNT", 8);

        Vector direction = player.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() == 0) {
            direction = new Vector(0, 0, 1);
        }
        final Vector flatDirection = direction;

        World world = player.getWorld();

        new BukkitRunnable() {
            int current = 0;

            @Override
            public void run() {
                if (current >= slashCount || !player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                double distance = Math.min(maxDistance, spacing * (current + 1));
                Location base = player.getLocation().clone().add(flatDirection.clone().multiply(distance));
                Location ground = base.clone();
                ground.setY(world.getHighestBlockYAt(base) + 0.1);

                spawnSlashStand(ground, player.getLocation().getYaw(), standLifeTicks);
                world.spawnParticle(Particle.SWEEP_ATTACK, ground.clone().add(0, 1.0, 0), sweepCount, 0.1, 0.1, 0.1, 0.01);
                world.spawnParticle(Particle.CLOUD, ground.clone().add(0, 0.2, 0), cloudCount, 0.45, 0.1, 0.45, 0.01);

                for (Entity entity : world.getNearbyEntities(ground, radius, height, radius)) {
                    if (!(entity instanceof Player target) || target.getUniqueId().equals(player.getUniqueId())) {
                        continue;
                    }
                    applyAbilityDamage(player, target, damage);
                }
                current++;
            }
        }.runTaskTimer(plugin, 0L, intervalTicks);
    }

    private void castFlamethrower(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        String root = "CURSED_BLADE.ABILITIES.CURSED_FLAMETHROWER";

        if (isOnCooldown(player.getUniqueId(), flamethrowerCooldowns, cfg.getLong(root + ".COOLDOWN_MILLIS", 12000L))) {
            sendMessage(player, cfg.getString("CURSED_BLADE.MESSAGES.FLAMETHROWER_COOLDOWN", "&cCursed Flamethrower is on cooldown."));
            return;
        }

        flamethrowerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        int durationTicks = cfg.getInt(root + ".DURATION_TICKS", 60);
        double maxRange = cfg.getDouble(root + ".MAX_RANGE", 8.0);
        double step = cfg.getDouble(root + ".RAY_STEP", 0.8);
        double hitRadius = cfg.getDouble(root + ".HIT_RADIUS", 1.15);
        double tickDamage = cfg.getDouble(root + ".DAMAGE_PER_TICK", 1.4);
        int fireTicks = cfg.getInt(root + ".FIRE_TICKS", 40);

        new BukkitRunnable() {
            int lived = 0;

            @Override
            public void run() {
                if (lived >= durationTicks || !player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                Location eye = player.getEyeLocation();
                Vector direction = eye.getDirection().normalize();
                renderFlamethrowerDisplay(eye.clone().add(direction.clone().multiply(1.75)), eye);

                for (double d = 1.0; d <= maxRange; d += step) {
                    Location point = eye.clone().add(direction.clone().multiply(d));
                    player.getWorld().spawnParticle(Particle.FLAME, point, 4, 0.15, 0.15, 0.15, 0.001);
                    player.getWorld().spawnParticle(Particle.SMOKE, point, 1, 0.05, 0.05, 0.05, 0.001);

                    for (Entity entity : player.getWorld().getNearbyEntities(point, hitRadius, hitRadius, hitRadius)) {
                        if (!(entity instanceof Player target) || target.getUniqueId().equals(player.getUniqueId())) {
                            continue;
                        }
                        applyAbilityDamage(player, target, tickDamage);
                        target.setFireTicks(Math.max(target.getFireTicks(), fireTicks));
                    }
                }

                lived++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void spawnSlashStand(Location location, float yaw, int lifetimeTicks) {
        String root = "CURSED_BLADE.ABILITIES.SWORD_SLASH.VISUAL";
        ArmorStand stand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        stand.customName(Component.text(plugin.getConfig().getString(root + ".CUSTOM_NAME", "Tower Skeleton Slash")));
        stand.setInvisible(plugin.getConfig().getBoolean(root + ".INVISIBLE", false));
        stand.setMarker(plugin.getConfig().getBoolean(root + ".MARKER", true));
        stand.setGravity(plugin.getConfig().getBoolean(root + ".GRAVITY", false));
        stand.setInvulnerable(plugin.getConfig().getBoolean(root + ".INVULNERABLE", true));
        stand.setPersistent(false);
        stand.setRotation(yaw, 0f);
        Bukkit.getScheduler().runTaskLater(plugin, stand::remove, lifetimeTicks);
    }

    private void renderFlamethrowerDisplay(Location location, Location eye) {
        String root = "CURSED_BLADE.ABILITIES.CURSED_FLAMETHROWER.VISUAL";
        TextDisplay display = (TextDisplay) location.getWorld().spawnEntity(location, EntityType.TEXT_DISPLAY);
        display.text(Component.text(plugin.getConfig().getString(root + ".GLYPH", "⛹")));
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(false);
        display.setViewRange((float) plugin.getConfig().getDouble(root + ".VIEW_RANGE", 1.0));
        int block = plugin.getConfig().getInt(root + ".BRIGHTNESS_BLOCK", 15);
        int sky = plugin.getConfig().getInt(root + ".BRIGHTNESS_SKY", 15);
        display.setBrightness(new Display.Brightness(block, sky));
        display.setPersistent(false);

        Transformation transformation = display.getTransformation();
        float scale = (float) plugin.getConfig().getDouble(root + ".SCALE", 4.0);
        display.setTransformation(new Transformation(
                transformation.getTranslation(),
                transformation.getLeftRotation(),
                new Vector3f(scale, scale, scale),
                transformation.getRightRotation()
        ));
        display.setRotation(eye.getYaw(), eye.getPitch());
        int life = plugin.getConfig().getInt(root + ".LIFETIME_TICKS", 2);
        Bukkit.getScheduler().runTaskLater(plugin, display::remove, life);
    }

    private void applyAbilityDamage(Player source, LivingEntity target, double amount) {
        target.damage(amount, source);
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
