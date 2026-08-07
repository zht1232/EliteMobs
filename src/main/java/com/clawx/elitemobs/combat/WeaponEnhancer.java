package com.clawx.elitemobs.combat;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Mob;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteConfig;
import java.util.*;
import static com.clawx.elitemobs.combat.EnchantUtil.get;

public class WeaponEnhancer {
    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();

    private static final Set<EntityType> NO_WEAPON_MOBS = EnumSet.of(
        EntityType.SPIDER, EntityType.CAVE_SPIDER,
        EntityType.SLIME, EntityType.MAGMA_CUBE,
        EntityType.SILVERFISH, EntityType.ENDERMITE,
        EntityType.CREEPER, EntityType.BLAZE, EntityType.GHAST,
        EntityType.ENDERMAN, EntityType.WITCH, EntityType.EVOKER,
        EntityType.RAVAGER, EntityType.HOGLIN,
        EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN,
        EntityType.SHULKER, EntityType.PHANTOM
    );

    public WeaponEnhancer(EliteMobsPlugin plugin) { this.plugin = plugin; }
    public void reload() {}

    public void enhanceWeapon(Mob mob, int level) {
        EntityType type = mob.getType();
        if (NO_WEAPON_MOBS.contains(type)) return;

        Material mat = getWeaponForMob(type, level);
        ItemStack weapon = new ItemStack(mat);

        // ????????????
        Set<Enchantment> validPool = getEnchantPoolForWeapon(mat);
        applyEnchantments(weapon, validPool, level);

        // ??????
        addSpecialEnchantments(weapon, type, level);

        EntityEquipment eq = mob.getEquipment();
        if (eq != null) {
            eq.setItemInMainHand(weapon);
            eq.setItemInMainHandDropChance(0.9f);
        }
    }

    private Material getWeaponForMob(EntityType type, int level) {
        return switch (type) {
            case SKELETON, STRAY -> Material.BOW;
            case WITHER_SKELETON -> Material.STONE_SWORD;
            case ZOMBIFIED_PIGLIN, PIGLIN -> Material.GOLDEN_SWORD;
            case PIGLIN_BRUTE -> Material.GOLDEN_AXE;
            case PILLAGER -> Material.CROSSBOW;
            case VINDICATOR -> Material.IRON_AXE;
            default -> {
                if (level >= 15) yield rng.nextBoolean() ? Material.NETHERITE_SWORD : Material.NETHERITE_AXE;
                if (level >= 10) {
                    double r = rng.nextDouble();
                    yield r < 0.3 ? (rng.nextBoolean() ? Material.NETHERITE_SWORD : Material.NETHERITE_AXE)
                                  : (rng.nextBoolean() ? Material.DIAMOND_SWORD : Material.DIAMOND_AXE);
                }
                if (level >= 7) yield rng.nextBoolean() ? Material.DIAMOND_SWORD : Material.DIAMOND_AXE;
                if (level >= 4) yield rng.nextBoolean() ? Material.IRON_SWORD : Material.IRON_AXE;
                yield rng.nextBoolean() ? Material.STONE_SWORD : Material.STONE_AXE;
            }
        };
    }

    /**
     * ????????????
     */
    private Set<Enchantment> getEnchantPoolForWeapon(Material mat) {
        Set<Enchantment> pool = new HashSet<>();
        boolean isSword = mat.name().contains("SWORD");
        boolean isAxe = mat.name().contains("AXE");
        boolean isBow = mat == Material.BOW;
        boolean isCrossbow = mat == Material.CROSSBOW;

        // ????
        if (isSword || isAxe) {
            pool.add(Enchantment.SHARPNESS);
            pool.add(Enchantment.SMITE);
            pool.add(Enchantment.BANE_OF_ARTHROPODS);
            pool.add(Enchantment.UNBREAKING);
            pool.add(Enchantment.MENDING);
        }
        // ???
        if (isSword) {
            pool.add(Enchantment.FIRE_ASPECT);
            pool.add(Enchantment.KNOCKBACK);
            pool.add(Enchantment.LOOTING);
            addIfNotNull(pool, get("SWEEPING_EDGE"));
        }
        // 斧头：原版不支持 FIRE_ASPECT（仅限剑），不添加
        // ???
        if (isBow) {
            pool.add(Enchantment.POWER);
            pool.add(Enchantment.PUNCH);
            pool.add(Enchantment.FLAME);
            pool.add(Enchantment.INFINITY);
            pool.add(Enchantment.UNBREAKING);
            pool.add(Enchantment.MENDING);
        }
        // ???
        if (isCrossbow) {
            pool.add(Enchantment.MULTISHOT);
            pool.add(Enchantment.QUICK_CHARGE);
            pool.add(Enchantment.PIERCING);
            pool.add(Enchantment.UNBREAKING);
            pool.add(Enchantment.MENDING);
        }
        return pool;
    }

    private void applyEnchantments(ItemStack weapon, Set<Enchantment> pool, int level) {
        if (pool.isEmpty()) return;
        List<Enchantment> list = new ArrayList<>(pool);
        Collections.shuffle(list, rng);

        int maxCount = level >= 15 ? 5 : (level >= 7 ? 3 : (level >= 4 ? 2 : 1));
        int count = Math.min(1 + rng.nextInt(maxCount), list.size());

        int maxLvl = Math.min(5 + level / 2, 10);
        int minLvl = Math.max(1, level / 3);

        for (int i = 0; i < count; i++) {
            Enchantment ench = list.get(i);
            try {
                int lvl = rng.nextInt(Math.min(ench.getMaxLevel(), maxLvl) - minLvl + 1) + minLvl;
                weapon.addUnsafeEnchantment(ench, lvl);
            } catch (Exception ignored) {}
        }
    }

    private void addSpecialEnchantments(ItemStack weapon, EntityType type, int level) {
        if (type == EntityType.SKELETON || type == EntityType.STRAY) {
            try {
                if (rng.nextDouble() < 0.4 + level * 0.05) {
                    int lvl = Math.min(1 + level / 3, 5);
                    weapon.addUnsafeEnchantment(Enchantment.POWER, lvl);
                }
            } catch (Exception ignored) {}
        }
        if (type == EntityType.PILLAGER) {
            try {
                if (rng.nextDouble() < 0.3 + level * 0.04) {
                    weapon.addUnsafeEnchantment(Enchantment.PIERCING, Math.min(1 + level / 3, 4));
                }
                if (rng.nextDouble() < 0.2 + level * 0.04) {
                    weapon.addUnsafeEnchantment(Enchantment.QUICK_CHARGE, Math.min(1 + level / 4, 3));
                }
            } catch (Exception ignored) {}
        }
    }

    private static void addIfNotNull(Set<Enchantment> set, Enchantment e) {
        if (e != null) set.add(e);
    }
}
