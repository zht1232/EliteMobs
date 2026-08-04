package com.clawx.elitemobs;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 经济/点券挂钩（Vault 金币 + PlayerPoints 点券）。
 * 两个都是软依赖，通过反射调用，未安装对应插件时自动跳过，不影响插件运行。
 *
 * <p>Vault 用法：{@code net.milkbowl.vault.economy.Economy.depositPlayer(OfflinePlayer, double)}</p>
 * <p>PlayerPoints 用法：{@code org.black_ixx.playerpoints.PlayerPoints.getInstance().getAPI()}
 *    → {@code PlayerPointsAPI.give(UUID, int)} / {@code look(UUID)}</p>
 */
public final class EconomyHook {

    private static boolean vaultReady = false;
    private static Object vaultEconomy;
    private static Method vaultDeposit;
    private static Method vaultGetBalance;
    private static Method vaultWithdraw;

    private static boolean pointsReady = false;
    private static Object pointsAPI;
    private static Method pointsGive;
    private static Method pointsLook;
    private static Method pointsTake;

    private EconomyHook() {}

    /** 初始化/重新检测 Vault 与 PlayerPoints。 */
    public static void init() {
        vaultReady = false;
        pointsReady = false;
        vaultEconomy = null;
        pointsAPI = null;

        // ---- Vault 金币 ----
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            try {
                RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager()
                        .getRegistration(Class.forName("net.milkbowl.vault.economy.Economy"));
                if (rsp != null) {
                    vaultEconomy = rsp.getProvider();
                    vaultDeposit = vaultEconomy.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class);
                    vaultGetBalance = vaultEconomy.getClass().getMethod("getBalance", OfflinePlayer.class);
                    vaultWithdraw = vaultEconomy.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                    vaultReady = true;
                }
            } catch (Throwable ignored) {}
        }

        // ---- PlayerPoints 点券 ----
        if (Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            try {
                org.bukkit.plugin.Plugin pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
                ClassLoader cl = pp.getClass().getClassLoader();
                Class<?> cls = Class.forName("org.black_ixx.playerpoints.PlayerPoints", true, cl);
                Object instance = cls.getMethod("getInstance").invoke(null);
                pointsAPI = cls.getMethod("getAPI").invoke(instance);
                pointsGive = pointsAPI.getClass().getMethod("give", UUID.class, int.class);
                pointsLook = pointsAPI.getClass().getMethod("look", UUID.class);
                pointsTake = pointsAPI.getClass().getMethod("take", UUID.class, int.class);
                pointsReady = true;
            } catch (Throwable ignored) {}
        }
    }

    /** Vault 经济是否可用。 */
    public static boolean isVaultReady() { return vaultReady; }
    /** PlayerPoints 点券是否可用。 */
    public static boolean isPlayerPointsReady() { return pointsReady; }

    /** 给玩家发放金币（Vault）。amount<=0 或 Vault 不可用时返回 false。 */
    public static boolean depositMoney(OfflinePlayer player, double amount) {
        if (!vaultReady || player == null || amount <= 0) return false;
        try {
            vaultDeposit.invoke(vaultEconomy, player, amount);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 查询玩家金币余额（Vault）。不可用时返回 0。 */
    public static double getMoney(OfflinePlayer player) {
        if (!vaultReady || player == null) return 0;
        try {
            return ((Number) vaultGetBalance.invoke(vaultEconomy, player)).doubleValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 给玩家发放点券（PlayerPoints）。amount<=0 或 PlayerPoints 不可用时返回 false。 */
    public static boolean addPoints(OfflinePlayer player, int amount) {
        if (!pointsReady || player == null || amount <= 0) return false;
        try {
            pointsGive.invoke(pointsAPI, player.getUniqueId(), amount);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 查询玩家点券余额（PlayerPoints）。不可用时返回 0。 */
    public static int getPoints(OfflinePlayer player) {
        if (!pointsReady || player == null) return 0;
        try {
            return ((Number) pointsLook.invoke(pointsAPI, player.getUniqueId())).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 扣除玩家金币（Vault）。amount<=0 或 Vault 不可用时返回 false。 */
    public static boolean withdrawMoney(OfflinePlayer player, double amount) {
        if (!vaultReady || player == null || amount <= 0) return false;
        try {
            vaultWithdraw.invoke(vaultEconomy, player, amount);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 扣除玩家点券（PlayerPoints）。amount<=0 或 PlayerPoints 不可用时返回 false。 */
    public static boolean takePoints(OfflinePlayer player, int amount) {
        if (!pointsReady || player == null || amount <= 0) return false;
        try {
            return ((Boolean) pointsTake.invoke(pointsAPI, player.getUniqueId(), amount));
        } catch (Throwable t) {
            return false;
        }
    }
}
