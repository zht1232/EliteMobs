package com.clawx.elitemobs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import com.clawx.elitemobs.utils.StringUtil;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class EliteCombatListener implements Listener {
    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();
    private final Map<UUID, Integer> comboKills = new HashMap<>();
    /** 二段跳宝石：上次二段跳时间戳（毫秒） */
    private final Map<UUID, Long> lastDoubleJump = new HashMap<>();
    /** 防重入：target.damage() 会再次派发 EntityDamageByEntityEvent 重入 onPlayerAttackWithGem。
     *  仅限主线程使用（Bukkit 事件处理均在主线程），无需 ThreadLocal。 */
    private boolean processingGemAttack = false;

    public EliteCombatListener(EliteMobsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEliteTargetPlayer(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && event.getEntity() instanceof LivingEntity e && EliteMobManager.isElite(e)) {
            if (p.hasPermission("elitemobs.bypass")) event.setCancelled(true);
        }
    }

    // ==================== Elite Armor Set Bonus ====================

    /**
     * 计算玩家精英护甲套装加成。
     * 返回所有淬炼护甲的总精英等级。0 = 没有精英护甲。
     */
    private int getEliteSetLevel(Player p) {
        int total = 0;
        var inv = p.getInventory();
        for (ItemStack armor : new ItemStack[]{inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()}) {
            if (armor == null || !armor.hasItemMeta()) continue;
            var pdc = armor.getItemMeta().getPersistentDataContainer();
            if (pdc.has(EliteMobManager.ARMOR_LV_KEY, org.bukkit.persistence.PersistentDataType.INTEGER)) {
                total += pdc.get(EliteMobManager.ARMOR_LV_KEY, org.bukkit.persistence.PersistentDataType.INTEGER);
            }
        }
        return total;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSetBonusDamageReduction(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isSetBonusEnabled()) return;
        // 封印：套装减伤暂时失效
        if (plugin.getBossManager().isSealedPlayer(p)) return;
        int setLevel = getEliteSetLevel(p);
        if (setLevel <= 0) return;

        // 套装加成：每点套装等级提供 X% 额外减伤，封顶 Y%（config: armor-set-bonus）
        double setBonus = Math.min(setLevel * cfg.getSetBonusReductionPerLevel(),
                cfg.getSetBonusMaxReduction()) / 100.0;
        event.setDamage(event.getDamage() * (1.0 - setBonus));
    }

    // ==================== 封印（Seal）：淬炼加成暂时失效 ====================

    /** 封印：玩家攻击精英时，抵消武器淬炼攻击力（elite_damage 加成） */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSealedPlayerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (!(event.getEntity() instanceof LivingEntity le) || !EliteMobManager.isElite(le)) return;
        if (!plugin.getBossManager().isSealedPlayer(p)) return;
        double bonus = getWeaponEssenceBonus(p);
        if (bonus > 0) event.setDamage(Math.max(1.0, event.getDamage() - bonus));
    }

    /** 封印：玩家受到伤害时，抵消护甲淬炼减伤（elite_armor 带来的护甲减伤） */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSealedPlayerHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!plugin.getBossManager().isSealedPlayer(p)) return;
        double armorBonus = getArmorEssenceBonus(p);
        if (armorBonus <= 0) return;
        double reduction = Math.min(20, armorBonus) / 25.0; // MC 护甲减伤近似：armor/25，封顶 20
        if (reduction > 0 && reduction < 1) event.setDamage(event.getDamage() / (1.0 - reduction));
    }

    /** 读取主手武器上的淬炼攻击力加成（elite_damage modifier 值）。 */
    private double getWeaponEssenceBonus(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || !hand.hasItemMeta()) return 0;
        NamespacedKey key = new NamespacedKey(plugin, "elite_damage");
        ItemAttributeModifiers mods = hand.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (mods == null) return 0;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (e.attribute() == Attribute.ATTACK_DAMAGE && e.modifier().getKey().equals(key)) {
                return Math.max(0, e.modifier().getAmount());
            }
        }
        return 0;
    }

    /** 读取玩家所有护甲上的淬炼护甲值加成总和（elite_armor modifier）。 */
    private double getArmorEssenceBonus(Player p) {
        double bonus = 0;
        NamespacedKey key = new NamespacedKey(plugin, "elite_armor");
        for (ItemStack a : new ItemStack[]{p.getInventory().getHelmet(), p.getInventory().getChestplate(),
                p.getInventory().getLeggings(), p.getInventory().getBoots()}) {
            if (a == null || !a.hasItemMeta()) continue;
            ItemAttributeModifiers mods = a.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (mods == null) continue;
            for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
                if (e.attribute() == Attribute.ARMOR && e.modifier().getKey().equals(key)) {
                    bonus += Math.max(0, e.modifier().getAmount());
                }
            }
        }
        return bonus;
    }

    // 套装效果粒子（每2秒检测一次，阈值由 config 控制）
    public void startSetBonusTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            EliteConfig cfg = plugin.getEliteConfig();
            if (!cfg.isSetBonusEnabled()) return;
            int speedLevel = cfg.getSetBonusSpeedLevel();
            int regenLevel = cfg.getSetBonusRegenLevel();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                int setLevel = getEliteSetLevel(p);
                if (setLevel >= regenLevel) {
                    // 达到再生阈值：生命恢复 + 粒子
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.REGENERATION, 60, 0, true, false));
                    EliteMobManager.spawnParticleSafe(p.getWorld(), org.bukkit.Particle.TOTEM_OF_UNDYING,
                        p.getLocation().add(0, 0.5, 0), 1);
                } else if (setLevel >= speedLevel) {
                    // 达到速度阈值：速度提升
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SPEED, 60, 0, true, false));
                }
            }
        }, 40L, 40L);
    }

    // ==================== 磁力宝石（自动拾取附近掉落物） ====================

    /** 计算玩家身上磁力宝石的拾取半径（主手 + 全部护甲，取最大；无则 0）。 */
    private int getMagnetRadius(Player p) {
        int best = 0;
        var inv = p.getInventory();
        ItemStack[] items = new ItemStack[]{inv.getItemInMainHand(),
                inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        for (ItemStack it : items) {
            if (it == null || !it.hasItemMeta()) continue;
            String[] ids = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGems(it);
            int[] lvs = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGemLevels(it);
            for (int i = 0; i < com.clawx.elitemobs.essence.EliteGemFactory.MAX_GEM_SLOTS; i++) {
                if (ids[i] != null && "magnet".equals(gemEffectFor(ids[i]))) {
                    best = Math.max(best, com.clawx.elitemobs.essence.EliteGemFactory.magnetRadius(lvs[i]));
                }
            }
        }
        return best;
    }

    // ==================== 二段跳宝石（等级越高蓄力越快/冷却越短） ====================

    /** 计算玩家武器上二段跳宝石的最高等级（只认主手/副手武器，不认护甲）。 */
    private int getDoubleJumpLevel(Player p) {
        int best = 0;
        var inv = p.getInventory();
        ItemStack[] items = new ItemStack[]{inv.getItemInMainHand(), inv.getItemInOffHand()};
        for (ItemStack it : items) {
            if (it == null || !it.hasItemMeta()) continue;
            String[] ids = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGems(it);
            int[] lvs = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGemLevels(it);
            for (int i = 0; i < com.clawx.elitemobs.essence.EliteGemFactory.MAX_GEM_SLOTS; i++) {
                if (ids[i] != null && "doublejump".equals(gemEffectFor(ids[i]))) {
                    best = Math.max(best, lvs[i]);
                }
            }
        }
        return best;
    }

    /** 二段跳宝石：空中双击空格二段跳；等级越高冷却越短（蓄力越快）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDoubleJumpToggle(org.bukkit.event.player.PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        int lv = getDoubleJumpLevel(p);
        if (lv <= 0) return;
        if (!event.isFlying()) return; // 只处理双击空格（尝试开启飞行）
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        long last = lastDoubleJump.getOrDefault(p.getUniqueId(), 0L);
        // 仅在空中且冷却已过才二段跳
        if (!p.isOnGround() && now - last >= com.clawx.elitemobs.essence.EliteGemFactory.jumpCooldown(lv)) {
            p.setFlying(false);
            p.setAllowFlight(false); // 消耗一次，落地后按冷却恢复
            // 二段跳 = 向前冲 + 向上跳（玩家朝向水平方向，等级越高冲得越远）
            org.bukkit.util.Vector dir = p.getLocation().getDirection();
            dir.setY(0).normalize();
            double forward = Math.min(0.5 + lv * 0.03, 1.0);
            org.bukkit.util.Vector vel = dir.multiply(forward);
            vel.setY(com.clawx.elitemobs.essence.EliteGemFactory.jumpPower(lv));
            p.setVelocity(vel);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.9f);
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.3, 0.1, 0.3, 0);
            lastDoubleJump.put(p.getUniqueId(), now);
        }
        // 冷却未过或在地面：吞掉本次双击（不二段跳），落地蓄力完成后才能再次二段跳
    }

    /** 定时恢复二段跳：玩家落地且冷却已过 → 重新允许飞行（下一次二段跳）。 */
    public void startDoubleJumpTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                int lv = getDoubleJumpLevel(p);
                if (lv <= 0) continue;
                if (!p.isOnGround() || p.getAllowFlight()) continue;
                long now = System.currentTimeMillis();
                long last = lastDoubleJump.getOrDefault(p.getUniqueId(), 0L);
                if (now - last >= com.clawx.elitemobs.essence.EliteGemFactory.jumpCooldown(lv)) {
                    p.setAllowFlight(true);
                }
            }
        }, 20L, 20L);
    }

    /** 磁力宝石定时任务：把玩家磁力半径内的掉落物吸向玩家（每 0.5 秒，距离越近吸力越强）。 */
    public void startMagnetTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.isDead() || !p.isOnline()) continue;
                int radius = getMagnetRadius(p);
                if (radius <= 0) continue;
                Location pl = p.getLocation();
                for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
                    if (!(e instanceof Item item) || item.isDead()) continue;
                    Location il = item.getLocation();
                    double dist = il.distance(pl);
                    if (dist <= 0.01 || dist > radius) continue;
                    org.bukkit.util.Vector dir = pl.toVector().subtract(il.toVector()).normalize();
                    double speed = 0.35 + (1.0 - dist / radius) * 0.25;
                    item.setVelocity(dir.multiply(speed).setY(Math.max(0.2, speed * 0.6)));
                }
            }
        }, 40L, 10L);
    }

    // ==================== 精英强制索敌（应对"玩家免疫追击"类插件） ====================

    /**
     * 每 1 秒让精英主动索敌：若精英当前无有效目标，则锁定范围内最近的、未拥有
     * elitemobs.bypass 权限的玩家。这样即使其他插件取消了原版的追击事件，精英仍能
     * 重新锁定玩家。若对方插件连 setTarget 触发的追击事件也取消，则需在对方插件中
     * 排除/放行 EliteMobs 的怪物（见 config target-range 配置说明）。
     */
    public void startTargetTask() {
        int range = plugin.getEliteConfig().getTargetRange();
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (EliteMobManager.EliteMobData data : plugin.getMobManager().getEliteMobs()) {
                LivingEntity e = data.entity;
                if (e == null || e.isDead() || !e.isValid()) continue;
                if (!(e instanceof Mob mob)) continue;
                // 已有有效目标则不干预；玩家目标跑出索敌范围时重新索敌（避免无限追远），非玩家目标不干预
                LivingEntity cur = mob.getTarget();
                boolean need = false;
                if (cur == null || cur.isDead() || !cur.isValid()) {
                    need = true;
                } else if (cur instanceof Player cp) {
                    if (cp.hasPermission("elitemobs.bypass")) need = true;
                    // 跨世界目标（Bukkit 不允许跨世界测距）→ 视为失效重新索敌
                    else if (!cp.getWorld().equals(e.getWorld())) need = true;
                    else if (cp.getLocation().distance(e.getLocation()) > range) need = true;
                }
                if (!need) continue;
                // 寻找范围内最近的非豁免玩家（找到即锁定，无需遍历全部）
                Player best = null;
                double bestDist = range;
                Location loc = e.getLocation();
                for (Entity ent : e.getNearbyEntities(range, range, range)) {
                    if (!(ent instanceof Player p) || p.isDead() || !p.isOnline()) continue;
                    if (p.hasPermission("elitemobs.bypass")) continue;
                    double d = p.getLocation().distance(loc);
                    if (d <= bestDist) { bestDist = d; best = p; break; }
                }
                if (best != null) mob.setTarget(best);
            }
        }, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamageElite(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof LivingEntity e && event.getDamager() instanceof Player p && EliteMobManager.isElite(e)) {
            int lv = EliteMobManager.getEliteLevel(e);
            double hp = e.getHealth();
            org.bukkit.attribute.AttributeInstance maxAttr = e.getAttribute(Attribute.MAX_HEALTH);
            double max = maxAttr != null ? maxAttr.getBaseValue() : Math.max(hp, 1.0);
            double pct = Math.max(0, (hp / Math.max(max, 1.0)) * 100);
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < 10; i++) bar.append(i < (int) Math.ceil(pct / 10.0) ? ChatColor.DARK_RED + "\u2588" : ChatColor.GRAY + "\u2588");
            p.sendActionBar(ChatColor.RED + "[" + bar + ChatColor.RED + "] " + ChatColor.GOLD + fmt(e.getType()) + " Lv." + lv + ChatColor.GRAY + " | " + String.format("%.0f%%", pct));
        }
    }

    /** 主手武器上镶嵌的宝石效果：雷电=概率召唤闪电 / 击退=稳定击退敌人（按宝石等级）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAttackWithGem(EntityDamageByEntityEvent event) {
        if (processingGemAttack) return; // 防重入：避免 target.damage() 触发连环闪电
        if (!(event.getDamager() instanceof Player p)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target.isDead()) return;
        // 封印：宝石效果（雷电/击退/吸血/火焰附加）暂时失效
        if (plugin.getBossManager().isSealedPlayer(p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || !hand.hasItemMeta()) return;

        String[] gemIds = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGems(hand);
        int[] gemLvs = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGemLevels(hand);

        processingGemAttack = true;
        try {
            // 击退宝石：稳定施加（等级 = 装备上击退宝石的最高等级）
            Integer kb = hand.getItemMeta().getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey("elitemobs", "gem_knockback"),
                    org.bukkit.persistence.PersistentDataType.INTEGER);
            if (kb != null && kb > 0) {
                org.bukkit.util.Vector dir = target.getLocation().toVector()
                        .subtract(p.getLocation().toVector()).normalize();
                double power = 0.4 + kb * 0.12;
                target.setVelocity(dir.multiply(power).setY(0.3 + kb * 0.04));
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.0f);
            }

            // 雷电宝石：按等级概率召唤闪电
            for (int i = 0; i < com.clawx.elitemobs.essence.EliteGemFactory.MAX_GEM_SLOTS; i++) {
                if (gemIds[i] == null) continue;
                String eff = gemEffectFor(gemIds[i]);
                if ("thunder".equals(eff)) {
                    int lv = gemLvs[i];
                    double chance = com.clawx.elitemobs.essence.EliteGemFactory.thunderChance(lv);
                    if (rng.nextDouble() < chance) {
                        // 假闪电动画 + 手动伤害 + 手动给苦力怕充电：
                        // 不产生真闪电实体（strikeLightning），彻底避免落雷爆炸/火焰摧毁掉落物
                        target.getWorld().strikeLightningEffect(target.getLocation());
                        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
                        if (target instanceof Creeper c) {
                            c.setPowered(true); // 苦力怕变高压爬行者
                        }
                        target.damage(2.0 + lv * 0.5, p);
                    }
                }
            }
            // 吸血 + 火焰附加宝石（攻击时效果，对任意目标生效）
            for (int i = 0; i < com.clawx.elitemobs.essence.EliteGemFactory.MAX_GEM_SLOTS; i++) {
                if (gemIds[i] == null) continue;
                String eff = gemEffectFor(gemIds[i]);
                int lv = gemLvs[i];
                if ("lifesteal".equals(eff)) {
                    double heal = 1.0 + lv * 0.5; // 吸血：基础 1 + 每级 0.5 颗心
                    org.bukkit.attribute.AttributeInstance maxHp = p.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHp != null) {
                        p.setHealth(Math.min(p.getHealth() + heal, maxHp.getValue()));
                    }
                    EliteMobManager.spawnParticleSafe(p.getWorld(), org.bukkit.Particle.HEART,
                            p.getLocation().add(0, 1, 0), 3);
                }
                if ("fire_aspect".equals(eff)) {
                    target.setFireTicks(20 * (2 + lv / 2)); // 火焰附加：燃烧 2 + 等级/2 秒
                }
            }
        } finally {
            processingGemAttack = false;
        }
    }

    /** 耐久宝石：装备上每级减免 10% 耐久损耗；Lv.10 后装备无法破坏。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        String[] ids = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGems(item);
        int[] lvs = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGemLevels(item);
        int lv = 0;
        for (int i = 0; i < com.clawx.elitemobs.essence.EliteGemFactory.MAX_GEM_SLOTS; i++) {
            if (ids[i] != null && "unbreaking".equals(gemEffectFor(ids[i]))) {
                lv = Math.max(lv, lvs[i]);
            }
        }
        if (lv <= 0) return;
        if (lv >= 10) {
            event.setCancelled(true); // 无限耐久
        } else {
            int reduced = (int) Math.max(0, Math.round(event.getDamage() * (1.0 - lv * 0.1)));
            event.setDamage(reduced);
        }
    }

    // ==================== 武器熟练度暴击（weapon-proficiency） ====================

    /**
     * 武器熟练度暴击：武器每击杀累计熟练度（击杀驱动升星），每星提供暴击率；
     * 暴击伤害 = 基础伤害 × 倍率（满星额外加成）；被 Boss 封印时失效；
     * 不对玩家生效（PVP 不暴击）；不作用于宝石触发的额外伤害（防重入）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerProficiencyCrit(EntityDamageByEntityEvent event) {
        if (processingGemAttack) return; // 宝石触发的重入伤害不暴击
        Player p = null;
        if (event.getDamager() instanceof Player pp) p = pp;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player pp) p = pp;
        if (p == null) return;
        if (event.getEntity() instanceof Player) return; // PVP 不触发
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isProfEnabled()) return;
        if (plugin.getBossManager().isSealedPlayer(p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        int stars = com.clawx.elitemobs.essence.EliteGemFactory.getProf(hand);
        if (stars <= 0) return;
        double chance = Math.min(stars * cfg.getProfCritPerStar(), cfg.getProfMaxCritChance());
        if (rng.nextDouble() >= chance) return;
        double mult = cfg.getProfCritMultiplier();
        if (stars >= cfg.getProfMaxStars()) mult += cfg.getProfFullStarCritBonus();
        event.setDamage(event.getDamage() * mult);
        event.getEntity().getWorld().playSound(event.getEntity().getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.2f);
        EliteMobManager.spawnParticleSafe(event.getEntity().getWorld(),
                Particle.CRIT, event.getEntity().getLocation().add(0, 1, 0), 12);
    }

    // ==================== 武器熟练度：击杀统计（击杀驱动升星） ====================

    /**
     * 玩家用已淬炼武器击杀敌对生物 → 熟练度击杀数 +1（精英 ×elite-kill-multiplier）；
     * 达到指数阈值（第 N 星 = kill-base × kill-growth^(N-1)）自动升星。
     * 刷怪笼刷出的怪不计（保护刷怪笼刷怪塔，与 exclude-spawner-mobs 一致）；
     * 只统计怪物类（排除动物/水生/环境/村民/傀儡/悦灵/盔甲架/宠物）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKillForProficiency(EntityDeathEvent event) {
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isProfEnabled()) return;
        LivingEntity e = event.getEntity();
        if (e instanceof Player) return;
        Player p = e.getKiller();
        if (p == null || !p.isOnline()) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!com.clawx.elitemobs.essence.EliteGemFactory.hasProfData(hand)) return; // 未淬炼武器不计
        if (cfg.isProfExcludeSpawnerKills()) {
            try {
                if (e.getEntitySpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER) return;
            } catch (Throwable ignored) {}
        }
        if (e instanceof Animals || e instanceof WaterMob || e instanceof Ambient
                || e instanceof Villager || e instanceof AbstractVillager
                || e instanceof Golem || e instanceof Allay
                || e instanceof ArmorStand || e instanceof Tameable) return;
        if (cfg.isProfCountEliteOnly() && !EliteMobManager.isElite(e)) return;
        int amount = EliteMobManager.isElite(e) ? cfg.getProfEliteKillMultiplier() : 1;
        addProfKillProgress(p, hand, amount);
    }

    /** 增加武器击杀进度并按指数阈值升星（含提示/音效/粒子；实时刷新 Lore 显示进度）。 */
    private void addProfKillProgress(Player p, ItemStack hand, int amount) {
        EliteConfig cfg = plugin.getEliteConfig();
        int stars = com.clawx.elitemobs.essence.EliteGemFactory.getProf(hand);
        int max = cfg.getProfMaxStars();
        if (stars >= max) return;
        int kills = com.clawx.elitemobs.essence.EliteGemFactory.getProfKills(hand) + amount;
        boolean leveled = false;
        while (stars < max) {
            int need = com.clawx.elitemobs.essence.EliteGemFactory.profKillThreshold(
                    stars + 1, cfg.getProfKillBase(), cfg.getProfKillGrowth());
            if (kills < need) break;
            kills -= need;
            stars++;
            leveled = true;
        }
        if (kills != com.clawx.elitemobs.essence.EliteGemFactory.getProfKills(hand)) {
            com.clawx.elitemobs.essence.EliteGemFactory.setProfKills(hand, kills);
        }
        if (stars != com.clawx.elitemobs.essence.EliteGemFactory.getProf(hand)) {
            com.clawx.elitemobs.essence.EliteGemFactory.setProf(hand, stars);
        }
        // 实时刷新 Lore（熟练进度/星级行），并确保背包槽位引用同步
        p.getInventory().setItemInMainHand(hand);
        com.clawx.elitemobs.essence.EliteEssenceUpgradeListener ess = plugin.getEssenceListener();
        if (ess != null) ess.refreshWeaponLore(hand);
        if (leveled) {
            double crit = Math.min(stars * cfg.getProfCritPerStar(), cfg.getProfMaxCritChance());
            p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[EliteMobs] " + ChatColor.YELLOW
                    + "你的武器熟练度提升至 "
                    + ChatColor.translateAlternateColorCodes('&', com.clawx.elitemobs.essence.EliteGemFactory.profStars(stars))
                    + ChatColor.YELLOW + "！暴击率 +" + String.format("%.0f%%", crit * 100));
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            EliteMobManager.spawnParticleSafe(p.getWorld(), Particle.TOTEM_OF_UNDYING,
                    p.getLocation().add(0, 1, 0), 16);
        }
    }

    /** 根据宝石 id 返回效果类型（委托到 EliteConfig 缓存）。 */
    private String gemEffectFor(String gemId) {
        return plugin.getEliteConfig().gemEffectFor(gemId);
    }

    /**
     * 取消插件真闪电引燃方块：保留真闪电的伤害与苦力怕充电，
     * 但不再在落点点火（不烧毁地面/建筑）。只影响本插件标记的闪电。
     */
    @EventHandler(ignoreCancelled = true)
    public void onLightningIgnite(org.bukkit.event.block.BlockIgniteEvent event) {
        if (event.getCause() != org.bukkit.event.block.BlockIgniteEvent.IgniteCause.LIGHTNING) return;
        if (event.getIgnitingEntity() instanceof org.bukkit.entity.LightningStrike ls
                && ls.hasMetadata("elitemobs_lightning")) {
            event.setCancelled(true);
        }
    }

    /** 从死亡掉落中移除被偷物品的副本（按偷窃标记精确匹配；每件被偷物品移除一份）。 */
    private void removeStolenDrops(List<ItemStack> drops, List<ItemStack> stolen) {
        if (drops == null || stolen == null) return;
        for (ItemStack st : stolen) {
            if (st == null) continue;
            String id = EliteMobManager.getStolenId(st);
            if (id == null) continue;
            drops.removeIf(d -> d != null && id.equals(EliteMobManager.getStolenId(d)));
        }
    }

    /** 清空怪身上带偷窃标记的装备槽（防止与其他死亡处理路径重复归还）。 */
    private void clearStolenEquipment(LivingEntity e, List<ItemStack> stolen) {
        if (stolen == null || stolen.isEmpty()) return;
        Set<String> ids = new HashSet<>();
        for (ItemStack st : stolen) {
            String id = EliteMobManager.getStolenId(st);
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return;
        org.bukkit.inventory.EntityEquipment geq = e.getEquipment();
        if (geq == null) return;
        if (isMarked(geq.getItemInOffHand(), ids)) geq.setItemInOffHand(null);
        if (isMarked(geq.getBoots(), ids)) geq.setBoots(null);
        if (isMarked(geq.getLeggings(), ids)) geq.setLeggings(null);
        if (isMarked(geq.getChestplate(), ids)) geq.setChestplate(null);
        if (isMarked(geq.getHelmet(), ids)) geq.setHelmet(null);
    }

    private boolean isMarked(ItemStack slot, Set<String> ids) {
        String id = EliteMobManager.getStolenId(slot);
        return id != null && ids.contains(id);
    }

    /** 清除掉落物列表上所有带偷窃标记物品的标记（保留物品本身，保证可正常堆叠）。 */
    private void stripStolenMarks(List<ItemStack> drops) {
        if (drops == null) return;
        for (ItemStack d : drops) {
            if (d != null && EliteMobManager.getStolenId(d) != null) {
                EliteMobManager.stripStolenMark(d);
            }
        }
    }

    /** 清除怪装备槽上带偷窃标记物品的标记（保留物品本身；镜像需写回装备槽）。 */
    private void stripEquipmentMarks(LivingEntity e) {
        org.bukkit.inventory.EntityEquipment geq = e.getEquipment();
        if (geq == null) return;
        ItemStack off = geq.getItemInOffHand();
        if (EliteMobManager.getStolenId(off) != null) { EliteMobManager.stripStolenMark(off); geq.setItemInOffHand(off); }
        ItemStack boots = geq.getBoots();
        if (EliteMobManager.getStolenId(boots) != null) { EliteMobManager.stripStolenMark(boots); geq.setBoots(boots); }
        ItemStack legs = geq.getLeggings();
        if (EliteMobManager.getStolenId(legs) != null) { EliteMobManager.stripStolenMark(legs); geq.setLeggings(legs); }
        ItemStack chest = geq.getChestplate();
        if (EliteMobManager.getStolenId(chest) != null) { EliteMobManager.stripStolenMark(chest); geq.setChestplate(chest); }
        ItemStack helm = geq.getHelmet();
        if (EliteMobManager.getStolenId(helm) != null) { EliteMobManager.stripStolenMark(helm); geq.setHelmet(helm); }
    }

    /** 掉落物耐火/岩浆：防止精英掉落物被火烧毁、掉岩浆消失（只保护带 elitemobs 标记的掉落物）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemBurn(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Item item)) return;
        org.bukkit.event.entity.EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE
                && cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE_TICK
                && cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.LAVA) return;
        // 只保护本插件标记的掉落物（宝石/符文/淬炼装备），避免全局改动其他掉落物
        ItemStack stack = item.getItemStack();
        if (stack == null || !stack.hasItemMeta()) return;
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if (key.getNamespace().equals("elitemobs")) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEliteDeath(EntityDeathEvent event) {
        LivingEntity e = event.getEntity();
        boolean elite = EliteMobManager.isElite(e);
        if (!elite) return;

        // Boss死亡：清理血条（掉落统一走末尾 rollGemDrops，Boss 加成由 isBoss 判定，避免双倍掉落）
        if (com.clawx.elitemobs.ai.EliteBossManager.isBoss(e)) {
            plugin.getBossManager().onBossDeath(e);

            // Boss击杀广播（受 general.spawn-announce.boss-alert 控制，默认开）
            int level = EliteMobManager.getEliteLevel(e);
            Player killer = e.getKiller();
            if (killer != null && plugin.getEliteConfig().isBossAlertEnabled()) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "\u2620 "
                    + ChatColor.GREEN + killer.getName()
                    + ChatColor.GOLD + " \u51fb\u6740\u4e86 Boss "
                    + ChatColor.RED + e.getType().name().toLowerCase().replace('_', ' ')
                    + ChatColor.GRAY + " [Lv." + level + "]!");
            }
        }

        // ===== 死亡归还被偷物品（features.item-steal.return-on-death）=====
        if (plugin.getEliteConfig().isReturnStolenItemsOnDeath()) {
            List<ItemStack> stolen = plugin.getMobManager().takeStolenItems(e.getUniqueId());
            if (stolen != null && !stolen.isEmpty()) {
                // 按偷窃标记精确移除掉落物中被偷物品的副本（装备 dropChance=1.0 已把物品加入 drops），
                // 镜像对象也能按 PDC 标记匹配 → 掉落一份 + 归还一份 = 恰好一份，不再复制
                removeStolenDrops(event.getDrops(), stolen);
                // 清空怪身上带偷窃标记的装备槽（防止与其他死亡处理路径重复）
                clearStolenEquipment(e, stolen);
                Player gk = e.getKiller();
                if (gk != null) {
                    for (ItemStack it : stolen) {
                        if (it == null) continue;
                        EliteMobManager.stripStolenMark(it);
                        gk.getInventory().addItem(it).values().forEach(d -> e.getWorld().dropItemNaturally(e.getLocation(), d));
                    }
                    gk.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[EliteMobs] " + ChatColor.GRAY
                            + "\u8fd4\u8fd8\u4e86\u88ab\u5077\u8d70\u7684 " + ChatColor.YELLOW + stolen.size()
                            + ChatColor.GRAY + " \u4ef6\u7269\u54c1\uff01");
                } else {
                    for (ItemStack it : stolen) {
                        if (it == null) continue;
                        EliteMobManager.stripStolenMark(it);
                        e.getWorld().dropItemNaturally(e.getLocation(), it);
                    }
                }
            }
        } else {
            // 不归还：物品随装备自然掉落（恰好一份），仅清除掉落/装备上的偷窃标记（保证可正常堆叠）
            stripStolenMarks(event.getDrops());
            stripEquipmentMarks(e);
        }

        plugin.getMobManager().handleEliteDeath(e.getUniqueId());
        // 词缀死亡效果（分裂词缀生成小精英）
        if (plugin.getAffixHandler() != null) plugin.getAffixHandler().onDeath(e);
        int level = EliteMobManager.getEliteLevel(e);
        EntityType type = e.getType();
        Location loc = e.getLocation().clone();

        // ===== ?????? =====
        spawnDeathParticles(e.getWorld(), loc, type, level);

        // ?????????
        if (e instanceof Creeper) {
            for (PotionEffect effect : new ArrayList<>(e.getActivePotionEffects())) {
                e.removePotionEffect(effect.getType());
            }
        }

        // ????????
        event.getDrops().removeIf(item ->
            item.getType() == Material.LINGERING_POTION ||
            item.getType() == Material.TIPPED_ARROW ||
            item.getType() == Material.POTION
        );

        // ===== Lv.8+ ?????? =====
        if (level >= 8) {
            ItemStack skull = createEliteSkull(type, level);
            if (skull != null) event.getDrops().add(skull);
        }

        // ===== 宝石/自定义掉落物（Boss 走一次，避免双倍掉落）=====
        rollGemDrops(event, level, com.clawx.elitemobs.ai.EliteBossManager.isBoss(e));

        // ===== 击杀奖励（金币 + 点券）=====
        Player killer = e.getKiller();
        if (killer != null) {
            boolean boss = com.clawx.elitemobs.ai.EliteBossManager.isBoss(e);
            EliteConfig cfg = plugin.getEliteConfig();

            // 先计算 LuckPerms 倍数（应用到金币/点券/掉落/经验）
            double lootMult = 1.0, xpMult = 1.0;
            if (cfg.isLuckPermsEnabled()) {
                try {
                    for (Map<String, Object> group : cfg.getLuckPermsGroups()) {
                        String groupName = (String) group.get("group");
                        if (killer.hasPermission("group." + groupName) || killer.hasPermission("luckperms.group." + groupName)) {
                            if (group.containsKey("loot-multiplier")) lootMult = ((Number) group.get("loot-multiplier")).doubleValue();
                            if (group.containsKey("xp-multiplier")) xpMult = ((Number) group.get("xp-multiplier")).doubleValue();
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 连杀加成（连续击杀精英，奖励逐渐提升）
            double comboMult = 1.0;
            int combo = 0;
            if (cfg.isComboEnabled()) {
                combo = comboKills.getOrDefault(killer.getUniqueId(), 0) + 1;
                comboKills.put(killer.getUniqueId(), combo);
                comboMult = Math.min(1.0 + (combo - 1) * cfg.getComboPerKill(), Math.max(1.0, cfg.getComboMaxMult()));
            }

            double rewardMult = lootMult * comboMult;
            String comboTag = combo >= 2 ? ChatColor.DARK_PURPLE + " (\u8fde\u6740 x" + String.format("%.1f", comboMult) + ")" : "";

            // 金币 (Vault)
            if (cfg.isMoneyRewardEnabled()) {
                double money = cfg.getMoneyRewardBase() + cfg.getMoneyRewardPerLevel() * level;
                if (boss) money *= cfg.getMoneyRewardBossMult();
                money *= rewardMult;
                if (EconomyHook.depositMoney(killer, money)) {
                    killer.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[EliteMobs] " + ChatColor.GRAY + "\u83b7\u5f97 " + ChatColor.GREEN + "$" + fmtMoney(money) + ChatColor.GRAY + " \u51fb\u6740\u5956\u52b1!" + comboTag);
                }
            }

            // 点券 (PlayerPoints)
            if (cfg.isPointsRewardEnabled()) {
                double pts = cfg.getPointsRewardBase() + cfg.getPointsRewardPerLevel() * level;
                if (boss) pts *= cfg.getPointsRewardBossMult();
                pts *= rewardMult;
                int ptsInt = Math.max(1, (int) Math.round(pts));
                if (EconomyHook.addPoints(killer, ptsInt)) {
                    killer.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[EliteMobs] " + ChatColor.GRAY + "\u83b7\u5f97 " + ChatColor.AQUA + ptsInt + " \u70b9\u5238" + ChatColor.GRAY + "!" + comboTag);
                }
            }

            // LuckPerms: 额外掉落 + 经验倍数
            if (lootMult > 1.0 && cfg.isCustomLootEnabled()) {
                int extraDrops = (int) Math.floor(event.getDrops().size() * (lootMult - 1.0));
                for (int i = 0; i < extraDrops; i++) {
                    if (!event.getDrops().isEmpty()) event.getDrops().add(event.getDrops().get(rng.nextInt(event.getDrops().size())).clone());
                }
            }
            if (xpMult > 1.0) event.setDroppedExp((int) Math.round(event.getDroppedExp() * xpMult));
        }
    }

    /** 玩家死亡：清空连杀计数 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeathResetCombo(PlayerDeathEvent event) {
        if (!plugin.getEliteConfig().isComboResetOnDeath()) return;
        comboKills.remove(event.getEntity().getUniqueId());
    }

    /**
     * ?????? - ????+??
     */
    private void spawnDeathParticles(org.bukkit.World world, Location loc, EntityType type, int level) {
        if (!plugin.getEliteConfig().isParticleEffectsEnabled()) return;
        // 1. ???? - ?????
        for (int i = 0; i < 30; i++) {
            double angle = Math.random() * Math.PI * 2;
            double speed = 0.3 + Math.random() * 1.2;
            EliteMobManager.spawnParticleSafe(world, Particle.CRIT,
                loc.clone().add(Math.cos(angle) * speed, 0.3 + Math.random() * 0.8, Math.sin(angle) * speed),
                1);
        }
        // 2. ????
        for (int i = 0; i < 20; i++) {
            EliteMobManager.spawnParticleSafe(world, Particle.DAMAGE_INDICATOR,
                loc.clone().add(Math.random()-0.5, 0.5+Math.random()*1.5, Math.random()-0.5),
                1);
        }
        // 3. ??????
        for (int i = 0; i < 15; i++) {
            EliteMobManager.spawnParticleSafe(world, Particle.WITCH,
                loc.clone().add(Math.random()-0.5, 1+Math.random()*1.5, Math.random()-0.5),
                1);
        }
        // 4. ??????
        for (int i = 0; i < 12; i++) {
            EliteMobManager.spawnParticleSafe(world, Particle.SOUL,
                loc.clone().add(Math.random()-0.5, 1+Math.random()*2, Math.random()-0.5),
                1);
        }
        // 5. ????????
        for (int i = 0; i < 10; i++) {
            EliteMobManager.spawnParticleSafe(world, Particle.ENCHANTED_HIT,
                loc.clone().add(Math.random()-0.5, Math.random()*2, Math.random()-0.5),
                1);
        }
        // 6. ???????
        for (int i = 0; i < 8; i++) {
            EliteMobManager.spawnParticleSafe(world, Particle.TOTEM_OF_UNDYING,
                loc.clone().add(Math.random()-0.5, 0.5+Math.random(), Math.random()-0.5),
                1);
        }
        // 7. Lv.5+ ??/????
        if (level >= 5) {
            for (int i = 0; i < 15; i++) {
                double angle = Math.random() * Math.PI * 2;
                double r = 0.3 + Math.random() * 1.0;
                EliteMobManager.spawnParticleSafe(world, Particle.FLAME,
                    loc.clone().add(Math.cos(angle)*r, Math.random()*1.5, Math.sin(angle)*r),
                    1);
            }
        }
        // 8. Lv.7+ ????
        if (level >= 7) {
            for (int i = 0; i < 25; i++) {
                double angle = (i / 25.0) * Math.PI * 2;
                double r = 1.0 + Math.sin(angle * 3) * 0.3;
                EliteMobManager.spawnParticleSafe(world, Particle.DRAGON_BREATH,
                    loc.clone().add(Math.cos(angle)*r, 0.5+Math.random()*1.5, Math.sin(angle)*r),
                    1);
            }
        }
        // 9. Lv.9+ ????
        if (level >= 9) {
            for (int i = 0; i < 20; i++) {
                EliteMobManager.spawnParticleSafe(world, Particle.SOUL_FIRE_FLAME,
                    loc.clone().add(Math.random()-0.5, Math.random()*2, Math.random()-0.5),
                    1);
            }
        }
        // ??
        loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_DEATH, 1.0f, 1.0f);
        loc.getWorld().playSound(loc, Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.5f);
    }

    /**
     * ??????? - ????????????
     */
    /**
     * 精英苦力怕爆炸：清除身上所有药水效果（防止原版机制把 Boss 晋升等正面效果
     * 扩散成正面药水云），并在爆炸位置生成负面药水云（迟缓/中毒/凋零）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEliteCreeperExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper)) return;
        if (!EliteMobManager.isElite(creeper)) return;
        // 爆炸前清除苦力怕身上的药水效果，避免把原版效果（含正面效果）扩散成药水云
        for (PotionEffect effect : new ArrayList<>(creeper.getActivePotionEffects())) {
            creeper.removePotionEffect(effect.getType());
        }
        int level = EliteMobManager.getEliteLevel(creeper);
        onCreeperExplosion(creeper, event.getLocation(), level);
    }

    public void onCreeperExplosion(Creeper creeper, Location loc, int level) {
        if (!plugin.getEliteConfig().isParticleEffectsEnabled()) return;
        try {
            // 2. ????????
            for (int i = 0; i < 40; i++) {
                double angle = Math.random() * Math.PI * 2;
                double speed = 0.5 + Math.random() * 1.5;
                EliteMobManager.spawnParticleSafe(loc.getWorld(), Particle.EXPLOSION,
                    loc.clone().add(Math.cos(angle) * speed, 0.5 + Math.random() * 0.5, Math.sin(angle) * speed),
                    1);
            }
            // ????????
            for (int i = 0; i < 25; i++) {
                double angle = Math.random() * Math.PI * 2;
                double radius = 0.5 + Math.random() * 2.0;
                EliteMobManager.spawnParticleSafe(loc.getWorld(), Particle.INSTANT_EFFECT,
                    loc.clone().add(Math.cos(angle) * radius, 1.0 + Math.random(), Math.sin(angle) * radius),
                    1);
            }
            // ???????????????
            org.bukkit.entity.AreaEffectCloud cloud = (org.bukkit.entity.AreaEffectCloud) loc.getWorld().spawnEntity(loc.clone(), EntityType.AREA_EFFECT_CLOUD);
            cloud.setDuration(100 + level * 20);
            cloud.setRadius(2.0f + level * 0.2f);
            cloud.setRadiusPerTick(0);
            cloud.setWaitTime(0);
            cloud.setReapplicationDelay(20);
            cloud.setColor(org.bukkit.Color.fromRGB(60, 0, 80));
            if (level >= 3) cloud.addCustomEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, Math.min(level/3, 2)), true);
            if (level >= 5) cloud.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 100, Math.min(level/4, 2)), true);
            if (level >= 7) cloud.addCustomEffect(new PotionEffect(PotionEffectType.WITHER, 60, Math.min(level/5, 1)), true);

            loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f);
            loc.getWorld().playSound(loc, Sound.ENTITY_CREEPER_PRIMED, 1.5f, 0.8f);
        } catch (Exception ignored) {}
    }

    /**
     * ?????????Lv.8+ ???
     */
    private ItemStack createEliteSkull(EntityType type, int level) {
        Material skullMat = getSkullMaterial(type);
        if (skullMat == null || skullMat == Material.AIR) return null;

        ItemStack skull = new ItemStack(skullMat);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta == null) return null;

        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "\u2694 \u7cbe\u82f1\u9b54\u7269\u5934\u9885");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
        lore.add(ChatColor.GRAY + "\u602a\u7269: " + ChatColor.WHITE + fmt(type));
        lore.add(ChatColor.GRAY + "\u7b49\u7ea7: " + ChatColor.RED + "Lv." + level);
        lore.add(ChatColor.GRAY + "\u51fb\u6740\u65e5\u671f: " + ChatColor.YELLOW + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        lore.add(ChatColor.GRAY + "\u7a00\u6709\u5ea6: " + (level >= 10 ? ChatColor.DARK_PURPLE + "\u4f20\u5947" : level >= 8 ? ChatColor.GOLD + "\u53f2\u8bd7" : ChatColor.BLUE + "\u7a00\u6709"));
        lore.add(ChatColor.DARK_GRAY + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
        meta.setLore(lore);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        if (level >= 10) meta.addEnchant(Enchantment.MENDING, 1, true);
        skull.setItemMeta(meta);
        return skull;
    }

    private Material getSkullMaterial(EntityType type) {
        return switch (type) {
            case ZOMBIE, HUSK, DROWNED -> Material.ZOMBIE_HEAD;
            case SKELETON, STRAY -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case CREEPER -> Material.CREEPER_HEAD;
            case PIGLIN, PIGLIN_BRUTE -> Material.PIGLIN_HEAD;
            case ENDER_DRAGON -> Material.DRAGON_HEAD;
            case PLAYER -> Material.PLAYER_HEAD;
            default -> null;
        };
    }

    private String fmt(org.bukkit.entity.EntityType t) {
        return StringUtil.formatName(t.name());
    }

    /**
     * 处理精英/Boss 的掉落物（统一入口）。
     *
     * <p>掉落逻辑与原版铁砧淬炼一致：按 loot.essence-drops 等级段概率
     * 掉落武器精华/护甲精华（带等级），精华用于铁砧淬炼装备。</p>
     *
     * @param event 死亡事件（向其 Drops 添加物品）
     * @param level 精英等级
     * @param boss  是否为 Boss（Boss 使用更高的判定概率）
     */
    /**
     * 处理精英/Boss 的掉落物（统一入口）。
     *
     * <p>掉落优先级: 宝石(概率最高) > 保护符 > 符文(概率最低)。
     * 宝石掉落时从 gems/*.yml 所有允许掉落的宝石中按各自 chance 权重随机选一颗。</p>
     */
    private void rollGemDrops(EntityDeathEvent event, int level, boolean boss) {
        EliteConfig cfg = plugin.getEliteConfig();
        boolean enabled = cfg.isGemDropsEnabled();
        if (!enabled) return;

        // 1) 宝石掉落：必掉（只要 gems/*.yml 有可用宝石），颗数随精英等级提升，每颗独立权重随机（可不同种）
        EntityType mobType = event.getEntity().getType();
        List<EliteConfig.CustomDrop> pool = new ArrayList<>();
        for (EliteConfig.CustomDrop d : cfg.getCustomDrops()) {
            if (d.allows(mobType) && d.getChance(level) > 0) pool.add(d);
        }
        if (!pool.isEmpty()) {
            // 颗数 = 1 + 精英等级/3（Lv1→1, Lv3→2, Lv6→3, Lv9→4, Lv12→5, Lv15→6, Lv18→7），Boss 额外 +1
            int count = Math.min(1 + level / 3 + (boss ? 1 : 0), 10);
            for (int n = 0; n < count; n++) {
                // 每颗独立按权重随机选宝石（可同种也可不同种）
                EliteConfig.CustomDrop chosen = pickWeightedGem(pool, level);
                int amt = randomInt(chosen.amountMin, chosen.amountMax);
                // 宝石等级由精英等级决定（1 + level/3，上限为该宝石 max-level）
                int gemLevel = Math.max(1, Math.min(chosen.maxLevel, 1 + level / 3));
                ItemStack item = buildCustomDrop(chosen, gemLevel);
                if (item != null) {
                    item.setAmount(amt);
                    event.getDrops().add(item);
                }
            }
        }

        // 2) 保护符掉落（概率介于宝石与符文之间：宝石 > 保护符 >>> 符文）
        double charmChance = cfg.getCharmDropChance();
        if (boss) charmChance = Math.min(charmChance * 1.5, 1.0);
        if (charmChance > 0 && rng.nextDouble() < charmChance) {
            event.getDrops().add(com.clawx.elitemobs.essence.EliteEssenceFactory
                    .createProtectionCharm(plugin.getMessages()));
        }

        // 3) 符文（极难掉落：概率最低，Boss ×2）
        if (cfg.isRuneDropsEnabled()) {
            double runeChance = cfg.getRuneDropChance();
            if (boss) runeChance *= 2.0;
            if (runeChance > 0 && rng.nextDouble() < runeChance) {
                String[] types = {"HEALTH", "SPEED", "STRENGTH", "REGEN", "RESIST", "FIRE"};
                // 符文掉落等级公式（config rune.drops 可配置）:
                //   runeLevel = clamp(base + floor(精英等级 / divisor), 1, max-level)
                //   默认 base=1 divisor=3 max=10: 精英1-2→Lv1 / 3-5→2 / 6-8→3 / 9-11→4 / 12-14→5 / 15-17→6 / 18-20→7
                int runeLevel = Math.max(1, Math.min(cfg.getRuneDropMaxLevel(),
                        cfg.getRuneDropLevelBase() + level / cfg.getRuneDropLevelDivisor()));
                ItemStack rune = com.clawx.elitemobs.rune.EliteRuneFactory.createRune(
                        types[rng.nextInt(types.length)], runeLevel, plugin.getMessages());
                event.getDrops().add(rune);
            }
        }
    }

    /** 从允许掉落的宝石池中按各自 chance 加权随机选一颗（权重 = 该宝石在指定等级段的概率）。 */
    private EliteConfig.CustomDrop pickWeightedGem(List<EliteConfig.CustomDrop> pool, int level) {
        double total = 0;
        for (EliteConfig.CustomDrop d : pool) total += d.getChance(level);
        double roll = rng.nextDouble() * total;
        EliteConfig.CustomDrop chosen = pool.get(pool.size() - 1);
        double acc = 0;
        for (EliteConfig.CustomDrop d : pool) {
            acc += d.getChance(level);
            if (roll < acc) { chosen = d; break; }
        }
        return chosen;
    }

    /** 构建自定义宝石物品（材质/头颅纹理/药水/附魔/光效/名称/Lore，带宝石等级）。 */
    private ItemStack buildCustomDrop(EliteConfig.CustomDrop d, int level) {
        try {
            return d.build(level);
        } catch (Exception e) {
            plugin.getLogger().warning("构建自定义宝石失败 " + d.id + ": " + e.getMessage());
            return null;
        }
    }

    /** 给头颅应用 base64 纹理（反射兼容 Paper）。 */
    private void applySkullTexture(SkullMeta meta, String base64) {
        try {
            Class<?> profileClass = Class.forName("com.destroystokyo.paper.profile.CraftPlayerProfile");
            java.lang.reflect.Constructor<?> ctor = profileClass.getConstructor(java.util.UUID.class, String.class);
            Object profile = ctor.newInstance(java.util.UUID.randomUUID(), null);
            java.lang.reflect.Method setProperty = profile.getClass().getMethod("setProperty",
                    Class.forName("com.destroystokyo.paper.profile.ProfileProperty"));
            setProperty.invoke(profile, new Object[]{new com.destroystokyo.paper.profile.ProfileProperty("textures", base64, null)});
            java.lang.reflect.Method setPlayerProfile = meta.getClass().getMethod("setPlayerProfile",
                    Class.forName("com.destroystokyo.paper.profile.PlayerProfile"));
            setPlayerProfile.invoke(meta, profile);
        } catch (Exception ignored) {}
    }

    private int randomInt(int min, int max) {
        return (min >= max) ? min : min + rng.nextInt(max - min + 1);
    }

    /** 金币显示格式化：整数不带小数，否则保留两位 */
    private String fmtMoney(double m) {
        if (m == Math.floor(m) && !Double.isInfinite(m)) return String.valueOf((long) m);
        return String.format("%.2f", m);
    }

    /** 玩家当前连杀数（供 PAPI/其他模块使用） */
    public int getPlayerCombo(java.util.UUID uuid) {
        return comboKills.getOrDefault(uuid, 0);
    }
}
