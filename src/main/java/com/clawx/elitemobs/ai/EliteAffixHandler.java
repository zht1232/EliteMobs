package com.clawx.elitemobs.ai;

import com.clawx.elitemobs.EliteConfig;
import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.EliteMobsPlugin;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 精英怪词缀系统。
 * 精英怪生成时按配置随机获得 1-2 个词缀，词缀改变其战斗行为与外观：
 * 火焰/冰霜/荆棘/吸血/狂暴/分裂/瞬移/雷链。
 */
public class EliteAffixHandler implements Listener {

    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();
    // 词缀持续效果 tick 计数（按实体）
    private final Map<UUID, Integer> tickCounts = new ConcurrentHashMap<>();

    private static final String META_AFFIX = "elite_affix";
    private static final String META_BERSERK = "elite_berserk";
    private static final String META_NO_AFFIX = "elite_no_affix";

    public EliteAffixHandler(EliteMobsPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================== 应用 ====================

    /** 判定并应用词缀到精英怪（仅存元数据，名字后缀在 applyVisuals 时追加） */
    public void rollAndApply(LivingEntity e) {
        if (e == null || e.hasMetadata(META_NO_AFFIX)) return;
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isAffixEnabled()) return;
        if (rng.nextDouble() >= cfg.getAffixChance()) return;

        int min = cfg.getAffixMin(), max = cfg.getAffixMax();
        int count = min + (max > min ? rng.nextInt(max - min + 1) : 0);
        List<EliteAffix> chosen = cfg.rollAffixes(rng, count);
        if (chosen.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (EliteAffix a : chosen) {
            if (sb.length() > 0) sb.append(',');
            sb.append(a.name());
        }
        e.setMetadata(META_AFFIX, new FixedMetadataValue(plugin, sb.toString()));
    }

    /** 读取精英怪携带的词缀 */
    public static Set<EliteAffix> getAffixes(LivingEntity e) {
        Set<EliteAffix> set = new HashSet<>();
        if (e == null || !e.hasMetadata(META_AFFIX)) return set;
        String raw = e.getMetadata(META_AFFIX).get(0).asString();
        for (String s : raw.split(",")) {
            EliteAffix a = EliteAffix.fromString(s);
            if (a != null) set.add(a);
        }
        return set;
    }

    /** 在名字末尾追加词缀标记（配合应用时的名字后缀） */
    public static void appendAffixSuffix(LivingEntity e) {
        Set<EliteAffix> affixes = getAffixes(e);
        if (affixes.isEmpty() || e.getCustomName() == null) return;
        StringBuilder sb = new StringBuilder(e.getCustomName());
        for (EliteAffix a : affixes) {
            sb.append(' ').append(a.getColor()).append('[').append(a.getDisplay()).append(']');
        }
        e.setCustomName(sb.toString());
    }

    // ==================== 持续效果（由 EliteMobManager.tickAllEliteMobs 调用） ====================

    /** 每 AI tick 调用一次（内部按计数间隔触发具体效果） */
    public void tick(LivingEntity e) {
        Set<EliteAffix> affixes = getAffixes(e);
        if (affixes.isEmpty()) return;
        int tick = tickCounts.merge(e.getUniqueId(), 1, Integer::sum);

        for (EliteAffix a : affixes) {
            switch (a) {
                case FIRE_AURA -> {
                    if (tick % 2 == 0) { // 约每 20 tick
                        for (Entity ent : e.getNearbyEntities(3, 3, 3)) {
                            if (ent instanceof Player p && !p.hasPermission("elitemobs.bypass")) {
                                p.setFireTicks(Math.max(p.getFireTicks(), 40));
                            }
                        }
                        particle(e.getWorld(), Particle.FLAME, e.getLocation().add(0, 1, 0), 4, 1.5, 1, 1.5);
                    }
                }
                case BLINK -> {
                    if (tick % 5 == 0) {
                        Player target = nearestPlayer(e, 10);
                        if (target != null) {
                            Location tl = target.getLocation();
                            Location dest = tl.clone().add(tl.getDirection().multiply(-2)).setDirection(tl.getDirection());
                            e.teleport(dest);
                            particle(target.getWorld(), Particle.DRAGON_BREATH, tl, 12, 0.5, 0.5, 0.5);
                            target.getWorld().playSound(tl, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.8f);
                        }
                    }
                }
                case CHAIN -> {
                    if (tick % 3 == 0) { // 约每 30 tick
                        for (Entity ent : e.getNearbyEntities(5, 4, 5)) {
                            if (ent instanceof Player p && !p.hasPermission("elitemobs.bypass")) {
                                p.damage(2.0, e);
                            }
                        }
                        particle(e.getWorld(), Particle.ELECTRIC_SPARK, e.getLocation().add(0, 1, 0), 6, 2, 1, 2);
                        e.getWorld().playSound(e.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.5f);
                    }
                }
                case BERSERK -> {
                    if (!e.hasMetadata(META_BERSERK)) {
                        AttributeInstance maxHp = e.getAttribute(Attribute.MAX_HEALTH);
                        if (maxHp != null && maxHp.getValue() > 0 && e.getHealth() / maxHp.getValue() < 0.3) {
                            e.setMetadata(META_BERSERK, new FixedMetadataValue(plugin, true));
                            AttributeInstance dmg = e.getAttribute(Attribute.ATTACK_DAMAGE);
                            if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * 1.5);
                            particle(e.getWorld(), Particle.CRIT, e.getLocation().add(0, 1, 0), 20, 1, 1, 1);
                            e.getWorld().playSound(e.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.5f);
                        }
                    }
                }
                default -> {}
            }
        }
    }

    // ==================== 命中效果（监听器） ====================

    /** 精英攻击玩家：冰霜减速 + 吸血回血 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEliteHitPlayer(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LivingEntity elite) || !EliteMobManager.isElite(elite)) return;
        if (!(event.getEntity() instanceof Player p) || p.hasPermission("elitemobs.bypass")) return;
        Set<EliteAffix> affixes = getAffixes(elite);
        if (affixes.isEmpty()) return;

        if (affixes.contains(EliteAffix.FROST)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, true, false));
            particle(p.getWorld(), Particle.SNOWFLAKE, p.getLocation().add(0, 1, 0), 6, 0.4, 0.4, 0.4);
        }
        if (affixes.contains(EliteAffix.LIFESTEAL)) {
            AttributeInstance maxHp = elite.getAttribute(Attribute.MAX_HEALTH);
            if (maxHp != null) {
                double heal = Math.min(elite.getHealth() + 2.0, maxHp.getValue());
                elite.setHealth(heal);
                particle(elite.getWorld(), Particle.HEART, elite.getLocation().add(0, 1.5, 0), 3, 0.3, 0.3, 0.3);
            }
        }
    }

    /** 玩家近战攻击精英：荆棘反伤 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerHitElite(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p) || p.hasPermission("elitemobs.bypass")) return;
        if (!(event.getEntity() instanceof LivingEntity elite) || !EliteMobManager.isElite(elite)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!getAffixes(elite).contains(EliteAffix.THORNS)) return;
        p.damage(3.0, elite);
        particle(p.getWorld(), Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 4, 0.3, 0.3, 0.3);
    }

    // ==================== 死亡分裂（由 EliteCombatListener 调用） ====================

    /** 精英死亡：分裂词缀生成小精英；清理 tick 计数 */
    public void onDeath(LivingEntity e) {
        tickCounts.remove(e.getUniqueId());
        if (!getAffixes(e).contains(EliteAffix.SPLIT)) return;

        int level = EliteMobManager.getEliteLevel(e);
        int count = 2 + rng.nextInt(2); // 2-3 个分身
        for (int i = 0; i < count; i++) {
            Location loc = e.getLocation().clone().add(rng.nextDouble() * 2 - 1, 0, rng.nextDouble() * 2 - 1);
            Entity ent = e.getWorld().spawnEntity(loc, e.getType());
            if (!(ent instanceof LivingEntity mini)) continue;
            // 分身不再带词缀，避免无限分裂
            mini.setMetadata(META_NO_AFFIX, new FixedMetadataValue(plugin, true));
            mini.setMetadata("elite_minion", new FixedMetadataValue(plugin, true));
            plugin.getMobManager().makeElite(mini, Math.max(1, level / 2));
            AttributeInstance maxHp = mini.getAttribute(Attribute.MAX_HEALTH);
            if (maxHp != null) mini.setHealth(maxHp.getValue() * 0.4);
        }
        particle(e.getWorld(), Particle.SOUL, e.getLocation(), 20, 1, 1, 1);
    }

    // ==================== 工具 ====================

    private Player nearestPlayer(LivingEntity e, double range) {
        Player best = null;
        double bestDist = range;
        for (Entity ent : e.getNearbyEntities(range, range, range)) {
            if (ent instanceof Player p && !p.hasPermission("elitemobs.bypass")) {
                double d = p.getLocation().distance(e.getLocation());
                if (d < bestDist) { bestDist = d; best = p; }
            }
        }
        return best;
    }

    /**
     * 安全生成粒子：兼容不同服务端版本的粒子数据要求（如 Paper 26.2 某些粒子
     * 在 7 参数重载下会抛 IllegalArgumentException），出错时静默降级，避免刷屏。
     */
    private static void particle(World w, Particle p, Location loc, int count, double ox, double oy, double oz) {
        if (w == null || loc == null) return;
        try {
            w.spawnParticle(p, loc, count, ox, oy, oz, 0.0);
        } catch (Throwable t) {
            try { w.spawnParticle(p, loc, count, ox, oy, oz); } catch (Throwable ignored) {}
        }
    }
}
