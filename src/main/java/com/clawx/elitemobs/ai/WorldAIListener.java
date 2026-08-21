package com.clawx.elitemobs.ai;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import com.clawx.elitemobs.EliteConfig;
import com.clawx.elitemobs.EliteMobsPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界 AI 增强 —— 让原版怪物对玩家行为与环境产生反应（含精英怪）。
 *
 * <ul>
 *   <li>玩家受伤 / 低血量 / 严重饥饿 → 附近怪物（含精英）索敌</li>
 *   <li>挖掘方块声音 → 附近怪物被吸引</li>
 *   <li>僵尸发现光源 → 前去探索（Pathfinder 移动）</li>
 *   <li>同类型怪物偶尔聚集</li>
 * </ul>
 */
public class WorldAIListener implements Listener {

    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();
    /** 玩家 -> 上次吸引时间（ms），用于冷却防反复拉怪。 */
    private final Map<UUID, Long> lastAttract = new ConcurrentHashMap<>();

    private static final Set<Material> LIGHT_BLOCKS = EnumSet.of(
            Material.TORCH, Material.WALL_TORCH, Material.SOUL_TORCH, Material.SOUL_WALL_TORCH,
            Material.REDSTONE_LAMP, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.LANTERN,
            Material.SOUL_LANTERN, Material.JACK_O_LANTERN, Material.GLOWSTONE, Material.SEA_LANTERN,
            Material.END_ROD, Material.SHROOMLIGHT);

    public WorldAIListener(EliteMobsPlugin plugin) { this.plugin = plugin; }

    // ==================== 事件 ====================

    /** 玩家受伤：附近怪物（含精英）索敌。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (p.isDead()) return;
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isWorldAIEnabled() || !cfg.isAttractOnHurt()) return;
        if (!tryAttract(p)) return;
        attractMobs(p, p.getLocation(), cfg.getAttractRadius());
    }

    /** 挖掘方块声音：附近怪物被吸引索敌破坏者。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        Player p = event.getPlayer();
        if (p.isDead() || !p.isOnline()) return;
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isWorldAIEnabled() || !cfg.isAttractOnBreak()) return;
        attractMobs(p, event.getBlock().getLocation(), cfg.getBreakAttractRadius());
    }

    // ==================== 周期任务 ====================

    public void startTask() {
        // 低血量 / 严重饥饿吸引（每 3 秒）
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            EliteConfig cfg = plugin.getEliteConfig();
            if (!cfg.isWorldAIEnabled()) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isDead() || !p.isOnline()) continue;
                boolean lowHp = cfg.isAttractOnLowHp()
                        && p.getHealth() < p.getMaxHealth() * cfg.getLowHpThreshold();
                boolean hungry = cfg.isAttractOnHunger()
                        && p.getFoodLevel() < cfg.getHungerThreshold();
                if ((lowHp || hungry) && tryAttract(p)) attractMobs(p, p.getLocation(), cfg.getAttractRadius());
            }
        }, 60L, 60L);

        // 僵尸探索光源（每 4 秒）
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            EliteConfig cfg = plugin.getEliteConfig();
            if (!cfg.isWorldAIEnabled() || !cfg.isZombieLightExplore()) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isDead() || !p.isOnline()) continue;
                exploreLight(p);
            }
        }, 80L, 80L);

        // 怪物聚集（每 6 秒）
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            EliteConfig cfg = plugin.getEliteConfig();
            if (!cfg.isWorldAIEnabled() || !cfg.isMobGathering()) return;
            gatherMobs();
        }, 120L, 120L);
    }

    // ==================== 逻辑 ====================

    /** 吸引冷却：冷却时间内不重复吸引，防反复拉怪。 */
    private boolean tryAttract(Player p) {
        int cd = plugin.getEliteConfig().getAttractCooldown();
        long now = System.currentTimeMillis();
        Long last = lastAttract.get(p.getUniqueId());
        if (last != null && now - last < cd * 1000L) return false;
        lastAttract.put(p.getUniqueId(), now);
        return true;
    }

    /** 让附近僵尸（含精英僵尸）索敌玩家。只吸引僵尸，且单次最多吸引 max 只（随机）。 */
    private void attractMobs(Player p, Location loc, int radius) {
        int max = plugin.getEliteConfig().getMaxAttractPerTrigger();
        double r2 = radius * radius;
        List<Mob> candidates = new ArrayList<>();
        for (Entity e : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (!isZombie(e.getType())) continue;          // 只吸引僵尸
            if (!(e instanceof Mob m)) continue;
            if (e instanceof Player) continue;
            if (e.isDead()) continue;
            if (e.getLocation().distanceSquared(loc) > r2) continue;
            if (m.getTarget() != null && m.getTarget().equals(p)) continue; // 已在追玩家
            candidates.add(m);
        }
        Collections.shuffle(candidates, rng);
        int n = Math.min(max, candidates.size());
        for (int i = 0; i < n; i++) {
            try {
                candidates.get(i).setTarget(p);
            } catch (Exception ignored) {}
        }
    }

    /** 僵尸发现玩家附近的光源，前去探索。 */
    private void exploreLight(Player p) {
        EliteConfig cfg = plugin.getEliteConfig();
        int radius = cfg.getLightSearchRadius();
        World w = p.getWorld();
        Location center = p.getLocation();
        int b = (int) Math.ceil(center.getY());
        int minY = Math.max(w.getMinHeight(), b - radius);
        int maxY = Math.min(w.getMaxHeight() - 1, b + radius);
        Location found = null;
        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Location loc = center.clone().add(dx, dy, dz);
                    if (loc.getBlockY() < minY || loc.getBlockY() > maxY) continue;
                    if (LIGHT_BLOCKS.contains(w.getBlockAt(loc).getType())) {
                        found = loc;
                        break outer;
                    }
                }
            }
        }
        if (found == null) return;
        // 光源附近 + 玩家附近的无目标僵尸走向光源
        int sr = Math.max(radius, 24);
        for (Entity e : w.getNearbyEntities(found, sr, sr, sr)) {
            if (!isZombie(e.getType())) continue;
            if (!(e instanceof Mob mob)) continue;
            if (mob.getTarget() != null) continue;   // 有目标的不打断
            try {
                mob.getPathfinder().moveTo(found, 1.0);
            } catch (Exception ignored) {}
        }
    }

    /** 同类型怪物聚集：每组 ≥3 只时，离质心远的走向质心。 */
    private void gatherMobs() {
        EliteConfig cfg = plugin.getEliteConfig();
        int radius = cfg.getGatherRadius();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || !p.isOnline()) continue;
            Map<EntityType, List<Mob>> groups = new HashMap<>();
            for (Entity e : p.getWorld().getNearbyEntities(p.getLocation(), radius, radius, radius)) {
                // 只聚集团队（僵尸类），避免末影人等大量聚集
                if (!isZombie(e.getType())) continue;
                if (e instanceof Mob mob && mob.getTarget() == null) {
                    groups.computeIfAbsent(e.getType(), k -> new ArrayList<>()).add(mob);
                }
            }
            for (List<Mob> group : groups.values()) {
                if (group.size() < 3) continue;
                double sx = 0, sy = 0, sz = 0;
                for (Mob m : group) {
                    Location l = m.getLocation();
                    sx += l.getX(); sy += l.getY(); sz += l.getZ();
                }
                Location center = new Location(p.getWorld(),
                        sx / group.size(), sy / group.size(), sz / group.size());
                for (Mob m : group) {
                    if (m.getLocation().distanceSquared(center) > 16) {
                        try {
                            m.getPathfinder().moveTo(center, 1.0);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    private boolean isZombie(EntityType t) {
        return t == EntityType.ZOMBIE || t == EntityType.HUSK
                || t == EntityType.DROWNED || t == EntityType.ZOMBIE_VILLAGER
                || t == EntityType.ZOMBIFIED_PIGLIN;
    }
}
