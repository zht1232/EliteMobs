package com.clawx.elitemobs.gem;

/**
 * 宝石类型（与 SnowyGems 的 GemType 一一对应）。
 * <ul>
 *   <li>{@link #NORMAL}     - 镶嵌类宝石：放入镶嵌台(铁砧)为装备附加属性/附魔</li>
 *   <li>{@link #PLAYER_GEM} - 玩家宝石：右键/食用直接生效（如点券兑换券）</li>
 *   <li>{@link #RANDOM_GEM} - 随机宝石：右键后按概率随机从 randomPool 抽取奖励</li>
 * </ul>
 */
public enum GemType {
    NORMAL,
    PLAYER_GEM,
    RANDOM_GEM;

    /**
     * 解析字符串类型名（兼容 "PlayerGem"/"RandomGem" 驼峰写法）。
     * 未知类型回退为 NORMAL。
     */
    public static GemType fromString(String s) {
        if (s == null) return NORMAL;
        String up = s.trim().toUpperCase().replace("_", "");
        switch (up) {
            case "PLAYER", "PLAYERGEM", "PLAYERGEMS" -> { return PLAYER_GEM; }
            case "RANDOM", "RANDOMGEM", "RANDOMGEMS" -> { return RANDOM_GEM; }
            default -> { return NORMAL; }
        }
    }
}
