package com.clawx.elitemobs.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 通用加权概率抽取工具（借鉴原版 EliteMobs WeightedProbability）。
 * 统一收口插件里分散的"双循环累加 + nextInt(total)"加权抽取逻辑：
 * 词缀 rollAffixes、护甲材质池、武器/护甲附魔池等都可复用。
 */
public final class WeightedProbability {
    private WeightedProbability() {}

    /** 按权重随机抽取一个 key；总权重非正返回 null。使用 Math.random。 */
    public static <T> T pick(Map<T, ? extends Number> weights) {
        double total = 0;
        for (Number w : weights.values()) total += Math.max(0, w.doubleValue());
        if (total <= 0) return null;
        double roll = Math.random() * total;
        for (Map.Entry<T, ? extends Number> e : weights.entrySet()) {
            roll -= Math.max(0, e.getValue().doubleValue());
            if (roll <= 0) return e.getKey();
        }
        return null;
    }

    /** 按权重随机抽取一个 key（指定随机源）。 */
    public static <T> T pick(Random rng, Map<T, ? extends Number> weights) {
        double total = 0;
        for (Number w : weights.values()) total += Math.max(0, w.doubleValue());
        if (total <= 0) return null;
        double roll = rng.nextDouble() * total;
        for (Map.Entry<T, ? extends Number> e : weights.entrySet()) {
            roll -= Math.max(0, e.getValue().doubleValue());
            if (roll <= 0) return e.getKey();
        }
        return null;
    }

    /** 按权重不重复抽取 count 个 key（抽取过的 key 不再参与后续抽取）。 */
    public static <T> List<T> pickMultiple(Map<T, ? extends Number> weights, int count) {
        List<T> result = new ArrayList<>();
        List<Map.Entry<T, ? extends Number>> pool = new ArrayList<>(weights.entrySet());
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            double total = 0;
            for (Map.Entry<T, ? extends Number> e : pool) total += Math.max(0, e.getValue().doubleValue());
            if (total <= 0) break;
            double roll = Math.random() * total;
            Map.Entry<T, ? extends Number> picked = null;
            for (Map.Entry<T, ? extends Number> e : pool) {
                roll -= Math.max(0, e.getValue().doubleValue());
                if (roll <= 0) { picked = e; break; }
            }
            if (picked == null) picked = pool.get(pool.size() - 1);
            result.add(picked.getKey());
            pool.remove(picked);
        }
        return result;
    }
}
