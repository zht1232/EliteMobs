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
            var lvlKey = new org.bukkit.NamespacedKey(plugin, "armor_lv");
            if (pdc.has(lvlKey, org.bukkit.persistence.PersistentDataType.INTEGER)) {
                total += pdc.get(lvlKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            }
        }
        return total;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSetBonusDamageReduction(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        int setLevel = getEliteSetLevel(p);
        if (setLevel <= 0) return;

        // 套装加成：每件平均等级提供2%额外减伤，最高20%
        double setBonus = Math.min(setLevel * 2.0, 20.0) / 100.0;
        event.setDamage(event.getDamage() * (1.0 - setBonus));
    }

    // 套装效果粒子（每2秒检测一次）
    public void startSetBonusTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                int setLevel = getEliteSetLevel(p);
                if (setLevel >= 4) {
                    // 4件以上：生命恢复 + 粒子
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.REGENERATION, 60, 0, true, false));
                    EliteMobManager.spawnParticleSafe(p.getWorld(), org.bukkit.Particle.TOTEM_OF_UNDYING,
                        p.getLocation().add(0, 0.5, 0), 1);
                } else if (setLevel >= 2) {
                    // 2件以上：速度提升
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SPEED, 60, 0, true, false));
                }
            }
        }, 40L, 40L);
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
     * 处理精英/Boss 的宝石与自定义掉落物（统一入口）。
     *
     * <p>snowygems 模式: 通过 {@link SnowyGemsHook} 调用 SnowyGems 真实 API，
     * 构建可镶嵌的真实宝石掉落。custom 模式: 按配置中的自定义掉落物逐条判定。</p>
     *
     * @param event 死亡事件（向其 Drops 添加物品）
     * @param level 精英等级
     * @param boss  是否为 Boss（Boss 使用更高的判定概率）
     */
    private void rollGemDrops(EntityDeathEvent event, int level, boolean boss) {
        EliteConfig cfg = plugin.getEliteConfig();
        String mode = cfg.getGemDropMode();
        if ("disabled".equals(mode)) return;
        if (!cfg.isGemDropsEnabled()) return;

        if ("snowygems".equals(mode)) {
            if (!SnowyGemsHook.isAvailable()) {
                if (SnowyGemsHook.isPluginLoaded())
                    plugin.getLogger().warning("gem-drops.mode 为 snowygems 但 SnowyGems 宝石未就绪（可能 MC 版本不兼容），已跳过宝石掉落。可改用 custom 模式或等 SnowyGems 适配。");
                else
                    plugin.getLogger().warning("gem-drops.mode 为 snowygems 但未检测到 SnowyGems 插件，已跳过宝石掉落。可改用 custom 模式。");
                return;
            }
            // 按等级段判定是否掉落（Boss 用 boss 概率）
            if (rng.nextDouble() >= cfg.getGemDropChance(level, boss)) return;
            int amount = randomInt(cfg.getSnowyAmountMin(), cfg.getSnowyAmountMax());
            for (int i = 0; i < amount; i++) {
                String gemId = SnowyGemsHook.randomGemId(rng, cfg.getSnowyGemPool(level));
                if (gemId == null) continue;
                ItemStack gem = SnowyGemsHook.buildGem(gemId, 1);
                if (gem != null) event.getDrops().add(gem);
            }
        } else if ("custom".equals(mode)) {
            // 由服主配置的每条掉落物独立判定
            for (EliteConfig.CustomDrop drop : cfg.getCustomDrops()) {
                if (!drop.allows(event.getEntityType())) continue; // 按生物类型过滤
                double c = drop.getChance(level);
                if (c <= 0.0) continue;
                if (rng.nextDouble() >= c) continue;
                ItemStack item = drop.build();
                item.setAmount(randomInt(drop.amountMin, drop.amountMax));
                event.getDrops().add(item);
            }
        }
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
