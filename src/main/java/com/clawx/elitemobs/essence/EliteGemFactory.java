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

    /** 吸血宝石：每击吸血量 = 1 + 等级×0.5（颗心）。 */
    public static double lifestealHeal(int level) { return 1.0 + level * 0.5; }

    /** 火焰附加宝石：燃烧秒数 = 2 + 等级/2。 */
    public static int fireAspectSeconds(int level) { return 2 + level / 2; }

    /** 耐久宝石：减免比例（Lv.10 = 1.0 = 无限耐久），0.1~0.9 为减免 10%~90%。 */
    public static double unbreakingReduction(int level) { return level >= 10 ? 1.0 : level * 0.1; }

    // ==================== 武器/护甲品质（首次淬炼时掷定，影响后续成功率） ====================
    // 9 档品质：残次 < 粗劣 < 普通 < 优秀 < 传说 < 史诗 < 神话 < 至臻 < 不朽
    // 劣质（残次/粗劣）削弱淬炼成功率，优质加成；颜色各档独立。

    public static final NamespacedKey KEY_QUALITY = new NamespacedKey("elitemobs", "quality");
    public static final NamespacedKey KEY_QUALITY_VER = new NamespacedKey("elitemobs", "quality_ver");
    public static final NamespacedKey KEY_PROF = new NamespacedKey("elitemobs", "prof");
    public static final NamespacedKey KEY_PROF_KILLS = new NamespacedKey("elitemobs", "prof_kills");

    public static final int Q_TRASH = 0;     // 残次
    public static final int Q_POOR = 1;      // 粗劣
    public static final int Q_COMMON = 2;    // 普通
    public static final int Q_UNCOMMON = 3;  // 优秀
    public static final int Q_LEGENDARY = 4; // 传说
    public static final int Q_EPIC = 5;      // 史诗
    public static final int Q_MYTHIC = 6;    // 神话
    public static final int Q_ULTRA = 7;     // 至臻
    public static final int Q_IMMORTAL = 8;  // 不朽
    public static final int MAX_QUALITY = 8;

    /** 读取装备品质。旧版 5 档（普通=0..神话=4）自动迁移到新版刻度（普通=2..神话=6，即 +2）。 */
    public static int getQuality(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return Q_COMMON;
        var pdc = equip.getItemMeta().getPersistentDataContainer();
        Integer q = pdc.get(KEY_QUALITY, PersistentDataType.INTEGER);
        if (q == null) return Q_COMMON;
        // 无版本标记且旧刻度（0-4）→ 迁移到新刻度
        if (!pdc.has(KEY_QUALITY_VER, PersistentDataType.BYTE) && q <= 4) {
            return Math.max(0, Math.min(MAX_QUALITY, q + 2));
        }
        return Math.max(0, Math.min(MAX_QUALITY, q));
    }

    /** 写入装备品质（自动夹取到 0-8，并写版本标记）。 */
    public static void setQuality(ItemStack equip, int q) {
        if (equip == null) return;
        final int v = Math.max(0, Math.min(MAX_QUALITY, q));
        equip.editMeta(meta -> {
            var pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_QUALITY, PersistentDataType.INTEGER, v);
            pdc.set(KEY_QUALITY_VER, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** 按权重掷定品质（返回 0-8；权重数组需 9 个，不足时按 0 补全）。 */
    public static int rollQuality(java.util.Random rng, int[] weights) {
        if (weights == null || weights.length == 0) return Q_COMMON;
        int total = 0;
        for (int i = 0; i <= MAX_QUALITY; i++) {
            total += Math.max(0, i < weights.length ? weights[i] : 0);
        }
        if (total <= 0) return Q_COMMON;
        int roll = rng.nextInt(total);
        for (int i = 0; i <= MAX_QUALITY; i++) {
            roll -= Math.max(0, i < weights.length ? weights[i] : 0);
            if (roll < 0) return i;
        }
        return Q_COMMON;
    }

    /** 品质显示名（含颜色，9 档各档独立配色）。 */
    public static String qualityName(int q) {
        return switch (q) {
            case Q_TRASH -> "&8残次";
            case Q_POOR -> "&7粗劣";
            case Q_COMMON -> "&f普通";
            case Q_UNCOMMON -> "&e优秀";
            case Q_LEGENDARY -> "&6传说";
            case Q_EPIC -> "&5史诗";
            case Q_MYTHIC -> "&4神话";
            case Q_ULTRA -> "&2至臻";
            case Q_IMMORTAL -> "&c不朽";
            default -> "&f普通";
        };
    }

    // ==================== 武器熟练度（击杀驱动升星，指数增长，提升暴击率） ====================

    /** 武器是否已初始化熟练度（已淬炼武器才有；无则击杀不计入）。 */
    public static boolean hasProfData(ItemStack equip) {
        return equip != null && equip.hasItemMeta()
                && equip.getItemMeta().getPersistentDataContainer().has(KEY_PROF, PersistentDataType.INTEGER);
    }

    /** 读取武器熟练度星级（0-5）。 */
    public static int getProf(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return 0;
        Integer p = equip.getItemMeta().getPersistentDataContainer().get(KEY_PROF, PersistentDataType.INTEGER);
        return p == null ? 0 : Math.max(0, Math.min(5, p));
    }

    /** 写入熟练度星级（0-5）。 */
    public static void setProf(ItemStack equip, int stars) {
        if (equip == null) return;
        final int v = Math.max(0, Math.min(5, stars));
        equip.editMeta(meta -> meta.getPersistentDataContainer().set(KEY_PROF, PersistentDataType.INTEGER, v));
    }

    /** 读取当前星级内的击杀进度（升星后清零重新累计）。 */
    public static int getProfKills(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return 0;
        Integer k = equip.getItemMeta().getPersistentDataContainer().get(KEY_PROF_KILLS, PersistentDataType.INTEGER);
        return k == null ? 0 : Math.max(0, k);
    }

    /** 写入击杀进度。 */
    public static void setProfKills(ItemStack equip, int kills) {
        if (equip == null) return;
        final int v = Math.max(0, kills);
        equip.editMeta(meta -> meta.getPersistentDataContainer().set(KEY_PROF_KILLS, PersistentDataType.INTEGER, v));
    }

    /** 第 star 颗星（star=1..max，即从 star-1 星升到 star 星）所需击杀数：base × growth^(star-1)（指数增长）。 */
    public static int profKillThreshold(int star, int base, double growth) {
        if (star <= 1) return Math.max(1, base);
        double t = base * Math.pow(Math.max(1.0, growth), star - 1);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE / 2L, Math.round(t)));
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

