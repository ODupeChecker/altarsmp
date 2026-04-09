package dev.altarly;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AbilityListener implements Listener {
    private static final String[] FLAME_GLYPHS = {"⛿", "⛾", "⛽", "⛼", "⛻", "⛺", "⛹", "⛸", "🁉", "🁊"};
    private static final String CHAIN_META = "altarly_chain_propagation";

    private final JavaPlugin plugin;
    private final WeaponManager weaponManager;
    private final BetterTeamsHook betterTeamsHook;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, Long> ruinedSlashCooldowns = new HashMap<>();
    private final Map<UUID, Long> ruinstepCooldowns = new HashMap<>();
    private final Map<UUID, Long> blinkStrikeCooldowns = new HashMap<>();
    private final Map<UUID, Long> enderChainCooldowns = new HashMap<>();
    private final Map<UUID, Set<UUID>> activeEnderLinks = new HashMap<>();

    public AbilityListener(JavaPlugin plugin, WeaponManager weaponManager, BetterTeamsHook betterTeamsHook) {
        this.plugin = plugin;
        this.weaponManager = weaponManager;
        this.betterTeamsHook = betterTeamsHook;
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        WeaponManager.LegendaryType type = weaponManager.getLegendaryType(mainHand);
        if (type == null) {
            return;
        }

        event.setCancelled(true);

        if (type == WeaponManager.LegendaryType.RUINED_BLADE) {
            if (player.isSneaking() && plugin.getConfig().getBoolean("ALTARS.RUINED_BLADE.ABILITIES.RUINSTEP.ENABLED", true)) {
                castRuinstep(player);
            } else if (!player.isSneaking() && plugin.getConfig().getBoolean("ALTARS.RUINED_BLADE.ABILITIES.RUINED_SLASH.ENABLED", true)) {
                castRuinedSlash(player);
            }
            return;
        }

        if (player.isSneaking() && plugin.getConfig().getBoolean("ALTARS.ENDER_BLADE.ABILITIES.ENDER_CHAIN.ENABLED", true)) {
            castEnderChain(player);
        } else if (!player.isSneaking() && plugin.getConfig().getBoolean("ALTARS.ENDER_BLADE.ABILITIES.BLINK_STRIKE.ENABLED", true)) {
            castBlinkStrike(player);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getEntity().hasMetadata(CHAIN_META)) {
            return;
        }

        Set<UUID> linked = activeEnderLinks.get(victim.getUniqueId());
        if (linked == null || linked.isEmpty()) {
            return;
        }

        double amount = event.getFinalDamage();
        Player attacker = event.getDamager() instanceof Player p ? p : null;

        for (UUID linkedId : linked) {
            Player linkedPlayer = Bukkit.getPlayer(linkedId);
            if (linkedPlayer == null || !linkedPlayer.isOnline() || linkedPlayer.isDead()) {
                continue;
            }
            if (attacker != null && betterTeamsHook.isFriendly(attacker, linkedPlayer)) {
                continue;
            }
            linkedPlayer.setMetadata(CHAIN_META, new org.bukkit.metadata.FixedMetadataValue(plugin, true));
            applyTrueDamage(attacker == null ? victim : attacker, linkedPlayer, amount);
            linkedPlayer.removeMetadata(CHAIN_META, plugin);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeLinks(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        removeLinks(event.getEntity().getUniqueId());
    }

    private void castRuinedSlash(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        String root = "ALTARS.RUINED_BLADE.ABILITIES.RUINED_SLASH";

        long cooldownMillis = cfg.getLong(root + ".COOLDOWN_MILLIS", 5500L);
        if (isOnCooldown(player.getUniqueId(), ruinedSlashCooldowns, cooldownMillis)) {
            sendMessage(player, cfg.getString("ALTARS.MESSAGES.RUINED_SLASH_COOLDOWN", "&cRuined Slash is on cooldown."));
            return;
        }

        ruinedSlashCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        showCooldownBar(player, cfg.getString(root + ".ABILITY_NAME", "Ruined Slash"), cooldownMillis, BossBar.Color.PURPLE);

        int totalBlocks = cfg.getInt(root + ".DISTANCE_BLOCKS", 7);
        double damage = cfg.getDouble(root + ".TRUE_DAMAGE", 5.0);
        double hitRadius = cfg.getDouble(root + ".HIT_RADIUS", 0.65);

        Location origin = player.getLocation();
        Vector forward = origin.getDirection().setY(0).normalize();
        if (forward.lengthSquared() == 0) {
            forward = new Vector(0, 0, 1);
        }

        for (int step = 1; step <= totalBlocks; step++) {
            Location point = origin.clone().add(forward.clone().multiply(step));
            point.getWorld().spawnParticle(org.bukkit.Particle.SWEEP_ATTACK, point.clone().add(0, 1.1, 0), 1, 0, 0, 0, 0);
            point.getWorld().spawnParticle(org.bukkit.Particle.CRIT, point.clone().add(0, 1.0, 0), 4, 0.15, 0.15, 0.15, 0.02);
            damageNearbyPlayers(player, point.clone().add(0, 1.0, 0), hitRadius, damage);
        }

        playSoundNearby(player, cfg.getString(root + ".SOUND", "minecraft:entity.player.attack.sweep"), 1.0f, 1.15f,
                cfg.getDouble("ALTARS.RUINED_BLADE.SOUNDS.RADIUS", 15.0));
    }

    private void castRuinstep(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        String root = "ALTARS.RUINED_BLADE.ABILITIES.RUINSTEP";

        long cooldownMillis = cfg.getLong(root + ".COOLDOWN_MILLIS", 12000L);
        if (isOnCooldown(player.getUniqueId(), ruinstepCooldowns, cooldownMillis)) {
            sendMessage(player, cfg.getString("ALTARS.MESSAGES.RUINSTEP_COOLDOWN", "&cRuinstep is on cooldown."));
            return;
        }

        ruinstepCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        showCooldownBar(player, cfg.getString(root + ".ABILITY_NAME", "Ruinstep"), cooldownMillis, BossBar.Color.RED);

        double dashSpeed = cfg.getDouble(root + ".DASH_SPEED", 1.85);
        double liftVelocity = cfg.getDouble(root + ".LIFT_VELOCITY", 0.42);
        double flameOffset = Math.max(0.4, cfg.getDouble(root + ".FLAME_OFFSET_BLOCKS", 0.9));
        double flameHeight = cfg.getDouble(root + ".FLAME_HEIGHT_OFFSET", 0.1);
        double displayScale = Math.max(0.1, cfg.getDouble(root + ".FLAME_SCALE", 4.0));
        int minAirTicks = Math.max(2, cfg.getInt(root + ".MIN_AIR_TICKS", 5));
        double hitRadius = cfg.getDouble(root + ".HIT_RADIUS", 0.75);
        double trueDamage = cfg.getDouble(root + ".TRUE_DAMAGE", 3.0);

        Location start = player.getLocation();
        Vector horizontalDirection = start.getDirection().setY(0).normalize();
        if (horizontalDirection.lengthSquared() == 0) {
            horizontalDirection = new Vector(0, 0, 1);
        }
        final Vector dashDirection = horizontalDirection.clone();

        Vector dashVelocity = dashDirection.clone().multiply(dashSpeed).setY(liftVelocity);
        player.setVelocity(dashVelocity);
        playSoundNearby(player, cfg.getString(root + ".DASH_SOUND", "minecraft:entity.blaze.shoot"), 1.0f, 0.95f,
                cfg.getDouble("ALTARS.RUINED_BLADE.SOUNDS.RADIUS", 15.0));

        new BukkitRunnable() {
            int ticksLived = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }
                if (ticksLived >= minAirTicks && player.isOnGround()) {
                    cancel();
                    return;
                }

                Location current = player.getLocation();
                Vector currentDirection = player.getVelocity().clone().setY(0);
                if (currentDirection.lengthSquared() < 0.0001) {
                    currentDirection = dashDirection.clone();
                } else {
                    currentDirection.normalize();
                }

                Location spawn = current.clone().add(currentDirection.clone().multiply(-flameOffset)).add(0, flameHeight, 0);
                spawnFlameDisplay(spawn, displayScale);
                damageNearbyPlayers(player, spawn.clone().add(0, 0.8, 0), hitRadius, trueDamage);

                ticksLived++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void castBlinkStrike(Player caster) {
        FileConfiguration cfg = plugin.getConfig();
        String root = "ALTARS.ENDER_BLADE.ABILITIES.BLINK_STRIKE";

        long cooldownMillis = cfg.getLong(root + ".COOLDOWN_MILLIS", 7000L);
        if (isOnCooldown(caster.getUniqueId(), blinkStrikeCooldowns, cooldownMillis)) {
            sendMessage(caster, cfg.getString("ALTARS.MESSAGES.BLINK_STRIKE_COOLDOWN", "&cBlink Strike is on cooldown."));
            return;
        }

        double range = cfg.getDouble(root + ".RANGE", 7.0);
        Player target = getClosestEnemy(caster, range, true);
        if (target == null) {
            sendMessage(caster, cfg.getString("ALTARS.MESSAGES.NO_ENDER_TARGET", "&cNo Ender Target Found"));
            return;
        }

        Location behind = target.getLocation().clone();
        Vector backward = target.getLocation().getDirection().setY(0).normalize();
        if (backward.lengthSquared() == 0) {
            backward = new Vector(0, 0, 1);
        }
        behind.subtract(backward.multiply(1.2));
        behind.setYaw(target.getLocation().getYaw());
        behind.setPitch(caster.getLocation().getPitch());

        blinkStrikeCooldowns.put(caster.getUniqueId(), System.currentTimeMillis());
        showCooldownBar(caster, cfg.getString(root + ".ABILITY_NAME", "Blink Strike"), cooldownMillis, BossBar.Color.PURPLE);

        caster.teleport(behind);
        double trueDamage = cfg.getDouble(root + ".TRUE_DAMAGE", 5.0);
        applyTrueDamage(caster, target, trueDamage);

        playSoundNearby(caster, cfg.getString(root + ".SOUND", "minecraft:entity.enderman.death"), 1.0f, 1.0f,
                cfg.getDouble(root + ".SOUND_RADIUS", 10.0));
    }

    private void castEnderChain(Player caster) {
        FileConfiguration cfg = plugin.getConfig();
        String root = "ALTARS.ENDER_BLADE.ABILITIES.ENDER_CHAIN";

        long cooldownMillis = cfg.getLong(root + ".COOLDOWN_MILLIS", 14000L);
        if (isOnCooldown(caster.getUniqueId(), enderChainCooldowns, cooldownMillis)) {
            sendMessage(caster, cfg.getString("ALTARS.MESSAGES.ENDER_CHAIN_COOLDOWN", "&cEnder Chain is on cooldown."));
            return;
        }

        double radius = cfg.getDouble(root + ".RADIUS", 20.0);
        List<Player> enemies = getNearbyEnemies(caster, radius, false);
        if (enemies.size() < 2) {
            sendMessage(caster, cfg.getString("ALTARS.MESSAGES.NO_CHAIN_TARGETS", "&cNO Enemies Found"));
            return;
        }

        Player first = enemies.get(0);
        Player second = enemies.get(1);

        enderChainCooldowns.put(caster.getUniqueId(), System.currentTimeMillis());
        showCooldownBar(caster, cfg.getString(root + ".ABILITY_NAME", "Ender Chain"), cooldownMillis, BossBar.Color.BLUE);

        linkPlayers(first, second);
        int linkDurationTicks = cfg.getInt(root + ".LINK_DURATION_TICKS", 160);
        spawnChainEffect(first, second, linkDurationTicks);

        new BukkitRunnable() {
            @Override
            public void run() {
                unlinkPlayers(first.getUniqueId(), second.getUniqueId());
            }
        }.runTaskLater(plugin, linkDurationTicks);
    }

    private void spawnChainEffect(Player first, Player second, int durationTicks) {
        new BukkitRunnable() {
            int lived = 0;

            @Override
            public void run() {
                if (lived >= durationTicks || !first.isOnline() || !second.isOnline() || first.isDead() || second.isDead()) {
                    cancel();
                    return;
                }

                Location a = first.getLocation().clone().add(0, 1.0, 0);
                Location b = second.getLocation().clone().add(0, 1.0, 0);
                Vector step = b.toVector().subtract(a.toVector()).multiply(0.1);
                Location point = a.clone();

                for (int i = 0; i < 10; i++) {
                    World world = point.getWorld();
                    world.spawnParticle(org.bukkit.Particle.PORTAL, point, 2, 0.05, 0.05, 0.05, 0.0);
                    world.spawnParticle(org.bukkit.Particle.DUST, point, 1, 0.01, 0.01, 0.01, 0.0,
                            new org.bukkit.Particle.DustOptions(Color.PURPLE, 1.2f));
                    point.add(step);
                }

                lived += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private List<Player> getNearbyEnemies(Player source, double radius, boolean requireLineOfSight) {
        List<Player> targets = new ArrayList<>();
        for (Entity entity : source.getWorld().getNearbyEntities(source.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof Player target) || target.getUniqueId().equals(source.getUniqueId())) {
                continue;
            }
            if (betterTeamsHook.isFriendly(source, target)) {
                continue;
            }
            if (requireLineOfSight && !hasClearPath(source, target)) {
                continue;
            }
            targets.add(target);
        }

        targets.sort(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(source.getLocation())));
        return targets;
    }

    private Player getClosestEnemy(Player source, double radius, boolean requireLineOfSight) {
        List<Player> enemies = getNearbyEnemies(source, radius, requireLineOfSight);
        return enemies.isEmpty() ? null : enemies.get(0);
    }

    private boolean hasClearPath(Player source, Player target) {
        if (!source.hasLineOfSight(target)) {
            return false;
        }
        var rayTrace = source.getWorld().rayTraceBlocks(source.getEyeLocation(),
                target.getEyeLocation().toVector().subtract(source.getEyeLocation().toVector()).normalize(),
                source.getEyeLocation().distance(target.getEyeLocation()));
        return rayTrace == null || rayTrace.getHitBlock() == null;
    }

    private void linkPlayers(Player first, Player second) {
        activeEnderLinks.computeIfAbsent(first.getUniqueId(), ignored -> new HashSet<>()).add(second.getUniqueId());
        activeEnderLinks.computeIfAbsent(second.getUniqueId(), ignored -> new HashSet<>()).add(first.getUniqueId());
    }

    private void removeLinks(UUID playerId) {
        Set<UUID> links = activeEnderLinks.remove(playerId);
        if (links == null) {
            return;
        }
        for (UUID linked : links) {
            Set<UUID> linkedSet = activeEnderLinks.get(linked);
            if (linkedSet != null) {
                linkedSet.remove(playerId);
                if (linkedSet.isEmpty()) {
                    activeEnderLinks.remove(linked);
                }
            }
        }
    }

    private void unlinkPlayers(UUID first, UUID second) {
        Set<UUID> firstLinks = activeEnderLinks.get(first);
        if (firstLinks != null) {
            firstLinks.remove(second);
            if (firstLinks.isEmpty()) {
                activeEnderLinks.remove(first);
            }
        }
        Set<UUID> secondLinks = activeEnderLinks.get(second);
        if (secondLinks != null) {
            secondLinks.remove(first);
            if (secondLinks.isEmpty()) {
                activeEnderLinks.remove(second);
            }
        }
    }

    private void spawnFlameDisplay(Location location, double scale) {
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setText(FLAME_GLYPHS[0]);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(true);
            entity.setShadowed(false);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTransformation(new Transformation(
                    new Vector3f(),
                    new AxisAngle4f(),
                    new Vector3f((float) scale, (float) scale, (float) scale),
                    new AxisAngle4f()
            ));
        });

        new BukkitRunnable() {
            int frame = 1;

            @Override
            public void run() {
                if (!display.isValid()) {
                    cancel();
                    return;
                }
                if (frame >= FLAME_GLYPHS.length) {
                    display.setText(" ");
                    display.remove();
                    cancel();
                    return;
                }

                display.setText(FLAME_GLYPHS[frame]);
                frame++;
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void damageNearbyPlayers(Player source, Location center, double radius, double amount) {
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof Player target) || target.getUniqueId().equals(source.getUniqueId())) {
                continue;
            }
            if (betterTeamsHook.isFriendly(source, target)) {
                continue;
            }
            applyTrueDamage(source, target, amount);
        }
    }

    private void playSoundNearby(Player source, String soundKey, float volume, float pitch, double radius) {
        for (Entity entity : source.getWorld().getNearbyEntities(source.getLocation(), radius, radius, radius)) {
            if (entity instanceof Player target) {
                target.playSound(Sound.sound(Key.key(soundKey), Sound.Source.PLAYER, volume, pitch));
            }
        }
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
        String prefix = plugin.getConfig().getString("ALTARS.MESSAGES.PREFIX", "");
        player.sendMessage(serializer.deserialize(prefix + message));
    }

    private boolean isOnCooldown(UUID uuid, Map<UUID, Long> cooldowns, long cooldownMillis) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(uuid);
        return last != null && (now - last) < cooldownMillis;
    }
}
