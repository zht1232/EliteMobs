package com.clawx.elitemobs.spawn;

import com.clawx.elitemobs.EliteConfig;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.compat.ProtectionHook;
import com.clawx.elitemobs.db.ElitePersistence;
import com.clawx.elitemobs.db.EliteRecord;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Boss 直接布署器（替代"玩家附近精英晋升"）。
 *
 * <p>流程：每隔 interval-seconds 秒，若在线 Boss 数（含潜伏）不足上限，
 * 就在某在线玩家周围随机方向/距离找一个安全点，把 Boss 数据写入 SQLite（潜伏状态），
 * 并全服广播坐标。玩家接近物化距离时由 ElitePersistence 物化实体——远距离/未加载
 * 区块不再有实体，杜绝"Boss 凭空消失"。</p>
 */
public class BossSpawner {
    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();

    public BossSpawner(EliteMobsPlugin plugin) {
        this.plugin = plugin;
    }

    /** 启动布署任务（在插件 onEnable 中调用）。 */
    public void start() {
        EliteConfig cfg = plugin.getEliteConfig();
        long interval = Math.max(120L, (long) cfg.getBossSpawnIntervalSeconds() * 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tryPlanBoss, interval, interval);
    }

    /** 尝试布署一个 Boss：数量不足 + 有在线玩家时，在世界远端随机选点入库。 */
    public void tryPlanBoss() {
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isEnabled() || !cfg.isBossSpawnEnabled()) return;
        ElitePersistence pers = plugin.getPersistence();
        if (pers == null || !pers.isEnabled()) return; // 持久化禁用时无法布署（/em boss 仍可用）
        if (pers.countBosses() >= cfg.getBossSpawnMaxConcurrent()) return;

        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || p.isDead() || !p.isValid()) continue;
            if (!cfg.isWorldEnabled(p.getWorld().getName())) continue;
            candidates.add(p);
        }
        if (candidates.isEmpty()) return;

        Player base = candidates.get(rng.nextInt(candidates.size()));
        World w = base.getWorld();
        double minD = cfg.getBossSpawnMinDistance();
        double maxD = cfg.getBossSpawnMaxDistance();
        Location pl = base.getLocation();

        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = minD + rng.nextDouble() * (maxD - minD);
            int tx = (int) Math.floor(pl.getX() + Math.cos(angle) * dist);
            int tz = (int) Math.floor(pl.getZ() + Math.sin(angle) * dist);
            try {
                if (!w.getWorldBorder().isInside(new Location(w, tx, 64, tz))) continue;
            } catch (Exception ignored) {}
            // 优先选已生成的区块，避免同步生成大量新区块造成卡顿
            if (!w.isChunkGenerated(tx >> 4, tz >> 4)) continue;
            Location spot = EliteSpawnHandler.findSafeSpot(w, tx, tz);
            if (spot == null) continue;
            if (ProtectionHook.isProtected(spot)) continue; // 不布署在玩家领地内
            planBossAt(w, spot, cfg);
            return;
        }
    }

    private void planBossAt(World w, Location spot, EliteConfig cfg) {
        int minLv = Math.max(1, cfg.getBossSpawnMinLevel());
        int maxLv = Math.max(minLv, cfg.getBossSpawnMaxLevel());
        int level = minLv + rng.nextInt(maxLv - minLv + 1);
        List<EntityType> types = new ArrayList<>(cfg.getEnabledMobTypes());
        EntityType type = types.isEmpty() ? EntityType.ZOMBIE : types.get(rng.nextInt(types.size()));

        EliteRecord r = new EliteRecord();
        r.recordId = UUID.randomUUID().toString();
        r.world = w.getName();
        r.x = spot.getX();
        r.y = spot.getY();
        r.z = spot.getZ();
        r.type = type.name();
        r.level = level;
        r.maxHealth = 0;   // 物化时按实际计算
        r.health = 0;      // 满血
        r.boss = true;
        r.spawnTime = System.currentTimeMillis();
        r.reason = "boss_scheduler";
        r.entityUuid = null; // 潜伏
        r.updatedAt = r.spawnTime;
        plugin.getPersistence().insertRecord(r);
        announceBoss(r, true);
    }

    /** 全服广播 Boss 布署（含坐标）。 */
    private void announceBoss(EliteRecord r, boolean alert) {
        if (!alert || !plugin.getEliteConfig().isBossAlertEnabled()) return;
        String bossName = com.clawx.elitemobs.ai.EliteBossManager.buildBossDisplayName(r.type, r.level);
        String announce = ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 "
            + ChatColor.RED + "" + ChatColor.BOLD + "Boss\u8b66\u62a5\uff01"
            + ChatColor.GRAY + " \u2014 " + bossName + ChatColor.GRAY + " \u5728 "
            + ChatColor.WHITE + r.world + ChatColor.GRAY + " \u5750\u6807 ("
            + ChatColor.YELLOW + (int) Math.floor(r.x) + ", " + (int) Math.floor(r.y) + ", " + (int) Math.floor(r.z)
            + ChatColor.GRAY + ") \u964d\u751f\u4e86\uff01";
        Bukkit.broadcastMessage(announce);
        com.clawx.elitemobs.utils.StringColorAnimator.animateTitleAll(plugin,
            ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 BOSS\u8b66\u62a5\uff01",
            ChatColor.RED + bossName + ChatColor.GRAY + " \u964d\u4e34\u4e86\uff01",
            ChatColor.RED, ChatColor.GOLD);
    }

    /** 服务器启动时广播仍潜伏的 Boss 位置（受 boss.spawn.announce-on-start 控制）。 */
    public void reAnnouncePending() {
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isBossAnnounceOnStart()) return;
        ElitePersistence pers = plugin.getPersistence();
        if (pers == null) return;
        List<EliteRecord> pending = pers.getPendingBosses();
        if (pending.isEmpty()) return;
        for (EliteRecord r : pending) {
            String bossName = com.clawx.elitemobs.ai.EliteBossManager.buildBossDisplayName(r.type, r.level);
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 "
                + ChatColor.RED + "Boss " + bossName + ChatColor.GRAY + " \u4ecd\u5728 "
                + ChatColor.WHITE + r.world + ChatColor.GRAY + " (" + ChatColor.YELLOW
                + (int) Math.floor(r.x) + ", " + (int) Math.floor(r.y) + ", " + (int) Math.floor(r.z)
                + ChatColor.GRAY + ") \u6f5c\u4f0f\uff0c\u63a5\u8fd1\u5373\u73b0\u8eab\uff01");
        }
    }
}
