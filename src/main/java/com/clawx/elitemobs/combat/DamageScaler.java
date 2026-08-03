package com.clawx.elitemobs.combat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.EliteConfig;
import java.util.*;

public class DamageScaler implements Listener {
    private final EliteMobsPlugin plugin;
    private final Map<String, Location> spawns = new HashMap<>();

    public DamageScaler(EliteMobsPlugin plugin) { this.plugin = plugin; reload(); }

    public void reload() {
        spawns.clear(); for (World w : Bukkit.getWorlds()) spawns.put(w.getName(), w.getSpawnLocation().clone());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEliteDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LivingEntity d) || !EliteMobManager.isElite(d)) return;
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isDamageScalingEnabled()) return;
        double bonus = 0;
        long st = EliteMobManager.getSpawnTime(d);
        if (st > 0) {
            bonus += (System.currentTimeMillis() - st) / 60000.0 * cfg.getDamagePerMinute();
        }
        Location sl = spawns.get(d.getWorld().getName());
        if (sl != null) bonus += d.getLocation().distance(sl) * cfg.getDamagePerBlockFromSpawn();
        int np = 0;
        for (Entity e : d.getNearbyEntities(16, 16, 16)) if (e instanceof Player) np++;
        if (np > 1) bonus += Math.min(np - 1, 6) * 0.05;
        double maxBonus = cfg.getMaxDamageMultiplier() - cfg.getBaseDamageMultiplier();
        if (bonus > 0) event.setDamage(event.getDamage() * (1.0 + Math.min(bonus, Math.max(0, maxBonus))));
    }

    public double calculateMultiplier(LivingEntity e) {
        if (!EliteMobManager.isElite(e)) return 1.0;
        EliteConfig cfg = plugin.getEliteConfig();
        double m = cfg.getBaseDamageMultiplier();
        long st = EliteMobManager.getSpawnTime(e);
        if (st > 0) m += (System.currentTimeMillis() - st) / 60000.0 * cfg.getDamagePerMinute();
        Location sl = spawns.get(e.getWorld().getName());
        if (sl != null) m += e.getLocation().distance(sl) * cfg.getDamagePerBlockFromSpawn();
        int lv = EliteMobManager.getEliteLevel(e);
        if (lv > 0) m += (lv - 1) * 0.02;
        return Math.min(m, cfg.getMaxDamageMultiplier());
    }
}
