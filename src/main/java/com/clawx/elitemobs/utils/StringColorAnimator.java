package com.clawx.elitemobs.utils;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 动态彩色标题/副标题动画（借鉴原版 EliteMobs StringColorAnimator）。
 * 每 2 tick 逐字插入主/次颜色码，形成"打字机 + 双色渐变"效果，播完自动结束。
 * 用法示例（Boss 登场 / 第二阶段）：
 *   StringColorAnimator.animateTitleAll(plugin, "§cBOSS 警报！", "名字 [Lv.X]",
 *       ChatColor.RED, ChatColor.GOLD);
 */
public final class StringColorAnimator {
    private StringColorAnimator() {}

    /** 标题宽度上限（半角单位）：MC title 每行约 40 单位，留余量防溢出。 */
    private static final int MAX_WIDTH = 36;

    /**
     * 按可见字符宽度截断字符串（中文/全角按 2 单位），自动跳过 § 颜色码。
     * 超宽时截断并追加省略号，保证 title/副标题永不溢出屏幕。
     */
    static String fitWidth(String s) {
        if (s == null) return "";
        StringBuilder visible = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { i++; continue; } // 跳过颜色码（§x 两个字符，不占可见宽度）
            int cw = (c >= 0x2E80 || c == '\u2026') ? 2 : 1; // CJK 范围视为全角
            if (w + cw > MAX_WIDTH) {
                visible.append('\u2026'); // …
                break;
            }
            visible.append(c);
            w += cw;
        }
        return visible.toString();
    }

    /** 给单个玩家播放动态彩色标题。 */
    public static void animateTitle(JavaPlugin plugin, Player player, String title, String subtitle,
                                    ChatColor primary, ChatColor secondary) {
        if (player == null || !player.isOnline()) return;
        String safeTitle = fitWidth(title);
        String safeSub = fitWidth(subtitle);
        new BukkitRunnable() {
            int titleIndex = 1;
            int subtitleIndex = 1;
            @Override public void run() {
                if (titleIndex <= safeTitle.length()) {
                    StringBuilder t = new StringBuilder(safeTitle).insert(titleIndex, primary);
                    if (titleIndex > 1) t.insert(titleIndex - 2, secondary);
                    player.sendTitle(primary + convert(t.toString()), secondary + safeSub, 0, 5, 0);
                    titleIndex++;
                    return;
                }
                if (subtitleIndex <= safeSub.length()) {
                    StringBuilder s = new StringBuilder(safeSub).insert(subtitleIndex, primary);
                    if (subtitleIndex > 1) s.insert(subtitleIndex - 2, secondary);
                    player.sendTitle(secondary + safeTitle, primary + convert(s.toString()), 0, 5, 0);
                    subtitleIndex++;
                    return;
                }
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /** 全服广播动态彩色标题（单一任务遍历所有在线玩家，避免为每个玩家创建独立任务）。 */
    public static void animateTitleAll(JavaPlugin plugin, String title, String subtitle,
                                       ChatColor primary, ChatColor secondary) {
        String safeTitle = fitWidth(title);
        String safeSub = fitWidth(subtitle);
        new BukkitRunnable() {
            int titleIndex = 1;
            int subtitleIndex = 1;
            @Override public void run() {
                if (titleIndex <= safeTitle.length()) {
                    StringBuilder t = new StringBuilder(safeTitle).insert(titleIndex, primary);
                    if (titleIndex > 1) t.insert(titleIndex - 2, secondary);
                    String tStr = primary + convert(t.toString());
                    String sStr = secondary + safeSub;
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        if (p != null && p.isOnline()) p.sendTitle(tStr, sStr, 0, 5, 0);
                    }
                    titleIndex++;
                    return;
                }
                if (subtitleIndex <= safeSub.length()) {
                    StringBuilder s = new StringBuilder(safeSub).insert(subtitleIndex, primary);
                    if (subtitleIndex > 1) s.insert(subtitleIndex - 2, secondary);
                    String tStr = secondary + safeTitle;
                    String sStr = primary + convert(s.toString());
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        if (p != null && p.isOnline()) p.sendTitle(tStr, sStr, 0, 5, 0);
                    }
                    subtitleIndex++;
                    return;
                }
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /** 同世界范围内玩家广播动态彩色标题（单任务遍历，range<0 视为全服）。 */
    public static void animateTitleNear(JavaPlugin plugin, World w, Location loc, double range,
                                        String title, String subtitle,
                                        ChatColor primary, ChatColor secondary) {
        String safeTitle = fitWidth(title);
        String safeSub = fitWidth(subtitle);
        double r2 = range < 0 ? Double.POSITIVE_INFINITY : range * range;
        new BukkitRunnable() {
            int titleIndex = 1;
            int subtitleIndex = 1;
            @Override public void run() {
                if (titleIndex <= safeTitle.length()) {
                    StringBuilder t = new StringBuilder(safeTitle).insert(titleIndex, primary);
                    if (titleIndex > 1) t.insert(titleIndex - 2, secondary);
                    String tStr = primary + convert(t.toString());
                    String sStr = secondary + safeSub;
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        if (p == null || !p.isOnline()) continue;
                        if (w != null && loc != null && (!p.getWorld().equals(w)
                                || p.getLocation().distanceSquared(loc) > r2)) continue;
                        p.sendTitle(tStr, sStr, 0, 5, 0);
                    }
                    titleIndex++;
                    return;
                }
                if (subtitleIndex <= safeSub.length()) {
                    StringBuilder s = new StringBuilder(safeSub).insert(subtitleIndex, primary);
                    if (subtitleIndex > 1) s.insert(subtitleIndex - 2, secondary);
                    String tStr = secondary + safeTitle;
                    String sStr = primary + convert(s.toString());
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        if (p == null || !p.isOnline()) continue;
                        if (w != null && loc != null && (!p.getWorld().equals(w)
                                || p.getLocation().distanceSquared(loc) > r2)) continue;
                        p.sendTitle(tStr, sStr, 0, 5, 0);
                    }
                    subtitleIndex++;
                    return;
                }
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private static String convert(String in) {
        return ChatColor.translateAlternateColorCodes('&', in);
    }
}
