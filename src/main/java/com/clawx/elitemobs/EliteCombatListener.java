package com.clawx.elitemobs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class EliteCombatListener implements Listener {
    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();
    private final Map<UUID, Integer> comboKills = new HashMap<>();

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
        int setLevel = getEliteSetLevel(p);
        if (setLevel <= 0) return;

        // 套装加成：每点套装等级提供 X% 额外减伤，封顶 Y%（config: armor-set-bonus）
        double setBonus = Math.min(setLevel * cfg.getSetBonusReductionPerLevel(),
                cfg.getSetBonusMaxReduction()) / 100.0;
        event.setDamage(event.getDamage() * (1.0 - setBonus));
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
                    else if (cp.getLocation().distance(e.getLocation()) > range) need = true;
                }
                if (!need) continue;
                // 寻找范围内最近的非豁免玩家
                Player best = null;
                double bestDist = range;
                Location loc = e.getLocation();
                for (Entity ent : e.getNearbyEntities(range, range, range)) {
                    if (!(ent instanceof Player p) || p.isDead() || !p.isOnline()) continue;
                    if (p.hasPermission("elitemobs.bypass")) continue;
                    double d = p.getLocation().distance(loc);
                    if (d <= bestDist) { bestDist = d; best = p; }
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
            double max = e.getAttribute(Attribute.MAX_HEALTH).getBaseValue();
            double pct = Math.max(0, (hp / max) * 100);
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < 10; i++) bar.append(i < (int) Math.ceil(pct / 10.0) ? ChatColor.DARK_RED + "\u2588" : ChatColor.GRAY + "\u2588");
            p.sendActionBar(ChatColor.RED + "[" + bar + ChatColor.RED + "] " + ChatColor.GOLD + fmt(e.getType()) + " Lv." + lv + ChatColor.GRAY + " | " + String.format("%.0f%%", pct));
        }
    }

    /** 主手武器上镶嵌的宝石效果：雷电=概率召唤闪电 / 击退=稳定击退敌人（按宝石等级）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAttackWithGem(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target.isDead()) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || !hand.hasItemMeta()) return;

        String[] gemIds = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGems(hand);
        int[] gemLvs = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGemLevels(hand);

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
                    target.getWorld().strikeLightningEffect(target.getLocation());
                    target.getWorld().playSound(target.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
                    target.damage(2.0 + lv * 0.5, p);
                }
            }
        }
    }

    /** 根据宝石 id 返回效果类型（通过 CustomDrop 定义）。 */
    private String gemEffectFor(String gemId) {
        for (var d : plugin.getEliteConfig().getCustomDrops()) {
            if (d.id != null && d.id.equalsIgnoreCase(gemId) && d.effect != null) {
                return d.effect.toLowerCase();
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEliteDeath(EntityDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!EliteMobManager.isElite(e)) return;

        // Boss死亡：清理血条 + 专属掉落
        if (com.clawx.elitemobs.ai.EliteBossManager.isBoss(e)) {
            plugin.getBossManager().onBossDeath(e);
            // Boss专属掉落：宝石/自定义掉落物 + 头颅
            int level = EliteMobManager.getEliteLevel(e);
            rollGemDrops(event, level, true);

            // Boss击杀广播
            Player killer = e.getKiller();
            if (killer != null) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "\u2620 "
                    + ChatColor.GREEN + killer.getName()
                    + ChatColor.GOLD + " \u51fb\u6740\u4e86 Boss "
                    + ChatColor.RED + e.getType().name().toLowerCase().replace('_', ' ')
                    + ChatColor.GRAY + " [Lv." + level + "]!");
            }
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

        // ===== 宝石/自定义掉落物 =====
        rollGemDrops(event, level, false);

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
        String n = t.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : n.split(" ")) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return sb.toString().trim();
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
        if (!cfg.isGemDropsEnabled()) return;

        // 1) 宝石掉落：先按等级段概率判定（宝石概率最高），成功则掉落多颗，每颗独立权重随机
        double gemChance = cfg.getGemDropChance(level, boss);
        if (gemChance > 0 && rng.nextDouble() < gemChance) {
            EntityType mobType = event.getEntity().getType();
            List<EliteConfig.CustomDrop> pool = new ArrayList<>();
            for (EliteConfig.CustomDrop d : cfg.getCustomDrops()) {
                if (d.allows(mobType) && d.getChance(level) > 0) pool.add(d);
            }
            if (!pool.isEmpty()) {
                // 掉落颗数：普通精英 amount-min~max，Boss 更多（boss-min~max）
                int min = boss ? cfg.getGemBossMin() : cfg.getGemAmountMin();
                int max = boss ? cfg.getGemBossMax() : cfg.getGemAmountMax();
                int count = randomInt(min, max);
                for (int n = 0; n < count; n++) {
                    // 每颗独立按权重随机选宝石（可同种也可不同种）
                    EliteConfig.CustomDrop chosen = pickWeightedGem(pool, level);
                    int amt = randomInt(chosen.amountMin, chosen.amountMax);
                    // 宝石等级由精英等级决定（1 + level/3，上限为该宝石 max-level）
                    int gemLevel = Math.max(1, Math.min(chosen.maxLevel, 1 + level / 3));
                    ItemStack item = buildCustomDrop(chosen, gemLevel);
                    if (item != null) { item.setAmount(amt); event.getDrops().add(item); }
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
