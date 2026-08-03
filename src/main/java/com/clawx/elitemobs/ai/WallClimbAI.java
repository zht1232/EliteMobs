package com.clawx.elitemobs.ai;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;
import java.util.*;

public class WallClimbAI implements Listener {
    private final EliteMobsPlugin plugin;
    private final Random random = new Random();

    public WallClimbAI(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        new BukkitRunnable() { public void run() {
            if (!plugin.getEliteConfig().isWallClimbEnabled()) return;
            EliteMobManager mgr = plugin.getMobManager();
            for (UUID id : mgr.getWallClimbers()) {
                Entity e = Bukkit.getEntity(id);
                if (e instanceof Mob m && !m.isDead() && m.isValid() && m.getTarget() != null) {
                    tryClimb(m);
                }
            }
        }}.runTaskTimer(plugin, 10L, 3L);
    }

    private void tryClimb(Mob mob) {
        if (!(mob.getTarget() instanceof LivingEntity target)) return;
        if (!mob.isOnGround()) return;

        Location ml = mob.getLocation();
        Location tl = target.getLocation();
        double heightDiff = tl.getY() - ml.getY();
        if (heightDiff < 1.0) return;

        boolean nearWall = isNearWall(mob);
        if (!nearWall && heightDiff < 2.5) return;

        Vector dir = tl.toVector().subtract(ml.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();

        double speed = plugin.getEliteConfig().getWallClimbSpeed();
        double boost = nearWall ? speed * 1.5 : speed;

        Vector velocity = mob.getVelocity();
        velocity.setX(dir.getX() * boost);
        velocity.setZ(dir.getZ() * boost);
        if (heightDiff > 0.5) velocity.setY(0.35);
        mob.setVelocity(velocity);

        if (nearWall && random.nextInt(5) == 0) {
            mob.getWorld().spawnParticle(Particle.BLOCK,
                ml.clone().add(0, 0.5, 0), 3, 0.3, 0.3, 0.3, 0,
                mob.getWorld().getBlockAt(ml.clone().add(dir.getX(), 0, dir.getZ())).getBlockData());
        }
    }

    private boolean isNearWall(Mob mob) {
        Location loc = mob.getLocation();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block b = loc.clone().add(dx, 0.5, dz).getBlock();
                if (b.getType().isSolid()) return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        // cleanup hook - no longer needs lastPlace tracking
    }
}
