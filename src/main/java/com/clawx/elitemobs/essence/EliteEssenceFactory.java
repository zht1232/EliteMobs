package com.clawx.elitemobs.essence;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import com.clawx.elitemobs.EliteMobsPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 精华（宝石）工厂 —— 严格按原版 EliteEssenceFactory 逻辑实现。
 *
 * <p>创建三种淬炼物品（= 宝石种类）：</p>
 * <ul>
 *   <li>武器精华（武器宝石）：为武器加攻击力，成功率随等级提升</li>
 *   <li>护甲精华（护甲宝石）：为护甲加减伤，写入 armor_lv</li>
 *   <li>淬炼保护符：淬炼失败时防降级/防销毁</li>
 * </ul>
 *
 * <p>PDC 键（与原版完全一致）：essence / essence_level / armor_essence /
 * armor_essence_level / charm</p>
 */
public final class EliteEssenceFactory {

    public static final NamespacedKey KEY_ESSENCE = new NamespacedKey("elitemobs", "essence");
    public static final NamespacedKey KEY_ESSENCE_LVL = new NamespacedKey("elitemobs", "essence_level");
    public static final NamespacedKey KEY_ARMOR_ESSENCE = new NamespacedKey("elitemobs", "armor_essence");
    public static final NamespacedKey KEY_ARMOR_ESSENCE_LVL = new NamespacedKey("elitemobs", "armor_essence_level");
    public static final NamespacedKey KEY_CHARM = new NamespacedKey("elitemobs", "charm");
    public static final NamespacedKey KEY_REMOVER = new NamespacedKey("elitemobs", "gem_remover");

    /** 武器精华纹理（SnowyGems 攻击伤害宝石外观，等级越高品质越高） */
    private static final String[] TEX = {
        // Lv.1-10 统一使用 SnowyGems「攻击伤害宝石」纹理（红色系，随等级颜色变化）
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEzODc0ZTgyYmM0NjZhOWM5Zjc5YWFmYmE3MTUxN2UyZWNjMjk0MWM2YmM5Njc5MDYyYTk3YjkxYjJkYWUzOSJ9fX0=",
    };

    /** 护甲精华纹理（SnowyGems「护甲宝石」外观） */
    private static final String ARMOR_TEX = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmJkMzFhZDFlOTNmMTY2Zjk3ODU5Y2Q5ZjJiODBkYTUyYTgyOTQ0MDA5N2Y1ZDY3YThjMjEwZDEyMmI5ZDVlNSJ9fX0=";

    private static final String CHARM_TEX = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTY4ODQ2MGM3MGUxNTlkZDk1YmZmMzgxZTA2ZGNhYzAwZWEzZWNjOGRkMmYwMWRmZDE3MzdhYzRlZjE1NzcxMCJ9fX0=";

    /** 宝石拆卸器纹理（SnowyGems「夜视药水」外观，暗蓝调与保护符区分） */
    private static final String REMOVER_TEX = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTYyOWEzZDNmNTE5ZGNkMzg1YjU5OTJlZjM0YmZhOGE2OGMzNzQ0NDc1MGI5NGVkZTVjMmY1MjFlMTczNDIifX19";

    private EliteEssenceFactory() {}

    /** 给头颅应用 base64 纹理（原版反射实现，兼容 Paper）。 */
    public static void applyTexture(SkullMeta meta, String base64) {
        try {
            Class<?> profileClass = Class.forName("com.destroystokyo.paper.profile.CraftPlayerProfile");
            Constructor<?> ctor = profileClass.getConstructor(UUID.class, String.class);
            Object profile = ctor.newInstance(UUID.randomUUID(), null);
            Method setProperty = profile.getClass().getMethod("setProperty",
                    Class.forName("com.destroystokyo.paper.profile.ProfileProperty"));
            setProperty.invoke(profile, new Object[]{new com.destroystokyo.paper.profile.ProfileProperty("textures", base64, null)});
            Method setPlayerProfile = meta.getClass().getMethod("setPlayerProfile",
                    Class.forName("com.destroystokyo.paper.profile.PlayerProfile"));
            setPlayerProfile.invoke(meta, profile);
        } catch (Exception ignored) {
            // 兼容失败时静默降级为普通头颅
        }
    }

    public static ItemStack createEliteEssence(int level) {
        return createEliteEssence(level, null);
    }

    /** 创建武器精华（武器宝石）。原版：纹理按等级、显示名、成功率 lore、PDC 标记。 */
    public static ItemStack createEliteEssence(int level, FileConfiguration msgs) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta == null) return stack;

        int texIdx = Math.max(0, Math.min(level - 1, TEX.length - 1));
        applyTexture(meta, TEX[texIdx]);

        String name = msg(msgs, "essence.name", "&c&l攻击伤害宝石");
        String color = level >= 9 ? "&2" : level >= 7 ? "&6" : level >= 4 ? "&d" : level >= 2 ? "&c" : "&b";
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                "&8&l&k||" + color + name + " &7[&fLv." + level + "&7]&8&l&k||"));

        // 按等级分品质（SnowyGems 风格）
        String quality = qualityFor(level);
        double rate = Math.min(35.0 + (level - 1) * 4.5, 95.0);
        String loreRate = msg(msgs, "essence.lore.success-rate", "成功率: {rate}%").replace("{rate}", String.format("%.0f", rate));
        String loreUsage = msg(msgs, "essence.lore.usage", "将装备与此宝石放入铁砧淬炼");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&',
                "&c攻击伤害宝石 &7品质: " + quality));
        lore.add(ChatColor.DARK_GRAY + "武器 Lv." + level);
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7" + loreUsage));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&a" + loreRate));
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(KEY_ESSENCE, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(KEY_ESSENCE_LVL, PersistentDataType.INTEGER, level);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 按等级返回品质文本（SnowyGems 风格：普通/优秀/传说/史诗/神话） */
    private static String qualityFor(int level) {
        if (level >= 10) return "&2&l神话";
        if (level >= 7) return "&b&l史诗";
        if (level >= 4) return "&a&l传说";
        if (level >= 2) return "&e&l优秀";
        return "&f普通";
    }

    public static ItemStack createArmorEssence(int level) {
        return createArmorEssence(level, null);
    }

    /** 创建护甲精华（护甲宝石）。原版：纹理按等级、成功率 lore、PDC 标记。 */
    public static ItemStack createArmorEssence(int level, FileConfiguration msgs) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta == null) return stack;

        int texIdx = Math.max(0, Math.min(level - 1, TEX.length - 1));
        applyTexture(meta, ARMOR_TEX);

        String name = msg(msgs, "armor-essence.name", "&7&l护甲宝石");
        String color = level >= 9 ? "&2" : level >= 4 ? "&d" : level >= 2 ? "&c" : "&b";
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                "&8&l&k||" + color + name + " &7[&fLv." + level + "&7]&8&l&k||"));

        // 按等级分品质（SnowyGems 风格）
        String quality = qualityFor(level);
        // 成功率显示与判定一致：封顶 95%（baseRate 35% + (lv-1)*4.5%）
        double rate = Math.min(35.0 + (level - 1) * 4.5, 95.0);
        String loreRate = msg(msgs, "armor-essence.lore.success-rate", "成功率: {rate}%").replace("{rate}", String.format("%.0f", rate));
        String loreUsage = msg(msgs, "armor-essence.lore.usage", "将装备与此宝石放入铁砧淬炼");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&',
                "&7护甲宝石 &7品质: " + quality));
        lore.add(ChatColor.DARK_GRAY + "护甲 Lv." + level);
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7" + loreUsage));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&a" + loreRate));
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(KEY_ARMOR_ESSENCE, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(KEY_ARMOR_ESSENCE_LVL, PersistentDataType.INTEGER, level);
        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack createProtectionCharm() {
        return createProtectionCharm(null);
    }

    /** 创建淬炼保护符（原版：固定纹理、lore、PDC 标记）。 */
    public static ItemStack createProtectionCharm(FileConfiguration msgs) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta == null) return stack;
        applyTexture(meta, CHARM_TEX);

        String name = msg(msgs, "protection-charm.name", "&e&l淬炼保护符");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "protection-charm.lore.0", "&7放在背包中即可生效")));
        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "protection-charm.lore.1", "&7淬炼失败时&c免受降级")));
        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "protection-charm.lore.2", "&4一次性消耗品")));
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(KEY_CHARM, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 创建宝石拆卸器（铁砧中与已淬炼装备合成，拆卸所有宝石）。 */
    public static ItemStack createGemRemover() {
        return createGemRemover(null);
    }

    /** 创建宝石拆卸器（带消息配置）。 */
    public static ItemStack createGemRemover(FileConfiguration msgs) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta == null) return stack;
        applyTexture(meta, REMOVER_TEX);

        String name = msg(msgs, "gem-remover.name", "&d&l宝石拆卸器");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "gem-remover.lore.0", "&7将已淬炼装备与此拆卸器放入铁砧")));
        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "gem-remover.lore.1", "&7拆卸装备上&e所有宝石&7，返还宝石")));
        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "gem-remover.lore.2", "&7返还宝石等级&c会流失&7（Lv.X→X颗 Lv.X-1）")));
        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "gem-remover.lore.3", "&4一次性消耗品")));
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(KEY_REMOVER, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 是否为宝石拆卸器。 */
    public static boolean isGemRemover(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(KEY_REMOVER, PersistentDataType.BYTE);
    }

    // ==================== 判定方法 ====================

    public static boolean isWeaponEssence(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) return false;
        var meta = item.getItemMeta();
        if (meta.getPersistentDataContainer().has(KEY_ESSENCE, PersistentDataType.BYTE)) return true;
        // fallback：仅当无 PDC 时按显示名判断（精确到武器类，避免与护甲宝石混用）
        return meta.hasDisplayName() &&
                (meta.getDisplayName().contains("攻击伤害宝石") || meta.getDisplayName().contains("武器精华"));
    }

    public static boolean isArmorEssenceItem(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) return false;
        var meta = item.getItemMeta();
        if (meta.getPersistentDataContainer().has(KEY_ARMOR_ESSENCE, PersistentDataType.BYTE)) return true;
        // fallback：仅当无 PDC 时按显示名判断（精确到护甲类，避免与武器宝石混用）
        return meta.hasDisplayName() &&
                (meta.getDisplayName().contains("护甲宝石") || meta.getDisplayName().contains("护甲精华"));
    }

    public static boolean isProtectionCharm(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(KEY_CHARM, PersistentDataType.BYTE);
    }

    public static int getEssenceLevel(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return 1;
        var meta = item.getItemMeta();
        if (meta == null) return 1;
        if (meta.getPersistentDataContainer().has(KEY_ESSENCE_LVL, PersistentDataType.INTEGER)) {
            return meta.getPersistentDataContainer().get(KEY_ESSENCE_LVL, PersistentDataType.INTEGER);
        }
        // 从显示名解析 Lv.X
        try {
            if (meta.hasDisplayName()) {
                String d = meta.getDisplayName();
                int idx = d.indexOf("Lv.");
                if (idx >= 0) return Integer.parseInt(d.substring(idx + 3).replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {}
        return 1;
    }

    public static int getArmorEssenceLevel(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return 1;
        var meta = item.getItemMeta();
        if (meta == null) return 1;
        if (meta.getPersistentDataContainer().has(KEY_ARMOR_ESSENCE_LVL, PersistentDataType.INTEGER)) {
            return meta.getPersistentDataContainer().get(KEY_ARMOR_ESSENCE_LVL, PersistentDataType.INTEGER);
        }
        try {
            if (meta.hasDisplayName()) {
                String d = meta.getDisplayName();
                int idx = d.indexOf("Lv.");
                if (idx >= 0) return Integer.parseInt(d.substring(idx + 3).replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {}
        return 1;
    }

    private static String msg(FileConfiguration msgs, String key, String def) {
        return (msgs != null && msgs.contains(key)) ? msgs.getString(key) : def;
    }
}
