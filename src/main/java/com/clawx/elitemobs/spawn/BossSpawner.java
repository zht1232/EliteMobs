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
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Boss 直接布署器（替代"玩家附近精英晋升"）。
 *
 * <p>频率不固定：由权重公式动态计算下次布署间隔——</p>
 * <pre>
 *   nextDelay = base-interval × playerFactor × dayNightFactor × bossCountFactor × killActivityFactor × jitter
 *   playerFactor    = sqrt(sweetSpot / 在线人数)         10人=1.0，人多更快（×0.5），人少更慢（×2.0）
 *   dayNightFactor  = 白天 day-multiplier / 夜间 night-multiplier
 *   bossCountFactor = 1 + 当前Boss数/max-concurrent     Boss 多则间隔拉长，避免扎堆
 *   killActivity    = 窗口内击杀精英/Boss 越活跃间隔越短（每 10 击杀 ×0.9，下限 min-factor）
 *   jitter          = 0.7~1.3 随机抖动，避免机械节奏
 * </pre>
 *
 * <p>距离同样动态：按等级在 min/max 间插值 × 夜间倍率 × 随机 ±15%；
 * 选点支持群系权重（biome-weights），权重高的群系更容易被选中。</p>
 *
 * <p>布署 = 数据写入 SQLite（潜伏状态，无实体）+ 全服广播坐标；玩家接近物化距离时
 * 由 ElitePersistence 物化实体。远距离/未加载区块不再有实体，杜绝"Boss 凭空消失"。</p>
 */
public class BossSpawner {
    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();
    // 击杀活跃追踪：击杀时间戳队列（毫秒），由 ElitePersistence.onEliteDeath 上报
    private final Deque<Long> recentKills = new ArrayDeque<>();
    // 不适合当 Boss 的类型（防意外生成）
    private static final List<EntityType> BOSS_EXCLUDE_TYPES = List.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.ARMOR_STAND,
            EntityType.PLAYER, EntityType.VILLAGER, EntityType.BAT);

    public BossSpawner(EliteMobsPlugin plugin) {
        this.plugin = plugin;
    }

    /** 启动自调度布署任务（动态间隔，每次执行后按权重重算下一次）。 */
    public void start() {
        scheduleNext(100L); // 5 秒后首次
    }

    private void scheduleNext(long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                tryPlanBoss();
            } catch (Exception ex) {
                // 单次布署异常不打断调度链
                plugin.getLogger().warning("[EliteMobs] Boss \u5e03\u7f72\u5f02\u5e38: " + ex.getMessage());
            }
            scheduleNext(nextDelayTicks());
        }, Math.max(20L, delayTicks));
    }

    // ==================== 动态间隔权重 ====================

    /** 计算下次布署间隔（tick）：base × 人数 × 昼夜 × Boss数 × 击杀活跃 × 随机抖动，封顶 [min, 3×base]。 */
    private long nextDelayTicks() {
        EliteConfig cfg = plugin.getEliteConfig();
        int online = 0;
        World nightWorld = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || p.isDead() || !p.isValid()) continue;
            if (!cfg.isWorldEnabled(p.getWorld().getName())) continue;
            online++;
            if (nightWorld == null) nightWorld = p.getWorld();
        }
        if (online == 0) return Math.max(120L, (long) cfg.getBossSpawnMinIntervalSeconds() * 20L);

        // 人数因子：甜点人数时=1.0，人多更快、人少更慢，封顶 [0.5, 2.0]
        double playerFactor = Math.sqrt((double) cfg.getBossPlayerCountSweetSpot() / Math.max(1, online));
        playerFactor = Math.max(0.5, Math.min(2.0, playerFactor));

        // 昼夜因子：取任意在线玩家所在世界的游戏时间
        boolean night = nightWorld != null && isNight(nightWorld);
        double dayNightFactor = night ? cfg.getBossNightIntervalMultiplier() : cfg.getBossDayIntervalMultiplier();

        // Boss 数因子：Boss 越多间隔越长（防扎堆）
        double bossCountFactor = 1.0;
        ElitePersistence pers = plugin.getPersistence();
        if (pers != null && pers.isEnabled()) {
            bossCountFactor = 1.0 + (double) pers.countBosses() / Math.max(1, cfg.getBossSpawnMaxConcurrent());
        }

        // 击杀活跃因子：窗口内每击杀 10 只缩短 baseFactor（默认 10%），下限 min-factor
        int kills = killsInWindow(cfg.getBossKillActivityWindowMinutes());
        double killFactor = Math.max(cfg.getBossKillActivityMinFactor(),
                1.0 - kills * (cfg.getBossKillActivityBaseFactor() / 10.0));

        // 随机抖动 0.7~1.3
        double jitter = 0.7 + rng.nextDouble() * 0.6;

        long seconds = Math.round(cfg.getBossSpawnBaseIntervalSeconds()
                * playerFactor * dayNightFactor * bossCountFactor * killFactor * jitter);
        long minSec = cfg.getBossSpawnMinIntervalSeconds();
        long maxSec = Math.max(minSec, (long) cfg.getBossSpawnBaseIntervalSeconds() * 3);
        return Math.max(20L, Math.min(maxSec, Math.max(minSec, seconds)) * 20L);
    }

    private boolean isNight(World w) {
        long t = w.getTime() % 24000;
        return t >= 13000 && t < 23000;
    }

    /** 击杀活跃统计：窗口内的击杀数（自动清理过期时间戳）。 */
    private int killsInWindow(int minutes) {
        long cutoff = System.currentTimeMillis() - (long) minutes * 60000L;
        while (!recentKills.isEmpty() && recentKills.peekFirst() < cutoff) recentKills.pollFirst();
        return recentKills.size();
    }

    /** 由 ElitePersistence.onEliteDeath 上报精英/Boss 击杀（主线程调用）。 */
    public void recordEliteKill() {
        recentKills.addLast(System.currentTimeMillis());
    }

    // ==================== 布署 ====================

    /** 尝试布署一个 Boss：数量不足 + 有在线玩家时，按权重在世界远端选点入库。 */
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
        Location pl = base.getLocation();
        boolean night = isNight(w);
        Map<String, Double> biomeWeights = cfg.getBossBiomeWeights();
        double maxBiomeWeight = 1.0;
        for (double v : biomeWeights.values()) maxBiomeWeight = Math.max(maxBiomeWeight, v);

        // 先掷等级，距离按等级插值
        int minLv = Math.max(1, cfg.getBossSpawnMinLevel());
        int maxLv = Math.max(minLv, cfg.getBossSpawnMaxLevel());
        int level = minLv + rng.nextInt(maxLv - minLv + 1);
        double levelFactor = maxLv > minLv ? (level - minLv) / (double) (maxLv - minLv) : 0.0;

        double minD = cfg.getBossSpawnMinDistance();
        double maxD = cfg.getBossSpawnMaxDistance();
        double baseDist = minD + levelFactor * (maxD - minD);
        if (night) baseDist *= cfg.getBossNightDistanceMultiplier();

        for (int attempt = 0; attempt < 14; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = baseDist * (0.85 + rng.nextDouble() * 0.3); // ±15%
            int tx = (int) Math.floor(pl.getX() + Math.cos(angle) * dist);
            int tz = (int) Math.floor(pl.getZ() + Math.sin(angle) * dist);
            try {
                if (!w.getWorldBorder().isInside(new Location(w, tx, 64, tz))) continue;
            } catch (Exception ignored) {}
            // 优先选已生成的区块，避免同步生成大量新区块造成卡顿
            if (!w.isChunkGenerated(tx >> 4, tz >> 4)) continue;
            // 群系权重（拒绝采样）：权重越大越容易被选中；权重表为空则跳过
            if (!biomeWeights.isEmpty()) {
                String biomeName = w.getBiome(tx, 64, tz).name();
                double weight = biomeWeights.getOrDefault(biomeName, 1.0);
                if (weight <= 0) continue; // 权重 0 = 禁止
                if (weight < maxBiomeWeight && rng.nextDouble() > weight / maxBiomeWeight) continue;
            }
            Location spot = EliteSpawnHandler.findSafeSpot(w, tx, tz);
            if (spot == null) continue;
            if (ProtectionHook.isProtected(spot)) continue; // 不布署在玩家领地内
            planBossAt(w, spot, level);
            return;
        }
    }

    private void planBossAt(World w, Location spot, int level) {
        EliteConfig cfg = plugin.getEliteConfig();
        List<EntityType> types = new ArrayList<>();
        for (EntityType t : cfg.getEnabledMobTypes()) {
            if (!BOSS_EXCLUDE_TYPES.contains(t)) types.add(t);
        }
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

    /** 全服广播 Boss 布署（含坐标；受 announce-range 限制，仅同世界范围内玩家收到）。 */
    private void announceBoss(EliteRecord r, boolean alert) {
        if (!alert || !plugin.getEliteConfig().isBossAlertEnabled()) return;
        String bossName = com.clawx.elitemobs.ai.EliteBossManager.buildBossDisplayName(r.type, r.level);
        String announce = ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 "
            + ChatColor.RED + "" + ChatColor.BOLD + "Boss\u8b66\u62a5\uff01"
            + ChatColor.GRAY + " \u2014 " + bossName + ChatColor.GRAY + " \u5728 "
            + ChatColor.WHITE + r.world + ChatColor.GRAY + " \u5750\u6807 ("
            + ChatColor.YELLOW + (int) Math.floor(r.x) + ", " + (int) Math.floor(r.y) + ", " + (int) Math.floor(r.z)
            + ChatColor.GRAY + ") \u964d\u751f\u4e86\uff01";
        World w = Bukkit.getWorld(r.world);
        if (w == null) return;
        Location loc = new Location(w, r.x, r.y, r.z);
        com.clawx.elitemobs.ai.EliteBossManager.announceNear(plugin, w, loc, announce);
        com.clawx.elitemobs.utils.StringColorAnimator.animateTitleNear(plugin, w, loc,
            plugin.getEliteConfig().getBossAnnounceRange(),
            ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 BOSS\u8b66\u62a5\uff01",
            ChatColor.RED + bossName + ChatColor.GRAY + " \u964d\u4e34\u4e86\uff01",
            ChatColor.RED, ChatColor.GOLD);
    }

    /** 服务器启动时广播仍潜伏的 Boss 位置（受 boss.spawn.announce-on-start 与 announce-range 控制）。 */
    public void reAnnouncePending() {
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isBossAnnounceOnStart()) return;
        ElitePersistence pers = plugin.getPersistence();
        if (pers == null) return;
        List<EliteRecord> pending = pers.getPendingBosses();
        if (pending.isEmpty()) return;
        for (EliteRecord r : pending) {
            String bossName = com.clawx.elitemobs.ai.EliteBossManager.buildBossDisplayName(r.type, r.level);
            World w = Bukkit.getWorld(r.world);
            if (w == null) continue;
            Location loc = new Location(w, r.x, r.y, r.z);
            com.clawx.elitemobs.ai.EliteBossManager.announceNear(plugin, w, loc,
                ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 "
                + ChatColor.RED + "Boss " + bossName + ChatColor.GRAY + " \u4ecd\u5728 "
                + ChatColor.WHITE + r.world + ChatColor.GRAY + " (" + ChatColor.YELLOW
                + (int) Math.floor(r.x) + ", " + (int) Math.floor(r.y) + ", " + (int) Math.floor(r.z)
                + ChatColor.GRAY + ") \u6f5c\u4f0f\uff0c\u63a5\u8fd1\u5373\u73b0\u8eab\uff01");
        }
    }
}
