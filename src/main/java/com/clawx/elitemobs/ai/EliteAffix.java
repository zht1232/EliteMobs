package com.clawx.elitemobs.ai;

import org.bukkit.ChatColor;

/**
 * 精英怪词缀。
 * 每个词缀给精英怪附加独特的战斗行为与视觉标记（名字后缀 + 专属粒子/音效）。
 */
public enum EliteAffix {
    FIRE_AURA("火焰", ChatColor.RED),
    FROST("冰霜", ChatColor.AQUA),
    THORNS("荆棘", ChatColor.DARK_GREEN),
    LIFESTEAL("吸血", ChatColor.DARK_RED),
    BERSERK("狂暴", ChatColor.GOLD),
    SPLIT("分裂", ChatColor.DARK_PURPLE),
    BLINK("瞬移", ChatColor.LIGHT_PURPLE),
    CHAIN("雷链", ChatColor.YELLOW),
    BLOCK_BREAK("破块", ChatColor.DARK_GRAY);

    private final String display;
    private final ChatColor color;

    EliteAffix(String display, ChatColor color) {
        this.display = display;
        this.color = color;
    }

    public String getDisplay() { return display; }
    public ChatColor getColor() { return color; }

    /** 按枚举名解析，找不到返回 null */
    public static EliteAffix fromString(String s) {
        if (s == null) return null;
        for (EliteAffix a : values()) {
            if (a.name().equalsIgnoreCase(s.trim())) return a;
        }
        return null;
    }
}
