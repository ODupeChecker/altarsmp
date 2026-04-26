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
import org.bukkit.util.RayTraceResult;
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
    private final Map<UUID, Long> leviathanSlamCooldowns = new HashMap<>();
    private final Map<UUID, Long> whirlpoolPrisonCooldowns = new HashMap<>();

    private final Map<UUID, ChainLink> activeChains = new HashMap<>();
    private final Set<UUID> chainPropagationGuard = new HashSet<>();
    private final Map<UUID, Long> whirlpoolDamageProtection = new HashMap<>();

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
        boolean poseidonsTrident = weaponManager.isPoseidonsTrident(mainHand);
        if (!cursedBlade && !enderBlade && !poseidonsTrident) {
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

        if (enderBlade) {
            if (sneaking && plugin.getConfig().getBoolean("ENDER_BLADE.ABILITIES.ENDER_CHAIN.ENABLED", true)) {
                castEnderChain(player);
            } else if (!sneaking && plugin.getConfig().getBoolean("ENDER_BLADE.ABILITIES.BLINK_STRIKE.ENABLED", true)) {
                castBlinkStrike(player);
            }
            return;
        }

        if (sneaking && plugin.getConfig().getBoolean("POSEIDONS_TRIDENT.ABILITIES.WHIRLPOOL_PRISON.ENABLED", true)) {
            castWhirlpoolPrison(player);
        } else if (!sneaking && plugin.getConfig().getBoolean("POSEIDONS_TRIDENT.ABILITIES.LEVIATHAN_SLAM.ENABLED", true)) {
            castLeviathanSlam(player);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player damaged) || event.isCancelled()) {
            return;
        }

        if (isWhirlpoolProtected(damaged.getUniqueId()) && !isAllowedWhirlpoolDamage(event)) {
            event.setCancelled(true);
            return;
        }

        ChainLink chain = activeChains.get(damaged.getUniqueId());
        if (chain == null || chainPropagationGuard.contains(damaged.getUniqueId())) {
            return;
        }

        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }

        if (!(byEntity.getDamager() instanceof Player attacker) || !isChainMeleeHit(byEntity, attacker)) {
            return;
        }

        double mirroredDamage = event.getFinalDamage();
        if (mirroredDamage <= 0.0) {
            return;
        }

        double chainMaxDamage = Math.max(0.0, plugin.getConfig().getDouble("ENDER_BLADE.ABILITIES.ENDER_CHAIN.MAX_LINK_DAMAGE", 6.0));
        mirroredDamage = Math.min(mirroredDamage, chainMaxDamage);
        if (mirroredDamage <= 0.0) {
            return;
        }

        if (wouldHitBeLethal(damaged, event.getFinalDamage())) {
            cleanupChainMembers(chain);
            return;
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

    private boolean isChainMeleeHit(EntityDamageByEntityEvent event, Player attacker) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return false;
        }

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType() == Material.AIR) {
            return true;
        }

        return weapon.getType().name().endsWith("_SWORD");
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

    private void castLeviathanSlam(Player player) {
        String root = "POSEIDONS_TRIDENT.ABILITIES.LEVIATHAN_SLAM";

        long cooldownMillis = plugin.getConfig().getLong(root + ".COOLDOWN_MILLIS", 45000L);
        if (isOnCooldown(player.getUniqueId(), leviathanSlamCooldowns, cooldownMillis)) {
            sendMessage(player, "POSEIDONS_TRIDENT", plugin.getConfig().getString("POSEIDONS_TRIDENT.MESSAGES.LEVIATHAN_SLAM_COOLDOWN", "&cLeviathan Slam is on cooldown."));
            return;
        }

        leviathanSlamCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        player.swingMainHand();
        showCooldownBar(player, plugin.getConfig().getString(root + ".ABILITY_NAME", "Leviathan Slam"), cooldownMillis, BossBar.Color.BLUE);

        double damage = plugin.getConfig().getDouble(root + ".TRUE_DAMAGE", 6.0);
        double knockUpForce = plugin.getConfig().getDouble(root + ".KNOCK_UP_FORCE", 0.75);
        int stunTicks = plugin.getConfig().getInt(root + ".STUN_TICKS", 30);
        double maxRadius = plugin.getConfig().getDouble(root + ".SHOCKWAVE_RADIUS", 8.0);
        int waveTicks = plugin.getConfig().getInt(root + ".WAVE_TICKS", 14);

        Location groundLoc = player.getLocation();

        spawnLeviathanCharge(groundLoc.clone());
        groundLoc.getWorld().spawnParticle(Particle.SPLASH, groundLoc.clone().add(0, 0.1, 0), 60, 1.0, 0.2, 1.0, 0.3);
        groundLoc.getWorld().spawnParticle(Particle.DUST, groundLoc.clone().add(0, 0.2, 0), 20, 0.5, 0.2, 0.5,
                new Particle.DustOptions(Color.fromRGB(0, 198, 217), 2.0f));
        playSoundInRadius(player, plugin.getConfig().getString(root + ".SOUND", "minecraft:entity.generic.splash"), 1.0f, 0.5f,
                plugin.getConfig().getDouble("POSEIDONS_TRIDENT.SOUND_BROADCAST_RADIUS", 20.0));
        playSoundInRadius(player, plugin.getConfig().getString(root + ".SOUND_SECONDARY", "minecraft:entity.elder_guardian.hurt"), 0.9f, 0.8f,
                plugin.getConfig().getDouble("POSEIDONS_TRIDENT.SOUND_BROADCAST_RADIUS", 20.0));

        Set<UUID> alreadyHit = new HashSet<>();

        new BukkitRunnable() {
            int tick = 1;

            @Override
            public void run() {
                if (!player.isOnline() || tick > waveTicks) {
                    cancel();
                    return;
                }

                double radius = (tick / (double) waveTicks) * maxRadius;
                spawnWaterShockwaveRing(groundLoc, radius);
                spawnLeviathanExpansionFill(groundLoc, radius, maxRadius, tick, waveTicks);

                double outerBand = radius + (maxRadius / waveTicks) + 0.3;
                for (Entity entity : groundLoc.getWorld().getNearbyEntities(groundLoc, outerBand, 2.5, outerBand)) {
                    if (!(entity instanceof Player target) || !isEnemyTarget(player, target)) continue;
                    if (alreadyHit.contains(target.getUniqueId())) continue;
                    double dist = target.getLocation().distance(groundLoc);
                    if (dist > outerBand) continue;
                    alreadyHit.add(target.getUniqueId());
                    applyTrueDamage(player, target, damage);
                    target.setVelocity(target.getVelocity().setY(knockUpForce));
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, stunTicks, 10, false, false));
                }

                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void castWhirlpoolPrison(Player player) {
        String root = "POSEIDONS_TRIDENT.ABILITIES.WHIRLPOOL_PRISON";

        long cooldownMillis = plugin.getConfig().getLong(root + ".COOLDOWN_MILLIS", 60000L);
        if (isOnCooldown(player.getUniqueId(), whirlpoolPrisonCooldowns, cooldownMillis)) {
            sendMessage(player, "POSEIDONS_TRIDENT", plugin.getConfig().getString("POSEIDONS_TRIDENT.MESSAGES.WHIRLPOOL_PRISON_COOLDOWN", "&cWhirlpool Prison is on cooldown."));
            return;
        }

        whirlpoolPrisonCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        player.swingMainHand();
        showCooldownBar(player, plugin.getConfig().getString(root + ".ABILITY_NAME", "Whirlpool Prison"), cooldownMillis, BossBar.Color.BLUE);

        double placementRange = plugin.getConfig().getDouble(root + ".PLACEMENT_RANGE", 15.0);
        double pullRadius = plugin.getConfig().getDouble(root + ".PULL_RADIUS", 6.0);
        double pullForce = plugin.getConfig().getDouble(root + ".PULL_FORCE", 0.35);
        double trueDamagePerTick = plugin.getConfig().getDouble(root + ".TRUE_DAMAGE_PER_TICK", 0.6);
        int durationTicks = plugin.getConfig().getInt(root + ".DURATION_TICKS", 100);
        double burstDamage = plugin.getConfig().getDouble(root + ".BURST_DAMAGE", 9.0);
        double burstKnockback = plugin.getConfig().getDouble(root + ".BURST_KNOCKBACK", 1.4);

        RayTraceResult ray = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                placementRange
        );

        final Location vortexCenter;
        if (ray != null && ray.getHitBlock() != null) {
            vortexCenter = ray.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
        } else {
            vortexCenter = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(placementRange));
        }

        playSoundInRadius(player, plugin.getConfig().getString(root + ".SOUND", "minecraft:entity.elder_guardian.ambient"), 1.0f, 0.65f,
                plugin.getConfig().getDouble("POSEIDONS_TRIDENT.SOUND_BROADCAST_RADIUS", 20.0));
        vortexCenter.getWorld().spawnParticle(Particle.BUBBLE_POP, vortexCenter.clone().add(0, 0.2, 0), 40, 0.8, 0.2, 0.8, 0.08);
        vortexCenter.getWorld().spawnParticle(Particle.DUST, vortexCenter.clone().add(0, 0.4, 0), 24, 0.6, 0.2, 0.6,
                new Particle.DustOptions(Color.fromRGB(73, 214, 255), 1.8f));

        new BukkitRunnable() {
            int tick = 0;
            double spinAngle = 0;

            @Override
            public void run() {
                if (!player.isOnline() || tick >= durationTicks) {
                    vortexCenter.getWorld().spawnParticle(Particle.EXPLOSION, vortexCenter.clone().add(0, 1, 0), 3, 0.5, 0.5, 0.5, 0.1);
                    vortexCenter.getWorld().spawnParticle(Particle.SPLASH, vortexCenter.clone().add(0, 1, 0), 120, 2.5, 1.0, 2.5, 0.6);
                    vortexCenter.getWorld().spawnParticle(Particle.DUST, vortexCenter.clone().add(0, 1, 0), 40, 2.0, 1.0, 2.0,
                            new Particle.DustOptions(Color.fromRGB(0, 198, 217), 2.5f));
                    playSoundInRadius(player, "minecraft:entity.generic.explode", 1.0f, 0.55f,
                            plugin.getConfig().getDouble("POSEIDONS_TRIDENT.SOUND_BROADCAST_RADIUS", 20.0));

                    for (Entity entity : vortexCenter.getWorld().getNearbyEntities(vortexCenter, pullRadius, pullRadius, pullRadius)) {
                        if (!(entity instanceof Player target) || !isEnemyTarget(player, target)) continue;
                        applyTrueDamage(player, target, burstDamage);
                        Vector away = target.getLocation().toVector().subtract(vortexCenter.toVector()).setY(0.35).normalize().multiply(burstKnockback);
                        target.setVelocity(away);
                    }

                    cancel();
                    return;
                }

                spawnVortexParticles(vortexCenter, pullRadius, spinAngle);
                spawnWhirlpoolCore(vortexCenter, pullRadius, spinAngle, tick, durationTicks);
                spinAngle += 0.28;

                if (tick % 2 == 0) {
                    for (Entity entity : vortexCenter.getWorld().getNearbyEntities(vortexCenter, pullRadius, pullRadius, pullRadius)) {
                        if (!(entity instanceof Player target) || !isEnemyTarget(player, target)) continue;
                        Vector toCenter = vortexCenter.toVector().subtract(target.getLocation().toVector()).setY(0).normalize().multiply(pullForce);
                        target.setVelocity(target.getVelocity().add(toCenter));
                        markWhirlpoolProtected(target);
                        applyTrueDamage(player, target, trueDamagePerTick);
                    }
                }

                tick += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void spawnWaterShockwaveRing(Location center, double radius) {
        int points = Math.max(18, (int) (radius * 10));
        for (int i = 0; i < points; i++) {
            double angle = (2 * Math.PI * i) / points;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location point = center.clone().add(x, 0.1, z);
            center.getWorld().spawnParticle(Particle.SPLASH, point, 2, 0.08, 0.08, 0.08, 0.15);
            center.getWorld().spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0,
                    new Particle.DustOptions(Color.fromRGB(0, 198, 217), 1.6f));
        }
    }

    private void spawnLeviathanCharge(Location center) {
        for (int i = 0; i < 14; i++) {
            double angle = (2 * Math.PI * i) / 14;
            Location point = center.clone().add(Math.cos(angle) * 0.95, 0.18, Math.sin(angle) * 0.95);
            center.getWorld().spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0,
                    new Particle.DustOptions(Color.fromRGB(30, 168, 255), 1.6f));
            center.getWorld().spawnParticle(Particle.BUBBLE_POP, point, 1, 0.03, 0.03, 0.03, 0.02);
        }
    }

    private void spawnLeviathanExpansionFill(Location center, double radius, double maxRadius, int tick, int waveTicks) {
        if (radius <= 0.25) {
            return;
        }

        int fillPoints = Math.max(14, (int) (radius * 16));
        for (int i = 0; i < fillPoints; i++) {
            double angle = ((2 * Math.PI) / fillPoints) * i + (tick * 0.09);
            double normalized = i / (double) fillPoints;
            double ringRadius = radius * (0.2 + 0.8 * normalized);
            Location point = center.clone().add(Math.cos(angle) * ringRadius, 0.12, Math.sin(angle) * ringRadius);
            center.getWorld().spawnParticle(Particle.DUST, point, 1, 0.02, 0.03, 0.02,
                    new Particle.DustOptions(Color.fromRGB(96, 224, 255), 1.1f));
            if ((i + tick) % 4 == 0) {
                center.getWorld().spawnParticle(Particle.SPLASH, point, 1, 0.06, 0.03, 0.06, 0.08);
            }
        }

        if (tick % 2 == 0) {
            double centerPulse = Math.max(0.6, 1.5 - (radius / Math.max(0.1, maxRadius)));
            center.getWorld().spawnParticle(Particle.DUST, center.clone().add(0, 0.2, 0), 3, centerPulse, 0.02, centerPulse,
                    new Particle.DustOptions(Color.fromRGB(0, 170, 236), 1.4f));
        }

        int crestColumns = 4;
        for (int i = 0; i < crestColumns; i++) {
            double columnAngle = ((2 * Math.PI) / crestColumns) * i + (tick * 0.18);
            Location crest = center.clone().add(Math.cos(columnAngle) * radius, 0.25, Math.sin(columnAngle) * radius);
            center.getWorld().spawnParticle(Particle.BUBBLE_POP, crest, 1, 0.04, 0.07, 0.04, 0.03);
        }
    }

    private void spawnVortexParticles(Location center, double radius, double baseAngle) {
        int spiralPoints = 24;
        double height = 3.5;
        for (int i = 0; i < spiralPoints; i++) {
            double t = (double) i / spiralPoints;
            double r = radius * (1.0 - t * 0.45);
            double a = baseAngle + t * Math.PI * 5;
            double y = t * height;
            Location point = center.clone().add(Math.cos(a) * r, y, Math.sin(a) * r);
            center.getWorld().spawnParticle(Particle.SPLASH, point, 1, 0.04, 0.04, 0.04, 0.06);
            center.getWorld().spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0,
                    new Particle.DustOptions(Color.fromRGB(0, 198, 217), 1.3f));
        }
        int ringPoints = 18;
        for (int i = 0; i < ringPoints; i++) {
            double a = baseAngle + (2 * Math.PI * i) / ringPoints;
            Location point = center.clone().add(Math.cos(a) * radius, 0.1, Math.sin(a) * radius);
            center.getWorld().spawnParticle(Particle.SPLASH, point, 2, 0.05, 0.05, 0.05, 0.1);
        }
    }

    private void spawnWhirlpoolCore(Location center, double radius, double baseAngle, int tick, int durationTicks) {
        double progress = Math.min(1.0, tick / (double) Math.max(1, durationTicks));
        double funnelRadius = Math.max(0.6, radius * (0.45 - (progress * 0.2)));

        for (int layer = 0; layer < 6; layer++) {
            double y = 0.15 + (layer * 0.4);
            double swirlAngle = baseAngle + layer * 0.65;
            Location swirlPoint = center.clone().add(Math.cos(swirlAngle) * funnelRadius, y, Math.sin(swirlAngle) * funnelRadius);
            center.getWorld().spawnParticle(Particle.DUST, swirlPoint, 1, 0.0, 0.0, 0.0,
                    new Particle.DustOptions(Color.fromRGB(81, 226, 255), 1.2f));
            if ((layer + tick) % 2 == 0) {
                center.getWorld().spawnParticle(Particle.BUBBLE_POP, swirlPoint, 1, 0.02, 0.04, 0.02, 0.02);
            }
        }

        if (tick % 4 == 0) {
            center.getWorld().spawnParticle(Particle.DUST, center.clone().add(0, 0.6, 0), 8, 0.25, 0.35, 0.25,
                    new Particle.DustOptions(Color.fromRGB(0, 164, 229), 1.5f));
        }
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

    private void cleanupChainMembers(ChainLink link) {
        if (link == null || link.members().size() < 2) {
            return;
        }
        List<UUID> members = new ArrayList<>(link.members());
        cleanupChain(members.get(0), members.get(1));
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

    private boolean wouldHitBeLethal(Player target, double incomingDamage) {
        double effectiveHealth = target.getHealth() + Math.max(0.0, target.getAbsorptionAmount());
        return incomingDamage >= effectiveHealth;
    }

    private void markWhirlpoolProtected(Player target) {
        long graceMillis = Math.max(50L, plugin.getConfig().getLong("POSEIDONS_TRIDENT.ABILITIES.WHIRLPOOL_PRISON.EXTERNAL_DAMAGE_PROTECTION_MILLIS", 250L));
        whirlpoolDamageProtection.put(target.getUniqueId(), System.currentTimeMillis() + graceMillis);
    }

    private boolean isWhirlpoolProtected(UUID playerId) {
        Long until = whirlpoolDamageProtection.get(playerId);
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            whirlpoolDamageProtection.remove(playerId);
            return false;
        }
        return true;
    }

    private boolean isAllowedWhirlpoolDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        return cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.CUSTOM;
    }

    private record ChainLink(UUID caster, Set<UUID> members) {
    }
}
