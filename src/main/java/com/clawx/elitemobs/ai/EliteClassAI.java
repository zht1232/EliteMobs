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
    // 坦克飞绕盾牌（原版 EliteMobs 风格：Item 掉落物实体 + velocity 平滑旋转，无朝向问题、不卡顿）
    private final Map<UUID, org.bukkit.entity.Item[]> tankDisplays = new java.util.concurrent.ConcurrentHashMap<>();
    // 法师飞绕附魔书（Item 实体 + 随机目标，velocity 平滑飞舞）
    private final Map<UUID, org.bukkit.entity.Item[]> mageBooks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.util.Vector[]> mageBookOffsets = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, int[]> mageBookTimers = new java.util.concurrent.ConcurrentHashMap<>();

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
                                        // 扔喷溅药水（减速+虚弱）
                                        ThrownPotion potion = e.launchProjectile(ThrownPotion.class);
                                        potion.setShooter(e);
                                        org.bukkit.inventory.ItemStack potionItem = new org.bukkit.inventory.ItemStack(Material.SPLASH_POTION);
                                        org.bukkit.inventory.meta.PotionMeta pm = (org.bukkit.inventory.meta.PotionMeta) potionItem.getItemMeta();
                                        pm.addCustomEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1), true);
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
        }.runTaskTimer(plugin, 20L, 1L);
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

    /** 坦克：底部护盾环（配合 ItemDisplay 飞绕盾牌） */
    public static void drawShieldRing(World world, Entity entity, int tick) {
        Location loc = entity.getLocation();
        double cx = loc.getX(), by = loc.getY(), cz = loc.getZ();
        for (int i = 0; i < 12; i++) {
            double a = (i * Math.PI * 2 / 12) + (tick * 0.05);
            world.spawnParticle(org.bukkit.Particle.WAX_ON, cx + Math.cos(a) * 0.9, by + 0.15, cz + Math.sin(a) * 0.9, 1, 0, 0, 0, 0);
        }
    }

    // ==================== 坦克飞绕盾牌（ItemDisplay 实体） ====================

    /** 为坦克创建 6 个飞绕盾牌（Item 掉落物实体：不可拾取、无重力、无敌，跟随怪物） */
    private void ensureTankDisplays(LivingEntity e) {
        if (tankDisplays.containsKey(e.getUniqueId())) return;
        int n = 6;
        org.bukkit.entity.Item[] arr = new org.bukkit.entity.Item[n];
        try {
            for (int i = 0; i < n; i++) {
                // 每个盾牌写入唯一 PDC，防止掉落物互相 merge 成堆
                org.bukkit.inventory.ItemStack shield = new org.bukkit.inventory.ItemStack(Material.SHIELD);
                var meta = shield.getItemMeta();
                meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "orb_id"),
                        org.bukkit.persistence.PersistentDataType.INTEGER, i);
                shield.setItemMeta(meta);
                org.bukkit.entity.Item it = e.getWorld().dropItem(e.getLocation().add(0, 1.0, 0), shield);
                it.setPickupDelay(Integer.MAX_VALUE); // 不可拾取
                it.setGravity(false);
                it.setInvulnerable(true);
                arr[i] = it;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[EliteMobs] \u5766\u514b\u98de\u7ed5\u76fe\u724c\u521b\u5efa\u5931\u8d25: " + ex.getMessage());
            for (org.bukkit.entity.Item it : arr) if (it != null) it.remove();
            return;
        }
        tankDisplays.put(e.getUniqueId(), arr);
        plugin.getLogger().info("[EliteMobs] \u5df2\u4e3a\u5766\u514b " + e.getType().name() + " \u521b\u5efa " + n + " \u4e2a\u98de\u7ed5\u76fe\u724c");
    }

    /** 每 tick 更新坦克飞绕盾牌（绕身体平滑旋转 + 跟随怪物移动，原版用 velocity 推进而非 teleport 防卡顿） */
    private void updateTankDisplays(LivingEntity e, int tick) {
        org.bukkit.entity.Item[] arr = tankDisplays.get(e.getUniqueId());
        if (arr == null) return;
        Location center = e.getLocation().add(0, 1.0, 0);
        double speed = 0.15;
        for (int i = 0; i < arr.length; i++) {
            org.bukkit.entity.Item it = arr[i];
            if (it == null || !it.isValid()) continue;
            double a = (tick * speed + i * Math.PI * 2 / arr.length);
            double r = 1.2;
            Location target = center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            org.bukkit.util.Vector mv = target.toVector().subtract(it.getLocation().toVector());
            if (mv.lengthSquared() > 9) { // 怪物瞬移/距离过大直接传送，避免拉出长线
                it.teleport(target);
            } else {
                it.setVelocity(mv.multiply(0.3)); // 向目标平滑推进 30%
            }
        }
    }

    /** 清理坦克飞绕盾牌（死亡/失效时移除实体） */
    private void cleanupTankDisplays(UUID uuid) {
        org.bukkit.entity.Item[] arr = tankDisplays.remove(uuid);
        if (arr == null) return;
        for (org.bukkit.entity.Item it : arr) if (it != null && it.isValid()) it.remove();
    }

    /** 精英死亡时清理坦克飞绕盾牌与法师飞绕附魔书 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyEliteDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        cleanupTankDisplays(event.getEntity().getUniqueId());
        cleanupMageBooks(event.getEntity().getUniqueId());
    }

    // ==================== 法师飞绕附魔书（原版 Item 实体 + 随机轨迹） ====================

    /** 随机一个环绕偏移（半径 0.8-1.8、高度 0.4-1.8 的圆柱范围内随机点） */
    private org.bukkit.util.Vector randomBookOffset() {
        double r = 0.8 + rng.nextDouble() * 1.0;
        double a = rng.nextDouble() * Math.PI * 2;
        double y = 0.4 + rng.nextDouble() * 1.4;
        return new org.bukkit.util.Vector(Math.cos(a) * r, y, Math.sin(a) * r);
    }

    /** 为法师创建 5 本飞绕附魔书（不可拾取、无重力、无敌） */
    private void ensureMageBooks(LivingEntity e) {
        if (mageBooks.containsKey(e.getUniqueId())) return;
        int n = 5;
        org.bukkit.entity.Item[] arr = new org.bukkit.entity.Item[n];
        org.bukkit.util.Vector[] off = new org.bukkit.util.Vector[n];
        int[] tim = new int[n];
        try {
            for (int i = 0; i < n; i++) {
                org.bukkit.inventory.ItemStack book = new org.bukkit.inventory.ItemStack(Material.ENCHANTED_BOOK);
                var meta = book.getItemMeta();
                meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("elitemobs", "orb_id"),
                        org.bukkit.persistence.PersistentDataType.INTEGER, i);
                book.setItemMeta(meta);
                org.bukkit.entity.Item it = e.getWorld().dropItem(e.getLocation().add(0, 1, 0), book);
                it.setPickupDelay(Integer.MAX_VALUE);
                it.setGravity(false);
                it.setInvulnerable(true);
                arr[i] = it;
                off[i] = randomBookOffset();
                tim[i] = rng.nextInt(40);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[EliteMobs] \u6cd5\u5e08\u98de\u7ed5\u9644\u9b54\u4e66\u521b\u5efa\u5931\u8d25: " + ex.getMessage());
            for (org.bukkit.entity.Item it : arr) if (it != null) it.remove();
            return;
        }
        mageBooks.put(e.getUniqueId(), arr);
        mageBookOffsets.put(e.getUniqueId(), off);
        mageBookTimers.put(e.getUniqueId(), tim);
    }

    /** 每 tick 更新法师附魔书：随机换目标 + velocity 平滑飞舞 */
    private void updateMageBooks(LivingEntity e, int tick) {
        org.bukkit.entity.Item[] arr = mageBooks.get(e.getUniqueId());
        org.bukkit.util.Vector[] off = mageBookOffsets.get(e.getUniqueId());
        int[] tim = mageBookTimers.get(e.getUniqueId());
        if (arr == null || off == null || tim == null) return;
        Location base = e.getLocation();
        for (int i = 0; i < arr.length; i++) {
            org.bukkit.entity.Item it = arr[i];
            if (it == null || !it.isValid()) continue;
            tim[i]--;
            if (tim[i] <= 0) { // 每 1-2.5 秒换一个新随机目标，看起来随机飞舞
                off[i] = randomBookOffset();
                tim[i] = 20 + rng.nextInt(30);
            }
            Location target = base.clone().add(off[i]);
            org.bukkit.util.Vector mv = target.toVector().subtract(it.getLocation().toVector());
            if (mv.lengthSquared() > 9) {
                it.teleport(target);
            } else {
                it.setVelocity(mv.multiply(0.25));
            }
        }
    }

    /** 清理法师飞绕附魔书 */
    private void cleanupMageBooks(UUID uuid) {
        org.bukkit.entity.Item[] arr = mageBooks.remove(uuid);
        if (arr != null) for (org.bukkit.entity.Item it : arr) if (it != null && it.isValid()) it.remove();
        mageBookOffsets.remove(uuid);
        mageBookTimers.remove(uuid);
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
