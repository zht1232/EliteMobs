package com.clawx.elitemobs.utils;

import org.bukkit.World;

/**
 * 月相检测（借鉴原版 EliteMobs MoonPhaseDetector）。
 * MC 月相 = 世界满月后经过的天数 mod 8：
 *   days=0 满月 → 渐亏 → 下弦 → 残月 → 新月 → 蛾眉 → 上弦 → 渐盈 → 回到满月。
 * 用于"满月夜精英生成率/强度翻倍"等夜间玩法。
 */
public final class MoonPhaseDetector {
    public enum MoonPhase {
        FULL_MOON, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT,
        NEW_MOON, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS
    }

    private MoonPhaseDetector() {}

    public static MoonPhase getPhase(World world) {
        if (world == null) return MoonPhase.FULL_MOON;
        int days = (int) (world.getFullTime() / 24000L);
        int phase = Math.floorMod(days, 8);
        return switch (phase) {
            case 0 -> MoonPhase.FULL_MOON;
            case 1 -> MoonPhase.WANING_GIBBOUS;
            case 2 -> MoonPhase.LAST_QUARTER;
            case 3 -> MoonPhase.WANING_CRESCENT;
            case 4 -> MoonPhase.NEW_MOON;
            case 5 -> MoonPhase.WAXING_CRESCENT;
            case 6 -> MoonPhase.FIRST_QUARTER;
            default -> MoonPhase.WAXING_GIBBOUS;
        };
    }

    public static boolean isFullMoon(World world) {
        return getPhase(world) == MoonPhase.FULL_MOON;
    }

    /** 中文名（便于日志/调试）。 */
    public static String getPhaseName(World world) {
        return switch (getPhase(world)) {
            case FULL_MOON -> "满月";
            case WANING_GIBBOUS -> "亏凸月";
            case LAST_QUARTER -> "下弦月";
            case WANING_CRESCENT -> "残月";
            case NEW_MOON -> "新月";
            case WAXING_CRESCENT -> "蛾眉月";
            case FIRST_QUARTER -> "上弦月";
            case WAXING_GIBBOUS -> "盈凸月";
        };
    }
}
