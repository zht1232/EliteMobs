package com.clawx.elitemobs.compat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import java.lang.reflect.Method;

/**
 * 领地/区域保护软依赖检测（WorldGuard / GriefPrevention / Towny / Factions）。
 *
 * <p>全部通过反射调用：服务器未安装对应插件时自动跳过，不会因缺类而崩溃。
 * 用于"精英 AI 尊重玩家领地"——例如破块 AI 不破坏玩家建筑、爆炸不破坏受保护方块。</p>
 */
public final class ProtectionHook {
    private static boolean checked = false;
    private static boolean hasWG = false, hasGP = false, hasTowny = false, hasFactions = false;

    private ProtectionHook() {}

    /** 惰性检测已安装的领地插件（只检测一次） */
    private static void ensureChecked() {
        if (checked) return;
        checked = true;
        hasWG = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
        hasGP = Bukkit.getPluginManager().getPlugin("GriefPrevention") != null;
        hasTowny = Bukkit.getPluginManager().getPlugin("Towny") != null;
        hasFactions = Bukkit.getPluginManager().getPlugin("Factions") != null;
    }

    /** 该位置是否位于任一已安装领地的受保护区域内 */
    public static boolean isProtected(Location loc) {
        ensureChecked();
        if (loc == null) return false;
        if (hasWG && worldGuardProtected(loc)) return true;
        if (hasGP && griefPreventionProtected(loc)) return true;
        if (hasTowny && townyProtected(loc)) return true;
        if (hasFactions && factionsProtected(loc)) return true;
        return false;
    }

    /** WorldGuard：位置是否有区域覆盖 */
    private static boolean worldGuardProtected(Location loc) {
        try {
            Object plugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
            Class<?> cls = plugin.getClass();
            Method instM = cls.getMethod("inst");
            Object wg = instM.invoke(null);
            Method getRC = cls.getMethod("getRegionContainer");
            Object container = getRC.invoke(wg);
            Method get = container.getClass().getMethod("get", org.bukkit.World.class);
            Object rm = get.invoke(container, loc.getWorld());
            if (rm == null) return false;
            Method getApp = rm.getClass().getMethod("getApplicableRegions", Location.class);
            Object regions = getApp.invoke(rm, loc);
            Method sizeM = regions.getClass().getMethod("size");
            int size = (Integer) sizeM.invoke(regions);
            return size > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** GriefPrevention：位置是否在领地内 */
    private static boolean griefPreventionProtected(Location loc) {
        try {
            Class<?> gpCls = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object gp = gpCls.getField("instance").get(null);
            Object dataStore = gpCls.getMethod("getDataStore").invoke(gp);
            Object claim = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, Object.class)
                    .invoke(dataStore, loc, false, null);
            return claim != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Towny：位置是否非野外（城镇/领地） */
    private static boolean townyProtected(Location loc) {
        try {
            Class<?> apiCls = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
            Object api = apiCls.getMethod("getInstance").invoke(null);
            Class<?> wcCls = Class.forName("com.palmergames.bukkit.towny.object.WorldCoord");
            Object wc = wcCls.getMethod("parseWorldCoord", Location.class).invoke(null, loc);
            Method isWild = apiCls.getMethod("isWilderness", wcCls);
            Boolean wild = (Boolean) isWild.invoke(api, wc);
            return !Boolean.TRUE.equals(wild);
        } catch (Exception e) {
            return false;
        }
    }

    /** Factions：位置是否属于某个非野外派系领地 */
    private static boolean factionsProtected(Location loc) {
        try {
            Class<?> boardCls = Class.forName("com.massivecraft.factions.Board");
            Object board = boardCls.getMethod("getInstance").invoke(null);
            Object faction = boardCls.getMethod("getFactionAt", Location.class).invoke(board, loc);
            if (faction == null) return false;
            Object isWild = faction.getClass().getMethod("isWilderness").invoke(faction);
            return !Boolean.TRUE.equals(isWild);
        } catch (Exception e) {
            return false;
        }
    }
}
