package com.clawx.elitemobs.ai;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Particle;
import java.util.Random;

/**
 * 精英怪职业系统。
 * 每种职业有独特的属性加成、行为和视觉效果。
 */
public enum EliteClass {
    TANK("\u5766\u514b", ChatColor.BLUE, Color.BLUE, Particle.BUBBLE),
    ASSASSIN("\u523a\u5ba2", ChatColor.DARK_PURPLE, Color.PURPLE, Particle.PORTAL),
    MAGE("\u6cd5\u5e08", ChatColor.LIGHT_PURPLE, Color.FUCHSIA, Particle.WITCH),
    SUMMONER("\u53ec\u5524\u5e08", ChatColor.DARK_GREEN, Color.GREEN, Particle.HAPPY_VILLAGER);

    private final String displayName;
    private final ChatColor color;
    private final Color particleColor;
    private final Particle particle;

    private static final Random RNG = new Random();

    EliteClass(String displayName, ChatColor color, Color particleColor, Particle particle) {
        this.displayName = displayName;
        this.color = color;
        this.particleColor = particleColor;
        this.particle = particle;
    }

    public String getDisplayName() { return displayName; }
    public ChatColor getColor() { return color; }
    public Color getParticleColor() { return particleColor; }
    public Particle getParticle() { return particle; }

    /**
     * 根据等级随机分配职业。
     * 低等级偏坦/刺，高等级偏法/召。
     */
    public static EliteClass randomForLevel(int level) {
        double r = RNG.nextDouble();
        if (level <= 3) {
            return r < 0.4 ? TANK : r < 0.8 ? ASSASSIN : r < 0.9 ? MAGE : SUMMONER;
        } else if (level <= 6) {
            return r < 0.25 ? TANK : r < 0.5 ? ASSASSIN : r < 0.75 ? MAGE : SUMMONER;
        } else {
            return r < 0.15 ? TANK : r < 0.35 ? ASSASSIN : r < 0.65 ? MAGE : SUMMONER;
        }
    }

    /** 名称前缀标签 */
    public String getNameTag() {
        return color + "[" + displayName + "] " + ChatColor.RESET;
    }
}
