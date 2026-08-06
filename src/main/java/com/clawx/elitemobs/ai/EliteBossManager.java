package com.clawx.elitemobs.ai;

import com.clawx.elitemobs.EliteConfig;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 精英Boss系统。
 * 高等级精英怪（Lv.15+）有概率晋升为Boss，拥有：
 * - 巨型血量（3x）
 * - Boss血条（所有附近玩家可见）
 * - 特殊技能（冲击波/治愈/召唤）
 * - 专属掉落
 */
public class EliteBossManager {
    private final EliteMobsPlugin plugin;
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Random rng = new Random();
    // 已触发第二阶段的 Boss（每只 Boss 仅触发一次）
    private final Set<UUID> phase2Triggered = ConcurrentHashMap.newKeySet();

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

        // 3倍血量
        AttributeInstance hp = entity.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            double newHp = hp.getBaseValue() * 3.0;
            hp.setBaseValue(newHp);
            entity.setHealth(newHp);
        }

        // 体型放大
        entity.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 999999, 2, false, false));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 999999, 1, false, false));

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

        // 全服广播（含坐标）
        String announce = ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u2620 "
            + ChatColor.RED + "" + ChatColor.BOLD + "Boss\u8b66\u62a5\uff01"
            + ChatColor.GRAY + " \u2014 " + bossName + ChatColor.GRAY + " \u5728 "
            + ChatColor.WHITE + loc.getWorld().getName()
            + ChatColor.GRAY + " \u5750\u6807 ("
            + ChatColor.YELLOW + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
            + ChatColor.GRAY + ") \u964d\u751f\u4e86\uff01";
        Bukkit.broadcastMessage(announce);
    }

    public static boolean isBoss(LivingEntity entity) {
        if (entity == null) return false;
        if (entity.hasMetadata("elite_boss")) return true;
        return entity.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("elitemobs", "elite_boss"),
                org.bukkit.persistence.PersistentDataType.BOOLEAN);
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

                    // Boss技能（每5秒）
                    if (tick % 100 == 0) {
                        bossAbility(le);
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 1L);
    }

    /** Boss 特殊技能 */
    private void bossAbility(LivingEntity boss) {
        Location loc = boss.getLocation();
        int ability = rng.nextInt(3);

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
                    AttributeInstance mhp = minion.getAttribute(Attribute.MAX_HEALTH);
                    if (mhp != null) { mhp.setBaseValue(mhp.getBaseValue() * 2); minion.setHealth(mhp.getBaseValue()); }
                    if (minion instanceof Mob m && boss instanceof Mob bm && bm.getTarget() != null) {
                        m.setTarget(bm.getTarget());
                    }
                }
                EliteMobManager.spawnParticleSafe(boss.getWorld(), Particle.SOUL, loc.clone().add(0, 0.5, 0), 15);
                boss.getWorld().playSound(loc, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2.0f, 0.8f);
            }
        }
    }

    /** Boss死亡时清理血条 */
    public void onBossDeath(LivingEntity entity) {
        BossBar bar = bossBars.remove(entity.getUniqueId());
        if (bar != null) bar.removeAll();
        phase2Triggered.remove(entity.getUniqueId());
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
        // 全服广播第二阶段
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "\u26a1 "
            + ChatColor.RED + name + ChatColor.GOLD + " \u8fdb\u5165\u4e86\u7b2c\u4e8c\u9636\u6bb5\uff01"
            + ChatColor.DARK_RED + " \u26a1");
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
    }
}
