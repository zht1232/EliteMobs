package com.clawx.elitemobs.ai;

import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;

/**
 * 精英怪职业 AI 行为。
 * 坦克：减伤+嘲讽  刺客：隐身+暴击  法师：远程火球+药水  召唤师：召唤小怪
 */
public class EliteClassAI implements Listener {
    private final EliteMobsPlugin plugin;
    private static final Random rng = new Random();
    // 坦克飞绕不死图腾（Item 掉落物实体 + velocity 平滑推进，跟随怪物移动不卡顿）
    private final Map<UUID, org.bukkit.entity.Item[]> tankDisplays = new java.util.concurrent.ConcurrentHashMap<>();
    // 法师飞绕附魔书（Item 实体 + 原子核式多轨道公转，velocity 平滑推进，性能好）
    private final Map<UUID, org.bukkit.entity.Item[]> mageBooks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, BookOrbit[]> mageBookOrbits = new java.util.concurrent.ConcurrentHashMap<>();

    public EliteClassAI(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        startClassTick();
    }

    /** 给精英怪分配职业并应用属性 */
    public void applyClass(LivingEntity entity, int level, EliteClass eliteClass) {
        entity.setMetadata("elite_class", new FixedMetadataValue(plugin, eliteClass.name()));
        // PDC 持久化：metadata 不跨 chunk，区块重载后职业不丢失
        entity.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "elite_class"),
                org.bukkit.persistence.PersistentDataType.STRING, eliteClass.name());

        switch (eliteClass) {
            case TANK -> {
                // 坦克：2倍血量，击退抗性
                AttributeInstance hp = entity.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) { hp.setBaseValue(hp.getBaseValue() * 1.5); entity.setHealth(hp.getBaseValue()); }
                AttributeInstance kb = entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
                if (kb != null) kb.setBaseValue(1.0);
            }
            case ASSASSIN -> {
                // 刺客：速度提升
                AttributeInstance spd = entity.getAttribute(Attribute.MOVEMENT_SPEED);
                if (spd != null) spd.setBaseValue(spd.getBaseValue() * 1.4);
            }
            case MAGE -> {
                // 法师：抗性提升
                entity.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 999999, 1, false, false));
            }
            case SUMMONER -> {
                // 召唤师：额外血量
                AttributeInstance hp = entity.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) { hp.setBaseValue(hp.getBaseValue() * 1.3); entity.setHealth(hp.getBaseValue()); }
            }
        }
    }

    public static EliteClass getEliteClass(LivingEntity entity) {
        if (entity == null) return null;
        String name = null;
        if (entity.hasMetadata("elite_class")) name = entity.getMetadata("elite_class").get(0).asString();
        if (name == null) {
            name = entity.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey("elitemobs", "elite_class"),
                    org.bukkit.persistence.PersistentDataType.STRING);
        }
        if (name == null) return null;
        try { return EliteClass.valueOf(name); } catch (Exception e) { return null; }
    }

    /** 每 tick 处理职业行为 */
    private void startClassTick() {
        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                tick++;
                for (EliteMobManager.EliteMobData data : plugin.getMobManager().getEliteMobs()) {
                    LivingEntity e = data.entity;
                    if (e == null || e.isDead() || !e.isValid()) {
                        if (e != null) {
                            cleanupTankDisplays(e.getUniqueId());
                            cleanupMageBooks(e.getUniqueId());
                        }
                        continue;
                    }
                    EliteClass cls = getEliteClass(e);
                    if (cls == null) continue;

                    Location loc = e.getLocation();
                    World world = e.getWorld();

                    // 坦克飞绕盾牌（每tick跟随旋转，无论是否锁定目标都显示）
                    if (cls == EliteClass.TANK) {
                        ensureTankDisplays(e);
                        updateTankDisplays(e, tick);
                    }
                    // 法师飞绕附魔书（每tick随机飞舞）
                    if (cls == EliteClass.MAGE) {
                        ensureMageBooks(e);
                        updateMageBooks(e, tick);
                    }

                    // 职业专属粒子特效（每3tick，绑定实体坐标）
                    if (tick % 3 == 0) {
                        switch (cls) {
                            case MAGE -> drawHexagram(world, e, tick);
                            case TANK -> drawShieldRing(world, e, tick);
                            case ASSASSIN -> drawShadowTrail(world, e, tick);
                            case SUMMONER -> drawDarkSwirl(world, e, tick);
                        }
                    }

                    // Boss额外特效
                    if (com.clawx.elitemobs.ai.EliteBossManager.isBoss(e) && tick % 10 == 0) {
                        drawBossAura(world, e, tick);
                    }

                    if (!(e instanceof Mob mob) || mob.getTarget() == null) continue;

                    // 骷髅/流浪者精英：主动速射（每 1 秒射击，远程压制）
                    if ((e.getType() == EntityType.SKELETON || e.getType() == EntityType.STRAY)
                            && tick % 20 == 0) {
                        double d = e.getLocation().distance(mob.getTarget().getLocation());
                        if (d >= 4 && d <= 24) rapidFireSkeleton(e, mob.getTarget());
                    }

                    // 怪物专属技能（按类型特征）
                    mobUniqueSkill(e, mob, tick);

                    switch (cls) {
                        case ASSASSIN -> {
                            // 刺客：间歇隐身
                            if (tick % 60 == 0) {
                                if (e.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                                    e.removePotionEffect(PotionEffectType.INVISIBILITY);
                                } else {
                                    e.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false));
                                }
                            }
                        }
                        case MAGE -> {
                            // 法师：每3秒发射火球或扔药水
                            if (tick % 60 == 0 && mob.getTarget() != null) {
                                LivingEntity target = mob.getTarget();
                                double dist = target.getLocation().distance(loc);
                                if (dist < 20 && dist > 3) {
                                    if (rng.nextBoolean()) {
                                        // 小火球（设置shooter归属法师）
                                        SmallFireball fb = e.launchProjectile(SmallFireball.class);
                                        fb.setShooter(e);
                                        fb.setYield(1.0f);
                                    } else {
                                        // 扔喷溅药水（只留虚弱，去掉减速）
                                        ThrownPotion potion = e.launchProjectile(ThrownPotion.class);
                                        potion.setShooter(e);
                                        org.bukkit.inventory.ItemStack potionItem = new org.bukkit.inventory.ItemStack(Material.SPLASH_POTION);
                                        org.bukkit.inventory.meta.PotionMeta pm = (org.bukkit.inventory.meta.PotionMeta) potionItem.getItemMeta();
                                        pm.addCustomEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0), true);
                                        potion.setItem(potionItem);
                                    }
                                }
                            }
                        }
                        case SUMMONER -> {
                            // 召唤师：每8秒召唤小怪（每只召唤师最多3只）
                            if (tick % 160 == 0) {
                                int myMinions = 0;
                                UUID summonerId = e.getUniqueId();
                                for (Entity ent : e.getNearbyEntities(16, 16, 16)) {
                                    if (ent.hasMetadata("elite_minion_owner")
                                        && ent.getMetadata("elite_minion_owner").get(0).asString().equals(summonerId.toString())) {
                                        myMinions++;
                                    }
                                }
                                if (myMinions < 3) {
                                    EntityType minionType = rng.nextBoolean() ? EntityType.SILVERFISH : EntityType.ENDERMITE;
                                    Location spawnLoc = loc.clone().add(rng.nextDouble() * 4 - 2, 0, rng.nextDouble() * 4 - 2);
                                    // 召唤光柱特效（窜天粒子，持续3秒）
                                    drawSummonPillar(e.getWorld(), spawnLoc, plugin);
                                    LivingEntity minion = (LivingEntity) e.getWorld().spawnEntity(spawnLoc, minionType);
                                    minion.setMetadata("elite_minion", new FixedMetadataValue(plugin, true));
                                    minion.setMetadata("elite_minion_owner", new FixedMetadataValue(plugin, summonerId.toString()));
                                    // PDC 持久化：metadata 不跨 chunk，区块重载后仍可识别为精英召唤物
                                    minion.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "elite_minion"),
                                            org.bukkit.persistence.PersistentDataType.BOOLEAN, true);
                                    if (minion instanceof Mob m && mob.getTarget() != null) {
                                        m.setTarget(mob.getTarget());
                                    }
                                    e.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, spawnLoc.clone().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0);
                                    e.getWorld().playSound(spawnLoc, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 1.0f);
                                }
                            }
                        }
                        case TANK -> {
                            // 坦克：对附近玩家施加减速（嘲讽光环）
                            if (tick % 40 == 0) {
                                for (Entity ent : e.getNearbyEntities(4, 4, 4)) {
                                    if (ent instanceof Player p && !p.hasPermission("elitemobs.bypass")) {
                                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, true, false));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 3L);
    }

    // ==================== 战斗事件 ====================

    /** 坦克减伤 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTankDamageReduce(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity e)) return;
        if (!EliteMobManager.isElite(e)) return;
        EliteClass cls = getEliteClass(e);
        if (cls == EliteClass.TANK) {
            event.setDamage(event.getDamage() * 0.5); // 50%减伤
        }
    }

    /** 刺客暴击（仅限近战伤害） */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAssassinCrit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LivingEntity e)) return;
        if (!EliteMobManager.isElite(e)) return;
        // 只对直接近战攻击触发暴击（排除火球/药水等投射物）
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        EliteClass cls = getEliteClass(e);
        if (cls == EliteClass.ASSASSIN && rng.nextDouble() < 0.3) {
            event.setDamage(event.getDamage() * 2.0); // 30%暴击率，2倍伤害
            e.getWorld().spawnParticle(Particle.CRIT, event.getEntity().getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0);
            e.getWorld().playSound(e.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.2f);
        }
    }

    /** 召唤师死亡时清理小怪 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSummonerDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!e.hasMetadata("elite_class")) return;
        EliteClass cls = getEliteClass(e);
        if (cls != EliteClass.SUMMONER) return;

        String ownerId = e.getUniqueId().toString();
        for (Entity ent : e.getNearbyEntities(16, 16, 16)) {
            if (ent.hasMetadata("elite_minion_owner")
                && ent.getMetadata("elite_minion_owner").get(0).asString().equals(ownerId)) {
                // 小怪失去主人后受到伤害并消失
                ent.getWorld().spawnParticle(Particle.SMOKE, ent.getLocation().add(0, 0.5, 0), 5, 0.2, 0.2, 0.2, 0);
                ent.remove();
            }
        }
    }

    // ==================== 骷髅速射 ====================

    /** 骷髅精英主动速射：瞄准目标发射箭矢（比原版骷髅 AI 更频繁的远程压制）。 */
    private void rapidFireSkeleton(LivingEntity e, LivingEntity target) {
        try {
            Location from = e.getLocation().add(0, e.getHeight() * 0.8, 0);
            Location to = target.getLocation().add(0, target.getHeight() * 0.5, 0);
            org.bukkit.entity.Arrow arrow = e.launchProjectile(org.bukkit.entity.Arrow.class);
            arrow.setShooter(e);
            org.bukkit.util.Vector dir = to.toVector().subtract(from.toVector()).normalize();
            double dist = from.distance(to);
            // 抛物线补偿 + 等级伤害加成
            arrow.setVelocity(dir.multiply(1.9).setY(dir.getY() + dist * 0.03));
            arrow.setDamage(arrow.getDamage() + EliteMobManager.getEliteLevel(e) * 0.3);
            arrow.setKnockbackStrength(1);
            e.getWorld().playSound(from, org.bukkit.Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.9f);
        } catch (Exception ignored) {}
    }

    // ==================== 怪物专属技能（按类型特征） ====================

    /** 各种怪物按自身特征周期性释放专属技能（蜘蛛蛛网/毒、僵尸召唤、凋零骷髅凋零等）。 */
    private void mobUniqueSkill(LivingEntity e, Mob mob, int tick) {
        EntityType t = e.getType();
        LivingEntity target = mob.getTarget();
        if (target == null) return;
        boolean bypass = target instanceof Player p && p.hasPermission("elitemobs.bypass");
        double dist = e.getLocation().distance(target.getLocation());
        switch (t) {
            case SPIDER, CAVE_SPIDER -> { // 蜘蛛：蛛网困敌 + 投毒
                if (tick % 120 == 0) {
                    if (rng.nextBoolean() && !bypass) {
                        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1, false, false));
                        EliteMobManager.spawnParticleSafe(e.getWorld(), Particle.ITEM_SLIME,
                                target.getLocation().add(0, 1, 0), 8);
                        e.getWorld().playSound(e.getLocation(), Sound.ENTITY_SPIDER_HURT, 1.0f, 0.8f);
                    } else {
                        shootWebAt(e, target);
                    }
                }
            }
            case ZOMBIE, HUSK, DROWNED -> { // 僵尸：周期性召唤尸群
                if (tick % 240 == 0 && !bypass) {
                    int count = 1 + rng.nextInt(2);
                    for (int i = 0; i < count; i++) {
                        Location sl = e.getLocation().clone().add(rng.nextDouble() * 4 - 2, 0, rng.nextDouble() * 4 - 2);
                        LivingEntity mini = (LivingEntity) e.getWorld().spawnEntity(sl, EntityType.ZOMBIE);
                        mini.setMetadata("elite_minion", new FixedMetadataValue(plugin, true));
                        mini.setMetadata("elite_minion_owner", new FixedMetadataValue(plugin, e.getUniqueId().toString()));
                        // PDC 持久化：metadata 不跨 chunk，区块重载后仍可识别为精英召唤物
                        mini.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "elite_minion"),
                                org.bukkit.persistence.PersistentDataType.BOOLEAN, true);
                        if (mini instanceof Mob m) m.setTarget(target);
                    }
                    drawSummonPillar(e.getWorld(), e.getLocation(), plugin);
                }
            }
            case WITHER_SKELETON -> { // 凋零骷髅：周期给附近玩家凋零
                if (tick % 100 == 0 && !bypass && dist < 6) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 0, false, false));
                    EliteMobManager.spawnParticleSafe(e.getWorld(), Particle.SMOKE, target.getLocation().add(0, 1, 0), 6);
                }
            }
            case ENDERMAN -> { // 末影人：闪现到目标背后偷袭
                if (tick % 80 == 0 && !bypass && dist > 3 && dist < 16) {
                    Location dest = target.getLocation().clone().add(target.getLocation().getDirection().multiply(-2));
                    e.teleport(dest);
                    e.getWorld().playSound(e.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    EliteMobManager.spawnParticleSafe(e.getWorld(), Particle.PORTAL, dest, 20);
                }
            }
            case CREEPER -> { // 苦力怕：近身点燃引信
                if (dist < 3 && tick % 60 == 0 && e instanceof Creeper c) {
                    c.setIgnited(true);
                    EliteMobManager.spawnParticleSafe(e.getWorld(), Particle.SMOKE, e.getLocation(), 10);
                }
            }
            case BLAZE -> { // 烈焰人：额外火焰弹连射
                if (tick % 60 == 0 && !bypass && dist < 24) {
                    SmallFireball fb = e.launchProjectile(SmallFireball.class);
                    fb.setShooter(e);
                }
            }
            case WITCH -> { // 女巫：投掷剧毒药水
                if (tick % 100 == 0 && !bypass && dist < 16) {
                    ThrownPotion potion = e.launchProjectile(ThrownPotion.class);
                    potion.setShooter(e);
                    org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(Material.SPLASH_POTION);
                    org.bukkit.inventory.meta.PotionMeta pm = (org.bukkit.inventory.meta.PotionMeta) it.getItemMeta();
                    pm.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 100, 1), true);
                    potion.setItem(it);
                }
            }
            default -> {}
        }
    }

    /** 蜘蛛：向目标脚下喷临时蛛网（4 秒后还原）。 */
    private void shootWebAt(LivingEntity e, LivingEntity target) {
        try {
            World w = target.getWorld();
            Location loc = target.getLocation();
            org.bukkit.block.Block b = w.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            Material old = b.getType();
            org.bukkit.block.data.BlockData oldData = b.getBlockData();
            if (old == Material.COBWEB || old != Material.AIR) return; // 只在空气位置放网
            // 保护区检查：受保护区域内不放蛛网（WorldGuard/GriefPrevention/Towny/Factions）
            if (com.clawx.elitemobs.compat.ProtectionHook.isProtected(loc)) return;
            b.setType(Material.COBWEB);
            w.playSound(loc, Sound.BLOCK_COBWEB_PLACE, 1.0f, 0.8f);
            EliteMobManager.spawnParticleSafe(w, Particle.ITEM_SNOWBALL, loc, 6);
            new BukkitRunnable() {
                @Override public void run() {
                    if (b.getType() == Material.COBWEB) {
                        b.setType(old);
                        b.setBlockData(oldData);
                    }
                }
            }.runTaskLater(plugin, 80L); // 4 秒后还原
        } catch (Exception ignored) {}
    }

    // ==================== 粒子特效绘制（每tick获取实体最新位置） ====================

    /** 法师：六芒星法阵（贴地固定朝向，不旋转） */
    public static void drawHexagram(World world, Entity entity, int tick) {
        double radius = 1.2;
        Location loc = entity.getLocation();
        double cx = loc.getX(), cy = loc.getY() + 0.05, cz = loc.getZ();

        // 六芒星6个顶点（固定朝向，去掉旋转偏移，避免转动显得杂乱）
        double[] px = new double[6], pz = new double[6];
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI / 3;
            px[i] = cx + Math.cos(angle) * radius;
            pz[i] = cz + Math.sin(angle) * radius;
        }
        // 正三角 + 倒三角
        drawLine(world, cy, px[0], pz[0], px[2], pz[2], 10);
        drawLine(world, cy, px[2], pz[2], px[4], pz[4], 10);
        drawLine(world, cy, px[4], pz[4], px[0], pz[0], 10);
        drawLine(world, cy, px[1], pz[1], px[3], pz[3], 10);
        drawLine(world, cy, px[3], pz[3], px[5], pz[5], 10);
        drawLine(world, cy, px[5], pz[5], px[1], pz[1], 10);
    }

    private static void drawLine(World world, double y, double x1, double z1, double x2, double z2, int points) {
        for (int i = 0; i < points; i++) {
            double t = (double) i / points;
            world.spawnParticle(org.bukkit.Particle.DUST, x1 + (x2 - x1) * t, y, z1 + (z2 - z1) * t, 1, 0, 0, 0, 0,
                new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(180, 100, 255), 1.2f));
        }
    }

    /** 坦克：底部护盾环（配合 Item 掉落物飞绕盾牌） */
    public static void drawShieldRing(World world, Entity entity, int tick) {
        Location loc = entity.getLocation();
        double cx = loc.getX(), by = loc.getY(), cz = loc.getZ();
        for (int i = 0; i < 12; i++) {
            double a = (i * Math.PI * 2 / 12) + (tick * 0.05);
            world.spawnParticle(org.bukkit.Particle.WAX_ON, cx + Math.cos(a) * 0.9, by + 0.15, cz + Math.sin(a) * 0.9, 1, 0, 0, 0, 0);
        }
    }

    // ==================== 坦克飞绕不死图腾（Item 掉落物实体） ====================

    /** 为坦克创建 6 个飞绕不死图腾（Item 掉落物：不可拾取、无重力、无敌，跟随怪物） */
    private void ensureTankDisplays(LivingEntity e) {
        if (tankDisplays.containsKey(e.getUniqueId())) return;
        int n = 6;
        org.bukkit.entity.Item[] arr = new org.bukkit.entity.Item[n];
        try {
            for (int i = 0; i < n; i++) {
                org.bukkit.inventory.ItemStack totem = new org.bukkit.inventory.ItemStack(Material.TOTEM_OF_UNDYING);
                var meta = totem.getItemMeta();
                meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "orb_id"),
                        org.bukkit.persistence.PersistentDataType.INTEGER, i);
                totem.setItemMeta(meta);
                org.bukkit.entity.Item it = e.getWorld().dropItem(e.getLocation().add(0, 1.0, 0), totem);
                it.setPickupDelay(Integer.MAX_VALUE); // 不可拾取
                it.setGravity(false);
                it.setInvulnerable(true);
                // 标记归属：区块重载后据此识别装饰物并清理/重建，避免散落成可拾取掉落物
                it.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "decor_owner"),
                        org.bukkit.persistence.PersistentDataType.STRING, e.getUniqueId().toString());
                arr[i] = it;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[EliteMobs] \u5766\u514b\u98de\u7ed5\u56fe\u817e\u521b\u5efa\u5931\u8d25: " + ex.getMessage());
            for (org.bukkit.entity.Item it : arr) if (it != null) it.remove();
            return;
        }
        tankDisplays.put(e.getUniqueId(), arr);
        plugin.getLogger().info("[EliteMobs] \u5df2\u4e3a\u5766\u514b " + e.getType().name() + " \u521b\u5efa " + n + " \u4e2a\u98de\u7ed5\u56fe\u817e");
    }

    /** 每 tick 更新坦克飞绕图腾（绕身体平滑旋转 + 跟随怪物移动，velocity 推进防卡顿） */
    private void updateTankDisplays(LivingEntity e, int tick) {
        org.bukkit.entity.Item[] arr = tankDisplays.get(e.getUniqueId());
        if (arr == null) return;
        double scale = getEntityScale(e);          // Boss 放大后环绕半径/高度同步放大
        double r = 1.2 * scale;
        double yOff = 1.0 * scale;
        Location center = e.getLocation().add(0, yOff, 0);
        double speed = 0.15;
        for (int i = 0; i < arr.length; i++) {
            org.bukkit.entity.Item it = arr[i];
            if (it == null || !it.isValid()) continue;
            double a = (tick * speed + i * Math.PI * 2 / arr.length);
            Location target = center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            org.bukkit.util.Vector mv = target.toVector().subtract(it.getLocation().toVector());
            if (mv.lengthSquared() > 9) { // 怪物瞬移/距离过大直接传送，避免拉出长线
                it.teleport(target);
            } else {
                it.setVelocity(mv.multiply(0.3)); // 向目标平滑推进 30%
            }
        }
    }

    /** 清理坦克飞绕图腾（死亡/失效时移除实体） */
    private void cleanupTankDisplays(UUID uuid) {
        org.bukkit.entity.Item[] arr = tankDisplays.remove(uuid);
        if (arr == null) return;
        for (org.bukkit.entity.Item it : arr) if (it != null && it.isValid()) it.remove();
    }

    /** 清理指定归属者的全部装饰物（坦克图腾/法师书），供 /em clear 等命令使用 */
    public void cleanupDisplaysFor(java.util.UUID owner) {
        cleanupTankDisplays(owner);
        cleanupMageBooks(owner);
    }

    /** 读取实体 SCALE 属性（Boss 放大后环绕半径/高度随之放大） */
    private double getEntityScale(LivingEntity e) {
        try {
            AttributeInstance sc = e.getAttribute(Attribute.SCALE);
            if (sc != null) return Math.max(0.5, sc.getValue());
        } catch (Exception ignored) {}
        return 1.0;
    }

    /** 精英死亡时清理坦克飞绕盾牌与法师飞绕附魔书 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyEliteDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        cleanupTankDisplays(event.getEntity().getUniqueId());
        cleanupMageBooks(event.getEntity().getUniqueId());
    }

    /**
     * 区块加载时恢复：清理散落的装饰物（坦克图腾/法师书，区块重载后 Item 属性丢失会变成可拾取掉落物），
     * 并重新注册该区块的精英（恢复等级/职业装饰/Boss 血条管理），避免 Boss 丢失、装饰散落一地。
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        org.bukkit.Chunk chunk = event.getChunk();
        if (chunk == null) return;
        // 1) 清理散落的装饰物（带归属标记的 Item 或 boss_decor 标记的 Item）：同步清理内存 Map，存活的精英由下方重新注册后重建
        for (Entity ent : chunk.getEntities()) {
            if (!(ent instanceof org.bukkit.entity.Item it)) continue;
            String ownerStr = it.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey("elitemobs", "decor_owner"),
                    org.bukkit.persistence.PersistentDataType.STRING);
            boolean isBossDecor = it.getPersistentDataContainer().has(
                    new org.bukkit.NamespacedKey("elitemobs", "boss_decor"),
                    org.bukkit.persistence.PersistentDataType.BOOLEAN);
            if (ownerStr != null) {
                it.remove();
                try {
                    java.util.UUID oid = java.util.UUID.fromString(ownerStr);
                    tankDisplays.remove(oid);
                    mageBooks.remove(oid);
                    plugin.getBossManager().cleanupFreezeIcesForChunk(oid);
                } catch (Exception ignored) {}
            } else if (isBossDecor) {
                it.remove();
            }
        }
        // 2) 重新注册该区块的精英（恢复管理状态）
        plugin.getMobManager().revalidateChunk(chunk);
    }

    // ==================== 法师飞绕附魔书（原子核式多轨道公转） ====================

    /** 单本附魔书的原子轨道参数：半径 / 轨道倾角 / 公转相位 / 公转速度 / 轨道进动速度 */
    private static final class BookOrbit {
        final double radius, tilt, phase, angularSpeed, precessionSpeed;
        double precession;
        BookOrbit(double radius, double tilt, double phase, double angularSpeed, double precessionSpeed) {
            this.radius = radius;
            this.tilt = tilt;
            this.phase = phase;
            this.angularSpeed = angularSpeed;
            this.precessionSpeed = precessionSpeed;
            this.precession = rng.nextDouble() * Math.PI * 2;
        }
    }

    /** 为法师创建 6 本附魔书（3 条原子轨道 × 每轨道 2 个电子，像电子绕原子核） */
    private void ensureMageBooks(LivingEntity e) {
        if (mageBooks.containsKey(e.getUniqueId())) return;
        int n = 6;
        org.bukkit.entity.Item[] arr = new org.bukkit.entity.Item[n];
        BookOrbit[] orbits = new BookOrbit[n];
        try {
            // 3 条轨道：水平 / 倾斜50° / 倾斜-50°，半径递增；每条轨道 2 个电子（相位差 π）
            double[] radii = {1.1, 1.7, 2.3};
            double[] tilts = {0, Math.toRadians(50), Math.toRadians(-50)};
            double[] speeds = {0.06, 0.045, 0.03};
            double[] precessions = {0.012, -0.008, 0.006};
            for (int i = 0; i < n; i++) {
                int orbit = i / 2;
                double phase = (i % 2) * Math.PI;
                orbits[i] = new BookOrbit(radii[orbit], tilts[orbit], phase, speeds[orbit], precessions[orbit]);
                org.bukkit.inventory.ItemStack book = new org.bukkit.inventory.ItemStack(Material.ENCHANTED_BOOK);
                var meta = book.getItemMeta();
                meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "orb_id"),
                        org.bukkit.persistence.PersistentDataType.INTEGER, i);
                book.setItemMeta(meta);
                org.bukkit.entity.Item it = e.getWorld().dropItem(e.getLocation().add(0, 1, 0), book);
                it.setPickupDelay(Integer.MAX_VALUE);
                it.setGravity(false);
                it.setInvulnerable(true);
                // 标记归属：区块重载后据此识别装饰物并清理/重建
                it.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "decor_owner"),
                        org.bukkit.persistence.PersistentDataType.STRING, e.getUniqueId().toString());
                arr[i] = it;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[EliteMobs] \u6cd5\u5e08\u98de\u7ed5\u9644\u9b54\u4e66\u521b\u5efa\u5931\u8d25: " + ex.getMessage());
            for (org.bukkit.entity.Item it : arr) if (it != null) it.remove();
            return;
        }
        mageBooks.put(e.getUniqueId(), arr);
        mageBookOrbits.put(e.getUniqueId(), orbits);
    }

    /** 每 tick 更新法师附魔书：沿各自倾斜轨道公转 + 轨道进动，形成"电子云"原子核效果 */
    private void updateMageBooks(LivingEntity e, int tick) {
        org.bukkit.entity.Item[] arr = mageBooks.get(e.getUniqueId());
        BookOrbit[] orbits = mageBookOrbits.get(e.getUniqueId());
        if (arr == null || orbits == null) return;
        double scale = getEntityScale(e);          // Boss 放大后原子核轨道同步放大
        double centerY = 1.4 * scale;
        Location base = e.getLocation().clone().add(0, centerY, 0);
        for (int i = 0; i < arr.length; i++) {
            org.bukkit.entity.Item it = arr[i];
            if (it == null || !it.isValid()) continue;
            BookOrbit o = orbits[i];
            o.precession += o.precessionSpeed;     // 轨道整体缓慢进动
            double r = o.radius * scale;
            // 电子在轨道局部圆上的角位置（公转）
            double theta = o.phase + tick * o.angularSpeed;
            double x0 = Math.cos(theta) * r;
            double z0 = Math.sin(theta) * r;
            // 绕 X 轴倾斜（让轨道平面倾斜）
            double y1 = -z0 * Math.sin(o.tilt);
            double z1 = z0 * Math.cos(o.tilt);
            // 绕 Y 轴进动（轨道平面整体旋转）
            double cosP = Math.cos(o.precession), sinP = Math.sin(o.precession);
            double x2 = x0 * cosP + z1 * sinP;
            double z2 = -x0 * sinP + z1 * cosP;
            Location target = base.clone().add(x2, y1, z2);
            org.bukkit.util.Vector mv = target.toVector().subtract(it.getLocation().toVector());
            if (mv.lengthSquared() > 9) {
                it.teleport(target);
            } else {
                it.setVelocity(mv.multiply(0.3));  // 向轨道目标平滑推进
            }
        }
    }

    /** 清理法师飞绕附魔书 */
    private void cleanupMageBooks(UUID uuid) {
        org.bukkit.entity.Item[] arr = mageBooks.remove(uuid);
        if (arr != null) for (org.bukkit.entity.Item it : arr) if (it != null && it.isValid()) it.remove();
        mageBookOrbits.remove(uuid);
    }

    /** 刺客：暗影残影 */
    public static void drawShadowTrail(World world, Entity entity, int tick) {
        Location loc = entity.getLocation();
        for (int i = 0; i < 4; i++) {
            world.spawnParticle(org.bukkit.Particle.PORTAL,
                loc.getX() + (rng.nextDouble() - 0.5) * 0.5,
                loc.getY() + rng.nextDouble() * 1.8,
                loc.getZ() + (rng.nextDouble() - 0.5) * 0.5, 1, 0, 0, 0, 0);
        }
    }

    /** 召唤师：暗能漩涡 */
    public static void drawDarkSwirl(World world, Entity entity, int tick) {
        Location loc = entity.getLocation();
        double cx = loc.getX(), by = loc.getY(), cz = loc.getZ();
        for (int i = 0; i < 5; i++) {
            double angle = (tick * 0.15 + i * Math.PI * 2 / 5);
            double r = 0.5 + (tick % 30) / 30.0 * 0.5;
            world.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER,
                cx + Math.cos(angle) * r, by + 0.1 + (tick % 30) / 30.0 * 2.0, cz + Math.sin(angle) * r, 1, 0, 0, 0, 0);
        }
    }

    /** Boss：暗红光环（脉冲扩散） */
    public static void drawBossAura(World world, Entity entity, int tick) {
        Location loc = entity.getLocation();
        double pulse = (tick % 40) / 40.0;
        double radius = 1.0 + pulse * 1.8;
        int count = (int) (6 + pulse * 8);
        for (int i = 0; i < count; i++) {
            double angle = (i * Math.PI * 2 / count);
            EliteMobManager.spawnParticleSafe(world, org.bukkit.Particle.FLAME,
                loc.clone().add(Math.cos(angle) * radius, 0.5, Math.sin(angle) * radius), 1);
        }
    }

    /** 召唤光柱（窜天粒子效果） */
    public static void drawSummonPillar(World world, Location loc, org.bukkit.plugin.Plugin plugin) {
        new org.bukkit.scheduler.BukkitRunnable() {
            int step = 0;
            @Override public void run() {
                step++;
                if (step > 15) { cancel(); return; }
                double cx = loc.getX(), cy = loc.getY(), cz = loc.getZ();
                // 粗光柱从地面窜到高空
                for (double y = 0; y <= 15; y += 0.5) {
                    for (int i = 0; i < 3; i++) {
                        double ox = (rng.nextDouble() - 0.5) * 0.3;
                        double oz = (rng.nextDouble() - 0.5) * 0.3;
                        try {
                            if (y < 3) world.spawnParticle(org.bukkit.Particle.FLAME, cx + ox, cy + y, cz + oz, 1, 0, 0, 0, 0);
                            else if (y < 8) world.spawnParticle(org.bukkit.Particle.SMOKE, cx + ox, cy + y, cz + oz, 1, 0, 0, 0, 0);
                            else world.spawnParticle(org.bukkit.Particle.WITCH, cx + ox, cy + y, cz + oz, 1, 0, 0, 0, 0);
                        } catch (Exception ignored) {}
                    }
                }
                // 底部爆炸扩散
                for (int i = 0; i < 15; i++) {
                    double angle = Math.toRadians(i * (360.0 / 15));
                    try {
                        world.spawnParticle(org.bukkit.Particle.FLAME, cx + Math.cos(angle) * 1.5, cy + 0.2, cz + Math.sin(angle) * 1.5, 1, 0, 0, 0, 0);
                    } catch (Exception ignored) {}
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
        world.playSound(loc, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2.0f, 0.5f);
    }
}
