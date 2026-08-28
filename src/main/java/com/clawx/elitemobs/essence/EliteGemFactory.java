package com.clawx.elitemobs.essence;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.clawx.elitemobs.EliteMobsPlugin;

/**
 * 宝石槽系统 —— 统一宝石淬炼/镶嵌（替代原攻击伤害宝石/护甲宝石精华）。
 *
 * <p>所有 gems/*.yml 宝石都能淬炼到装备上，每种宝石独立等级 Lv.1-10，
 * 宝石等级之和用于解锁宝石槽与符文槽数量。</p>
 *
 * <p>淬炼机制与原版完全一致：成功率随宝石等级、失败降级（等级越高掉越多）、
 * 保护符防降级但宝石仍消耗。</p>
 */
public final class EliteGemFactory {

    /** 装备上的宝石槽 PDC 键（gem_1 ~ gem_4，存宝石类型 id，如 attack_gem） */
    public static final NamespacedKey[] KEY_GEM_SLOTS = {
        new NamespacedKey("elitemobs", "gem_1"),
        new NamespacedKey("elitemobs", "gem_2"),
        new NamespacedKey("elitemobs", "gem_3"),
        new NamespacedKey("elitemobs", "gem_4"),
    };
    /** 装备上的宝石槽等级 PDC 键（gem_lv_1 ~ gem_lv_4，存整数等级） */
    public static final NamespacedKey[] KEY_GEM_SLOT_LEVELS = {
        new NamespacedKey("elitemobs", "gem_lv_1"),
        new NamespacedKey("elitemobs", "gem_lv_2"),
        new NamespacedKey("elitemobs", "gem_lv_3"),
        new NamespacedKey("elitemobs", "gem_lv_4"),
    };
    public static final int MAX_GEM_SLOTS = 4;

    private EliteGemFactory() {}

    // ==================== 槽位数量解锁（按宝石等级之和） ====================

    /** 根据宝石等级之和返回可用的宝石槽数量（初始 1 个，慢慢解锁）。 */
    public static int gemSlotsForLevel(int totalGemLevel) {
        if (totalGemLevel >= 10) return 4;
        if (totalGemLevel >= 6) return 3;
        if (totalGemLevel >= 3) return 2;
        return 1;
    }

    /** 根据宝石等级之和返回可用的符文槽数量（初始 0 个，慢慢解锁）。 */
    public static int runeSlotsForTotalLevel(int totalGemLevel) {
        if (totalGemLevel >= 12) return 4;
        if (totalGemLevel >= 8) return 3;
        if (totalGemLevel >= 4) return 2;
        if (totalGemLevel >= 1) return 1;
        return 0;
    }

    // ==================== 读取装备宝石 ====================

    /** 读取装备已镶嵌的宝石（槽位 -> 宝石类型 id，空槽为 null）。 */
    public static String[] getInstalledGems(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return new String[MAX_GEM_SLOTS];
        var pdc = equip.getItemMeta().getPersistentDataContainer();
        String[] gems = new String[MAX_GEM_SLOTS];
        for (int i = 0; i < MAX_GEM_SLOTS; i++) {
            gems[i] = pdc.get(KEY_GEM_SLOTS[i], PersistentDataType.STRING);
        }
        return gems;
    }

    /** 读取装备各宝石槽等级（默认 1）。 */
    public static int[] getInstalledGemLevels(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return new int[MAX_GEM_SLOTS];
        var pdc = equip.getItemMeta().getPersistentDataContainer();
        int[] lv = new int[MAX_GEM_SLOTS];
        for (int i = 0; i < MAX_GEM_SLOTS; i++) {
            Integer v = pdc.get(KEY_GEM_SLOT_LEVELS[i], PersistentDataType.INTEGER);
            lv[i] = v == null ? 1 : Math.max(1, Math.min(10, v));
        }
        return lv;
    }

    /** 装备上所有宝石等级之和（用于解锁槽位）。 */
    public static int totalGemLevel(ItemStack equip) {
        int[] lv = getInstalledGemLevels(equip);
        String[] ids = getInstalledGems(equip);
        int sum = 0;
        for (int i = 0; i < MAX_GEM_SLOTS; i++) {
            if (ids[i] != null) sum += lv[i];
        }
        return sum;
    }

    /** 查找装备上已有该宝石的槽位（无则返回 -1）。 */
    public static int findGemSlot(ItemStack equip, String gemId) {
        String[] ids = getInstalledGems(equip);
        for (int i = 0; i < MAX_GEM_SLOTS; i++) {
            if (gemId.equalsIgnoreCase(ids[i])) return i;
        }
        return -1;
    }

    /** 查找装备上空闲宝石槽（无则返回 -1）。 */
    public static int findEmptyGemSlot(ItemStack equip) {
        String[] ids = getInstalledGems(equip);
        for (int i = 0; i < MAX_GEM_SLOTS; i++) {
            if (ids[i] == null) return i;
        }
        return -1;
    }

    /** 写入宝石到指定槽位（类型 + 等级）。 */
    public static void setGemSlot(ItemStack equip, int slot, String gemId, int level) {
        if (equip == null || slot < 0 || slot >= MAX_GEM_SLOTS) return;
        ItemMeta meta = equip.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_GEM_SLOTS[slot], PersistentDataType.STRING, gemId);
        pdc.set(KEY_GEM_SLOT_LEVELS[slot], PersistentDataType.INTEGER,
                Math.max(1, Math.min(10, level)));
        equip.setItemMeta(meta);
    }

    /** 移除指定槽位的宝石。 */
    public static void clearGemSlot(ItemStack equip, int slot) {
        if (equip == null || slot < 0 || slot >= MAX_GEM_SLOTS) return;
        ItemMeta meta = equip.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        pdc.remove(KEY_GEM_SLOTS[slot]);
        pdc.remove(KEY_GEM_SLOT_LEVELS[slot]);
        equip.setItemMeta(meta);
    }

    // ==================== 宝石物品识别（掉落物/指令发放的宝石） ====================

    /** 是否为宝石物品（gems/*.yml 构建，带 gem_id PDC）。 */
    public static boolean isGem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(
                        new NamespacedKey("elitemobs", "gem_id"), PersistentDataType.STRING);
    }

    /** 获取宝石物品的 id（非宝石返回 null）。 */
    public static String getGemId(ItemStack item) {
        if (!isGem(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey("elitemobs", "gem_id"), PersistentDataType.STRING);
    }

    /** 获取宝石物品的效果类型（attack/defense/thunder/knockback，非宝石返回 null）。 */
    public static String getGemEffect(ItemStack item) {
        if (!isGem(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey("elitemobs", "gem_effect"), PersistentDataType.STRING);
    }

    /** 获取宝石物品自身等级（非宝石返回 1）。 */
    public static int getGemLevel(ItemStack item) {
        if (!isGem(item)) return 1;
        Integer lv = item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey("elitemobs", "gem_level"), PersistentDataType.INTEGER);
        return lv == null ? 1 : Math.max(1, Math.min(10, lv));
    }

    /** 测试宝石成功率覆盖 PDC 键（0.0=必失败 / 1.0=必成功；无 = 按等级计算）。 */
    public static final NamespacedKey KEY_GEM_SUCCESS_RATE = new NamespacedKey("elitemobs", "gem_success_rate");

    /** 获取宝石的成功率覆盖值（-1 = 无覆盖按等级计算；0~1 = 强制成功率）。 */
    public static double getGemSuccessRate(ItemStack item) {
        if (!isGem(item)) return -1;
        Double d = item.getItemMeta().getPersistentDataContainer().get(
                KEY_GEM_SUCCESS_RATE, PersistentDataType.DOUBLE);
        return d == null ? -1 : d;
    }

    // ==================== 效果计算（按宝石等级，原算法） ====================

    /** 攻击宝石：攻击力 = 等级² × 0.5。 */
    public static double attackBonus(int level) { return level * level * 0.5; }

    /** 防御宝石：护甲减伤 = 等级 × 1.5。 */
    public static double defenseBonus(int level) { return level * 1.5; }

    /** 击退宝石：击退等级 = 等级（与攻击/防御一致的原算法，非概率）。 */
    public static int knockbackLevel(int level) { return level; }

    /** 雷电宝石：召唤闪电概率 = 等级越高概率越高（8% + 每级 7%，上限 85%）。 */
    public static double thunderChance(int level) { return Math.min(0.08 + level * 0.07, 0.85); }

    /** 磁力宝石：自动拾取距离 = 3 + 等级（上限 12 格，等级越高吸得越远）。 */
    public static int magnetRadius(int level) { return Math.min(3 + level, 12); }

    /** 二段跳宝石：跳跃力度（向上速度），等级越高跳得越高（上限 1.8）。 */
    public static double jumpPower(int level) { return Math.min(1.0 + level * 0.06, 1.8); }

    /** 二段跳宝石：再次起跳冷却（毫秒），等级越高蓄力越快（冷却越短，Lv1≈2.7s → Lv10=0.4s）。 */
    public static int jumpCooldown(int level) { return Math.max(400, 3000 - level * 260); }

    // ==================== 武器/护甲品质（首次淬炼时掷定，影响后续成功率） ====================

    public static final NamespacedKey KEY_QUALITY = new NamespacedKey("elitemobs", "quality");
    public static final NamespacedKey KEY_PROF = new NamespacedKey("elitemobs", "prof");

    public static final int Q_COMMON = 0;    // 普通
    public static final int Q_UNCOMMON = 1;  // 优秀
    public static final int Q_RARE = 2;      // 传说
    public static final int Q_EPIC = 3;      // 史诗
    public static final int Q_MYTHIC = 4;    // 神话
    public static final int MAX_QUALITY = 4;

    /** 读取装备品质（无标记返回普通 0）。 */
    public static int getQuality(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return Q_COMMON;
        Integer q = equip.getItemMeta().getPersistentDataContainer().get(KEY_QUALITY, PersistentDataType.INTEGER);
        return q == null ? Q_COMMON : Math.max(Q_COMMON, Math.min(MAX_QUALITY, q));
    }

    /** 写入装备品质（自动夹取到 0-4）。 */
    public static void setQuality(ItemStack equip, int q) {
        if (equip == null) return;
        equip.editMeta(meta -> meta.getPersistentDataContainer().set(KEY_QUALITY, PersistentDataType.INTEGER,
                Math.max(Q_COMMON, Math.min(MAX_QUALITY, q))));
    }

    /** 按权重掷定品质（返回 0-4）。 */
    public static int rollQuality(java.util.Random rng, int[] weights) {
        if (weights == null || weights.length == 0) return Q_COMMON;
        int total = 0;
        for (int w : weights) total += Math.max(0, w);
        if (total <= 0) return Q_COMMON;
        int roll = rng.nextInt(total);
        for (int i = 0; i < weights.length && i <= MAX_QUALITY; i++) {
            roll -= Math.max(0, weights[i]);
            if (roll < 0) return i;
        }
        return Q_COMMON;
    }

    /** 品质显示名（含颜色，与宝石品质命名一致：普通/优秀/传说/史诗/神话）。 */
    public static String qualityName(int q) {
        return switch (q) {
            case Q_UNCOMMON -> "&e&l优秀";
            case Q_RARE -> "&a&l传说";
            case Q_EPIC -> "&b&l史诗";
            case Q_MYTHIC -> "&2&l神话";
            default -> "&f普通";
        };
    }

    // ==================== 武器熟练度（淬炼成功 +1 星，提升暴击率） ====================

    /** 读取武器熟练度星级（0-5）。 */
    public static int getProf(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return 0;
        Integer p = equip.getItemMeta().getPersistentDataContainer().get(KEY_PROF, PersistentDataType.INTEGER);
        return p == null ? 0 : Math.max(0, Math.min(5, p));
    }

    /** 熟练度 +1 星（封顶 maxStars）。 */
    public static void addProf(ItemStack equip, int maxStars) {
        if (equip == null) return;
        int cur = getProf(equip);
        int max = Math.max(1, Math.min(5, maxStars));
        if (cur >= max) return;
        final int nv = cur + 1;
        equip.editMeta(meta -> meta.getPersistentDataContainer().set(KEY_PROF, PersistentDataType.INTEGER, nv));
    }

    /** 星级显示：★（金色）/ ☆（灰色），如 3 星 → "&e★★★&7☆☆"。 */
    public static String profStars(int stars) {
        stars = Math.max(0, Math.min(5, stars));
        StringBuilder sb = new StringBuilder("&e");
        for (int i = 0; i < stars; i++) sb.append('★');
        sb.append("&7");
        for (int i = stars; i < 5; i++) sb.append('☆');
        return sb.toString();
    }
}

