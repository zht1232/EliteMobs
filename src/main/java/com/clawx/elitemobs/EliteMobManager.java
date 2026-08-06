package com.clawx.elitemobs;

import com.clawx.elitemobs.combat.WeaponEnhancer;
import com.clawx.elitemobs.combat.EnchantUtil;
import com.clawx.elitemobs.ai.EliteClass;
import com.clawx.elitemobs.ai.EliteClassAI;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Particle;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class EliteMobManager {
    private final EliteMobsPlugin plugin;
    private final Map<UUID, EliteMobData> eliteMobs = new ConcurrentHashMap<>();
    private final NamespacedKey eliteKey, eliteLevelKey, eliteTypeKey, spawnTimestampKey;
    private final Random random = new Random();
    private static final Map<Particle, MethodHandle> PARTICLE_HANDLES = new ConcurrentHashMap<>();

    /** 精英护甲淬炼等级键：写入玩家可穿戴的护甲 PDC，用于套装加成判定 */
    public static final NamespacedKey ARMOR_LV_KEY = new NamespacedKey("elitemobs", "armor_lv");

    // Performance: capability-based tracking sets for AI iteration
    private final Set<UUID> wallClimbers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> blockBreakers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> itemStealers = ConcurrentHashMap.newKeySet();

    public static void spawnParticleSafe(World world, Particle particle, Location loc, int count) {
        try {
            MethodHandle handle = PARTICLE_HANDLES.computeIfAbsent(particle, p -> {
                try {
                    return MethodHandles.publicLookup().findVirtual(World.class, "spawnParticle",
                        java.lang.invoke.MethodType.methodType(void.class, Particle.class, Location.class, int.class));
                } catch (Exception e) { return null; }
            });
            if (handle != null) {
                handle.invoke(world, particle, loc, count);
                return;
            }
        } catch (Throwable ignored) {}
        try { world.spawnParticle(particle, loc, count); } catch (Exception ignored) {}
    }

    private static final Set<EntityType> NO_ARMOR_MOBS = EnumSet.of(
        EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.CREEPER, EntityType.BLAZE,
        EntityType.GHAST, EntityType.SLIME, EntityType.MAGMA_CUBE, EntityType.SILVERFISH,
        EntityType.ENDERMITE, EntityType.PHANTOM, EntityType.BAT, EntityType.ENDER_DRAGON,
        EntityType.WITHER, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.SHULKER,
        EntityType.ENDERMAN, EntityType.WITCH, EntityType.EVOKER, EntityType.RAVAGER, EntityType.HOGLIN
    );

    public EliteMobManager(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        this.eliteKey = new NamespacedKey(plugin, "elite");
        this.eliteLevelKey = new NamespacedKey(plugin, "elite_level");
        this.eliteTypeKey = new NamespacedKey(plugin, "elite_type");
        this.spawnTimestampKey = new NamespacedKey(plugin, "elite_spawn_time");
    }

    public void makeElite(LivingEntity entity) { makeElite(entity, -1); }

    public void makeElite(LivingEntity entity, int forcedLevel) { makeElite(entity, forcedLevel, null); }

    public void makeElite(LivingEntity entity, int forcedLevel, EliteClass forcedClass) {
        if (entity == null || isElite(entity)) return;
        EliteConfig config = plugin.getEliteConfig();
        EliteConfig.EliteMobProfile profile = config.getProfile(entity.getType());
        int level;
        if (forcedLevel > 0) {
            level = forcedLevel;
        } else {
            // 等级分布：85% Lv1-10, 12% Lv11-14, 3% Lv15-20
            double r = random.nextDouble();
            if (r < 0.85) {
                level = 1 + random.nextInt(10);
            } else if (r < 0.97) {
                level = 11 + random.nextInt(4);  // 11-14
            } else {
                level = 15 + random.nextInt(6);  // 15-20
            }
        }
        long spawnTime = System.currentTimeMillis();

        if (level >= config.getGlowMinLevel() && config.isGlowEnabled()) entity.setGlowing(true);

        // ??????
        spawnVisualEffect(entity.getWorld(), entity.getLocation(), level);

        double statMult = config.getHealthMultiplierMin() + random.nextDouble() * (config.getHealthMultiplierMax() - config.getHealthMultiplierMin());
        double hpBonus = (profile.healthMultiplier - 1.0) + (level * 0.05);
        double healthMult = Math.min(statMult + hpBonus, 2.5);
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) { maxHealth.setBaseValue(maxHealth.getBaseValue() * healthMult); entity.setHealth(maxHealth.getBaseValue()); }

        double speedMult = config.getSpeedMultiplierMin() + random.nextDouble() * (config.getSpeedMultiplierMax() - config.getSpeedMultiplierMin());
        speedMult += level * 0.008;
        AttributeInstance speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * Math.min(speedMult, 1.25));

        double dmgMult = Math.min(profile.damageMultiplier + (level * 0.04), 2.5);
        AttributeInstance ad = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (ad != null) ad.setBaseValue(ad.getBaseValue() * dmgMult);

        eliteMobs.put(entity.getUniqueId(), new EliteMobData(entity, level, dmgMult, spawnTime));
        registerCapabilities(entity.getUniqueId(), profile);
        entity.getPersistentDataContainer().set(eliteKey, PersistentDataType.BOOLEAN, true);
        entity.getPersistentDataContainer().set(eliteLevelKey, PersistentDataType.INTEGER, level);
        entity.getPersistentDataContainer().set(eliteTypeKey, PersistentDataType.STRING, entity.getType().name());
        entity.getPersistentDataContainer().set(spawnTimestampKey, PersistentDataType.LONG, spawnTime);
        entity.setMetadata("elite", new FixedMetadataValue(plugin, true));
        entity.setMetadata("elite_level", new FixedMetadataValue(plugin, level));
        entity.setMetadata("elite_spawn_time", new FixedMetadataValue(plugin, spawnTime));

        // 分配职业（指令可指定职业，否则按等级随机）
        EliteClass eliteClass = forcedClass != null ? forcedClass : EliteClass.randomForLevel(level);
        plugin.getEliteClassAI().applyClass(entity, level, eliteClass);
        // 分配词缀（如火焰/冰霜/吸血等）
        if (plugin.getAffixHandler() != null) plugin.getAffixHandler().rollAndApply(entity);

        applyVisuals(entity, level, config);
        applyEquipment(entity, level, config, profile);

        if (entity instanceof Mob mob && !NO_ARMOR_MOBS.contains(entity.getType())
                && config.isWeaponEnhancementEnabled()) {
            WeaponEnhancer we = plugin.getWeaponEnhancer();
            if (we != null) we.enhanceWeapon(mob, level);
        }

        if (entity instanceof Creeper c) c.setExplosionRadius((int) Math.round(Math.min(3.0f + (level * 0.3f), 10.0f)));
    }

    private void applyEquipment(LivingEntity entity, int level, EliteConfig config, EliteConfig.EliteMobProfile profile) {
        if (NO_ARMOR_MOBS.contains(entity.getType())) return;
        if (!(entity instanceof Mob mob)) return;
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;

        eq.setHelmet(createArmorPiece(config, level, "HELMET"));

        double chestChance = Math.min(config.getChestplateChance() + level * 0.04, 1.0);
        double legChance = Math.min(config.getLeggingsChance() + level * 0.04, 1.0);
        double bootChance = Math.min(config.getBootsChance() + level * 0.04, 1.0);

        // Lv.9+ 及 Boss 级(>=15) 保证全甲
        if (level >= 9) { chestChance = 1.0; legChance = 1.0; bootChance = 1.0; }

        if (random.nextDouble() < chestChance) eq.setChestplate(createArmorPiece(config, level, "CHESTPLATE"));
        if (random.nextDouble() < legChance) eq.setLeggings(createArmorPiece(config, level, "LEGGINGS"));
        if (random.nextDouble() < bootChance) eq.setBoots(createArmorPiece(config, level, "BOOTS"));

        eq.setHelmetDropChance(1.0f); eq.setChestplateDropChance(1.0f);
        eq.setLeggingsDropChance(1.0f); eq.setBootsDropChance(1.0f);
    }

    private ItemStack createArmorPiece(EliteConfig config, int level, String slot) {
        Material baseMat = pickTieredMaterial(level);
        Material pieceMat = getArmorPiece(baseMat, slot);
        return createEnchantedItem(pieceMat, level, config, slot);
    }

    /**
     * 根据等级选择护甲材质：
     * Lv.1-3: 皮60% 金25% 锁15%
     * Lv.4-6: 皮30% 金20% 锁20% 铁30%
     * Lv.7-8: 锁20% 铁50% 钻30%
     * Lv.9:  铁30% 钻60% 下界10%
     * Lv.10: 钻50% 下界50%
     * Lv.11-14: 钻40% 下界60%
     * Lv.15-20: 下界100%
     */
    private Material pickTieredMaterial(int level) {
        double r = random.nextDouble() * 100;
        if (level >= 15) {
            return Material.NETHERITE_HELMET;
        } else if (level >= 11) {
            return r < 40 ? Material.DIAMOND_HELMET : Material.NETHERITE_HELMET;
        } else if (level >= 10) {
            return r < 50 ? Material.DIAMOND_HELMET : Material.NETHERITE_HELMET;
        } else if (level == 9) {
            if (r < 30) return Material.IRON_HELMET;
            if (r < 90) return Material.DIAMOND_HELMET;
            return Material.NETHERITE_HELMET;
        } else if (level >= 7) {
            if (r < 20) return Material.CHAINMAIL_HELMET;
            if (r < 70) return Material.IRON_HELMET;
            return Material.DIAMOND_HELMET;
        } else if (level >= 4) {
            if (r < 30) return Material.LEATHER_HELMET;
            if (r < 50) return Material.GOLDEN_HELMET;
            if (r < 70) return Material.CHAINMAIL_HELMET;
            return Material.IRON_HELMET;
        } else {
            if (r < 60) return Material.LEATHER_HELMET;
            if (r < 85) return Material.GOLDEN_HELMET;
            return Material.CHAINMAIL_HELMET;
        }
    }


    private Material getArmorPiece(Material base, String slotSuffix) {
        String name = base.name().replace("_HELMET","").replace("_CHESTPLATE","").replace("_LEGGINGS","").replace("_BOOTS","");
        return Material.valueOf(name + "_" + slotSuffix);
    }

    private ItemStack createEnchantedItem(Material mat, int level, EliteConfig config, String slot) {
        ItemStack stack = new ItemStack(mat);
        // 写入淬炼等级键：套装加成（减伤/加速/回血）依赖它判定
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(
                ARMOR_LV_KEY, PersistentDataType.INTEGER, Math.max(1, level)));
        if (!config.isArmorEnchantEnabled()) return stack;

        double enchantChance = Math.min(config.getArmorEnchantChance() + level * 0.03, 1.0);
        if (random.nextDouble() >= enchantChance) return stack;

        Set<Enchantment> slotEnchants = getEnchantsForSlot(slot);
        if (slotEnchants.isEmpty()) return stack;

        // ????????????
        List<Enchantment> valid = new ArrayList<>();
        for (Enchantment e : slotEnchants) {
            try { if (e.canEnchantItem(stack)) valid.add(e); } catch (Exception ignored) {}
        }
        if (valid.isEmpty()) return stack;

        Collections.shuffle(valid, random);

        int maxCount = level >= 7 ? 3 : (level >= 4 ? 2 : 1);
        int count = Math.min(1 + random.nextInt(maxCount), valid.size());
        int maxLvl = Math.min(config.getMaxArmorEnchantLvl() + (level / 3), 10);
        int minLvl = Math.max(1, config.getMinArmorEnchantLvl() + (level >= 5 ? 1 : 0));

        for (int i = 0; i < count; i++) {
            Enchantment ench = valid.get(i);
            try {
                int enchLvl = randomInt(minLvl, Math.min(ench.getMaxLevel(), maxLvl));
                stack.addUnsafeEnchantment(ench, enchLvl);
            } catch (Exception ignored) {}
        }
        return stack;
    }

    private Set<Enchantment> getEnchantsForSlot(String slot) {
        Set<Enchantment> s = new HashSet<>();
        addIfNotNull(s, EnchantUtil.get("PROTECTION"));
        addIfNotNull(s, EnchantUtil.get("UNBREAKING"));
        addIfNotNull(s, EnchantUtil.get("MENDING"));
        addIfNotNull(s, EnchantUtil.get("BLAST_PROTECTION"));
        addIfNotNull(s, EnchantUtil.get("FIRE_PROTECTION"));
        addIfNotNull(s, EnchantUtil.get("PROJECTILE_PROTECTION"));

        if ("HELMET".equals(slot)) {
            addIfNotNull(s, EnchantUtil.get("RESPIRATION"));
            addIfNotNull(s, EnchantUtil.get("AQUA_AFFINITY"));
        } else if ("CHESTPLATE".equals(slot)) {
            addIfNotNull(s, EnchantUtil.get("THORNS"));
        } else if ("LEGGINGS".equals(slot)) {
            addIfNotNull(s, EnchantUtil.get("SWIFT_SNEAK"));
        } else if ("BOOTS".equals(slot)) {
            addIfNotNull(s, EnchantUtil.get("FEATHER_FALLING"));
            addIfNotNull(s, EnchantUtil.get("DEPTH_STRIDER"));
            addIfNotNull(s, EnchantUtil.get("FROST_WALKER"));
        }
        return s;
    }

    private static void addIfNotNull(Set<Enchantment> set, Enchantment e) {
        if (e != null) set.add(e);
    }

    /**
     * ????????????? - ????? 3 ?? spawnParticle ??
     */
    private void spawnVisualEffect(World world, Location loc, int level) {
        if (world == null || loc == null) return;
        loc = loc.clone();
        try {
            switch (level) {
                case 1, 2 -> {
                    for (int i = 0; i < 15; i++) spawnParticleSafe(world, Particle.SMOKE, loc.clone().add(random.nextDouble()-0.5, 1+random.nextDouble(), random.nextDouble()-0.5), 1);
                    for (int i = 0; i < 8; i++) spawnParticleSafe(world, Particle.FLAME, loc.clone().add(random.nextDouble()-0.5, random.nextDouble(), random.nextDouble()-0.5), 1);
                }
                case 3, 4 -> {
                    for (int i = 0; i < 20; i++) spawnParticleSafe(world, Particle.LAVA, loc.clone().add(random.nextDouble()-0.5, random.nextDouble(), random.nextDouble()-0.5), 1);
                    for (int i = 0; i < 15; i++) spawnParticleSafe(world, Particle.ENCHANTED_HIT, loc.clone().add(random.nextDouble()-0.5, 0.5+random.nextDouble(), random.nextDouble()-0.5), 1);
                    world.playSound(loc.clone(), org.bukkit.Sound.BLOCK_LAVA_EXTINGUISH, 1.0f, 1.0f);
                }
                case 5, 6 -> {
                    for (int i = 0; i < 30; i++) spawnParticleSafe(world, Particle.SOUL, loc.clone().add(random.nextDouble()-0.5, 1+random.nextDouble(), random.nextDouble()-0.5), 1);
                    for (int i = 0; i < 20; i++) spawnParticleSafe(world, Particle.DRAGON_BREATH, loc.clone().add(random.nextDouble()-0.5, 0.5+random.nextDouble(), random.nextDouble()-0.5), 1);
                    world.playSound(loc.clone(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.2f);
                }
                case 7, 8 -> {
                    for (int i = 0; i < 40; i++) spawnParticleSafe(world, Particle.SOUL, loc.clone().add(random.nextDouble()-0.5, 1.5+random.nextDouble(), random.nextDouble()-0.5), 1);
                    for (int i = 0; i < 30; i++) spawnParticleSafe(world, Particle.DRAGON_BREATH, loc.clone().add(random.nextDouble()-0.5, 0.5+random.nextDouble(), random.nextDouble()-0.5), 1);
                    for (int i = 0; i < 10; i++) spawnParticleSafe(world, Particle.SOUL, loc.clone().add(random.nextDouble()-0.5, 1+random.nextDouble(), random.nextDouble()-0.5), 1);
                    world.playSound(loc.clone(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.9f);
                }
                case 9 -> {
                    for (int i = 0; i < 60; i++) spawnParticleSafe(world, Particle.DRAGON_BREATH, loc.clone().add(random.nextDouble()-0.5, 1.5+random.nextDouble(), random.nextDouble()-0.5), 1);
                    for (int i = 0; i < 40; i++) spawnParticleSafe(world, Particle.SOUL, loc.clone().add(random.nextDouble()-0.5, random.nextDouble(), random.nextDouble()-0.5), 1);
                    world.playSound(loc.clone(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 2.0f, 0.8f);
                }
                default -> {
                    EliteConfig cfg = plugin.getEliteConfig();
                    if (cfg.isLightningEnabled() && level >= cfg.getLightningMinLevel()) {
                        // Lv.10+ 登场特效：仅粒子+音效，不再召唤真实闪电
                        // （真实闪电会破坏/引燃方块；闪电特效只保留给 Boss 晋升）
                        for (int i = 0; i < 50; i++) spawnParticleSafe(world, Particle.DRAGON_BREATH, loc.clone().add(random.nextDouble()-0.5, 1+random.nextDouble(), random.nextDouble()-0.5), 1);
                        for (int i = 0; i < 30; i++) spawnParticleSafe(world, Particle.SOUL, loc.clone().add(random.nextDouble()-0.5, random.nextDouble(), random.nextDouble()-0.5), 1);
                        world.playSound(loc.clone(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 2.0f, 0.6f);
                    } else {
                        for (int i = 0; i < 10; i++) spawnParticleSafe(world, Particle.FLAME, loc.clone().add(random.nextDouble()-0.5, random.nextDouble(), random.nextDouble()-0.5), 1);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Particle effect error: " + e.getMessage());
        }
    }

    private void applyVisuals(LivingEntity entity, int level, EliteConfig config) {
        if (config.isNameTagEnabled()) {
            String name = config.getNameTagFormat();
            if (name == null || name.isEmpty()) {
                name = "&c\u2620 &6{type} &7[&4Lv.{level}&7] &e" + getStarDisplay(level);
            }
            name = name.replace("{type}", entity.getType().name().toLowerCase().replace('_', ' '))
                      .replace("{level}", String.valueOf(level))
                      .replace("{stars}", getStarDisplay(level));
            // 加上职业前缀
            EliteClass cls = EliteClassAI.getEliteClass(entity);
            if (cls != null) {
                name = cls.getNameTag() + name;
            }
            entity.setCustomName(ChatColor.translateAlternateColorCodes('&', name));
            entity.setCustomNameVisible(true);
            // 词缀名字后缀（[火焰][冰霜]...）
            com.clawx.elitemobs.ai.EliteAffixHandler.appendAffixSuffix(entity);
        }
    }

    private String getStarDisplay(int level) {
        if (level <= 2) return "\u2726";
        if (level <= 4) return "\u2726\u2726";
        if (level <= 6) return "\u2726\u2726\u2726";
        if (level <= 8) return "\u2726\u2726\u2726\u2726";
        if (level <= 10) return "\u2726\u2726\u2726\u2726\u2726";
        if (level <= 14) return "\u2726\u2726\u2726\u2726\u2726\u2726";
        if (level <= 19) return "\u2726\u2726\u2726\u2726\u2726\u2726\u2726";
        return "\u2726\u2726\u2726\u2726\u2726\u2726\u2726\u2726";
    }

    public boolean isNightTime(World world) {
        long time = world.getTime() % 24000;
        return time >= 13000 && time < 23000;
    }

    public void tickAllEliteMobs() {
        for (Iterator<UUID> it = eliteMobs.keySet().iterator(); it.hasNext(); ) {
            UUID id = it.next();
            EliteMobData data = eliteMobs.get(id);
            if (data == null || data.entity == null || data.entity.isDead() || !data.entity.isValid()) { handleEliteDeath(id); it.remove(); continue; }
            // 词缀持续效果
            if (plugin.getAffixHandler() != null) plugin.getAffixHandler().tick(data.entity);
            if (isNightTime(data.entity.getWorld()) && plugin.getEliteConfig().isNightEnhancementEnabled()) {
                spawnNightParticles(data.entity);
                applyNightBoost(data.entity);
            }
        }
    }

    /**
     * 夜间强化：给实体施加力量/速度（夜间时段内精英与原版怪一致变强）。
     * 力量等级由 night-damage-multiplier、速度等级由 night-speed-bonus 折算。
     */
    private void applyNightBoost(LivingEntity e) {
        if (e == null || e.isDead() || !e.isValid()) return;
        EliteConfig cfg = plugin.getEliteConfig();
        int strength = Math.max(0, Math.min((int) Math.round((cfg.getNightDamageMultiplier() - 1.0) * 5), 4));
        int speed = Math.max(0, Math.min((int) Math.round(cfg.getNightSpeedBonus() / 0.1), 4));
        if (strength > 0) e.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 80, strength - 1, true, false));
        if (speed > 0) e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, speed - 1, true, false));
    }

    /** 原版怪物夜间加强：夜间给在线玩家附近的原版怪物施加力量/速度（与精英一致），可配置关闭。 */
    public void startVanillaNightBoostTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            EliteConfig cfg = plugin.getEliteConfig();
            if (!cfg.isNightEnhancementEnabled() || !cfg.isVanillaNightBoostEnabled()) return;
            for (org.bukkit.entity.Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.isDead() || !p.isValid()) continue;
                if (!isNightTime(p.getWorld())) continue;
                for (Entity ent : p.getNearbyEntities(16, 16, 16)) {
                    if (!(ent instanceof LivingEntity le)) continue;
                    if (le instanceof org.bukkit.entity.Player) continue;
                    // 排除非敌对生物：动物/水生/环境/村民/铁傀儡雪傀儡/悦灵/盔甲架/驯服宠物
                    if (le instanceof Animals || le instanceof WaterMob || le instanceof Ambient
                            || le instanceof Villager || le instanceof AbstractVillager
                            || le instanceof Golem || le instanceof Allay
                            || le instanceof ArmorStand || le instanceof Tameable) continue;
                    if (isElite(le)) continue; // 精英已在 tickAllEliteMobs 处理
                    applyNightBoost(le);
                }
            }
        }, 20L, 40L);
    }

    private void spawnNightParticles(LivingEntity entity) {
        Location loc = entity.getLocation().add(0, 0.5, 0);
        World w = entity.getWorld();
        for (int i = 0; i < 3; i++) {
            double ox = (random.nextDouble() - 0.5) * 1.2;
            double oy = random.nextDouble() * 1.5;
            double oz = (random.nextDouble() - 0.5) * 1.2;
            if (random.nextBoolean()) {
                spawnParticleSafe(w, Particle.FLAME, loc.clone().add(ox, oy, oz), 2);
            } else {
                spawnParticleSafe(w, Particle.SOUL, loc.clone().add(ox, oy, oz), 2);
            }
        }
    }

    public static boolean isElite(LivingEntity e) {
        if (e == null) return false;
        if (e.hasMetadata("elite")) return true;
        return e.getPersistentDataContainer().has(new org.bukkit.NamespacedKey("elitemobs", "elite"),
                org.bukkit.persistence.PersistentDataType.BOOLEAN);
    }
    public static int getEliteLevel(LivingEntity e) {
        if (e == null) return 0;
        if (e.hasMetadata("elite_level")) return e.getMetadata("elite_level").get(0).asInt();
        Integer lv = e.getPersistentDataContainer().get(new org.bukkit.NamespacedKey("elitemobs", "elite_level"),
                org.bukkit.persistence.PersistentDataType.INTEGER);
        return lv == null ? 0 : lv;
    }
    public static long getSpawnTime(LivingEntity e) { return (e != null && e.hasMetadata("elite_spawn_time")) ? e.getMetadata("elite_spawn_time").get(0).asLong() : 0; }
    public int countElitesInChunk(org.bukkit.Chunk chunk) { int c = 0; for (Entity e : chunk.getEntities()) if (e instanceof LivingEntity le && isElite(le)) c++; return c; }
    public int getEliteCount() { return eliteMobs.size(); }
    public Collection<EliteMobData> getEliteMobs() { return eliteMobs.values(); }
    private int randomInt(int min, int max) { return (min >= max) ? min : random.nextInt(max - min + 1) + min; }
    private final Map<UUID, List<ItemStack>> stolenItems = new ConcurrentHashMap<>();

    public void handleEliteDeath(UUID uuid) {
        eliteMobs.remove(uuid);
        stolenItems.remove(uuid);
        wallClimbers.remove(uuid);
        blockBreakers.remove(uuid);
        itemStealers.remove(uuid);
    }

    private void registerCapabilities(UUID uuid, EliteConfig.EliteMobProfile profile) {
        if (profile.canClimbWalls) wallClimbers.add(uuid);
        if (profile.canBreakBlocks) blockBreakers.add(uuid);
        if (profile.canStealItems) itemStealers.add(uuid);
    }

    public Set<UUID> getWallClimbers() { return wallClimbers; }
    public Set<UUID> getBlockBreakers() { return blockBreakers; }
    public Set<UUID> getItemStealers() { return itemStealers; }

    public boolean addStolenItem(UUID mobUuid, ItemStack item) {
        int max = plugin.getEliteConfig().getMaxStolenItems();
        List<ItemStack> list = stolenItems.computeIfAbsent(mobUuid, k -> new ArrayList<>());
        if (list.size() >= max) return false;
        list.add(item);
        return true;
    }

    /** 取出并移除某怪被偷的物品（供死亡归还原主/击杀者） */
    public List<ItemStack> takeStolenItems(UUID mobUuid) {
        return stolenItems.remove(mobUuid);
    }

    public static class EliteMobData {
        public final LivingEntity entity; public final int level; public final double baseDamageMultiplier; public final long spawnTimestamp;
        public EliteMobData(LivingEntity entity, int level, double baseDamageMultiplier, long spawnTimestamp) { this.entity = entity; this.level = level; this.baseDamageMultiplier = baseDamageMultiplier; this.spawnTimestamp = spawnTimestamp; }
    }
}
