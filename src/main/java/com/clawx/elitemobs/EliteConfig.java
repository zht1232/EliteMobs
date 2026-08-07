package com.clawx.elitemobs;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import com.clawx.elitemobs.combat.EnchantUtil;
import com.clawx.elitemobs.ai.EliteAffix;
import java.io.File;
import java.util.*;

public class EliteConfig {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private boolean enabled;
    private int aiTickInterval;
    private double eliteSpawnChance;
    private int minSpawnY;
    private int maxElitesPerChunk;
    private int targetRange = 16;   // 精英主动索敌范围（格），应对其他插件取消追击事件
    // 精英生成距离：等级越高的精英生成离最近玩家越远（避免高级怪刷在玩家/基地旁边）
    private double spawnDistBase = 8;
    private double spawnDistPerLevel = 0.8;
    private double spawnDistMax = 48;
    private double spawnMinPlayerDist = 48;   // 生成前距离检查：离玩家太近不精英化（0=禁用）
    // 护甲套装加成（armor-set-bonus）
    private boolean setBonusEnabled = true;
    private double setBonusReductionPerLevel = 2.0;   // 每点套装等级减伤 %
    private double setBonusMaxReduction = 20.0;       // 减伤封顶 %
    private int setBonusSpeedLevel = 6;               // 套装等级达到该值获得速度效果
    private int setBonusRegenLevel = 10;              // 套装等级达到该值获得再生效果
    // Boss 第二阶段（boss-phase2）
    private boolean bossPhase2Enabled = true;
    private double bossPhase2HpRatio = 0.5;           // 血量低于该比例触发第二阶段
    // Boss 体型放大（boss-scale）
    private double bossScale = 1.5;                    // 1.0=不放大
    // Boss 技能：跳跃扑击落地震击（ground-pound，借鉴原版 ground_pound）
    private boolean groundPoundEnabled = true;
    private double groundPoundChance = 0.10;           // 被玩家击中时触发概率
    private int groundPoundCooldownTicks = 200;        // 触发冷却（tick）
    private double groundPoundLaunchY = 1.5;           // 起跳初速度（y）
    private int groundPoundFallDelay = 20;             // 跳起后多久开始检测落地
    private double groundPoundRadius = 10;             // 落地震击影响半径
    private double groundPoundKnockback = 2.0;         // 击飞水平速度
    private double groundPoundKnockbackY = 1.5;        // 击飞垂直速度
    private int groundPoundSlowness = 60;              // 命中目标减速时长
    // Boss 技能：引导治疗（channel-healing，完整版：暂停AI+光束引导，借鉴原版 channel_healing）
    private boolean channelHealingEnabled = true;
    private int channelHealingScanInterval = 40;       // 扫描间隔（tick）
    private double channelHealingSearchRadius = 20;    // 治疗目标搜索半径
    private double channelHealingMaxDistance = 25;     // 引导最远距离（超出中断）
    private double channelHealingHealThreshold = 0.8;  // 目标血量低于该比例才治疗
    private double channelHealingHealFactor = 0.5;     // 每10tick回复 boss等级×该值 血量
    private int channelHealingLocalCooldown = 20;      // 引导结束后冷却
    // Boss 技能：封印（seal，仅二阶段后短时封印玩家淬炼加成）
    private boolean sealEnabled = true;
    private int sealDuration = 5;                      // 封印时长（秒）
    private double sealChance = 0.5;                   // 触发概率
    private double sealRadius = 12;                    // 影响半径
    private Set<EntityType> enabledMobTypes;
    private Map<EntityType, EliteMobProfile> mobProfiles;
    private boolean wallClimbEnabled, blockBreakEnabled, itemStealEnabled, damageScalingEnabled, weaponEnhancementEnabled;
    private double wallClimbSpeed; private int wallClimbMaxHeight;
    private Set<Material> breakableBlocks; private int blockBreakCooldownTicks, blockRestoreTicks; private boolean restoreBrokenBlocks;
    private double itemStealChance; private int itemStealCooldownTicks, maxStolenItems; private boolean returnStolenItemsOnDeath;
    private double damagePerMinute, damagePerBlockFromSpawn, maxDamageMultiplier, baseDamageMultiplier;
    private double weaponEnchantChance; private int maxEnchantLevel; private boolean allowNetheriteWeapons;
    private double eliteWeaponDropChance;    private boolean randomArmorMaterials;
    private double helmetChance, chestplateChance, leggingsChance, bootsChance;
    private Map<Material, Integer> materialPool = new LinkedHashMap<>();
    private Map<Enchantment, Integer> weaponEnchantPool = new LinkedHashMap<>();
    private Map<Enchantment, Integer> armorEnchantPool = new LinkedHashMap<>();
    // Armor enchant config
    private double armorDropChance;
    private double armorEnchantChance; private boolean armorEnchantEnabled;
    private int minArmorEnchantLvl, maxArmorEnchantLvl;
    // Elite stats
    private double healthMultiplierMin, healthMultiplierMax, damageMultiplierMin, damageMultiplierMax, speedMultiplierMin, speedMultiplierMax;
    private boolean glowEnabled, nameTagEnabled, particleEffectsEnabled, eliteCreeperEffectEnabled; private int glowMinLevel;
    private boolean lightningEnabled; private int lightningMinLevel;
    private String nameTagFormat; private int nameTagVisibleRange;
    private boolean respectWorldGuard, respectGriefPrevention, respectTowny, respectFactions;
    private Set<String> disabledWorlds; private Set<String> onlyEnabledWorlds;
    private boolean customLootEnabled; private double lootDropChance; private int minXpBonus, maxXpBonus;
    private boolean luckPermsEnabled; private List<Map<String, Object>> lpGroups;
    // Essence drops
    private boolean essenceDropsEnabled;
    private double essenceDropChanceLow;    // Lv.1-3
    private double essenceDropChanceMid;    // Lv.4-6
    private double essenceDropChanceHigh;   // Lv.7-9
    private double essenceDropChanceMax;    // Lv.10
    private int essenceMinAmount;
    private int essenceMaxAmount;
    private double essenceUpgradePerLevel = 0.045;
    private double essenceUpgradeMaxRate = 0.95;
    private double essenceUpgradeBaseRate = 0.35;
    private double essenceWeaponDmgMult = 0.5;
    private double essenceArmorBonusPerLevel = 1.5;
    private double essenceArmorMaxBonus = 15.0;
    // ????
    private boolean levelScalingEnabled;
    private String levelScalingFormula;
    private double levelScalingMultiplier;
    private boolean debugEnchant;
    // ????
    private boolean nightEnhancementEnabled;
    private double nightSpeedBonus;
    private double nightDamageMultiplier;
    private double nightSpawnMultiplier;
    private boolean vanillaNightBoostEnabled = true;
    // 月相系统（满月夜增强，借鉴原版 MoonPhaseDetector）
    private boolean moonPhaseEnabled = true;
    private double fullMoonSpawnMultiplier = 2.0;      // 满月夜生成率额外倍率
    private boolean fullMoonStrength = true;           // 满月夜精英额外获得力量/抗性
    private int fullMoonStrengthLevel = 1;             // 满月额外力量等级
    // 生成广播
    private boolean spawnAnnounceEnabled;
    private int spawnAnnounceMinLevel;
    private int spawnAnnounceRange;
    private boolean bossAlertEnabled = true;   // Boss 生成/击杀全服广播（独立于普通精英广播）
    // 宝石掉落模式
    private String gemDropMode;
    // 宝石掉落总开关
    private boolean gemDropsEnabled;
    // 宝石掉落概率（按等级段）
    private double gemDropChanceLow, gemDropChanceMid, gemDropChanceHigh, gemDropChanceMax;
    private int gemAmountMin = 1, gemAmountMax = 2;   // 普通精英每次掉落宝石颗数范围
    private int gemBossMin = 2, gemBossMax = 4;       // Boss 每次掉落宝石颗数范围
    // 保护符掉落概率（要求: 宝石概率 > 保护符 >>> 符文）
    private double charmDropChance = 0.08;
    // custom 模式: 自定义掉落物
    private List<CustomDrop> customDrops = new ArrayList<>();
    // 宝石效果缓存：gemId → effect（loadGemFiles 后构建，避免每次线性查找）
    private Map<String, String> gemEffectCache = new HashMap<>();
    // 击杀奖励 (Vault 金币 + PlayerPoints 点券)
    private boolean moneyRewardEnabled;
    private double moneyRewardBase, moneyRewardPerLevel, moneyRewardBossMult;
    private boolean pointsRewardEnabled;
    private int pointsRewardBase, pointsRewardPerLevel;
    private double pointsRewardBossMult;
    // 连杀加成
    private boolean comboEnabled;
    private double comboMaxMult, comboPerKill;
    private boolean comboResetOnDeath;
    // 精英词缀系统
    private boolean affixEnabled;
    private double affixChance;
    private int affixMin, affixMax;
    private final Map<EliteAffix, Integer> affixWeights = new LinkedHashMap<>();
    // 符文系统（镶嵌消耗）
    private double runeMoneyCost = 1000.0;
    private int runePointsCost = 50;
    private int runeXpCost = 30;
    // 符文掉落（极难）
    private boolean runeDropsEnabled = true;
    private double runeDropChance = 0.02;
    // 符文掉落等级公式: runeLevel = clamp(base + floor(精英等级/divisor), 1, maxLevel)
    private int runeDropLevelBase = 1;
    private int runeDropLevelDivisor = 3;
    private int runeDropMaxLevel = 10;

    public EliteConfig(JavaPlugin plugin) { this.plugin = plugin; this.config = plugin.getConfig(); load(); }

    @SuppressWarnings("unchecked")
    public void load() {
        enabled = config.getBoolean("general.enabled", true);
        aiTickInterval = config.getInt("general.ai-tick-interval", 10);
        eliteSpawnChance = config.getDouble("general.elite-spawn-chance", 0.05);
        minSpawnY = config.getInt("general.min-spawn-y", 0);
        maxElitesPerChunk = config.getInt("general.max-elites-per-chunk", 2);
        targetRange = config.getInt("general.target-range", 16);
        // 精英生成距离
        spawnDistBase = config.getDouble("general.spawn-distance.min-dist-base", 8);
        spawnDistPerLevel = config.getDouble("general.spawn-distance.dist-per-level", 0.8);
        spawnDistMax = config.getDouble("general.spawn-distance.max-dist", 48);
        spawnMinPlayerDist = config.getDouble("general.spawn-min-player-distance", 48);
        // 护甲套装加成
        setBonusEnabled = config.getBoolean("armor-set-bonus.enabled", true);
        setBonusReductionPerLevel = config.getDouble("armor-set-bonus.reduction-per-level", 2.0);
        setBonusMaxReduction = config.getDouble("armor-set-bonus.max-reduction", 20.0);
        setBonusSpeedLevel = config.getInt("armor-set-bonus.speed-level", 6);
        setBonusRegenLevel = config.getInt("armor-set-bonus.regen-level", 10);
        // Boss 第二阶段
        bossPhase2Enabled = config.getBoolean("boss-phase2.enabled", true);
        bossPhase2HpRatio = config.getDouble("boss-phase2.hp-ratio", 0.5);
        // Boss 体型放大与技能
        bossScale = Math.max(1.0, config.getDouble("boss-scale", 1.5));
        groundPoundEnabled = config.getBoolean("boss-skills.ground-pound.enabled", true);
        groundPoundChance = Math.max(0.0, Math.min(1.0, config.getDouble("boss-skills.ground-pound.trigger-chance", 0.10)));
        groundPoundCooldownTicks = config.getInt("boss-skills.ground-pound.global-cooldown", 200);
        groundPoundLaunchY = config.getDouble("boss-skills.ground-pound.launch-y", 1.5);
        groundPoundFallDelay = config.getInt("boss-skills.ground-pound.fall-delay", 20);
        groundPoundRadius = config.getDouble("boss-skills.ground-pound.radius", 10);
        groundPoundKnockback = config.getDouble("boss-skills.ground-pound.knockback", 2.0);
        groundPoundKnockbackY = config.getDouble("boss-skills.ground-pound.knockback-y", 1.5);
        groundPoundSlowness = config.getInt("boss-skills.ground-pound.slowness", 60);
        channelHealingEnabled = config.getBoolean("boss-skills.channel-healing.enabled", true);
        channelHealingScanInterval = Math.max(5, config.getInt("boss-skills.channel-healing.scan-interval", 40));
        channelHealingSearchRadius = config.getDouble("boss-skills.channel-healing.search-radius", 20);
        channelHealingMaxDistance = config.getDouble("boss-skills.channel-healing.max-channel-distance", 25);
        channelHealingHealThreshold = config.getDouble("boss-skills.channel-healing.heal-threshold", 0.8);
        channelHealingHealFactor = config.getDouble("boss-skills.channel-healing.heal-per-10ticks", 0.5);
        channelHealingLocalCooldown = config.getInt("boss-skills.channel-healing.local-cooldown", 20);
        // Boss 技能：封印
        sealEnabled = config.getBoolean("boss-skills.seal.enabled", true);
        sealDuration = Math.max(1, config.getInt("boss-skills.seal.duration", 5));
        sealChance = Math.max(0.0, Math.min(1.0, config.getDouble("boss-skills.seal.trigger-chance", 0.5)));
        sealRadius = config.getDouble("boss-skills.seal.radius", 12);
        enabledMobTypes = new HashSet<>();
        List<String> typeList = config.getStringList("general.enabled-mob-types");
        if (typeList.isEmpty()) {
            Collections.addAll(enabledMobTypes, EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
                    EntityType.CAVE_SPIDER, EntityType.WITHER_SKELETON, EntityType.PILLAGER, EntityType.VINDICATOR,
                    EntityType.EVOKER, EntityType.RAVAGER, EntityType.HUSK, EntityType.STRAY, EntityType.DROWNED,
                    EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE,
                    EntityType.ENDERMAN, EntityType.BLAZE, EntityType.WITCH, EntityType.CREEPER,
                    EntityType.HOGLIN, EntityType.GHAST, EntityType.MAGMA_CUBE, EntityType.SLIME,
                    EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.PHANTOM,
                    EntityType.SHULKER, EntityType.SILVERFISH, EntityType.ENDERMITE);
        } else {
            for (String name : typeList) {
                try { enabledMobTypes.add(EntityType.valueOf(name.toUpperCase())); }
                catch (IllegalArgumentException e) { plugin.getLogger().warning("Unknown mob type: " + name); }
            }
        }
        mobProfiles = new HashMap<>();
        // 优先从独立 mobs.yml 加载怪物定义（顶层直接是各怪物；兼容 config.yml 中带 mob-profiles 包裹的旧格式）
        org.bukkit.configuration.file.FileConfiguration mobCfg = loadYaml("mobs.yml");
        org.bukkit.configuration.ConfigurationSection profSec = null;
        if (mobCfg != null) {
            if (mobCfg.contains("mob-profiles")) {
                profSec = mobCfg.getConfigurationSection("mob-profiles");
            } else {
                // mobs.yml 顶层即各怪物定义（ZOMBIE: / SKELETON: ...）
                profSec = mobCfg.getRoot().getConfigurationSection("");
            }
        }
        if ((profSec == null || profSec.getKeys(false).isEmpty()) && config.contains("mob-profiles")) {
            profSec = config.getConfigurationSection("mob-profiles");
        }
        if (profSec != null) {
            for (String key : profSec.getKeys(false)) {
                try {
                    EntityType type = EntityType.valueOf(key.toUpperCase());
                    EliteMobProfile p = new EliteMobProfile();
                    p.healthMultiplier = profSec.getDouble(key + ".health-multiplier", 2.0);
                    p.damageMultiplier = profSec.getDouble(key + ".damage-multiplier", 1.5);
                    p.speedMultiplier = profSec.getDouble(key + ".speed-multiplier", 1.15);
                    p.canClimbWalls = profSec.getBoolean(key + ".can-climb-walls", false);
                    p.canBreakBlocks = profSec.getBoolean(key + ".can-break-blocks", false);
                    p.canStealItems = profSec.getBoolean(key + ".can-steal-items", true);
                    p.priority = profSec.getInt(key + ".priority", 5);
                    mobProfiles.put(type, p);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown mob type in profiles: " + key);
                }
            }
        }

        // Features
        wallClimbEnabled = config.getBoolean("features.wall-climb.enabled", true);
        wallClimbSpeed = config.getDouble("features.wall-climb.speed", 0.18);
        wallClimbMaxHeight = config.getInt("features.wall-climb.max-height", 20);

        blockBreakEnabled = config.getBoolean("features.block-break.enabled", true);
        blockBreakCooldownTicks = config.getInt("features.block-break.cooldown-ticks", 60);
        restoreBrokenBlocks = config.getBoolean("features.block-break.restore-blocks", true);
        blockRestoreTicks = config.getInt("features.block-break.restore-ticks", 200);
        breakableBlocks = new HashSet<>();
        List<String> breakList = config.getStringList("features.block-break.breakable-blocks");
        if (breakList.isEmpty()) {
            breakableBlocks.addAll(Arrays.asList(Material.STONE, Material.COBBLESTONE, Material.DIRT,
                    Material.GRASS_BLOCK, Material.SAND, Material.GRAVEL, Material.OAK_PLANKS,
                    Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS, Material.DARK_OAK_PLANKS,
                    Material.ACACIA_PLANKS, Material.JUNGLE_PLANKS, Material.GLASS, Material.IRON_ORE,
                    Material.COAL_ORE, Material.GOLD_ORE, Material.DIAMOND_ORE, Material.REDSTONE_ORE,
                    Material.EMERALD_ORE, Material.LAPIS_ORE));
        } else {
            for (String name : breakList) {
                try { breakableBlocks.add(Material.valueOf(name.toUpperCase())); }
                catch (IllegalArgumentException e) { plugin.getLogger().warning("Unknown block: " + name); }
            }
        }

        itemStealEnabled = config.getBoolean("features.item-steal.enabled", true);
        itemStealChance = config.getDouble("features.item-steal.steal-chance", 0.25);
        itemStealCooldownTicks = config.getInt("features.item-steal.cooldown-ticks", 80);
        returnStolenItemsOnDeath = config.getBoolean("features.item-steal.return-on-death", true);
        maxStolenItems = config.getInt("features.item-steal.max-stolen-items", 3);

        damageScalingEnabled = config.getBoolean("features.damage-scaling.enabled", true);
        damagePerMinute = config.getDouble("features.damage-scaling.damage-per-minute", 0.003);
        damagePerBlockFromSpawn = config.getDouble("features.damage-scaling.damage-per-block", 0.00005);
        maxDamageMultiplier = config.getDouble("features.damage-scaling.max-multiplier", 2.0);
        baseDamageMultiplier = config.getDouble("features.damage-scaling.base-multiplier", 1.0);

        weaponEnhancementEnabled = config.getBoolean("features.weapon-enhancement.enabled", true);
        weaponEnchantChance = config.getDouble("features.weapon-enhancement.enchant-chance", 0.40);
        maxEnchantLevel = config.getInt("features.weapon-enhancement.max-enchant-level", 4);
        allowNetheriteWeapons = config.getBoolean("features.weapon-enhancement.allow-netherite", false);
        eliteWeaponDropChance = config.getDouble("features.weapon-enhancement.elite-weapon-drop-chance", 0.90);

        // ??????
        levelScalingEnabled = config.getBoolean("equipment.armor-generation.level-scaling.enabled", true);
        levelScalingFormula = config.getString("equipment.armor-generation.level-scaling.formula", "exponential");
        levelScalingMultiplier = config.getDouble("equipment.armor-generation.level-scaling.base-multiplier", 0.15);
        debugEnchant = config.getBoolean("equipment.debug-enchant", false);
        // ????
        nightEnhancementEnabled = config.getBoolean("features.night-enhancement.enabled", true);
        nightSpeedBonus = config.getDouble("features.night-enhancement.speed-bonus", 0.12);
        nightDamageMultiplier = config.getDouble("features.night-enhancement.damage-multiplier", 1.2);
        nightSpawnMultiplier = config.getDouble("features.night-enhancement.spawn-multiplier", 1.5);
        vanillaNightBoostEnabled = config.getBoolean("features.night-enhancement.vanilla-mobs", true);
        // 月相系统（满月夜增强）
        moonPhaseEnabled = config.getBoolean("features.night-enhancement.moon-phase.enabled", true);
        fullMoonSpawnMultiplier = Math.max(1.0, config.getDouble("features.night-enhancement.moon-phase.full-moon-spawn-multiplier", 2.0));
        fullMoonStrength = config.getBoolean("features.night-enhancement.moon-phase.full-moon-strength", true);
        fullMoonStrengthLevel = Math.max(0, config.getInt("features.night-enhancement.moon-phase.full-moon-strength-level", 1));

        // 生成广播
        spawnAnnounceEnabled = config.getBoolean("general.spawn-announce.enabled", true);
        spawnAnnounceMinLevel = config.getInt("general.spawn-announce.min-level", 7);
        spawnAnnounceRange = config.getInt("general.spawn-announce.announce-range", -1);
        bossAlertEnabled = config.getBoolean("general.spawn-announce.boss-alert", true);

        // 宝石掉落模式
        gemDropMode = config.getString("gem-drops.mode", "custom");
        // 宝石掉落总开关 (向后兼容: 未配置时回退到旧的 essence-drops 开关)
        gemDropsEnabled = config.getBoolean("gem-drops.drops.enabled",
                config.getBoolean("loot.essence-drops.enabled", true));

        // 宝石掉落概率（按等级段）
        gemDropChanceLow = config.getDouble("gem-drops.gems.level-1-3",
                config.getDouble("gem-drops.drops.level-1-3",
                config.getDouble("loot.essence-drops.level-1-3", 0.15)));
        gemDropChanceMid = config.getDouble("gem-drops.gems.level-4-6",
                config.getDouble("gem-drops.drops.level-4-6",
                config.getDouble("loot.essence-drops.level-4-6", 0.35)));
        gemDropChanceHigh = config.getDouble("gem-drops.gems.level-7-9",
                config.getDouble("gem-drops.drops.level-7-9",
                config.getDouble("loot.essence-drops.level-7-9", 0.55)));
        gemDropChanceMax = config.getDouble("gem-drops.gems.level-10",
                config.getDouble("gem-drops.drops.level-10",
                config.getDouble("loot.essence-drops.level-10", 0.85)));
        gemAmountMin = Math.max(1, config.getInt("gem-drops.gems.amount-min", 1));
        gemAmountMax = Math.max(gemAmountMin, config.getInt("gem-drops.gems.amount-max", 2));
        gemBossMin = Math.max(1, config.getInt("gem-drops.gems.boss-min", 2));
        gemBossMax = Math.max(gemBossMin, config.getInt("gem-drops.gems.boss-max", 4));
        charmDropChance = config.getDouble("gem-drops.charm-drop-chance", 0.08);

        // custom 自定义掉落物（gem-drops.custom，向后兼容）
        customDrops = new ArrayList<>();
        if (config.contains("gem-drops.custom")) {
            for (Map<?, ?> raw : config.getMapList("gem-drops.custom")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) raw;
                    customDrops.add(CustomDrop.fromMap(this, m));
                } catch (Exception e) {
                    plugin.getLogger().warning("解析 gem-drops.custom 条目失败: " + e.getMessage());
                }
            }
        }

        // gems/*.yml 目录：每颗宝石一个文件，方便增删（推荐的宝石配置方式）
        loadGemFiles();

        // 构建宝石效果缓存（gemId → effect），供 gemEffectFor 快速查找
        gemEffectCache.clear();
        for (CustomDrop d : customDrops) {
            if (d.id != null && d.effect != null) {
                gemEffectCache.put(d.id.toLowerCase(), d.effect.toLowerCase());
            }
        }

        // 击杀奖励 (Vault 金币 + PlayerPoints 点券)
        moneyRewardEnabled = config.getBoolean("loot.rewards.money.enabled", true);
        moneyRewardBase = config.getDouble("loot.rewards.money.base", 0.0);
        moneyRewardPerLevel = config.getDouble("loot.rewards.money.per-level", 8.0);
        moneyRewardBossMult = config.getDouble("loot.rewards.money.boss-multiplier", 3.0);
        pointsRewardEnabled = config.getBoolean("loot.rewards.points.enabled", true);
        pointsRewardBase = config.getInt("loot.rewards.points.base", 0);
        pointsRewardPerLevel = config.getInt("loot.rewards.points.per-level", 1);
        pointsRewardBossMult = config.getDouble("loot.rewards.points.boss-multiplier", 3.0);
        comboEnabled = config.getBoolean("loot.rewards.combo.enabled", true);
        comboMaxMult = config.getDouble("loot.rewards.combo.max-multiplier", 3.0);
        comboPerKill = config.getDouble("loot.rewards.combo.per-kill", 0.1);
        comboResetOnDeath = config.getBoolean("loot.rewards.combo.reset-on-death", true);

        // 精英词缀系统
        affixEnabled = config.getBoolean("elite-affixes.enabled", true);
        affixChance = Math.max(0.0, Math.min(1.0, config.getDouble("elite-affixes.chance", 0.8)));
        affixMin = Math.max(1, config.getInt("elite-affixes.min-affixes", 1));
        affixMax = Math.max(affixMin, config.getInt("elite-affixes.max-affixes", 2));
        affixWeights.clear();
        if (config.contains("elite-affixes.weights")) {
            for (String key : config.getConfigurationSection("elite-affixes.weights").getKeys(false)) {
                EliteAffix a = EliteAffix.fromString(key.toUpperCase());
                if (a != null) affixWeights.put(a, Math.max(1, config.getInt("elite-affixes.weights." + key, 5)));
                else plugin.getLogger().warning("未知精英词缀: " + key);
            }
        }
        if (affixWeights.isEmpty()) {
            for (EliteAffix a : EliteAffix.values()) affixWeights.put(a, 5);
        }

        // Equipment generation config
        randomArmorMaterials = config.getBoolean("equipment.armor-generation.random-materials", true);
        helmetChance = config.getDouble("equipment.armor-generation.helmet-chance", 1.0);
        chestplateChance = config.getDouble("equipment.armor-generation.chestplate-chance", 0.8);
        leggingsChance = config.getDouble("equipment.armor-generation.leggings-chance", 0.7);
        bootsChance = config.getDouble("equipment.armor-generation.boots-chance", 0.6);
        
        // Material Pool
        materialPool.clear();
        if (config.contains("equipment.armor-generation.material-pool")) {
            for (String key : config.getConfigurationSection("equipment.armor-generation.material-pool").getKeys(false)) {
                try {
                    Material mat = Material.valueOf(key.toUpperCase());
                    int weight = config.getInt("equipment.armor-generation.material-pool." + key, 10);
                    materialPool.put(mat, weight);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (materialPool.isEmpty()) {
            materialPool.put(Material.LEATHER, 30);
            materialPool.put(Material.GOLDEN_HELMET, 10);
            materialPool.put(Material.CHAINMAIL_HELMET, 10);
            materialPool.put(Material.IRON_HELMET, 20);
            materialPool.put(Material.DIAMOND_HELMET, 10);
            materialPool.put(Material.NETHERITE_HELMET, 2);
        }

        // Weapon Enchant Pool
        weaponEnchantPool.clear();
        if (config.contains("equipment.weapon-enchant.enchant-pool")) {
            parseEnchantPool(config.getConfigurationSection("equipment.weapon-enchant.enchant-pool"), weaponEnchantPool);
        }
        
        // Armor Enchant Pool
        armorEnchantPool.clear();
        if (config.contains("equipment.armor-enchant.enchant-pool")) {
            parseEnchantPool(config.getConfigurationSection("equipment.armor-enchant.enchant-pool"), armorEnchantPool);
        }

        // Equipment section
        armorDropChance = config.getDouble("equipment.armor-drop-chance", 1.0);
        // Armor enchant sub-section
        armorEnchantEnabled = config.getBoolean("equipment.armor-enchant.enabled", true);
        armorEnchantChance = config.getDouble("equipment.armor-enchant.chance", 0.8);
        minArmorEnchantLvl = config.getInt("equipment.armor-enchant.min-level", 1);
        maxArmorEnchantLvl = config.getInt("equipment.armor-enchant.max-level", 4);

        // Elite stats
        healthMultiplierMin = config.getDouble("elite-stats.health-multiplier-min", 1.2);
        healthMultiplierMax = config.getDouble("elite-stats.health-multiplier-max", 2.0);
        damageMultiplierMin = config.getDouble("elite-stats.damage-multiplier-min", 1.0);
        damageMultiplierMax = config.getDouble("elite-stats.damage-multiplier-max", 1.5);
        speedMultiplierMin = config.getDouble("elite-stats.speed-multiplier-min", 1.02);
        speedMultiplierMax = config.getDouble("elite-stats.speed-multiplier-max", 1.15);

        // Visuals
        glowEnabled = config.getBoolean("visuals.glow.enabled", true); glowMinLevel = config.getInt("visuals.glow.min-level", 3); eliteCreeperEffectEnabled = config.getBoolean("features.creeper-explosion.negative-effect.enabled", true);
        lightningEnabled = config.getBoolean("visuals.lightning.enabled", true); lightningMinLevel = config.getInt("visuals.lightning.min-level", 10);
        nameTagEnabled = config.getBoolean("visuals.name-tag.enabled", true);
        nameTagFormat = config.getString("visuals.name-tag.format", "&c\u2694 &6{type} &7[&4Lv.{level}&7]");
        nameTagVisibleRange = config.getInt("visuals.name-tag.visible-range", 32);
        particleEffectsEnabled = config.getBoolean("visuals.particles.enabled", true);

        // Protection
        respectWorldGuard = config.getBoolean("protection.respect-worldguard", true);
        respectGriefPrevention = config.getBoolean("protection.respect-griefprevention", true);
        respectTowny = config.getBoolean("protection.respect-towny", true);
        respectFactions = config.getBoolean("protection.respect-factions", true);
        disabledWorlds = new HashSet<>(config.getStringList("protection.disabled-worlds"));
        List<String> enabledList = config.getStringList("protection.only-enabled-worlds");
        onlyEnabledWorlds = enabledList.isEmpty() ? null : new HashSet<>(enabledList);

        // Loot
        customLootEnabled = config.getBoolean("loot.enabled", true);
        lootDropChance = config.getDouble("loot.drop-chance", 0.35);
        minXpBonus = config.getInt("loot.xp-bonus-min", 5);
        maxXpBonus = config.getInt("loot.xp-bonus-max", 30);

        // Essence drops
        essenceDropsEnabled = config.getBoolean("loot.essence-drops.enabled", true);
        essenceDropChanceLow = config.getDouble("loot.essence-drops.level-1-3", 0.15);
        essenceDropChanceMid = config.getDouble("loot.essence-drops.level-4-6", 0.35);
        essenceDropChanceHigh = config.getDouble("loot.essence-drops.level-7-9", 0.55);
        essenceDropChanceMax = config.getDouble("loot.essence-drops.level-10", 0.85);
        essenceMinAmount = config.getInt("loot.essence-drops.min-amount", 1);
        essenceMaxAmount = config.getInt("loot.essence-drops.max-amount", 2);
        essenceUpgradePerLevel = config.getDouble("essence-upgrade.per-level", 0.045);
        essenceUpgradeMaxRate = config.getDouble("essence-upgrade.max-rate", 0.95);
        essenceUpgradeBaseRate = config.getDouble("essence-upgrade.base-rate", 0.35);
        essenceWeaponDmgMult = config.getDouble("essence-upgrade.weapon-damage-multiplier", 0.5);
        essenceArmorBonusPerLevel = config.getDouble("essence-upgrade.armor-bonus-per-level", 1.5);
        essenceArmorMaxBonus = config.getDouble("essence-upgrade.armor-max-bonus", 15.0);

        // 符文系统
        runeMoneyCost = config.getDouble("rune.install-cost.money", 1000.0);
        runePointsCost = config.getInt("rune.install-cost.points", 50);
        runeXpCost = config.getInt("rune.install-cost.xp-levels", 30);
        runeDropsEnabled = config.getBoolean("rune.drops.enabled", true);
        runeDropChance = config.getDouble("rune.drops.chance", 0.02);
        runeDropLevelBase = config.getInt("rune.drops.level-base", 1);
        runeDropLevelDivisor = Math.max(1, config.getInt("rune.drops.level-divisor", 3));
        runeDropMaxLevel = Math.max(1, config.getInt("rune.drops.max-level", 10));

        // LuckPerms
        luckPermsEnabled = config.getBoolean("luckperms.enabled", false);
        lpGroups = new ArrayList<>();
        if (luckPermsEnabled && config.contains("luckperms.groups")) {
            List<Map<?, ?>> raw = config.getMapList("luckperms.groups");
            for (Map<?, ?> m : raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                lpGroups.add(typed);
            }
        }
    }

    // ========== Getters ==========

    public boolean isEnabled() { return enabled; }
    public int getAITickInterval() { return aiTickInterval; }
    public double getEliteSpawnChance() { return eliteSpawnChance; }
    public int getMinSpawnY() { return minSpawnY; }
    public int getMaxElitesPerChunk() { return maxElitesPerChunk; }
    public int getTargetRange() { return targetRange; }
    public double getSpawnDistBase() { return spawnDistBase; }
    public double getSpawnDistPerLevel() { return spawnDistPerLevel; }
    public double getSpawnDistMax() { return spawnDistMax; }
    public double getSpawnMinPlayerDist() { return spawnMinPlayerDist; }
    // ========== 护甲套装加成 ==========
    public boolean isSetBonusEnabled() { return setBonusEnabled; }
    public double getSetBonusReductionPerLevel() { return setBonusReductionPerLevel; }
    public double getSetBonusMaxReduction() { return setBonusMaxReduction; }
    public int getSetBonusSpeedLevel() { return setBonusSpeedLevel; }
    public int getSetBonusRegenLevel() { return setBonusRegenLevel; }
    // ========== Boss 第二阶段 ==========
    public boolean isBossPhase2Enabled() { return bossPhase2Enabled; }
    public double getBossPhase2HpRatio() { return bossPhase2HpRatio; }

    // ========== Boss 体型与技能 ==========
    public double getBossScale() { return bossScale; }
    public boolean isGroundPoundEnabled() { return groundPoundEnabled; }
    public double getGroundPoundChance() { return groundPoundChance; }
    public int getGroundPoundCooldownTicks() { return groundPoundCooldownTicks; }
    public double getGroundPoundLaunchY() { return groundPoundLaunchY; }
    public int getGroundPoundFallDelay() { return groundPoundFallDelay; }
    public double getGroundPoundRadius() { return groundPoundRadius; }
    public double getGroundPoundKnockback() { return groundPoundKnockback; }
    public double getGroundPoundKnockbackY() { return groundPoundKnockbackY; }
    public int getGroundPoundSlowness() { return groundPoundSlowness; }
    public boolean isChannelHealingEnabled() { return channelHealingEnabled; }
    public int getChannelHealingScanInterval() { return channelHealingScanInterval; }
    public double getChannelHealingSearchRadius() { return channelHealingSearchRadius; }
    public double getChannelHealingMaxDistance() { return channelHealingMaxDistance; }
    public double getChannelHealingHealThreshold() { return channelHealingHealThreshold; }
    public double getChannelHealingHealFactor() { return channelHealingHealFactor; }
    public int getChannelHealingLocalCooldown() { return channelHealingLocalCooldown; }
    // 封印技能
    public boolean isSealEnabled() { return sealEnabled; }
    public int getSealDuration() { return sealDuration; }
    public double getSealChance() { return sealChance; }
    public double getSealRadius() { return sealRadius; }
    public Set<EntityType> getEnabledMobTypes() { return enabledMobTypes; }
    public EliteMobProfile getProfile(EntityType type) { return mobProfiles.getOrDefault(type, DEFAULT_PROFILE); }
    public boolean isWallClimbEnabled() { return wallClimbEnabled; }
    public boolean isBlockBreakEnabled() { return blockBreakEnabled; }
    public boolean isItemStealEnabled() { return itemStealEnabled; }
    public boolean isDamageScalingEnabled() { return damageScalingEnabled; }
    public boolean isWeaponEnhancementEnabled() { return weaponEnhancementEnabled; }
    public double getWallClimbSpeed() { return wallClimbSpeed; }
    public int getWallClimbMaxHeight() { return wallClimbMaxHeight; }
    public Set<Material> getBreakableBlocks() { return breakableBlocks; }
    public int getBlockBreakCooldownTicks() { return blockBreakCooldownTicks; }
    public boolean isRestoreBrokenBlocks() { return restoreBrokenBlocks; }
    public int getBlockRestoreTicks() { return blockRestoreTicks; }
    public double getItemStealChance() { return itemStealChance; }
    public int getItemStealCooldownTicks() { return itemStealCooldownTicks; }
    public boolean isReturnStolenItemsOnDeath() { return returnStolenItemsOnDeath; }
    public int getMaxStolenItems() { return maxStolenItems; }
    public double getDamagePerMinute() { return damagePerMinute; }
    public double getDamagePerBlockFromSpawn() { return damagePerBlockFromSpawn; }
    public double getMaxDamageMultiplier() { return maxDamageMultiplier; }
    public double getBaseDamageMultiplier() { return baseDamageMultiplier; }
    public double getWeaponEnchantChance() { return weaponEnchantChance; }
    public int getMaxEnchantLevel() { return maxEnchantLevel; }
    public boolean isAllowNetheriteWeapons() { return allowNetheriteWeapons; }
    public double getEliteWeaponDropChance() { return eliteWeaponDropChance; }

    public boolean isRandomArmorMaterials() { return randomArmorMaterials; }
    public double getHelmetChance() { return helmetChance; }
    public double getChestplateChance() { return chestplateChance; }
    public double getLeggingsChance() { return leggingsChance; }
    public double getBootsChance() { return bootsChance; }
    public Map<Material, Integer> getMaterialPool() { return materialPool; }
    public Map<Enchantment, Integer> getWeaponEnchantPool() { return weaponEnchantPool; }
    public Map<Enchantment, Integer> getArmorEnchantPool() { return armorEnchantPool; }
    public double getArmorDropChance() { return armorDropChance; }
    public boolean isArmorEnchantEnabled() { return armorEnchantEnabled; }
    public double getArmorEnchantChance() { return armorEnchantChance; }
    public int getMinArmorEnchantLvl() { return minArmorEnchantLvl; }
    public int getMaxArmorEnchantLvl() { return maxArmorEnchantLvl; }
    public double getHealthMultiplierMin() { return healthMultiplierMin; }
    public double getHealthMultiplierMax() { return healthMultiplierMax; }
    public double getDamageMultiplierMin() { return damageMultiplierMin; }
    public double getDamageMultiplierMax() { return damageMultiplierMax; }
    public double getSpeedMultiplierMin() { return speedMultiplierMin; }
    public double getSpeedMultiplierMax() { return speedMultiplierMax; }
    public boolean isGlowEnabled() { return glowEnabled; }
    public int getGlowMinLevel() { return glowMinLevel; }
    public boolean isLightningEnabled() { return lightningEnabled; }
    public int getLightningMinLevel() { return lightningMinLevel; }
    public boolean isEliteCreeperEffectEnabled() { return eliteCreeperEffectEnabled; }
    public boolean isNameTagEnabled() { return nameTagEnabled; }
    public boolean isParticleEffectsEnabled() { return particleEffectsEnabled; }
    public String getNameTagFormat() { return nameTagFormat; }
    public int getNameTagVisibleRange() { return nameTagVisibleRange; }
    public boolean isRespectWorldGuard() { return respectWorldGuard; }
    public boolean isRespectGriefPrevention() { return respectGriefPrevention; }
    public boolean isRespectTowny() { return respectTowny; }
    public boolean isRespectFactions() { return respectFactions; }
    public boolean isWorldEnabled(String w) { return onlyEnabledWorlds != null ? onlyEnabledWorlds.contains(w) : !disabledWorlds.contains(w); }
    public boolean isCustomLootEnabled() { return customLootEnabled; }
    public double getLootDropChance() { return lootDropChance; }
    public int getMinXpBonus() { return minXpBonus; }
    public int getMaxXpBonus() { return maxXpBonus; }
    public boolean isLuckPermsEnabled() { return luckPermsEnabled; }
    public List<Map<String, Object>> getLuckPermsGroups() { return lpGroups; }
    public boolean isEssenceDropsEnabled() { return essenceDropsEnabled; }
    public double getEssenceDropChance(int level) {
        if (level >= 10) return essenceDropChanceMax;
        if (level >= 7) return essenceDropChanceHigh;
        if (level >= 4) return essenceDropChanceMid;
        return essenceDropChanceLow;
    }
    public int getEssenceMinAmount() { return essenceMinAmount; }
    public int getEssenceMaxAmount() { return essenceMaxAmount; }
    public double getEssenceUpgradePerLevel() { return essenceUpgradePerLevel; }
    public double getEssenceUpgradeMaxRate() { return essenceUpgradeMaxRate; }
    public double getEssenceUpgradeBaseRate() { return essenceUpgradeBaseRate; }
    public double getEssenceWeaponDmgMult() { return essenceWeaponDmgMult; }
    public double getEssenceArmorBonusPerLevel() { return essenceArmorBonusPerLevel; }
    public double getEssenceArmorMaxBonus() { return essenceArmorMaxBonus; }

    // ========== 符文系统 ==========
    public double getRuneMoneyCost() { return runeMoneyCost; }
    public int getRunePointsCost() { return runePointsCost; }
    public int getRuneXpCost() { return runeXpCost; }
    public boolean isRuneDropsEnabled() { return runeDropsEnabled; }
    public double getRuneDropChance() { return runeDropChance; }
    public int getRuneDropLevelBase() { return runeDropLevelBase; }
    public int getRuneDropLevelDivisor() { return runeDropLevelDivisor; }
    public int getRuneDropMaxLevel() { return runeDropMaxLevel; }

    // ????
    public boolean isLevelScalingEnabled() { return levelScalingEnabled; }
    public String getLevelScalingFormula() { return levelScalingFormula; }
    public double getLevelScalingMultiplier() { return levelScalingMultiplier; }
    public boolean isDebugEnchant() { return debugEnchant; }
    public boolean isNightEnhancementEnabled() { return nightEnhancementEnabled; }
    public double getNightSpeedBonus() { return nightSpeedBonus; }
    public double getNightDamageMultiplier() { return nightDamageMultiplier; }
    public double getNightSpawnMultiplier() { return nightSpawnMultiplier; }
    /** 原版怪物夜间是否也获得与精英一致的力量/速度强化 */
    public boolean isVanillaNightBoostEnabled() { return vanillaNightBoostEnabled; }
    // 月相系统
    public boolean isMoonPhaseEnabled() { return moonPhaseEnabled; }
    public double getFullMoonSpawnMultiplier() { return fullMoonSpawnMultiplier; }
    public boolean isFullMoonStrength() { return fullMoonStrength; }
    public int getFullMoonStrengthLevel() { return fullMoonStrengthLevel; }

    // 生成广播
    public boolean isSpawnAnnounceEnabled() { return spawnAnnounceEnabled; }
    public int getSpawnAnnounceMinLevel() { return spawnAnnounceMinLevel; }
    public int getSpawnAnnounceRange() { return spawnAnnounceRange; }
    /** Boss 生成/击杀全服广播开关（独立，默认开） */
    public boolean isBossAlertEnabled() { return bossAlertEnabled; }
    public String getGemDropMode() { return gemDropMode; }

    // ========== 宝石掉落 (gem-drops) ==========
    public boolean isGemDropsEnabled() { return gemDropsEnabled; }

    /** 宝石掉落概率（按等级段） */
    public double getGemDropChance(int level, boolean boss) {
        double base;
        if (level >= 10) base = gemDropChanceMax;
        else if (level >= 7) base = gemDropChanceHigh;
        else if (level >= 4) base = gemDropChanceMid;
        else base = gemDropChanceLow;
        return Math.max(0.0, Math.min(1.0, boss ? Math.min(base * 1.5, 1.0) : base));
    }

    public int getGemAmountMin() { return gemAmountMin; }
    public int getGemAmountMax() { return gemAmountMax; }
    public int getGemBossMin() { return gemBossMin; }
    public int getGemBossMax() { return gemBossMax; }
    public double getCharmDropChance() { return charmDropChance; }

    /**
     * 加载 plugins/EliteMobs/gems/*.yml 目录下的宝石定义。
     * 每个文件 = 一颗宝石：文件顶层直接写 id/material/name/lore/chance 等字段
     * （与 gem-drops.custom 条目同构）。复制文件即可新增宝石，删除文件即可移除宝石。
     */
    private void loadGemFiles() {
        File dir = new File(plugin.getDataFolder(), "gems");
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".yml"));
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            try {
                org.bukkit.configuration.file.YamlConfiguration gc =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                // 顶层即宝石定义（含 material/id 等字段）
                Map<String, Object> m = new LinkedHashMap<>(gc.getValues(false));
                m.putIfAbsent("id", f.getName().replace(".yml", "").toLowerCase());
                customDrops.add(CustomDrop.fromMap(this, m));
                plugin.getLogger().info("  宝石已加载: " + m.getOrDefault("id", f.getName())
                        + " (" + f.getName() + ")");
            } catch (Exception e) {
                plugin.getLogger().warning("解析宝石文件 " + f.getName() + " 失败: " + e.getMessage());
            }
        }
    }

    /** custom 模式: 自定义掉落物列表 */
    public List<CustomDrop> getCustomDrops() { return customDrops; }

    /** 根据宝石 id 返回效果类型（通过缓存 O(1) 查找；找不到返回 null）。
     *  提取自 EliteCombatListener / EliteEssenceUpgradeListener 的重复方法。 */
    public String gemEffectFor(String gemId) {
        return gemId != null ? gemEffectCache.get(gemId.toLowerCase()) : null;
    }

    /** 宝石效果缓存（gemId → effect），O(1) 查找 */
    public Map<String, String> getGemEffectCache() { return gemEffectCache; }

    /** 加载插件数据目录下的独立 YAML 文件（不存在返回 null）。 */
    private org.bukkit.configuration.file.FileConfiguration loadYaml(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.isFile()) return null;
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
    }

    // ========== 击杀奖励 (Vault 金币 + PlayerPoints 点券) ==========
    public boolean isMoneyRewardEnabled() { return moneyRewardEnabled; }
    public double getMoneyRewardBase() { return moneyRewardBase; }
    public double getMoneyRewardPerLevel() { return moneyRewardPerLevel; }
    public double getMoneyRewardBossMult() { return moneyRewardBossMult; }
    public boolean isPointsRewardEnabled() { return pointsRewardEnabled; }
    public int getPointsRewardBase() { return pointsRewardBase; }
    public int getPointsRewardPerLevel() { return pointsRewardPerLevel; }
    public double getPointsRewardBossMult() { return pointsRewardBossMult; }
    public boolean isComboEnabled() { return comboEnabled; }
    public double getComboMaxMult() { return comboMaxMult; }
    public double getComboPerKill() { return comboPerKill; }
    public boolean isComboResetOnDeath() { return comboResetOnDeath; }

    // ========== 精英词缀 ==========
    public boolean isAffixEnabled() { return affixEnabled; }
    public double getAffixChance() { return affixChance; }
    public int getAffixMin() { return affixMin; }
    public int getAffixMax() { return affixMax; }
    public Map<EliteAffix, Integer> getAffixWeights() { return affixWeights; }

    /** 按权重随机抽取 count 个不重复词缀（统一走 WeightedProbability 加权抽取） */
    public List<EliteAffix> rollAffixes(Random rng, int count) {
        return com.clawx.elitemobs.utils.WeightedProbability.pickMultiple(affixWeights, count);
    }

    public void setPluginLogger(java.util.logging.Logger logger) {
        // Used for debug logging from MobManager
    }

    private void parseEnchantPool(org.bukkit.configuration.ConfigurationSection section, Map<Enchantment, Integer> pool) {
        EnchantUtil.parseEnchantPool(section, pool);
    }

    public static class EliteMobProfile {
        public double healthMultiplier = 2.0, damageMultiplier = 1.5, speedMultiplier = 1.15;
        public boolean canClimbWalls = false, canBreakBlocks = false, canStealItems = true;
        public int priority = 5;
    }

    /** 未配置 mob type 的默认 profile（常量，避免每次 new） */
    private static final EliteMobProfile DEFAULT_PROFILE = new EliteMobProfile();

    /**
     * 自定义掉落物 (gem-drops.custom 列表条目)。
     * 服主可在配置中完全自主定义精英怪掉落物。
     */
    public static class CustomDrop {
        public String id;
        public Material material;
        public String name;
        public List<String> lore = new ArrayList<>();
        public Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
        public boolean glow;
        public final Map<String, Double> chance = new LinkedHashMap<>();
        public int amountMin = 1, amountMax = 1;
        // 高级选项
        public String texture;                    // PLAYER_HEAD 头颅纹理 base64
        public String potionType;                 // 药水效果类型（如 SPEED）
        public int potionAmplifier;               // 药水等级（0=一级）
        public int potionDuration;                // 药水时长（秒）
        public Set<EntityType> mobTypes = new HashSet<>(); // 限定掉落生物，空=全部
        public String effect;                     // 宝石效果类型: attack=攻击 / defense=防御 / thunder=雷电 / knockback=击退 / speed=移速 / rare=稀有
        public int maxLevel = 1;                  // 宝石最高等级（掉落时按精英等级折算，默认1=无等级）
        /** 所属配置（读取成功率参数用），fromMap 时注入。 */
        public EliteConfig owner;

        static CustomDrop fromMap(EliteConfig cfg, Map<String, Object> m) {
            CustomDrop d = new CustomDrop();
            d.owner = cfg;
            d.id = String.valueOf(m.getOrDefault("id", "drop"));
            Object mat = m.get("material");
            d.material = Material.EMERALD;
            if (mat != null) {
                try { d.material = Material.valueOf(String.valueOf(mat).toUpperCase()); }
                catch (IllegalArgumentException e) {
                    cfg.plugin.getLogger().warning("gem-drops.custom 未知材质: " + mat + " (使用 EMERALD)");
                }
            }
            Object nm = m.get("name");
            d.name = nm != null ? String.valueOf(nm) : d.id;
            Object loreObj = m.get("lore");
            if (loreObj instanceof List<?> loreList) {
                for (Object line : loreList) if (line != null) d.lore.add(String.valueOf(line));
            }
            // 头颅纹理（base64）
            Object tex = m.get("texture");
            if (tex != null) d.texture = String.valueOf(tex);
            // 药水效果
            Object pot = m.get("potion-type");
            if (pot != null) {
                d.potionType = String.valueOf(pot).toUpperCase();
                d.potionAmplifier = Math.max(0, ((Number) m.getOrDefault("potion-amplifier", 0)).intValue());
                d.potionDuration = Math.max(1, ((Number) m.getOrDefault("potion-duration", 30)).intValue());
            }
            // 宝石效果类型（thunder=雷电 / knockback=击退，等级越高概率越高）
            Object effObj = m.get("effect");
            if (effObj != null) d.effect = String.valueOf(effObj).toLowerCase();
            d.maxLevel = Math.max(1, ((Number) m.getOrDefault("max-level", 1)).intValue());
            // 限定掉落生物
            Object mobObj = m.get("mob-types");
            if (mobObj instanceof List<?> mobList) {
                for (Object o : mobList) {
                    try { d.mobTypes.add(EntityType.valueOf(String.valueOf(o).toUpperCase())); }
                    catch (Exception e) { cfg.plugin.getLogger().warning("gem-drops.custom 未知生物类型: " + o); }
                }
            }
            Object enchObj = m.get("enchants");
            if (enchObj instanceof org.bukkit.configuration.ConfigurationSection enchCs) {
                for (String key : enchCs.getKeys(false)) {
                    Enchantment ench = EnchantUtil.get(key.toUpperCase());
                    if (ench == null) {
                        cfg.plugin.getLogger().warning("gem-drops.custom 未知附魔: " + key);
                        continue;
                    }
                    Object v = enchCs.get(key);
                    try { d.enchants.put(ench, Math.max(1, ((Number) v).intValue())); } catch (Exception ignored) {}
                }
            } else if (enchObj instanceof Map<?, ?> enchMap) {
                for (Map.Entry<?, ?> e : enchMap.entrySet()) {
                    Enchantment ench = EnchantUtil.get(String.valueOf(e.getKey()).toUpperCase());
                    if (ench == null) {
                        cfg.plugin.getLogger().warning("gem-drops.custom 未知附魔: " + e.getKey());
                        continue;
                    }
                    int lvl = ((Number) e.getValue()).intValue();
                    d.enchants.put(ench, Math.max(1, lvl));
                }
            }
            d.glow = Boolean.parseBoolean(String.valueOf(m.getOrDefault("glow", false)));
            Object chanceObj = m.get("chance");
            // 注意：getValues(false) 返回的嵌套 section 是 ConfigurationSection 而非 Map，
            // 必须同时兼容两种类型，否则 chance 解析失败导致所有宝石 pool 为空（不掉落）
            if (chanceObj instanceof org.bukkit.configuration.ConfigurationSection chanceCs) {
                for (String key : chanceCs.getKeys(false)) {
                    Object v = chanceCs.get(key);
                    try { d.chance.put(key, ((Number) v).doubleValue()); } catch (Exception ignored) {}
                }
            } else if (chanceObj instanceof Map<?, ?> chanceMap) {
                for (Map.Entry<?, ?> e : chanceMap.entrySet()) {
                    try { d.chance.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue()); }
                    catch (Exception ignored) {}
                }
            }
            d.amountMin = Math.max(1, ((Number) m.getOrDefault("amount-min", 1)).intValue());
            d.amountMax = Math.max(d.amountMin, ((Number) m.getOrDefault("amount-max", 1)).intValue());
            return d;
        }

        /** 该掉落物在指定等级段的掉落概率 */
        public double getChance(int level) {
            String key;
            if (level >= 10) key = "level-10";
            else if (level >= 7) key = "level-7-9";
            else if (level >= 4) key = "level-4-6";
            else key = "level-1-3";
            Double c = chance.get(key);
            if (c == null) c = chance.get("level-1-3");
            return c == null ? 0.0 : Math.max(0.0, Math.min(1.0, c));
        }

        /** 该掉落物是否允许从指定生物掉落（mob-types 为空时全部允许） */
        public boolean allows(EntityType type) {
            return mobTypes.isEmpty() || mobTypes.contains(type);
        }

        /** 解析药水效果类型（兼容新旧 API） */
        private static org.bukkit.potion.PotionEffectType resolvePotion(String name) {
            try {
                org.bukkit.potion.PotionEffectType t = org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(name.toLowerCase()));
                if (t != null) return t;
            } catch (Exception ignored) {}
            try { return org.bukkit.potion.PotionEffectType.getByName(name); } catch (Exception e) { return null; }
        }

        /** 应用玩家头颅纹理（反射，兼容 Paper） */
        private static void applyTexture(org.bukkit.inventory.meta.SkullMeta m, String texture) {
            try {
                Object pp = Class.forName("com.destroystokyo.paper.profile.CraftPlayerProfile")
                    .getConstructor(java.util.UUID.class, String.class)
                    .newInstance(java.util.UUID.randomUUID(), null);
                pp.getClass().getMethod("setProperty", com.destroystokyo.paper.profile.ProfileProperty.class)
                    .invoke(pp, new com.destroystokyo.paper.profile.ProfileProperty("textures", texture, null));
                m.getClass().getMethod("setPlayerProfile", Class.forName("com.destroystokyo.paper.profile.PlayerProfile"))
                    .invoke(m, pp);
            } catch (Exception ignored) {}
        }

        /** 宝石等级对应的名称颜色（与原版一致：Lv9+绿 / 7-8金 / 4-6粉 / 2-3红 / 1蓝）。 */
        private static String levelColor(int level) {
            if (level >= 9) return "&2";
            if (level >= 7) return "&6";
            if (level >= 4) return "&d";
            if (level >= 2) return "&c";
            return "&b";
        }

        /** 宝石效果对应的主色调。 */
        private static String effectColor(String eff) {
            return switch (eff == null ? "" : eff) {
                case "attack" -> "&c";
                case "defense" -> "&b";
                case "knockback" -> "&f";
                case "thunder" -> "&e";
                case "magnet" -> "&b";
                case "doublejump" -> "&a";
                case "rare" -> "&6";
                default -> "&f";
            };
        }

        /** 按等级返回品质文本（与原版一致：普通/优秀/传说/史诗/神话）。 */
        private static String qualityFor(int level) {
            if (level >= 10) return "&2&l神话";
            if (level >= 7) return "&b&l史诗";
            if (level >= 4) return "&a&l传说";
            if (level >= 2) return "&e&l优秀";
            return "&f普通";
        }

        /** 淬炼成功率：baseRate + (等级-1)*perLevel，封顶 maxRate（与淬炼判定一致）。 */
        private double successRate(int level) {
            double base = owner != null ? owner.getEssenceUpgradeBaseRate() : 0.35;
            double per = owner != null ? owner.getEssenceUpgradePerLevel() : 0.045;
            double max = owner != null ? owner.getEssenceUpgradeMaxRate() : 0.95;
            return Math.min(base + (level - 1) * per, max);
        }

        /** 构建该掉落物的 ItemStack（无等级，effect 宝石默认 Lv.1）。 */
        public org.bukkit.inventory.ItemStack build() {
            return build(1);
        }

        /** 构建该掉落物的 ItemStack（可指定宝石等级；effect 宝石按等级显示品质/成功率/等级）。 */
        public org.bukkit.inventory.ItemStack build(int level) {
            org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(material);
            org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
            if (meta == null) return stack;
            level = Math.max(1, Math.min(maxLevel, level));
            boolean isGem = effect != null && !effect.isEmpty();

            // 显示名：宝石使用原版风格 "&8&l&k||颜色名 &7[&fLv.X&7]&8&l&k||"
            String baseName = (name != null && !name.isEmpty()) ? name : id;
            if (isGem) {
                String color = levelColor(level);
                meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        "&8&l&k||" + color + baseName + " &7[&fLv." + level + "&7]&8&l&k||"));
            } else if (!baseName.isEmpty()) {
                meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', baseName));
            }

            // Lore：配置 lore 在前 + （宝石追加）品质 / 等级 / 用法 / 成功率
            List<String> colored = new ArrayList<>();
            if (lore != null) {
                for (String line : lore) colored.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
            }
            if (isGem) {
                colored.add(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        effectColor(effect) + baseName + " &7品质: " + qualityFor(level)));
                colored.add(org.bukkit.ChatColor.DARK_GRAY
                        + ("magnet".equals(effect) ? "装备" : "defense".equals(effect) ? "护甲" : "武器") + " Lv." + level);
                colored.add(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        "&7将装备与此宝石放入铁砧淬炼"));
                double rate = successRate(level);
                colored.add(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        "&a成功率: " + String.format("%.0f", rate * 100) + "%"));
            }
            meta.setLore(colored);
            // 头颅纹理
            if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull && texture != null && !texture.isEmpty()) {
                applyTexture(skull, texture);
            }
            // 药水效果
            if (meta instanceof org.bukkit.inventory.meta.PotionMeta pm && potionType != null) {
                org.bukkit.potion.PotionEffectType pet = resolvePotion(potionType);
                if (pet != null) {
                    pm.addCustomEffect(new org.bukkit.potion.PotionEffect(pet, potionDuration * 20, potionAmplifier, true, true), true);
                }
            }
            for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                try { meta.addEnchant(e.getKey(), e.getValue(), true); }
                catch (Exception ignored) {}
            }
            if (glow && enchants.isEmpty()) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            }
            // 写入宝石 PDC（id + effect + 等级）——供攻击效果与等级显示
            var pdc = meta.getPersistentDataContainer();
            pdc.set(new org.bukkit.NamespacedKey("elitemobs", "gem_id"),
                    org.bukkit.persistence.PersistentDataType.STRING, id);
            if (effect != null && !effect.isEmpty()) {
                pdc.set(new org.bukkit.NamespacedKey("elitemobs", "gem_effect"),
                        org.bukkit.persistence.PersistentDataType.STRING, effect);
            }
            int lv = Math.max(1, Math.min(maxLevel, level));
            pdc.set(new org.bukkit.NamespacedKey("elitemobs", "gem_level"),
                    org.bukkit.persistence.PersistentDataType.INTEGER, lv);
            stack.setItemMeta(meta);
            return stack;
        }
    }
}
