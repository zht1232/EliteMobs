package com.clawx.elitemobs.utils;

/**
 * 字符串格式化工具类（提取自 EliteCombatListener / EliteMobsCommand / ItemStealAI 的重复方法）。
 */
public final class StringUtil {

    private StringUtil() {}

    /**
     * 将枚举名/材质名格式化为可读的标题大小写字符串。
     * 例：{@code formatName("CAVE_SPIDER")} → {@code "Cave Spider"}。
     * 支持 {@code _} 和空格作为分隔符。
     */
    public static String formatName(String name) {
        if (name == null || name.isEmpty()) return "";
        String n = name.toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : n.split(" ")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
