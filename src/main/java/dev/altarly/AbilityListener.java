package dev.altarly;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.EntityEffect;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AbilityListener implements Listener {
    private static final String[] FLAME_GLYPHS = {"⛿", "⛾", "⛽", "⛼", "⛻", "⛺", "⛹", "⛸", "🁉", "🁊"};

    private final JavaPlugin plugin;
    private final WeaponManager weaponManager;
    private final TeamIntegration teamIntegration;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, Long> slashCooldowns = new HashMap<>();
    private final Map<UUID, Long> ruinstepCooldowns = new HashMap<>();
    private final Map<UUID, Long> blinkStrikeCooldowns = new HashMap<>();
    private final Map<UUID, Long> enderChainCooldowns = new HashMap<>();

    private final Map<UUID, ChainLink> activeChains = new HashMap<>();
    private final Set<UUID> chainPropagationGuard = new HashSet<>();

    public AbilityListener(JavaPlugin plugin, WeaponManager weaponManager, TeamIntegration teamIntegration) {
        this.plugin = plugin;
        this.weaponManager = weaponManager;
        this.teamIntegration = teamIntegration;
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();

        boolean cursedBlade = weaponManager.isCursedBlade(mainHand);
        boolean enderBlade = weaponManager.isEnderBlade(mainHand);
        if (!cursedBlade && !enderBlade) {
            return;
        }

        event.setCancelled(true);
        boolean sneaking = player.isSneaking();

        if (cursedBlade) {
            if (sneaking && plugin.getConfig().getBoolean("CURSED_BLADE.ABILITIES.RUINSTEP.ENABLED", true)) {
                castRuinstep(player);
            } else if (!sneaking && plugin.getConfig().getBoolean("CURSED_BLADE.ABILITIES.RUINED_SLASH.ENABLED", true)) {
                castRuinedSlash(player);
            }
            return;
        }

        if (sneaking && plugin.getConfig().getBoolean("ENDER_BLADE.ABILITIES.ENDER_CHAIN.ENABLED", true)) {
            castEnderChain(player);
        } else if (!sneaking && plugin.getConfig().getBoolean("ENDER_BLADE.ABILITIES.BLINK_STRIKE.ENABLED", true)) {
            castBlinkStrike(player);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player damaged) || event.isCancelled()) {
            return;
        }

        ChainLink chain = activeChains.get(damaged.getUniqueId());
        if (chain == null || chainPropagationGuard.contains(damaged.getUniqueId())) {
            return;
        }

        double mirroredDamage = event.getFinalDamage();
        if (mirroredDamage <= 0.0) {
            return;
        }

        Player attacker = null;
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player playerDamager) {
            attacker = playerDamager;
        }

        for (UUID linkedId : chain.members) {
            if (linkedId.equals(damaged.getUniqueId())) {
                continue;
            }
            Player linkedPlayer = Bukkit.getPlayer(linkedId);
            if (linkedPlayer == null || !linkedPlayer.isOnline() || linkedPlayer.isDead()) {
                continue;
            }
            chainPropagationGuard.add(linkedId);
            try {
                applyTrueDamage(attacker, linkedPlayer, mirroredDamage);
            } finally {
                chainPropagationGuard.remove(linkedId);
            }
        }
    }

    private void castRuinedSlash(Player player) {
        String root = "CURSED_BLADE.ABILITIES.RUINED_SLASH";

        long cooldownMillis = plugin.getConfig().getLong(root + ".COOLDOWN_MILLIS", 5500L);
        if (isOnCooldown(player.getUniqueId(), slashCooldowns, cooldownMillis)) {
            sendMessage(player, "CURSED_BLADE", plugin.getConfig().getString("CURSED_BLADE.MESSAGES.SLASH_COOLDOWN", "&cRuined Slash is on cooldown."));
            return;
        }

        slashCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        player.swingMainHand();
        showCooldownBar(player, plugin.getConfig().getString(root + ".ABILITY_NAME", "Ruined Slash"), cooldownMillis, BossBar.Color.PURPLE);

        int totalBlocks = plugin.getConfig().getInt(root + ".DISTANCE_BLOCKS", 7);
        double damage = plugin.getConfig().getDouble(root + ".TRUE_DAMAGE", 5.0);
        double hitRadius = plugin.getConfig().getDouble(root + ".HIT_RADIUS", 0.65);
        int startOffset = plugin.getConfig().getInt(root + ".START_OFFSET_BLOCKS", 1);
        String worldName = plugin.getConfig().getString(root + ".MM_WORLD", player.getWorld().getName());
        String mobId = plugin.getConfig().getString(root + ".MM_MOB", "TOWER_SKELETON_SLASH_FX:1");

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

        playSoundInRadius(player, plugin.getConfig().getString(root + ".SOUND", "littleroom_towerskeleton:sword_hit"), 1.0f, 1.15f,
                plugin.getConfig().getDouble("CURSED_BLADE.SOUND_BROADCAST_RADIUS", 15.0));
        playSoundInRadius(player, plugin.getConfig().getString(root + ".SOUND_SECONDARY", "minecraft:entity.player.attack.sweep"), 0.85f, 1.2f,
                plugin.getConfig().getDouble("CURSED_BLADE.SOUND_BROADCAST_RADIUS", 15.0));
    }

    private void castRuinstep(Player player) {
        String root = "CURSED_BLADE.ABILITIES.RUINSTEP";

        long cooldownMillis = plugin.getConfig().getLong(root + ".COOLDOWN_MILLIS", 12000L);
        if (isOnCooldown(player.getUniqueId(), ruinstepCooldowns, cooldownMillis)) {
            sendMessage(player, "CURSED_BLADE", plugin.getConfig().getString("CURSED_BLADE.MESSAGES.RUINSTEP_COOLDOWN", "&cRuinstep is on cooldown."));
            return;
        }

        ruinstepCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        player.swingMainHand();
        showCooldownBar(player, plugin.getConfig().getString(root + ".ABILITY_NAME", "Ruinstep"), cooldownMillis, BossBar.Color.RED);

        double dashSpeed = plugin.getConfig().getDouble(root + ".DASH_SPEED", 1.85);
        double liftVelocity = plugin.getConfig().getDouble(root + ".LIFT_VELOCITY", 0.42);
        double flameOffset = Math.max(0.4, plugin.getConfig().getDouble(root + ".FLAME_OFFSET_BLOCKS", 0.9));
        double flameHeight = plugin.getConfig().getDouble(root + ".FLAME_HEIGHT_OFFSET", 0.1);
        double displayScale = Math.max(0.1, plugin.getConfig().getDouble(root + ".FLAME_SCALE", 4.0));
        int minAirTicks = Math.max(2, plugin.getConfig().getInt(root + ".MIN_AIR_TICKS", 5));
        double hitRadius = plugin.getConfig().getDouble(root + ".HIT_RADIUS", 0.75);
        double trueDamage = plugin.getConfig().getDouble(root + ".TRUE_DAMAGE", 3.0);

        Location start = player.getLocation();
        Vector horizontalDirection = start.getDirection().setY(0).normalize();
        if (horizontalDirection.lengthSquared() == 0) {
            horizontalDirection = new Vector(0, 0, 1);
        }
        final Vector dashDirection = horizontalDirection.clone();

        Vector dashVelocity = dashDirection.clone().multiply(dashSpeed).setY(liftVelocity);
        player.setVelocity(dashVelocity);
        playSoundInRadius(player, plugin.getConfig().getString(root + ".DASH_SOUND", "minecraft:entity.blaze.shoot"), 1.0f, 0.95f,
                plugin.getConfig().getDouble("CURSED_BLADE.SOUND_BROADCAST_RADIUS", 15.0));
        playSoundInRadius(player, plugin.getConfig().getString(root + ".DASH_SOUND_SECONDARY", "minecraft:entity.player.attack.knockback"), 0.9f, 1.1f,
                plugin.getConfig().getDouble("CURSED_BLADE.SOUND_BROADCAST_RADIUS", 15.0));

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

                Vector behind = currentDirection.clone().multiply(-1.0);
                Location spawn = current.clone().add(behind.multiply(flameOffset)).add(0, flameHeight, 0);
                spawnFlameDisplay(spawn, displayScale);
                damageNearbyPlayers(player, spawn.clone().add(0, 0.8, 0), hitRadius, trueDamage);

                ticksLived++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void castBlinkStrike(Player caster) {
        String root = "ENDER_BLADE.ABILITIES.BLINK_STRIKE";

        long cooldownMillis = plugin.getConfig().getLong(root + ".COOLDOWN_MILLIS", 7000L);
        if (isOnCooldown(caster.getUniqueId(), blinkStrikeCooldowns, cooldownMillis)) {
            sendMessage(caster, "ENDER_BLADE", plugin.getConfig().getString("ENDER_BLADE.MESSAGES.BLINK_STRIKE_COOLDOWN", "&cBlink Strike is on cooldown."));
            return;
        }

        double range = plugin.getConfig().getDouble(root + ".RANGE_BLOCKS", 7.0);
        Player target = findClosestEnemy(caster, range);
        if (target == null || !caster.hasLineOfSight(target)) {
            sendMessage(caster, "ENDER_BLADE", plugin.getConfig().getString("ENDER_BLADE.MESSAGES.NO_ENDER_TARGET", "&cNo Ender Target Found"));
            return;
        }

        blinkStrikeCooldowns.put(caster.getUniqueId(), System.currentTimeMillis());
        caster.swingMainHand();
        showCooldownBar(caster, plugin.getConfig().getString(root + ".ABILITY_NAME", "Blink Strike"), cooldownMillis, BossBar.Color.PURPLE);

        double behindDistance = plugin.getConfig().getDouble(root + ".BEHIND_DISTANCE", 1.2);
        Vector backwards = target.getLocation().getDirection().setY(0).normalize().multiply(-behindDistance);
        if (backwards.lengthSquared() == 0) {
            backwards = new Vector(0, 0, -behindDistance);
        }
        Location teleportLocation = target.getLocation().clone().add(backwards);
        teleportLocation.setDirection(target.getLocation().getDirection());
        caster.teleport(teleportLocation);

        double trueDamage = plugin.getConfig().getDouble(root + ".TRUE_DAMAGE", 5.0);
        applyTrueDamage(caster, target, trueDamage);

        playSoundInRadius(caster, plugin.getConfig().getString(root + ".SOUND", "minecraft:entity.enderman.death"), 1.0f, 1.0f,
                plugin.getConfig().getDouble(root + ".SOUND_RADIUS", 10.0));
        Location fx = target.getLocation().clone().add(0, 1.0, 0);
        fx.getWorld().spawnParticle(Particle.PORTAL, fx, 30, 0.35, 0.45, 0.35, 0.1);
        fx.getWorld().spawnParticle(Particle.DRAGON_BREATH, fx, 14, 0.25, 0.35, 0.25, 0.01);
    }

    private void castEnderChain(Player caster) {
        String root = "ENDER_BLADE.ABILITIES.ENDER_CHAIN";

        long cooldownMillis = plugin.getConfig().getLong(root + ".COOLDOWN_MILLIS", 16000L);
        if (isOnCooldown(caster.getUniqueId(), enderChainCooldowns, cooldownMillis)) {
            sendMessage(caster, "ENDER_BLADE", plugin.getConfig().getString("ENDER_BLADE.MESSAGES.ENDER_CHAIN_COOLDOWN", "&cEnder Chain is on cooldown."));
            return;
        }

        double range = plugin.getConfig().getDouble(root + ".RADIUS_BLOCKS", 20.0);
        List<Player> targets = findEnemyTargets(caster, range, 2);
        if (targets.size() < 2) {
            sendMessage(caster, "ENDER_BLADE", plugin.getConfig().getString("ENDER_BLADE.MESSAGES.NO_CHAIN_TARGETS", "&cNO Enemies Found"));
            return;
        }

        enderChainCooldowns.put(caster.getUniqueId(), System.currentTimeMillis());
        caster.swingMainHand();
        showCooldownBar(caster, plugin.getConfig().getString(root + ".ABILITY_NAME", "Ender Chain"), cooldownMillis, BossBar.Color.PURPLE);

        Player first = targets.get(0);
        Player second = targets.get(1);

        first.setGlowing(true);
        second.setGlowing(true);

        ChainLink link = new ChainLink(caster.getUniqueId(), Set.of(first.getUniqueId(), second.getUniqueId()));
        activeChains.put(first.getUniqueId(), link);
        activeChains.put(second.getUniqueId(), link);

        playSoundInRadius(caster, plugin.getConfig().getString(root + ".SOUND", "minecraft:entity.enderman.death"), 0.95f, 0.8f,
                plugin.getConfig().getDouble(root + ".SOUND_RADIUS", 10.0));

        int ticks = Math.max(20, plugin.getConfig().getInt(root + ".DURATION_TICKS", 140));
        BukkitTask particleTask = new BukkitRunnable() {
            int lived = 0;

            @Override
            public void run() {
                Player a = Bukkit.getPlayer(first.getUniqueId());
                Player b = Bukkit.getPlayer(second.getUniqueId());
                if (a == null || b == null || !a.isOnline() || !b.isOnline() || a.isDead() || b.isDead() || lived >= ticks) {
                    cleanupChain(first.getUniqueId(), second.getUniqueId());
                    cancel();
                    return;
                }

                spawnChainParticles(a.getLocation().add(0, 1.1, 0), b.getLocation().add(0, 1.1, 0));
                lived += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);

        new BukkitRunnable() {
            @Override
            public void run() {
                particleTask.cancel();
                cleanupChain(first.getUniqueId(), second.getUniqueId());
            }
        }.runTaskLater(plugin, ticks);
    }

    private void cleanupChain(UUID first, UUID second) {
        activeChains.remove(first);
        activeChains.remove(second);
        Player a = Bukkit.getPlayer(first);
        Player b = Bukkit.getPlayer(second);
        if (a != null && a.isOnline() && !isPlayerInAnyChain(a.getUniqueId())) {
            a.setGlowing(false);
        }
        if (b != null && b.isOnline() && !isPlayerInAnyChain(b.getUniqueId())) {
            b.setGlowing(false);
        }
    }

    private boolean isPlayerInAnyChain(UUID playerId) {
        return activeChains.containsKey(playerId);
    }

    private void spawnChainParticles(Location start, Location end) {
        Vector line = end.toVector().subtract(start.toVector());
        int points = Math.max(8, (int) (line.length() * 4));
        Vector step = line.multiply(1.0 / points);

        Location point = start.clone();
        for (int i = 0; i <= points; i++) {
            point.getWorld().spawnParticle(Particle.PORTAL, point, 2, 0.03, 0.03, 0.03, 0.02);
            point.getWorld().spawnParticle(Particle.DUST, point, 1, 0.01, 0.01, 0.01,
                    new Particle.DustOptions(Color.fromRGB(170, 60, 255), 1.2f));
            point.add(step);
        }
    }

    private Player findClosestEnemy(Player caster, double range) {
        Player closest = null;
        double closestDistanceSq = range * range;

        for (Player other : caster.getWorld().getPlayers()) {
            if (!isEnemyTarget(caster, other)) {
                continue;
            }
            double distanceSq = caster.getLocation().distanceSquared(other.getLocation());
            if (distanceSq <= closestDistanceSq) {
                closestDistanceSq = distanceSq;
                closest = other;
            }
        }
        return closest;
    }

    private List<Player> findEnemyTargets(Player caster, double range, int maxTargets) {
        double rangeSquared = range * range;
        List<Player> candidates = new ArrayList<>();

        for (Player other : caster.getWorld().getPlayers()) {
            if (!isEnemyTarget(caster, other)) {
                continue;
            }
            if (caster.getLocation().distanceSquared(other.getLocation()) <= rangeSquared) {
                candidates.add(other);
            }
        }

        candidates.sort((a, b) -> Double.compare(
                caster.getLocation().distanceSquared(a.getLocation()),
                caster.getLocation().distanceSquared(b.getLocation())
        ));

        if (candidates.size() <= maxTargets) {
            return candidates;
        }
        return candidates.subList(0, maxTargets);
    }

    private boolean isEnemyTarget(Player caster, Player target) {
        return target != null
                && target.isOnline()
                && !target.isDead()
                && !target.getUniqueId().equals(caster.getUniqueId())
                && !teamIntegration.isSameTeam(caster, target);
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
            if (!(entity instanceof Player target) || !isEnemyTarget(source, target)) {
                continue;
            }
            applyTrueDamage(source, target, amount);
        }
    }

    private void dispatchMythicSpawn(String worldName, String mobId, int x, int y, int z, float yaw, float pitch) {
        String command = "mm mobs spawn " + mobId + " 1 " + worldName + "," + x + "," + y + "," + z + "," + yaw + "," + pitch;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void playSoundInRadius(Player source, String soundKey, float volume, float pitch, double radius) {
        double radiusSquared = radius * radius;
        Location center = source.getLocation();
        Sound sound = Sound.sound(Key.key(soundKey), Sound.Source.PLAYER, volume, pitch);

        for (Player nearby : source.getWorld().getPlayers()) {
            if (nearby.getLocation().distanceSquared(center) <= radiusSquared) {
                nearby.playSound(sound);
            }
        }
    }

    private void applyTrueDamage(Player source, LivingEntity target, double amount) {
        applyTrueDamage(target, amount);
    }

    private void applyTrueDamage(LivingEntity target, double amount) {
        if (amount <= 0.0 || target.isDead()) {
            return;
        }

        target.setNoDamageTicks(0);
        if (target instanceof Player playerTarget) {
            applyPlayerTrueDamage(playerTarget, amount);
            return;
        }

        double remaining = amount;
        double health = target.getHealth();
        if (remaining >= health) {
            target.setHealth(0.0);
            return;
        }

        target.setHealth(health - remaining);
    }

    private void applyTrueDamage(Entity source, LivingEntity target, double amount) {
        if (source instanceof Player playerSource) {
            applyTrueDamage(playerSource, target, amount);
            return;
        }
        applyTrueDamage(target, amount);
    }

    private void applyPlayerTrueDamage(Player player, double amount) {
        double remaining = amount;

        double absorption = player.getAbsorptionAmount();
        if (absorption > 0.0) {
            double absorbed = Math.min(absorption, remaining);
            player.setAbsorptionAmount(absorption - absorbed);
            remaining -= absorbed;
        }

        if (remaining <= 0.0) {
            return;
        }

        double health = player.getHealth();
        if (remaining >= health) {
            if (tryPopTotem(player)) {
                return;
            }
            player.setHealth(0.0);
            return;
        }

        player.setHealth(health - remaining);
    }

    private boolean tryPopTotem(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (consumeTotem(inventory, true) || consumeTotem(inventory, false)) {
            player.setHealth(1.0);
            player.setFireTicks(0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
            player.playEffect(EntityEffect.TOTEM_RESURRECT);
            return true;
        }
        return false;
    }

    private boolean consumeTotem(PlayerInventory inventory, boolean offHandFirst) {
        ItemStack stack = offHandFirst ? inventory.getItemInOffHand() : inventory.getItemInMainHand();
        if (stack == null || stack.getType() != Material.TOTEM_OF_UNDYING) {
            return false;
        }

        int amount = stack.getAmount();
        if (amount <= 1) {
            if (offHandFirst) {
                inventory.setItemInOffHand(null);
            } else {
                inventory.setItemInMainHand(null);
            }
            return true;
        }

        stack.setAmount(amount - 1);
        if (offHandFirst) {
            inventory.setItemInOffHand(stack);
        } else {
            inventory.setItemInMainHand(stack);
        }
        return true;
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

    private void sendMessage(Player player, String weaponRoot, String message) {
        String prefix = plugin.getConfig().getString(weaponRoot + ".MESSAGES.PREFIX", "");
        player.sendMessage(serializer.deserialize(prefix + message));
    }

    private boolean isOnCooldown(UUID uuid, Map<UUID, Long> cooldowns, long cooldownMillis) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(uuid);
        return last != null && (now - last) < cooldownMillis;
    }

    private record ChainLink(UUID caster, Set<UUID> members) {
    }
}
