package com.clawx.elitemobs.ai;

import com.clawx.elitemobs.EliteConfig;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.utils.StringColorAnimator;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 精英Boss系统。
 * 高等级精英怪（Lv.15+）有概率晋升为Boss，拥有：
 * - 巨型血量（3x）+ 体型放大
 * - Boss血条（所有附近玩家可见）
 * - 特殊技能（冲击波/治愈/召唤 + 跳跃扑击/引导治疗）
 * - 专属掉落
 */
public class EliteBossManager implements Listener {
    private final EliteMobsPlugin plugin;
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Random rng = new Random();
    // 已触发第二阶段的 Boss（每只 Boss 仅触发一次）
    private final Set<UUID> phase2Triggered = ConcurrentHashMap.newKeySet();
    // 跳跃扑击（GroundPound）触发冷却
    private final Set<UUID> groundPoundCooldowns = ConcurrentHashMap.newKeySet();
    // 引导治疗（ChannelHealing）状态：引导中 / 局部冷却
    private final Set<UUID> channelActive = ConcurrentHashMap.newKeySet();
    private final Set<UUID> channelCooldown = ConcurrentHashMap.newKeySet();
    // 冰冻（AttackFreeze）：玩家 → 环绕漂浮冰块实体
    private final Map<UUID, org.bukkit.entity.Item[]> freezeIces = new ConcurrentHashMap<>();
    // 封印（Seal）：玩家UUID → 封印到期时间（毫秒）
    private final Map<UUID, Long> sealedUntil = new ConcurrentHashMap<>();
    // 封印技能冷却（按 Boss，避免连放导致玩家永久封印）
    private final Set<UUID> sealCooldowns = ConcurrentHashMap.newKeySet();

    public EliteBossManager(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        startBossTick();
    }

    /** 强制晋升为Boss（指令调用，跳过概率） */
    public void forcePromoteToBoss(LivingEntity entity, int level) {
        promoteToBoss(entity, Math.max(level, 15));
    }

    /** 尝试将精英怪晋升为Boss（由生成系统调用） */
    public boolean tryPromoteToBoss(LivingEntity entity, int level) {
        if (level < 15) return false;
        // Lv.15-17: 5%概率, Lv.18-19: 15%, Lv.20+: 30%
        double chance = level >= 20 ? 0.30 : level >= 18 ? 0.15 : 0.05;
        if (rng.nextDouble() >= chance) return false;

        promoteToBoss(entity, level);
        return true;
    }

    private void promoteToBoss(LivingEntity entity, int level) {
        entity.setMetadata("elite_boss", new FixedMetadataValue(plugin, true));
        // PDC 持久化标记：metadata 不跨 chunk 持久化，区块卸载重载后会丢失，
        // 用 PDC 兜底保证 Boss 身份在重载后仍可识别（掉落/血条/二阶段依赖它）
        entity.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "elite_boss"),
                org.bukkit.persistence.PersistentDataType.BOOLEAN, true);

        // Boss 持久化：区块卸载重载后保留（普通精英默认不持久化，避免长期累积）
        entity.setPersistent(true);

        // 3倍血量
        AttributeInstance hp = entity.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            double newHp = hp.getBaseValue() * 3.0;
            hp.setBaseValue(newHp);
            entity.setHealth(newHp);
        }
        // 骷髅/流浪者 Boss：额外血量（远程怪较脆，补足坦度，避免被近身几刀秒）
        if (entity.getType() == EntityType.SKELETON || entity.getType() == EntityType.STRAY) {
            AttributeInstance shp = entity.getAttribute(Attribute.MAX_HEALTH);
            if (shp != null) {
                double newHp = shp.getBaseValue() * 2.0;
                shp.setBaseValue(newHp);
                entity.setHealth(newHp);
            }
        }

        // 体型放大（GENERIC_SCALE，同步放大模型与碰撞体积；Slime/岩浆怪用 size）
        applyBossScale(entity);

        entity.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 999999, 2, false, false));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 999999, 1, false, false));

        // Boss 额外获得多个词缀（在普通精英词缀基础上追加，名字后缀同步刷新）
        if (plugin.getAffixHandler() != null) {
            plugin.getAffixHandler().grantBossAffixes(entity, 2);
        }

        // 创建Boss血条
        String bossName = ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 "
            + ChatColor.RED + entity.getType().name().toLowerCase().replace('_', ' ')
            + ChatColor.GRAY + " [Lv." + level + "] "
            + ChatColor.DARK_RED + "" + ChatColor.BOLD + "BOSS";
        BossBar bar = Bukkit.createBossBar(bossName, BarColor.RED, BarStyle.SEGMENTED_12);
        bar.setProgress(1.0);
        bossBars.put(entity.getUniqueId(), bar);

        // 添加附近玩家到血条
        for (Player p : entity.getWorld().getPlayers()) {
            if (p.getLocation().distance(entity.getLocation()) <= 80) {
                bar.addPlayer(p);
            }
        }

        // 生成特效（真闪电：Boss 登场落雷，可对附近实体造成真实伤害/充电；打标记取消引燃，避免烧毁建筑/掉落物）
        Location loc = entity.getLocation();
        org.bukkit.entity.LightningStrike ls = loc.getWorld().strikeLightning(loc);
        if (ls != null) ls.setMetadata("elitemobs_lightning", new FixedMetadataValue(plugin, true));
        loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_SPAWN, 3.0f, 0.5f);
        for (int i = 0; i < 50; i++) {
            EliteMobManager.spawnParticleSafe(loc.getWorld(), Particle.SOUL_FIRE_FLAME,
                loc.clone().add(rng.nextDouble() - 0.5, rng.nextDouble() * 2, rng.nextDouble() - 0.5), 1);
        }

        // 全服广播（含坐标；受 spawn-announce.enabled 控制，关掉后 Boss 警报也不播）
        String announce = ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 "
            + ChatColor.RED + "" + ChatColor.BOLD + "Boss\u8b66\u62a5\uff01"
            + ChatColor.GRAY + " \u2014 " + bossName + ChatColor.GRAY + " \u5728 "
            + ChatColor.WHITE + loc.getWorld().getName()
            + ChatColor.GRAY + " \u5750\u6807 ("
            + ChatColor.YELLOW + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
            + ChatColor.GRAY + ") \u964d\u751f\u4e86\uff01";
        if (plugin.getEliteConfig().isBossAlertEnabled()) {
            Bukkit.broadcastMessage(announce);
            // 动态彩色标题（打字机+双色渐变）——最初版：含 Boss 名
            StringColorAnimator.animateTitleAll(plugin,
                ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 BOSS\u8b66\u62a5\uff01",
                ChatColor.RED + bossName + ChatColor.GRAY + " \u964d\u4e34\u4e86\uff01",
                ChatColor.RED, ChatColor.GOLD);
        }
    }

    /** Boss 体型放大：GENERIC_SCALE 同步放大模型与碰撞体积；史莱姆/岩浆怪用 size。 */
    private void applyBossScale(LivingEntity entity) {
        double scale = plugin.getEliteConfig().getBossScale();
        if (scale <= 1.0) return;
        try {
            if (entity instanceof Slime slime) {
                int newSize = Math.max(slime.getSize() + 2, (int) Math.round(slime.getSize() * scale));
                slime.setSize(newSize);
                return;
            }
            AttributeInstance sc = entity.getAttribute(Attribute.SCALE);
            if (sc != null) {
                sc.setBaseValue(scale);
                // 强制刷新实体模型缩放
                entity.setHealth(entity.getHealth());
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[EliteMobs] Boss \u4f53\u578b\u653e\u5927\u5931\u8d25: " + ex.getMessage());
        }
    }

    public static boolean isBoss(LivingEntity entity) {
        if (entity == null) return false;
        if (entity.hasMetadata("elite_boss")) return true;
        return entity.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("elitemobs", "elite_boss"),
                org.bukkit.persistence.PersistentDataType.BOOLEAN);
    }

    /** 区块重载后恢复 Boss 血条/技能管理（不重复加血/改体型，由 EliteMobManager.revalidateChunk 调用）。 */
    public void revalidateBoss(LivingEntity boss) {
        if (bossBars.containsKey(boss.getUniqueId())) return;
        int level = EliteMobManager.getEliteLevel(boss);
        String bossName = ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 "
            + ChatColor.RED + boss.getType().name().toLowerCase().replace('_', ' ')
            + ChatColor.GRAY + " [Lv." + level + "] "
            + ChatColor.DARK_RED + "" + ChatColor.BOLD + "BOSS";
        BossBar bar = Bukkit.createBossBar(bossName, BarColor.RED, BarStyle.SEGMENTED_12);
        bar.setProgress(1.0);
        bossBars.put(boss.getUniqueId(), bar);
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getLocation().distance(boss.getLocation()) <= 80) bar.addPlayer(p);
        }
        // 若血量已低于二阶段阈值，标记已触发（避免跨区块回来重复触发二阶段广播）
        EliteConfig cfg = plugin.getEliteConfig();
        if (cfg.isBossPhase2Enabled()) {
            AttributeInstance hp = boss.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null && hp.getValue() > 0 && boss.getHealth() / hp.getValue() <= cfg.getBossPhase2HpRatio()) {
                phase2Triggered.add(boss.getUniqueId());
            }
        }
    }

    /** Boss 定时任务：更新血条 + Boss技能 */
    private void startBossTick() {
        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                tick++;
                Iterator<Map.Entry<UUID, BossBar>> it = bossBars.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    UUID uuid = entry.getKey();
                    BossBar bar = entry.getValue();

                    Entity entity = Bukkit.getEntity(uuid);
                    if (entity == null || entity.isDead() || !entity.isValid()) {
                        bar.removeAll();
                        it.remove();
                        continue;
                    }

                    LivingEntity le = (LivingEntity) entity;
                    // 第二阶段判定（血量低于阈值）
                    checkPhase2(le);
                    // 更新血条进度
                    AttributeInstance hp = le.getAttribute(Attribute.MAX_HEALTH);
                    if (hp != null && hp.getValue() > 0) {
                        bar.setProgress(Math.max(0, Math.min(1, le.getHealth() / hp.getValue())));
                    }

                    // 更新血条可见玩家（80格范围）
                    Location loc = le.getLocation();
                    for (Player p : le.getWorld().getPlayers()) {
                        boolean inRange = p.getLocation().distance(loc) <= 80;
                        if (inRange && !bar.getPlayers().contains(p)) {
                            bar.addPlayer(p);
                        } else if (!inRange && bar.getPlayers().contains(p)) {
                            bar.removePlayer(p);
                        }
                    }

                    // Boss技能（每5秒，按 Boss 相位错开，避免多 Boss 技能同步触发）
                    if ((tick + Math.floorMod(le.getUniqueId().hashCode(), 100)) % 100 == 0) {
                        bossAbility(le);
                    }
                    // 引导治疗扫描（channel-healing，完整版）
                    if (plugin.getEliteConfig().isChannelHealingEnabled()
                            && tick % plugin.getEliteConfig().getChannelHealingScanInterval() == 0) {
                        scanChannelHealing(le);
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 5L);
    }

    /** 清理指定 UUID 关联的冰冻冰块（区块加载时调用，清理残留装饰物） */
    public void cleanupFreezeIcesForChunk(UUID uuid) {
        freezeIces.remove(uuid);
    }

    // ==================== 引导治疗（ChannelHealing，完整版） ====================

    /** 扫描：Boss 在战斗时找范围内低血量其他精英，启动引导治疗 */
    private void scanChannelHealing(LivingEntity boss) {
        EliteConfig cfg = plugin.getEliteConfig();
        if (channelActive.contains(boss.getUniqueId())) return;
        if (channelCooldown.contains(boss.getUniqueId())) return;
        if (boss instanceof Mob bm && bm.getTarget() == null) return; // 非战斗不引导
        LivingEntity target = findHealingTarget(boss);
        if (target != null) startChannel(boss, target);
    }

    /** 找 20 格内 血量<=阈值 的其他精英（跳过自身与正在被治疗的） */
    private LivingEntity findHealingTarget(LivingEntity boss) {
        EliteConfig cfg = plugin.getEliteConfig();
        double radius = cfg.getChannelHealingSearchRadius();
        for (Entity ent : boss.getNearbyEntities(radius, radius, radius)) {
            if (!(ent instanceof LivingEntity le)) continue;
            if (le.getUniqueId().equals(boss.getUniqueId())) continue;
            if (!EliteMobManager.isElite(le)) continue;
            if (le.hasMetadata("elite_healing")) continue;
            AttributeInstance hp = le.getAttribute(Attribute.MAX_HEALTH);
            if (hp == null || hp.getValue() <= 0) continue;
            if (le.getHealth() / hp.getValue() > cfg.getChannelHealingHealThreshold()) continue;
            return le;
        }
        return null;
    }

    /** 启动引导：Boss 暂停 AI + 光束连接 + 周期性回血，任一中断条件满足即结束 */
    private void startChannel(LivingEntity boss, LivingEntity target) {
        EliteConfig cfg = plugin.getEliteConfig();
        channelActive.add(boss.getUniqueId());
        target.setMetadata("elite_healing", new FixedMetadataValue(plugin, true));
        final boolean hadAI;
        if (boss instanceof Mob bm) { hadAI = bm.hasAI(); bm.setAI(false); } else { hadAI = true; } // 记录原始 AI 状态
        int level = EliteMobManager.getEliteLevel(boss);
        new BukkitRunnable() {
            int timer = 0;
            @Override public void run() {
                if (!isAlive(boss) || !isAlive(target) || boss.getWorld() != target.getWorld()) { finish(); return; }
                AttributeInstance maxHp = target.getAttribute(Attribute.MAX_HEALTH);
                if (maxHp == null || maxHp.getValue() <= 0) { finish(); return; }
                if (target.getHealth() / maxHp.getValue() > cfg.getChannelHealingHealThreshold()) { finish(); return; }
                if (boss.getLocation().distanceSquared(target.getLocation())
                        > cfg.getChannelHealingMaxDistance() * cfg.getChannelHealingMaxDistance()) { finish(); return; }
                timer++;
                if (timer % 10 == 0) {
                    double heal = level * cfg.getChannelHealingHealFactor();
                    target.setHealth(Math.min(target.getHealth() + heal, maxHp.getValue()));
                    EliteMobManager.spawnParticleSafe(target.getWorld(), Particle.TOTEM_OF_UNDYING,
                            target.getLocation().add(0, 1, 0), 20);
                    target.getWorld().playSound(target.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
                }
                drawHealingRay(boss.getLocation().add(0, 1, 0), target.getLocation().add(0, 1, 0));
            }
            private boolean isAlive(LivingEntity le) { return le != null && !le.isDead() && le.isValid(); }
            private void finish() {
                cancel();
                channelActive.remove(boss.getUniqueId());
                target.removeMetadata("elite_healing", plugin);
                if (boss instanceof Mob bm) bm.setAI(hadAI); // 恢复原始 AI 状态
                new BukkitRunnable() {
                    @Override public void run() { channelCooldown.remove(boss.getUniqueId()); }
                }.runTaskLater(plugin, Math.max(1, cfg.getChannelHealingLocalCooldown()));
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /** 从 Boss 头顶到目标头顶沿线撒 TOTEM 粒子，形成光束 */
    private void drawHealingRay(Location from, Location to) {
        World world = from.getWorld();
        if (world == null) return;
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 0.001) return;
        Vector step = dir.normalize().multiply(0.5);
        Location cur = from.clone();
        double travelled = 0;
        while (travelled <= len) {
            EliteMobManager.spawnParticleSafe(world, Particle.TOTEM_OF_UNDYING, cur, 1);
            cur.add(step);
            travelled += 0.5;
        }
    }

    // ==================== 跳跃扑击落地震击（GroundPound） ====================

    /** Boss 被玩家击中时概率触发：跳起 → 落地震击（击飞+减速） */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHitByPlayer(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity boss)) return;
        if (!isBoss(boss)) return;
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isGroundPoundEnabled()) return;
        if (!isPlayerDamage(event.getDamager())) return;
        if (groundPoundCooldowns.contains(boss.getUniqueId())) return;
        if (rng.nextDouble() >= cfg.getGroundPoundChance()) return;
        groundPoundCooldowns.add(boss.getUniqueId());
        new BukkitRunnable() {
            @Override public void run() { groundPoundCooldowns.remove(boss.getUniqueId()); }
        }.runTaskLater(plugin, Math.max(1, cfg.getGroundPoundCooldownTicks()));
        startGroundPound(boss);
    }

    private boolean isPlayerDamage(Entity damager) {
        if (damager instanceof Player) return true;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player) return true;
        return false;
    }

    /** 三段式：起跳 → 检测落地 → 落地震击 */
    private void startGroundPound(LivingEntity boss) {
        EliteConfig cfg = plugin.getEliteConfig();
        Location loc = boss.getLocation();
        World world = loc.getWorld();
        boss.setVelocity(new Vector(0, cfg.getGroundPoundLaunchY(), 0));
        EliteMobManager.spawnParticleSafe(world, Particle.CLOUD, loc, 10);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);
        new BukkitRunnable() {
            boolean landed = false;
            int guard = 0;
            @Override public void run() {
                if (boss.isDead() || !boss.isValid()) { cancel(); return; }
                if (++guard > 120) { cancel(); return; }
                // 仅在下坠时判定落地，避免起跳瞬间误触发震击
                if (boss.getVelocity().getY() < -0.05 && (boss.isOnGround() || isSolidBelow(boss))) {
                    if (landed) { cancel(); return; }
                    landed = true;
                    groundPoundLand(boss);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, Math.max(1, cfg.getGroundPoundFallDelay()), 1L);
    }

    private boolean isSolidBelow(LivingEntity e) {
        org.bukkit.block.Block below = e.getWorld().getBlockAt(
                e.getLocation().getBlockX(), e.getLocation().getBlockY() - 1, e.getLocation().getBlockZ());
        return below.getType().isOccluding() || below.getType().isSolid();
    }

    /** 落地：烟尘爆炸 + 范围内实体击飞+减速 */
    private void groundPoundLand(LivingEntity boss) {
        EliteConfig cfg = plugin.getEliteConfig();
        Location loc = boss.getLocation();
        World world = loc.getWorld();
        EliteMobManager.spawnParticleSafe(world, Particle.CLOUD, loc, 25);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f);
        double radius = cfg.getGroundPoundRadius();
        for (Entity ent : boss.getNearbyEntities(radius, radius, radius)) {
            if (ent.getUniqueId().equals(boss.getUniqueId())) continue;
            if (!(ent instanceof LivingEntity le)) continue;
            Vector dir = ent.getLocation().toVector().subtract(loc.toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 0.0001) dir = new Vector(rng.nextDouble() - 0.5, 0, rng.nextDouble() - 0.5);
            dir.normalize();
            le.setVelocity(dir.multiply(cfg.getGroundPoundKnockback()).setY(cfg.getGroundPoundKnockbackY()));
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, cfg.getGroundPoundSlowness(), 1, false, false));
        }
    }

    /** Boss 特殊技能 */
    private void bossAbility(LivingEntity boss) {
        Location loc = boss.getLocation();
        int ability = rng.nextInt(11);

        switch (ability) {
            case 0 -> { // 冲击波：对周围玩家造成伤害和击退
                for (Entity e : boss.getNearbyEntities(6, 4, 6)) {
                    if (e instanceof Player p && !p.hasPermission("elitemobs.bypass")) {
                        p.damage(4.0, boss);
                        p.setVelocity(p.getLocation().toVector()
                            .subtract(loc.toVector()).normalize().multiply(1.5).setY(0.8));
                    }
                }
                EliteMobManager.spawnParticleSafe(boss.getWorld(), Particle.EXPLOSION, loc.clone().add(0, 1, 0), 5);
                boss.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.8f);
            }
            case 1 -> { // 治愈：恢复10%最大血量
                AttributeInstance hp = boss.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) {
                    double heal = hp.getValue() * 0.1;
                    boss.setHealth(Math.min(boss.getHealth() + heal, hp.getValue()));
                }
                EliteMobManager.spawnParticleSafe(boss.getWorld(), Particle.HEART, loc.clone().add(0, 1, 0), 8);
                boss.getWorld().playSound(loc, Sound.ENTITY_EVOKER_CAST_SPELL, 1.5f, 0.8f);
            }
            case 2 -> { // 召唤护卫
                int count = 1 + rng.nextInt(2);
                for (int i = 0; i < count; i++) {
                    Location spawnLoc = loc.clone().add(rng.nextDouble() * 4 - 2, 0, rng.nextDouble() * 4 - 2);
                    EntityType type = rng.nextBoolean() ? EntityType.VINDICATOR : EntityType.PILLAGER;
                    // 召唤光柱特效
                    com.clawx.elitemobs.ai.EliteClassAI.drawSummonPillar(boss.getWorld(), spawnLoc, plugin);
                    LivingEntity minion = (LivingEntity) boss.getWorld().spawnEntity(spawnLoc, type);
                    minion.setMetadata("elite_minion", new FixedMetadataValue(plugin, true));
                    minion.setMetadata("boss_guard", new FixedMetadataValue(plugin, true));
                    // PDC 持久化：metadata 不跨 chunk，区块重载后仍可识别为精英召唤物
                    minion.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "elite_minion"),
                            org.bukkit.persistence.PersistentDataType.BOOLEAN, true);
                    AttributeInstance mhp = minion.getAttribute(Attribute.MAX_HEALTH);
                    if (mhp != null) { mhp.setBaseValue(mhp.getBaseValue() * 2); minion.setHealth(mhp.getBaseValue()); }
                    if (minion instanceof Mob m && boss instanceof Mob bm && bm.getTarget() != null) {
                        m.setTarget(bm.getTarget());
                    }
                }
                EliteMobManager.spawnParticleSafe(boss.getWorld(), Particle.SOUL, loc.clone().add(0, 0.5, 0), 15);
                boss.getWorld().playSound(loc, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2.0f, 0.8f);
            }
            case 3 -> { // 冰冻：强减速 + 真冰冻 + 玩家旁漂浮冰块
                for (Entity e : boss.getNearbyEntities(8, 6, 8)) {
                    if (e instanceof Player p && !p.hasPermission("elitemobs.bypass")) {
                        freezePlayer(p, 60);
                    }
                }
                EliteMobManager.spawnParticleSafe(boss.getWorld(), Particle.SNOWFLAKE, loc.clone().add(0, 1, 0), 30);
                boss.getWorld().playSound(loc, Sound.BLOCK_GLASS_BREAK, 2.0f, 0.8f);
            }
            case 4 -> { // 封印（仅二阶段）：短时间内封印附近玩家淬炼加成
                EliteConfig cfg = plugin.getEliteConfig();
                if (!cfg.isSealEnabled()) return; // 先检查配置
                if (!phase2Triggered.contains(boss.getUniqueId())) return; // 再检查二阶段
                if (sealCooldowns.contains(boss.getUniqueId())) return; // 最后检查冷却
                sealCooldowns.add(boss.getUniqueId());
                new BukkitRunnable() {
                    @Override public void run() { sealCooldowns.remove(boss.getUniqueId()); }
                }.runTaskLater(plugin, 400L); // 20 秒冷却，避免连放
                double radius = cfg.getSealRadius();
                for (Entity e : boss.getNearbyEntities(radius, radius, radius)) {
                    if (e instanceof Player p && !p.hasPermission("elitemobs.bypass")
                            && rng.nextDouble() < cfg.getSealChance()) {
                        applySeal(p);
                    }
                }
                EliteMobManager.spawnParticleSafe(boss.getWorld(), Particle.WITCH, loc.clone().add(0, 1, 0), 15);
                boss.getWorld().playSound(loc, Sound.ENTITY_EVOKER_CAST_SPELL, 1.5f, 0.5f);
            }
            case 5 -> { // 飞扑下坠：主动飞起 → 追踪锁定玩家 → 猛砸落地（给目标 title 预警）
                if (!(boss instanceof Mob bm) || !(bm.getTarget() instanceof Player tp)) return;
                double dist = boss.getLocation().distance(tp.getLocation());
                if (dist < 3 || dist > 20) return; // 太近/太远不飞扑
                tp.sendTitle(ChatColor.RED + "\u26a0 \u98de\u6251\uff01",
                        ChatColor.YELLOW + "\u5feb\u8eb2\u5f00\uff01", 5, 20, 5);
                diveBomb(boss, tp);
            }
            case 6 -> { // 声波（仿寻声守卫）：远程穿墙声波攻击目标玩家
                if (!(boss instanceof Mob bm) || !(bm.getTarget() instanceof Player tp)) return;
                double d = boss.getLocation().distance(tp.getLocation());
                if (d < 2 || d > 24) return;
                sonicBoom(boss, tp);
            }
            case 7 -> { // 雷电风暴：预警后目标周围连续落雷
                if (!(boss instanceof Mob bm) || !(bm.getTarget() instanceof Player tp)) return;
                thunderStorm(boss, tp);
            }
            case 8 -> magnetPull(boss); // 磁力拉扯：把远处玩家拉过来
            case 9 -> { // 假死装死（仅低血量）：隐身消失后偷袭玩家
                if (!(boss instanceof Mob bm) || !(bm.getTarget() instanceof Player tp)) return;
                AttributeInstance mh = boss.getAttribute(Attribute.MAX_HEALTH);
                if (mh == null || boss.getHealth() / mh.getValue() > 0.35) return;
                feignDeath(boss, tp);
            }
            case 10 -> summonSplit(boss); // 召唤分裂：分出 2 个残影，数秒后合体
        }
    }

    // ==================== 飞扑下坠（DiveBomb） ====================

    /**
     * 飞扑下坠：Boss 原地飞起 → 垂直砸下，落地造成范围伤害+击退+减速。
     * （简化版：不空中追踪，直接原地起跳落地震击）
     */
    private void diveBomb(LivingEntity boss, LivingEntity target) {
        Location loc = boss.getLocation();
        World world = loc.getWorld();
        boss.setVelocity(new Vector(0, 1.4, 0)); // 原地起跳
        EliteMobManager.spawnParticleSafe(world, Particle.CLOUD, loc, 10);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);
        new BukkitRunnable() {
            boolean landed = false;
            int guard = 0;
            @Override public void run() {
                if (boss.isDead() || !boss.isValid()) { cancel(); return; }
                if (++guard > 100) { cancel(); return; } // 兜底
                if (landed) { cancel(); return; }
                // 仅在下坠时判定落地
                if (boss.getVelocity().getY() < -0.05 && (boss.isOnGround() || isSolidBelow(boss))) {
                    landed = true;
                    diveLand(boss);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 10L, 1L); // 起跳后 10 tick 开始检测落地
    }

    /** 飞扑落地：范围伤害 + 击退 + 减速 + 冲击波粒子 */
    private void diveLand(LivingEntity boss) {
        Location loc = boss.getLocation();
        World world = loc.getWorld();
        EliteMobManager.spawnParticleSafe(world, Particle.CLOUD, loc, 25);
        EliteMobManager.spawnParticleSafe(world, Particle.EXPLOSION, loc.clone().add(0, 1, 0), 8);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f);
        for (Entity ent : boss.getNearbyEntities(6, 4, 6)) {
            if (ent.getUniqueId().equals(boss.getUniqueId())) continue;
            if (ent instanceof Player p && !p.hasPermission("elitemobs.bypass")) {
                p.damage(6.0, boss);
                Vector dir = p.getLocation().toVector().subtract(loc.toVector());
                dir.setY(0);
                if (dir.lengthSquared() < 0.0001) dir = new Vector(rng.nextDouble() - 0.5, 0, rng.nextDouble() - 0.5);
                dir.normalize();
                p.setVelocity(dir.multiply(1.8).setY(0.9));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, false));
            }
        }
    }

    // ==================== 声波（SonicBoom，仿寻声守卫） ====================

    /**
     * 声波攻击：仿原版 Warden 声波。从 Boss 嘴部发射一道穿墙青色声波束飞向目标，
     * 蓄力后声波环沿射线推进，命中造成无视护甲的高额伤害。
     */
    private void sonicBoom(LivingEntity boss, LivingEntity target) {
        World world = boss.getWorld();
        Location from = boss.getLocation().add(0, boss.getHeight() * 0.7, 0); // 嘴部高度
        Location to = target.getLocation().add(0, target.getHeight() * 0.4, 0);
        Vector dir = to.toVector().subtract(from.toVector());
        double dist = Math.max(1, dir.length()); // 声波飞行距离
        Vector step = dir.normalize().multiply(1.1); // 每 tick 前进 1.1 格
        // 蓄力：紫魂粒子 + 蓄力音效
        world.playSound(from, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 0.8f);
        EliteMobManager.spawnParticleSafe(world, Particle.SCULK_SOUL, from, 5);
        int damage = 8; // 无视护甲
        new BukkitRunnable() {
            double travelled = 0;
            boolean hit = false;
            @Override public void run() {
                if (boss.isDead() || !boss.isValid()) { cancel(); return; }
                if (hit) { cancel(); return; }
                travelled += 1.1;
                if (travelled >= dist) {
                    if (target.isValid() && !target.isDead()) {
                        target.damage(damage, boss);
                        EliteMobManager.spawnParticleSafe(world, Particle.SONIC_BOOM, to, 3);
                        world.playSound(to, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.8f);
                    }
                    hit = true;
                    cancel();
                    return;
                }
                // 声波环沿射线推进（只撒粒子，音效只在命中时播放，避免刷屏）
                Location pos = from.clone().add(step.clone().multiply(travelled));
                world.spawnParticle(Particle.SONIC_BOOM, pos, 1, 0, 0, 0, 0);
            }
        }.runTaskTimer(plugin, 10L, 1L); // 蓄力 0.5 秒后发射
    }

    // ==================== 雷电风暴（Thunderstorm） ====================

    /** 雷电风暴：预警后目标玩家周围连续落雷，落点附近玩家受伤 */
    private void thunderStorm(LivingEntity boss, Player target) {
        Location center = target.getLocation();
        World world = center.getWorld();
        target.sendTitle(ChatColor.YELLOW + "\u26a1 \u96f7\u7535\u98ce\u66b4\uff01",
                ChatColor.RED + "\u5feb\u627e\u4e2a\u5b89\u5168\u533a\uff01", 5, 20, 5);
        world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.8f);
        // 预警粒子：目标周围地面电弧闪烁
        for (int i = 0; i < 20; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double r = rng.nextDouble() * 6;
            EliteMobManager.spawnParticleSafe(world, Particle.ELECTRIC_SPARK,
                    center.clone().add(Math.cos(a) * r, 0.5, Math.sin(a) * r), 2);
        }
        new BukkitRunnable() {
            int strikes = 0;
            @Override public void run() {
                if (boss.isDead() || !boss.isValid()) { cancel(); return; }
                if (++strikes > 8) { cancel(); return; }
                double a = rng.nextDouble() * Math.PI * 2;
                double r = rng.nextDouble() * 6;
                Location p = target.getLocation().clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                org.bukkit.entity.LightningStrike ls = p.getWorld().strikeLightning(p);
                if (ls != null) ls.setMetadata("elitemobs_lightning", new FixedMetadataValue(plugin, true));
                for (Entity ent : p.getWorld().getNearbyEntities(p, 3, 3, 3)) {
                    if (ent instanceof Player pl && !pl.hasPermission("elitemobs.bypass")) {
                        pl.damage(4.0, boss);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 5L); // 1 秒后开始，每 5 tick 一道，共 8 道
    }

    // ==================== 磁力拉扯（MagnetPull） ====================

    /** 磁力拉扯：把远处玩家持续拉向 Boss，拉到面前后震击 */
    private void magnetPull(LivingEntity boss) {
        List<Player> targets = new ArrayList<>();
        for (Entity ent : boss.getNearbyEntities(18, 18, 18)) {
            if (ent instanceof Player p && !p.hasPermission("elitemobs.bypass")
                    && p.getLocation().distance(boss.getLocation()) >= 4) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) return;
        for (Player p : targets) {
            p.sendTitle(ChatColor.RED + "\u26a0 \u78c1\u529b\uff01",
                    ChatColor.YELLOW + "\u88ab\u62fd\u8fc7\u53bb\u4e86\uff01", 5, 20, 5);
        }
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_SHULKER_SHOOT, 1.5f, 0.5f);
        // 持续拉拽 15 tick
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (boss.isDead() || !boss.isValid()) { cancel(); return; }
                if (++t > 15) { cancel(); return; }
                for (Player p : targets) {
                    if (!p.isOnline() || p.isDead() || p.hasPermission("elitemobs.bypass")) continue;
                    Vector dir = boss.getLocation().toVector().subtract(p.getLocation().toVector());
                    dir.setY(0);
                    if (dir.lengthSquared() < 1.5) continue; // 已到面前
                    p.setVelocity(dir.normalize().multiply(1.1).setY(0.15));
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        // 拉拽结束后震击（把拉过来的玩家震开）
        new BukkitRunnable() {
            @Override public void run() {
                if (boss.isDead() || !boss.isValid()) return;
                for (Entity ent : boss.getNearbyEntities(5, 3, 5)) {
                    if (ent instanceof Player p && !p.hasPermission("elitemobs.bypass")) {
                        p.damage(4.0, boss);
                        Vector dir = p.getLocation().toVector().subtract(boss.getLocation().toVector());
                        dir.setY(0);
                        if (dir.lengthSquared() < 0.0001) dir = new Vector(0, 0, 0.1);
                        p.setVelocity(dir.normalize().multiply(1.5).setY(0.6));
                    }
                }
                EliteMobManager.spawnParticleSafe(boss.getWorld(), Particle.EXPLOSION,
                        boss.getLocation().add(0, 1, 0), 6);
                boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.8f);
            }
        }.runTaskLater(plugin, 17L);
    }

    // ==================== 假死装死（FeignDeath） ====================

    /** 假死装死：低血量时隐身+假坟，数秒后传送到目标背后偷袭 */
    private void feignDeath(LivingEntity boss, Player target) {
        Location loc = boss.getLocation();
        World world = loc.getWorld();
        boss.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 120, 0, true, false));
        EliteMobManager.spawnParticleSafe(world, Particle.SMOKE, loc, 20);
        world.playSound(loc, Sound.ENTITY_GENERIC_DEATH, 1.0f, 0.8f);
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "\u2026 " + ChatColor.GRAY + "Boss \u6d88\u5931\u4e86\uff1f");
        // 假坟装饰
        org.bukkit.entity.Item grave = world.dropItem(loc.clone().add(0, 0.5, 0),
                new org.bukkit.inventory.ItemStack(Material.BONE_BLOCK));
        grave.setPickupDelay(Integer.MAX_VALUE);
        grave.setGravity(false);
        grave.setInvulnerable(true);
        // PDC 标记：区块重载后由 onChunkLoad 清理，避免残留
        grave.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "boss_decor"),
                org.bukkit.persistence.PersistentDataType.BOOLEAN, true);
        // 3.5 秒后偷袭（留玩家反应时间）
        new BukkitRunnable() {
            @Override public void run() {
                if (boss.isDead() || !boss.isValid()) { grave.remove(); cancel(); return; }
                boss.removePotionEffect(PotionEffectType.INVISIBILITY);
                if (target.isOnline() && !target.isDead()) {
                    Location behind = target.getLocation().clone();
                    behind.add(behind.getDirection().multiply(-2)); // 背后
                    boss.teleport(behind);
                    target.damage(6.0, boss);
                    Vector dir = boss.getLocation().toVector().subtract(target.getLocation().toVector());
                    dir.setY(0);
                    if (dir.lengthSquared() < 0.0001) dir = new Vector(0, 0, 0.1);
                    target.setVelocity(dir.normalize().multiply(1.6).setY(0.5));
                    target.sendTitle(ChatColor.DARK_RED + "\u2620 \u5047\u6b7b\uff01",
                            ChatColor.RED + "\u88ab\u5077\u88ad\u4e86\uff01", 5, 20, 5);
                }
                EliteMobManager.spawnParticleSafe(world, Particle.SOUL_FIRE_FLAME, boss.getLocation(), 20);
                world.playSound(boss.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.6f);
                grave.remove();
            }
        }.runTaskLater(plugin, 70L);
    }

    // ==================== 召唤分裂（SummonSplit） ====================

    /** 召唤分裂：分出 2 个残影（各 30% 当前血量）协同攻击，6 秒后合体（剩余血量加回 Boss） */
    private void summonSplit(LivingEntity boss) {
        Location loc = boss.getLocation();
        World world = loc.getWorld();
        EliteMobManager.spawnParticleSafe(world, Particle.SOUL, loc.clone().add(0, 1, 0), 20);
        world.playSound(loc, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 0.8f);
        AttributeInstance maxHp = boss.getAttribute(Attribute.MAX_HEALTH);
        double splitHp = boss.getHealth() * 0.3;
        LivingEntity[] illusions = new LivingEntity[2];
        for (int i = 0; i < 2; i++) {
            Location sl = loc.clone().add(rng.nextDouble() * 2 - 1, 0, rng.nextDouble() * 2 - 1);
            LivingEntity il = (LivingEntity) world.spawnEntity(sl, boss.getType());
            il.setMetadata("boss_illusion", new FixedMetadataValue(plugin, true));
            il.setMetadata("elite_minion", new FixedMetadataValue(plugin, true));
            il.setCustomName(ChatColor.DARK_PURPLE + "\u6b8b\u5f71");
            il.setCustomNameVisible(true);
            AttributeInstance imax = il.getAttribute(Attribute.MAX_HEALTH);
            if (imax != null) {
                imax.setBaseValue(maxHp != null ? maxHp.getValue() * 0.6 : 20);
                il.setHealth(splitHp);
            }
            if (il instanceof Mob m && boss instanceof Mob bm && bm.getTarget() != null) {
                m.setTarget(bm.getTarget());
            }
            illusions[i] = il;
        }
        // 6 秒后合体
        new BukkitRunnable() {
            @Override public void run() {
                if (boss.isValid() && !boss.isDead()) {
                    double total = boss.getHealth();
                    for (LivingEntity il : illusions) {
                        if (il != null && il.isValid() && !il.isDead()) {
                            total += il.getHealth();
                            il.remove();
                        }
                    }
                    if (maxHp != null) boss.setHealth(Math.min(total, maxHp.getValue()));
                    EliteMobManager.spawnParticleSafe(world, Particle.SOUL, boss.getLocation(), 20);
                    world.playSound(boss.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1.5f, 0.8f);
                } else {
                    for (LivingEntity il : illusions) if (il != null && il.isValid()) il.remove();
                }
            }
        }.runTaskLater(plugin, 120L);
    }

    // ==================== 冰冻（AttackFreeze） ====================

    /** 冰冻玩家：强减速 + 真冰冻 + 身边漂浮冰块环绕（借鉴原版 attack_freeze）。 */
    private void freezePlayer(Player p, int ticks) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 4, true, false));
        p.setFreezeTicks(Math.min(ticks, 60)); // 真冰冻视觉
        spawnFreezeIces(p, ticks);
    }

    /** 在玩家身边生成 6 块漂浮冰块并环绕 ticks 秒后清理。 */
    private void spawnFreezeIces(Player p, int ticks) {
        cleanupFreezeIces(p.getUniqueId());
        int n = 6;
        org.bukkit.entity.Item[] arr = new org.bukkit.entity.Item[n];
        try {
            for (int i = 0; i < n; i++) {
                org.bukkit.inventory.ItemStack ice = new org.bukkit.inventory.ItemStack(Material.BLUE_ICE);
                var meta = ice.getItemMeta();
                meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "orb_id"),
                        org.bukkit.persistence.PersistentDataType.INTEGER, i);
                ice.setItemMeta(meta);
                org.bukkit.entity.Item it = p.getWorld().dropItem(p.getLocation().add(0, 1.2, 0), ice);
                it.setPickupDelay(Integer.MAX_VALUE);
                it.setGravity(false);
                it.setInvulnerable(true);
                // 标记归属，区块重载后由 onChunkLoad 一并清理，避免散落残留
                it.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "decor_owner"),
                        org.bukkit.persistence.PersistentDataType.STRING, p.getUniqueId().toString());
                arr[i] = it;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[EliteMobs] \u51b0\u5757\u53ec\u5524\u5931\u8d25: " + ex.getMessage());
            for (org.bukkit.entity.Item it : arr) if (it != null) it.remove();
            return;
        }
        freezeIces.put(p.getUniqueId(), arr);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { cleanupFreezeIces(p.getUniqueId()); cancel(); return; }
                if (++t > ticks) { cleanupFreezeIces(p.getUniqueId()); cancel(); return; }
                updateFreezeIces(p, t);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** 每 tick 让冰块绕玩家旋转。 */
    private void updateFreezeIces(Player p, int tick) {
        org.bukkit.entity.Item[] arr = freezeIces.get(p.getUniqueId());
        if (arr == null) return;
        Location center = p.getLocation().add(0, 1.2, 0);
        double speed = 0.2;
        for (int i = 0; i < arr.length; i++) {
            org.bukkit.entity.Item it = arr[i];
            if (it == null || !it.isValid()) continue;
            double a = (tick * speed + i * Math.PI * 2 / arr.length);
            Location target = center.clone().add(Math.cos(a) * 0.9, 0, Math.sin(a) * 0.9);
            Vector mv = target.toVector().subtract(it.getLocation().toVector());
            if (mv.lengthSquared() > 9) it.teleport(target);
            else it.setVelocity(mv.multiply(0.3));
        }
    }

    /** 清理指定 UUID 关联的冰冻冰块实体（冻结结束/封印登出/ /em clear 等使用） */
    public void cleanupFreezeIces(UUID uuid) {
        org.bukkit.entity.Item[] arr = freezeIces.remove(uuid);
        if (arr == null) return;
        for (org.bukkit.entity.Item it : arr) if (it != null && it.isValid()) it.remove();
    }

    // ==================== 封印（Seal） ====================

    /** 封印玩家：短时间内淬炼加成（武器攻击/护甲减伤/套装减伤）暂时失效。
     *  已封印则不重复刷新（避免 Boss 连放导致永久封印），附 ActionBar 倒计时。 */
    private void applySeal(Player p) {
        int secs = plugin.getEliteConfig().getSealDuration();
        long now = System.currentTimeMillis();
        Long cur = sealedUntil.get(p.getUniqueId());
        if (cur != null && cur > now) {
            // 已在封印中：不延长，只提示
            p.sendActionBar(ChatColor.DARK_PURPLE + "\u26d4 \u5c01\u5370\u5df2\u751f\u6548\uff0c\u7ee7\u7eed\u6218\u6597\uff01");
            return;
        }
        long until = now + secs * 1000L;
        sealedUntil.put(p.getUniqueId(), until);
        p.sendMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "\u2726 "
            + ChatColor.LIGHT_PURPLE + "\u4f60\u88ab\u5c01\u5370\u4e86\uff01\u6dec\u7ec3\u52a0\u6210\u6682\u65f6\u5931\u6548 " + secs + " \u79d2");
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.5f, 0.6f);
        EliteMobManager.spawnParticleSafe(p.getWorld(), Particle.WITCH, p.getLocation().add(0, 1, 0), 20);
        // ActionBar 倒计时：每 1 秒刷新，到期自动停止并清理
        new BukkitRunnable() {
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { sealedUntil.remove(p.getUniqueId()); cancel(); return; }
                long remain = until - System.currentTimeMillis();
                if (remain <= 0) {
                    sealedUntil.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                p.sendActionBar(ChatColor.DARK_PURPLE + "\u26d4 \u6dec\u7ec3\u88ab\u5c01\u5370 " + Math.max(1, remain / 1000) + " \u79d2");
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /** 玩家当前是否处于封印状态（EliteCombatListener 据此抵消淬炼加成）。 */
    public boolean isSealedPlayer(Player p) {
        if (p == null) return false;
        Long until = sealedUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    /** Boss死亡时清理血条与技能状态 */
    public void onBossDeath(LivingEntity entity) {
        BossBar bar = bossBars.remove(entity.getUniqueId());
        if (bar != null) bar.removeAll();
        phase2Triggered.remove(entity.getUniqueId());
        groundPoundCooldowns.remove(entity.getUniqueId());
        channelActive.remove(entity.getUniqueId());
        channelCooldown.remove(entity.getUniqueId());
        sealCooldowns.remove(entity.getUniqueId());
        // 若正在被治疗，清除标记
        for (Entity ent : entity.getNearbyEntities(32, 32, 32)) {
            ent.removeMetadata("elite_healing", plugin);
        }
    }

    /**
     * Boss 第二阶段：血量低于阈值时触发一次（强化 + 全服广播 + 血条变色 + 特效）。
     * 阈值/开关由 config boss-phase2 控制（默认 50%）。
     */
    private void checkPhase2(LivingEntity boss) {
        if (phase2Triggered.contains(boss.getUniqueId())) return;
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isBossPhase2Enabled()) return;
        double ratio = cfg.getBossPhase2HpRatio();
        AttributeInstance hp = boss.getAttribute(Attribute.MAX_HEALTH);
        if (hp == null || hp.getValue() <= 0) return;
        if (boss.getHealth() / hp.getValue() > ratio) return;
        phase2Triggered.add(boss.getUniqueId());

        String name = boss.getCustomName() != null
                ? ChatColor.stripColor(boss.getCustomName())
                : boss.getType().name().toLowerCase().replace('_', ' ');
        // 全服广播第二阶段 + 动态彩色标题（标题保持简短，Boss 名已在广播里）
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u26a1 "
            + ChatColor.RED + name + ChatColor.GOLD + " \u8fdb\u5165\u4e86\u7b2c\u4e8c\u9636\u6bb5\uff01"
            + ChatColor.DARK_RED + " \u26a1");
        StringColorAnimator.animateTitleAll(plugin,
            ChatColor.GOLD + "\u26a1 " + name + " \u8fdb\u5165\u7b2c\u4e8c\u9636\u6bb5\uff01\u26a1",
            ChatColor.RED + "\u5b83\u53d8\u5f97\u66f4\u52a0\u51f6\u731b\u4e86...",
            ChatColor.RED, ChatColor.GOLD);
        // 强化：力量 I + 速度 II + 抗性 I
        boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 999999, 0, true, false));
        boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 999999, 1, true, false));
        boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 999999, 0, true, false));
        // 特效：真闪电 + 粒子 + 音效（打标记取消引燃，避免烧毁掉落物/建筑）
        Location loc = boss.getLocation();
        org.bukkit.entity.LightningStrike ls2 = loc.getWorld().strikeLightning(loc);
        if (ls2 != null) ls2.setMetadata("elitemobs_lightning", new FixedMetadataValue(plugin, true));
        for (int i = 0; i < 40; i++) {
            EliteMobManager.spawnParticleSafe(loc.getWorld(), Particle.SOUL_FIRE_FLAME,
                loc.clone().add(rng.nextDouble() - 0.5, rng.nextDouble() * 2, rng.nextDouble() - 0.5), 1);
        }
        loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_SPAWN, 2.0f, 0.5f);
        // 血条换色（第二阶段）
        BossBar bar = bossBars.get(boss.getUniqueId());
        if (bar != null) bar.setColor(BarColor.PURPLE);
        // 二阶段开场：封印附近玩家（短时间淬炼封印）
        if (cfg.isSealEnabled()) {
            double radius = cfg.getSealRadius();
            for (Entity e : boss.getNearbyEntities(radius, radius, radius)) {
                if (e instanceof Player p && !p.hasPermission("elitemobs.bypass")) {
                    applySeal(p);
                }
            }
        }
    }
}
