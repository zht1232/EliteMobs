package com.clawx.elitemobs;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import com.clawx.elitemobs.combat.EnchantUtil;
import com.clawx.elitemobs.ai.EliteAffix;
import java.util.*;

public class EliteConfig {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private boolean enabled;
    private int aiTickInterval;
    private double eliteSpawnChance;
    private int minSpawnY;
    private int maxElitesPerChunk;
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
    // 生成广播
    private boolean spawnAnnounceEnabled;
    private int spawnAnnounceMinLevel;
    private int spawnAnnounceRange;
    // 宝石掉落模式
    private String gemDropMode;
    // 宝石掉落总开关
    private boolean gemDropsEnabled;
    // snowygems 模式: 各等级段宝石池 + 数量
    private final Map<String, List<String>> snowyGemPools = new LinkedHashMap<>();
    private int snowyAmountMin, snowyAmountMax;
    // custom 模式: 自定义掉落物
    private List<CustomDrop> customDrops = new ArrayList<>();
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

    public EliteConfig(JavaPlugin plugin) { this.plugin = plugin; this.config = plugin.getConfig(); load(); }

    @SuppressWarnings("unchecked")
    public void load() {
        enabled = config.getBoolean("general.enabled", true);
        aiTickInterval = config.getInt("general.ai-tick-interval", 10);
        eliteSpawnChance = config.getDouble("general.elite-spawn-chance", 0.05);
        minSpawnY = config.getInt("general.min-spawn-y", 0);
        maxElitesPerChunk = config.getInt("general.max-elites-per-chunk", 2);
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
        if (config.contains("mob-profiles")) {
            for (String key : config.getConfigurationSection("mob-profiles").getKeys(false)) {
                try {
                    EntityType type = EntityType.valueOf(key.toUpperCase());
                    EliteMobProfile p = new EliteMobProfile();
                    p.healthMultiplier = config.getDouble("mob-profiles."+key+".health-multiplier", 2.0);
                    p.damageMultiplier = config.getDouble("mob-profiles."+key+".damage-multiplier", 1.5);
                    p.speedMultiplier = config.getDouble("mob-profiles."+key+".speed-multiplier", 1.15);
                    p.canClimbWalls = config.getBoolean("mob-profiles."+key+".can-climb-walls", false);
                    p.canBreakBlocks = config.getBoolean("mob-profiles."+key+".can-break-blocks", false);
                    p.canStealItems = config.getBoolean("mob-profiles."+key+".can-steal-items", true);
                    p.priority = config.getInt("mob-profiles."+key+".priority", 5);
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

        // 生成广播
        spawnAnnounceEnabled = config.getBoolean("general.spawn-announce.enabled", true);
        spawnAnnounceMinLevel = config.getInt("general.spawn-announce.min-level", 7);
        spawnAnnounceRange = config.getInt("general.spawn-announce.announce-range", -1);

        // 宝石掉落模式
        gemDropMode = config.getString("gem-drops.mode", "snowygems");
        // 宝石掉落总开关 (向后兼容: 未配置时回退到旧的 essence-drops 开关)
        gemDropsEnabled = config.getBoolean("gem-drops.drops.enabled",
                config.getBoolean("loot.essence-drops.enabled", true));

        // snowygems 宝石池
        snowyGemPools.clear();
        String[] brackets = {"level-1-3", "level-4-6", "level-7-9", "level-10"};
        for (String br : brackets) {
            snowyGemPools.put(br, new ArrayList<>(config.getStringList("gem-drops.snowygems." + br)));
        }
        snowyAmountMin = Math.max(1, config.getInt("gem-drops.snowygems.amount-min", 1));
        snowyAmountMax = Math.max(snowyAmountMin, config.getInt("gem-drops.snowygems.amount-max", 1));

        // custom 自定义掉落物
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

        // 击杀奖励 (Vault 金币 + PlayerPoints 点券)
        moneyRewardEnabled = config.getBoolean("loot.rewards.money.enabled", true);
        moneyRewardBase = config.getDouble("loot.rewards.money.base", 0.0);
        moneyRewardPerLevel = config.getDouble("loot.rewards.money.per-level", 5.0);
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
    public Set<EntityType> getEnabledMobTypes() { return enabledMobTypes; }
    public EliteMobProfile getProfile(EntityType type) { return mobProfiles.getOrDefault(type, new EliteMobProfile()); }
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

    // ????
    public boolean isLevelScalingEnabled() { return levelScalingEnabled; }
    public String getLevelScalingFormula() { return levelScalingFormula; }
    public double getLevelScalingMultiplier() { return levelScalingMultiplier; }
    public boolean isDebugEnchant() { return debugEnchant; }
    public boolean isNightEnhancementEnabled() { return nightEnhancementEnabled; }
    public double getNightSpeedBonus() { return nightSpeedBonus; }
    public double getNightDamageMultiplier() { return nightDamageMultiplier; }
    public double getNightSpawnMultiplier() { return nightSpawnMultiplier; }

    // 生成广播
    public boolean isSpawnAnnounceEnabled() { return spawnAnnounceEnabled; }
    public int getSpawnAnnounceMinLevel() { return spawnAnnounceMinLevel; }
    public int getSpawnAnnounceRange() { return spawnAnnounceRange; }
    public String getGemDropMode() { return gemDropMode; }

    // ========== 宝石掉落 (gem-drops) ==========
    public boolean isGemDropsEnabled() { return gemDropsEnabled; }

    /** snowygems 模式: 指定等级段 (1-3/4-6/7-9/10+) 的宝石池 */
    public List<String> getSnowyGemPool(int level) {
        if (level >= 10) return snowyGemPools.get("level-10");
        if (level >= 7) return snowyGemPools.get("level-7-9");
        if (level >= 4) return snowyGemPools.get("level-4-6");
        return snowyGemPools.get("level-1-3");
    }

    public int getSnowyAmountMin() { return snowyAmountMin; }
    public int getSnowyAmountMax() { return snowyAmountMax; }

    /** snowygems 模式: 该等级段是否允许掉落 (普通精英用 drops.level-X-Y, Boss 用 drops.boss-level-X-Y) */
    public double getGemDropChance(int level, boolean boss) {
        String key = boss ? "gem-drops.drops.boss-level-" : "gem-drops.drops.level-";
        if (level >= 10) key += "10";
        else if (level >= 7) key += "7-9";
        else if (level >= 4) key += "4-6";
        else key += "1-3";
        return Math.max(0.0, Math.min(1.0, config.getDouble(key,
                boss ? 1.0 : config.getDouble("loot.essence-drops.level-1-3", 0.15))));
    }

    /** custom 模式: 自定义掉落物列表 */
    public List<CustomDrop> getCustomDrops() { return customDrops; }

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

    /** 按权重随机抽取 count 个不重复词缀 */
    public List<EliteAffix> rollAffixes(Random rng, int count) {
        List<EliteAffix> pool = new ArrayList<>(affixWeights.keySet());
        List<EliteAffix> result = new ArrayList<>();
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            int total = 0;
            for (EliteAffix a : pool) total += affixWeights.getOrDefault(a, 1);
            int roll = rng.nextInt(total);
            EliteAffix picked = null;
            int acc = 0;
            for (EliteAffix a : pool) {
                acc += affixWeights.getOrDefault(a, 1);
                if (roll < acc) { picked = a; break; }
            }
            if (picked == null) picked = pool.get(pool.size() - 1);
            result.add(picked);
            pool.remove(picked);
        }
        return result;
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

    /**
     * 自定义掉落物 (gem-drops.custom 列表条目)。
     * 无需 SnowyGems, 服主可在配置中完全自主定义精英怪掉落物。
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

        static CustomDrop fromMap(EliteConfig cfg, Map<String, Object> m) {
            CustomDrop d = new CustomDrop();
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
            // 限定掉落生物
            Object mobObj = m.get("mob-types");
            if (mobObj instanceof List<?> mobList) {
                for (Object o : mobList) {
                    try { d.mobTypes.add(EntityType.valueOf(String.valueOf(o).toUpperCase())); }
                    catch (Exception e) { cfg.plugin.getLogger().warning("gem-drops.custom 未知生物类型: " + o); }
                }
            }
            Object enchObj = m.get("enchants");
            if (enchObj instanceof Map<?, ?> enchMap) {
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
            if (chanceObj instanceof Map<?, ?> chanceMap) {
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

        /** 构建该掉落物的 ItemStack */
        public org.bukkit.inventory.ItemStack build() {
            org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(material);
            org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
            if (meta == null) return stack;
            if (name != null && !name.isEmpty()) {
                meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
            }
            if (lore != null && !lore.isEmpty()) {
                List<String> colored = new ArrayList<>();
                for (String line : lore) colored.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
                meta.setLore(colored);
            }
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
            stack.setItemMeta(meta);
            return stack;
        }
    }
}
