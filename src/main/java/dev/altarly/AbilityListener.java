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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AbilityListener implements Listener {

    private final AltarlyPlugin plugin;
    private final WeaponManager weaponManager;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, Long> slashCooldowns = new HashMap<>();
    private final Map<UUID, Long> flamethrowerCooldowns = new HashMap<>();

    public AbilityListener(AltarlyPlugin plugin, WeaponManager weaponManager) {
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
        if (sneaking && config().getBoolean("CURSED_BLADE.ABILITIES.CURSED_FLAMETHROWER.ENABLED", true)) {
            castFlamethrower(player);
        } else if (!sneaking && config().getBoolean("CURSED_BLADE.ABILITIES.SWORD_SLASH.ENABLED", true)) {
            castSwordSlash(player);
        }
    }

    public void resetCooldowns() {
        slashCooldowns.clear();
        flamethrowerCooldowns.clear();
    }

    private void castSwordSlash(Player player) {
        String root = "CURSED_BLADE.ABILITIES.SWORD_SLASH";

        if (isOnCooldown(player.getUniqueId(), slashCooldowns, config().getLong(root + ".COOLDOWN_MILLIS", 5500L))) {
            sendMessage(player, config().getString("CURSED_BLADE.MESSAGES.SLASH_COOLDOWN", "&cSword Slash is on cooldown."));
            return;
        }

        slashCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        int slashCount = config().getInt(root + ".SLASH_COUNT", 5);
        double maxDistance = config().getDouble(root + ".MAX_DISTANCE", 10.0);
        double spacing = maxDistance / Math.max(1, slashCount);
        double damage = config().getDouble(root + ".DAMAGE", 5.0);
        double radius = config().getDouble(root + ".RADIUS", 1.7);
        double height = config().getDouble(root + ".HEIGHT", 2.0);
        long intervalTicks = config().getLong(root + ".INTERVAL_TICKS", 4L);
        int standLifeTicks = config().getInt(root + ".VISUAL.LIFETIME_TICKS", 20);
        int sweepCount = config().getInt(root + ".VISUAL.PARTICLE_SWEEP_COUNT", 1);
        int cloudCount = config().getInt(root + ".VISUAL.PARTICLE_CLOUD_COUNT", 8);
        double visualScale = config().getDouble(root + ".VISUAL.SCALE", 1.0);

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
                Location ground = player.getLocation().clone().add(flatDirection.clone().multiply(distance));
                ground.setY(player.getLocation().getY() + 0.1);

                spawnSlashStand(ground, player.getLocation().getYaw(), standLifeTicks, visualScale);
                world.spawnParticle(Particle.SWEEP_ATTACK, ground.clone().add(0, 1.0 * visualScale, 0), sweepCount, 0.1 * visualScale, 0.1 * visualScale, 0.1 * visualScale, 0.01);
                world.spawnParticle(Particle.CLOUD, ground.clone().add(0, 0.2 * visualScale, 0), cloudCount, 0.45 * visualScale, 0.1, 0.45 * visualScale, 0.01);

                for (Entity entity : world.getNearbyEntities(ground, radius * visualScale, height * visualScale, radius * visualScale)) {
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
        String root = "CURSED_BLADE.ABILITIES.CURSED_FLAMETHROWER";

        if (isOnCooldown(player.getUniqueId(), flamethrowerCooldowns, config().getLong(root + ".COOLDOWN_MILLIS", 12000L))) {
            sendMessage(player, config().getString("CURSED_BLADE.MESSAGES.FLAMETHROWER_COOLDOWN", "&cCursed Flamethrower is on cooldown."));
            return;
        }

        flamethrowerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        int durationTicks = config().getInt(root + ".DURATION_TICKS", 60);
        double hitRadius = config().getDouble(root + ".HIT_RADIUS", 1.15);
        double tickDamage = config().getDouble(root + ".DAMAGE_PER_TICK", 1.4);
        int fireTicks = config().getInt(root + ".FIRE_TICKS", 40);
        int packetLifeTicks = config().getInt(root + ".PACKET_LIFE_TICKS", 6);
        double packetVelocity = config().getDouble(root + ".PACKET_VELOCITY", 0.5);
        double spawnOffset = config().getDouble(root + ".SPAWN_OFFSET", 0.9);
        double visualScale = config().getDouble(root + ".VISUAL.SCALE", 1.0);
        double displayOffset = config().getDouble(root + ".VISUAL.DISPLAY_OFFSET", 1.0);

        new BukkitRunnable() {
            int lived = 0;
            final List<FlamePacket> packets = new ArrayList<>();

            @Override
            public void run() {
                if (lived >= durationTicks || !player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                Location eye = player.getEyeLocation();
                Vector direction = eye.getDirection().normalize();
                Location spawnPoint = eye.clone().add(direction.clone().multiply(spawnOffset));
                packets.add(new FlamePacket(spawnPoint, direction.clone().multiply(packetVelocity), packetLifeTicks));
                renderFlamethrowerDisplay(spawnPoint.clone().add(direction.clone().multiply(displayOffset)), eye, visualScale);

                Iterator<FlamePacket> iterator = packets.iterator();
                while (iterator.hasNext()) {
                    FlamePacket packet = iterator.next();
                    packet.location.add(packet.velocity);

                    player.getWorld().spawnParticle(Particle.FLAME, packet.location, (int) Math.max(2, 5 * visualScale), 0.2 * visualScale, 0.2 * visualScale, 0.2 * visualScale, 0.001);
                    player.getWorld().spawnParticle(Particle.SMOKE, packet.location, (int) Math.max(1, 2 * visualScale), 0.1 * visualScale, 0.1 * visualScale, 0.1 * visualScale, 0.001);

                    for (Entity entity : player.getWorld().getNearbyEntities(packet.location, hitRadius * visualScale, hitRadius * visualScale, hitRadius * visualScale)) {
                        if (!(entity instanceof Player target) || target.getUniqueId().equals(player.getUniqueId())) {
                            continue;
                        }
                        applyAbilityDamage(player, target, tickDamage);
                        target.setFireTicks(Math.max(target.getFireTicks(), fireTicks));
                    }

                    packet.life--;
                    if (packet.life <= 0) {
                        iterator.remove();
                    }
                }

                lived++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void spawnSlashStand(Location location, float yaw, int lifetimeTicks, double scale) {
        String root = "CURSED_BLADE.ABILITIES.SWORD_SLASH.VISUAL";
        ArmorStand stand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        stand.customName(Component.text(config().getString(root + ".CUSTOM_NAME", "Tower Skeleton Slash")));
        stand.setInvisible(config().getBoolean(root + ".INVISIBLE", false));
        stand.setMarker(config().getBoolean(root + ".MARKER", true));
        stand.setGravity(config().getBoolean(root + ".GRAVITY", false));
        stand.setInvulnerable(config().getBoolean(root + ".INVULNERABLE", true));
        stand.setPersistent(false);
        stand.setSmall(scale < 0.9);
        stand.setRotation(yaw, 0f);
        Bukkit.getScheduler().runTaskLater(plugin, stand::remove, lifetimeTicks);
    }

    private void renderFlamethrowerDisplay(Location location, Location eye, double scaleMultiplier) {
        String root = "CURSED_BLADE.ABILITIES.CURSED_FLAMETHROWER.VISUAL";
        TextDisplay display = (TextDisplay) location.getWorld().spawnEntity(location, EntityType.TEXT_DISPLAY);
        display.text(Component.text(config().getString(root + ".GLYPH", "⛹")));
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(false);
        display.setViewRange((float) config().getDouble(root + ".VIEW_RANGE", 1.0));
        int block = config().getInt(root + ".BRIGHTNESS_BLOCK", 15);
        int sky = config().getInt(root + ".BRIGHTNESS_SKY", 15);
        display.setBrightness(new Display.Brightness(block, sky));
        display.setPersistent(false);

        Transformation transformation = display.getTransformation();
        float scale = (float) (config().getDouble(root + ".SCALE", 4.0) * scaleMultiplier);
        display.setTransformation(new Transformation(
                transformation.getTranslation(),
                transformation.getLeftRotation(),
                new Vector3f(scale, scale, scale),
                transformation.getRightRotation()
        ));
        display.setRotation(eye.getYaw(), eye.getPitch());
        int life = config().getInt(root + ".LIFETIME_TICKS", 2);
        Bukkit.getScheduler().runTaskLater(plugin, display::remove, life);
    }

    private void applyAbilityDamage(Player source, LivingEntity target, double amount) {
        target.damage(amount, source);
    }

    private void sendMessage(Player player, String message) {
        String prefix = config().getString("CURSED_BLADE.MESSAGES.PREFIX", "");
        player.sendMessage(serializer.deserialize(prefix + message));
    }

    private boolean isOnCooldown(UUID uuid, Map<UUID, Long> cooldowns, long cooldownMillis) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(uuid);
        return last != null && (now - last) < cooldownMillis;
    }

    private FileConfiguration config() {
        return plugin.getAltarlyConfig();
    }

    private static final class FlamePacket {
        private final Location location;
        private final Vector velocity;
        private int life;

        private FlamePacket(Location location, Vector velocity, int life) {
            this.location = location;
            this.velocity = velocity;
            this.life = life;
        }
    }
}
