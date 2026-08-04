package com.clawx.elitemobs.rune;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import com.clawx.elitemobs.EliteMobsPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 符文工厂 —— 独立于淬炼的「符文系统」。
 *
 * <p>符文是比宝石（精华）更高阶的附魔物品，通过铁砧镶嵌到已淬炼的装备上。
 * 符文效果不叠加攻击/护甲数值，而是提供更高级的被动效果：</p>
 * <ul>
 *   <li>生命符文：+最大生命值</li>
 *   <li>移速符文：+移动速度</li>
 *   <li>力量/再生/抗性等：持续药水效果</li>
 * </ul>
 *
 * <p>符文槽数量由装备的淬炼等级决定，镶嵌消耗金币+点券+经验值。</p>
 */
public final class EliteRuneFactory {

    /** 符文 PDC 键 */
    public static final NamespacedKey KEY_RUNE = new NamespacedKey("elitemobs", "rune");
    public static final NamespacedKey KEY_RUNE_TYPE = new NamespacedKey("elitemobs", "rune_type");
    public static final NamespacedKey KEY_RUNE_LEVEL = new NamespacedKey("elitemobs", "rune_level");
    /** 装备上的符文槽 PDC 键（rune_1 ~ rune_4，存符文类型字符串） */
    public static final NamespacedKey[] KEY_SLOTS = {
        new NamespacedKey("elitemobs", "rune_1"),
        new NamespacedKey("elitemobs", "rune_2"),
        new NamespacedKey("elitemobs", "rune_3"),
        new NamespacedKey("elitemobs", "rune_4"),
    };
    /** 装备上的符文槽等级 PDC 键（rune_lv_1 ~ rune_lv_4，存整数等级） */
    public static final NamespacedKey[] KEY_SLOT_LEVELS = {
        new NamespacedKey("elitemobs", "rune_lv_1"),
        new NamespacedKey("elitemobs", "rune_lv_2"),
        new NamespacedKey("elitemobs", "rune_lv_3"),
        new NamespacedKey("elitemobs", "rune_lv_4"),
    };

    /** 符文类型定义（id -> 展示名/材质/效果描述）。效果实现在 EliteRuneListener。
     *  每个符文标注适用装备：WEAPON=仅武器 / ARMOR=仅护甲。 */
    public static final Map<String, RuneType> TYPES = new LinkedHashMap<>();
    static {
        // 符文统一为头颅外观，纹理来自 SnowyGems 对应主题宝石（迅捷宝石已改为移速符文）
        TYPES.put("HEALTH", new RuneType("HEALTH", "生命符文", "&c生命符文",
                Material.REDSTONE, "装备最大生命值 &c+4", "\u2665", "生命值 &c+4", "ARMOR",
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDFhNmE0MTFiODAyYjIyZTUzMjQ3OTVjZTM0ZTNjYWU0YTgzNzk2YTE4ZDkyMzMyNjY2Y2JmMjE0ODhjMCJ9fX0="));
        TYPES.put("SPEED", new RuneType("SPEED", "移速符文", "&b移速符文",
                Material.SUGAR, "装备移动速度 &b+5%", "\u26a1", "移速 &b+5%", "ARMOR",
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzA1OGQwODBiZDYyMTU2ZTVmNmU1ZThkZmZhYTI4OTI2YmQ4MzM5MWRjMjZmZTc4MDY3M2VmNTJmMjZlYjA4YiJ9fX0="));
        TYPES.put("STRENGTH", new RuneType("STRENGTH", "力量符文", "&4力量符文",
                Material.BLAZE_POWDER, "穿戴时获得 &c力量 I &7效果", "\u2694", "力量 &cI", "WEAPON",
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTIwNzE2MjAzZGEwMzljYWZjYTI0YmJkOWYzZTliZDVjNjk4NWMzYzI1NTI3YmNkNTA2ZDM4OGU5OWJiN2FmZSJ9fX0="));
        TYPES.put("REGEN", new RuneType("REGEN", "再生符文", "&d再生符文",
                Material.GHAST_TEAR, "穿戴时获得 &d再生 I &7效果", "\u2668", "再生 &dI", "ARMOR",
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTFlN2ViMmU0NjFlOTZlNjMxY2JhMGMwY2RhYTU0NDg4MDYzMDJlZGFlOTFiNjFkYWZjMjgxYWU1ODRkOCJ9fX0="));
        TYPES.put("RESIST", new RuneType("RESIST", "抗性符文", "&7抗性符文",
                Material.IRON_INGOT, "穿戴时获得 &7抗性提升 I &7效果", "\u26e8", "抗性 &7I", "ARMOR",
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWRhNGU3OWVlZDEzODY1NjcwOGJjMzI1YzQ0MjE5Mjc1MTdjMWMwOGNjOTM5YTc2MDNiOGQxNWE5ZjI0ODU0ZCJ9fX0="));
        TYPES.put("FIRE", new RuneType("FIRE", "火焰符文", "&6火焰符文",
                Material.FIRE_CHARGE, "穿戴时获得 &6抗火 &7效果", "\u2600", "抗火 &6", "ARMOR",
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTRiOTNmNDE1MzJmYzI3ZWY3ZGY5MzNjMTgxYWMzMTY2ZDU2MDM3ZDVjNWZmNzVkMmU4NWFmZTM3Y2EyNTdkMyJ9fX0="));
    }

    private EliteRuneFactory() {}

    /** 符文类型描述 */
    public static final class RuneType {
        public final String id;
        public final String displayName;
        public final String coloredName;
        public final Material material;
        public final String desc;
        /** Lore 中使用的图标符号与简短效果 */
        public final String icon;
        public final String effect;
        /** 适用装备：WEAPON=仅武器 / ARMOR=仅护甲 */
        public final String target;
        /** 头颅纹理（符文统一为 PLAYER_HEAD 头颅外观） */
        public final String texture;
        RuneType(String id, String displayName, String coloredName, Material material, String desc,
                 String icon, String effect, String target, String texture) {
            this.id = id; this.displayName = displayName; this.coloredName = coloredName;
            this.material = material; this.desc = desc; this.icon = icon; this.effect = effect;
            this.target = target; this.texture = texture;
        }
        /** 是否为武器符文 */
        public boolean isWeapon() { return "WEAPON".equals(target); }
        /** 是否为护甲符文 */
        public boolean isArmor() { return "ARMOR".equals(target); }
    }

    /** 判断符文是否能镶嵌到该装备（符文通用：任意符文可镶嵌到任意已淬炼装备）。 */
    public static boolean canFit(ItemStack equip, String runeType) {
        if (TYPES.get(runeType) == null) return false;
        if (equip == null || !equip.hasItemMeta()) return false;
        // 只要装备已镶嵌任意宝石（已淬炼）即可
        String[] gems = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGems(equip);
        for (String g : gems) if (g != null) return true;
        return false;
    }

    /** 创建符文物品（默认 Lv.1）。 */
    public static ItemStack createRune(String typeId) {
        return createRune(typeId, 1, null);
    }

    /** 创建符文物品（带消息配置，默认 Lv.1）。 */
    public static ItemStack createRune(String typeId, FileConfiguration msgs) {
        return createRune(typeId, 1, msgs);
    }

    /** 创建符文物品（带等级与消息配置）。 */
    public static ItemStack createRune(String typeId, int level, FileConfiguration msgs) {
        RuneType t = TYPES.get(typeId.toUpperCase());
        if (t == null) t = TYPES.get("HEALTH");
        level = Math.max(1, Math.min(10, level));
        // 符文统一为头颅外观（各符文主题纹理）
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull
                && t.texture != null && !t.texture.isEmpty()) {
            com.clawx.elitemobs.essence.EliteEssenceFactory.applyTexture(skull, t.texture);
        }

        String name = msg(msgs, "rune.name", t.coloredName);
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                "&8&l&k||" + name + " &7Lv." + level + "&8&l&k||"));

        List<String> lore = new ArrayList<>();
        String desc = descFor(t, level);
        lore.add(ChatColor.translateAlternateColorCodes('&', "&f" + t.displayName
                + " &7品级: &6&l符文 &7Lv." + level));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7" + desc));
        lore.add(ChatColor.DARK_GRAY + "将装备与此符文放入铁砧镶嵌");
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(KEY_RUNE, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(KEY_RUNE_TYPE, PersistentDataType.STRING, t.id);
        meta.getPersistentDataContainer().set(KEY_RUNE_LEVEL, PersistentDataType.INTEGER, level);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 获取符文等级（非符文返回 1）。 */
    public static int getRuneLevel(ItemStack item) {
        if (!isRune(item)) return 1;
        Integer lv = item.getItemMeta().getPersistentDataContainer()
                .get(KEY_RUNE_LEVEL, PersistentDataType.INTEGER);
        return lv == null ? 1 : Math.max(1, Math.min(10, lv));
    }

    /** 生命符文：每级 +4 生命值。 */
    public static double healthBonus(int level) { return 4.0 * level; }

    /** 移速符文：每级 +5% 移动速度（MOVEMENT_SPEED 基础 0.1，5% = 0.005）。 */
    public static double speedBonus(int level) { return 0.005 * level; }

    /** 药水符文：1-3→I / 4-6→II / 7-9→III / 10→IV */
    public static int potionAmplifier(int level) { return (level - 1) / 3; }

    /** 等级对应的符文描述（用于物品 Lore 与列表显示）。 */
    public static String descFor(RuneType t, int level) {
        return switch (t.id) {
            case "HEALTH" -> "装备最大生命值 &c+" + (int) healthBonus(level);
            case "SPEED" -> "装备移动速度 &b+" + (int) (speedBonus(level) / 0.001) + "%";
            case "STRENGTH" -> "穿戴时获得 &c力量 " + roman(potionAmplifier(level) + 1) + " &7效果";
            case "REGEN" -> "穿戴时获得 &d再生 " + roman(potionAmplifier(level) + 1) + " &7效果";
            case "RESIST" -> "穿戴时获得 &7抗性提升 " + roman(potionAmplifier(level) + 1) + " &7效果";
            case "FIRE" -> "穿戴时获得 &6抗火 &7效果";
            default -> t.desc;
        };
    }

    /** 等级对应的简短效果（用于符文槽 Lore）。 */
    public static String effectFor(RuneType t, int level) {
        return switch (t.id) {
            case "HEALTH" -> "生命值 &c+" + (int) healthBonus(level);
            case "SPEED" -> "移速 &b+" + (int) (speedBonus(level) / 0.001) + "%";
            case "STRENGTH" -> "力量 &c" + roman(potionAmplifier(level) + 1);
            case "REGEN" -> "再生 &d" + roman(potionAmplifier(level) + 1);
            case "RESIST" -> "抗性 &7" + roman(potionAmplifier(level) + 1);
            case "FIRE" -> "抗火 &6";
            default -> t.effect;
        };
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV";
            case 5 -> "V"; case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII";
            case 9 -> "IX"; default -> String.valueOf(n);
        };
    }

    /** 是否为符文物品。 */
    public static boolean isRune(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(KEY_RUNE, PersistentDataType.BYTE);
    }

    /** 获取符文类型 id（非符文返回 null）。 */
    public static String getRuneType(ItemStack item) {
        if (!isRune(item)) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(KEY_RUNE_TYPE, PersistentDataType.STRING);
        return id != null ? id.toUpperCase() : null;
    }

    // ==================== 符文槽（装备侧） ====================

    /** 根据装备淬炼等级返回符文槽数量。 */
    public static int runeSlotsForLevel(int upgradeLevel) {
        if (upgradeLevel >= 10) return 4;
        if (upgradeLevel >= 7) return 3;
        if (upgradeLevel >= 4) return 2;
        if (upgradeLevel >= 1) return 1;
        return 0;
    }

    /** 读取装备已镶嵌的符文（槽位 -> 符文类型 id，空槽为 null）。 */
    public static String[] getInstalledRunes(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return new String[KEY_SLOTS.length];
        var pdc = equip.getItemMeta().getPersistentDataContainer();
        String[] runes = new String[KEY_SLOTS.length];
        for (int i = 0; i < KEY_SLOTS.length; i++) {
            String id = pdc.get(KEY_SLOTS[i], PersistentDataType.STRING);
            runes[i] = id != null ? id : null;
        }
        return runes;
    }

    /** 将符文写入装备下一个空槽位（含等级）；槽位已满返回 -1，成功返回槽位索引。 */
    public static int installRune(ItemStack equip, String runeType, int level) {
        if (equip == null || runeType == null) return -1;
        ItemMeta meta = equip.getItemMeta();
        if (meta == null) return -1;
        var pdc = meta.getPersistentDataContainer();
        // 符文槽数量由宝石等级之和决定（初始0个，随宝石等级提升解锁）
        int totalLevel = com.clawx.elitemobs.essence.EliteGemFactory.totalGemLevel(equip);
        int capacity = com.clawx.elitemobs.essence.EliteGemFactory.runeSlotsForTotalLevel(totalLevel);
        level = Math.max(1, Math.min(10, level));
        for (int i = 0; i < capacity && i < KEY_SLOTS.length; i++) {
            if (!pdc.has(KEY_SLOTS[i], PersistentDataType.STRING)) {
                pdc.set(KEY_SLOTS[i], PersistentDataType.STRING, runeType);
                pdc.set(KEY_SLOT_LEVELS[i], PersistentDataType.INTEGER, level);
                equip.setItemMeta(meta);
                return i;
            }
        }
        return -1;
    }

    private static String msg(FileConfiguration msgs, String key, String def) {
        return (msgs != null && msgs.contains(key)) ? msgs.getString(key) : def;
    }
}
