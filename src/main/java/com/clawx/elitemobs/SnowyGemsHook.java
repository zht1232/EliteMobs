package com.clawx.elitemobs;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * SnowyGems 真实 API 挂钩（反射实现，软依赖）。
 *
 * <p>本类直接调用 SnowyGems 插件自身的类（通过其 ClassLoader 加载），生成
 * 可在镶嵌台中正常镶嵌、拆卸、生效的<b>真实</b>宝石，而非外观相似的仿制品。</p>
 *
 * <p>SnowyGems (mc233.fun.snowygems) 关键 API（v0.0.1，Kotlin 单例）：</p>
 * <ul>
 *   <li>{@code config.GemRegistry.INSTANCE} → {@code get(String)->GemConfig},
 *       {@code all()->Collection<GemConfig>}</li>
 *   <li>{@code util.ItemFactory.INSTANCE} → {@code build(GemConfig,int)->ItemStack}，
 *       用于把宝石配置构造成真正的宝石物品</li>
 *   <li>{@code manager.GemManager.INSTANCE} → {@code give(Player,String,int)->boolean}，
 *       直接发放宝石到玩家背包</li>
 * </ul>
 */
public final class SnowyGemsHook {

    private static boolean available = false;
    private static ClassLoader classLoader;
    private static Class<?> gemRegistryClass;
    private static Class<?> gemConfigClass;
    private static Class<?> itemFactoryClass;
    private static Class<?> gemManagerClass;

    private SnowyGemsHook() {}

    /** 初始化/重新检测 SnowyGems 是否加载并解析其类。 */
    public static void init() {
        available = false;
        classLoader = null;
        gemRegistryClass = gemConfigClass = itemFactoryClass = gemManagerClass = null;
        Plugin snowy = Bukkit.getPluginManager().getPlugin("SnowyGems");
        if (snowy == null || !snowy.isEnabled()) return;
        try {
            classLoader = snowy.getClass().getClassLoader();
            gemRegistryClass = Class.forName("mc233.fun.snowygems.config.GemRegistry", true, classLoader);
            gemConfigClass = Class.forName("mc233.fun.snowygems.config.GemConfig", true, classLoader);
            itemFactoryClass = Class.forName("mc233.fun.snowygems.util.ItemFactory", true, classLoader);
            gemManagerClass = Class.forName("mc233.fun.snowygems.manager.GemManager", true, classLoader);
            // 必须确认宝石注册表里真的加载了宝石才算可用。
            // 若 SnowyGems 因 MC 版本不兼容等原因只加载了类、未初始化宝石注册表，
            // 这里会得到空列表并判定为不可用，避免误报"已连接"。
            Set<String> ids = getGemIds();
            if (ids.isEmpty()) {
                available = false;
            } else {
                // 再实际试构建一颗宝石，验证 TabooLib 的 NMS 层可用。
                // 在未适配的 MC 版本上（如 26.2），即使注册表里有宝石，
                // 构建时 NMS 仍会抛异常使 buildGem 返回 null，此时应判定为不可用。
                available = buildGem(ids.iterator().next(), 1) != null;
            }
        } catch (Throwable t) {
            available = false;
        }
    }

    /** SnowyGems 插件是否已加载并启用（无论其宝石是否就绪）。 */
    public static boolean isPluginLoaded() {
        Plugin snowy = Bukkit.getPluginManager().getPlugin("SnowyGems");
        return snowy != null && snowy.isEnabled();
    }

    /** SnowyGems 插件是否已加载且 API 可用（宝石注册表已就绪）。 */
    public static boolean isAvailable() { return available; }

    /** 获取 SnowyGems 单例字段（Kotlin object 的 INSTANCE）。 */
    private static Object singleton(Class<?> clazz) throws Exception {
        Field f = clazz.getField("INSTANCE");
        return f.get(null);
    }

    /** 按宝石 ID 获取 GemConfig 对象（反射）。ID 不存在返回 null。 */
    private static Object getGemConfig(String gemId) {
        if (!available || gemId == null) return null;
        try {
            Object registry = singleton(gemRegistryClass);
            Method get = gemRegistryClass.getMethod("get", String.class);
            return get.invoke(registry, gemId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 构建真实的 SnowyGems 宝石物品（用于精英怪掉落）。
     * 等价于 SnowyGems 的 ItemFactory.build(GemConfig, amount)。
     *
     * @param gemId  宝石 ID（SnowyGems gems/*.yml 顶层键，如 "生命宝石"）
     * @param amount 数量
     * @return 真实的宝石 ItemStack；不可用或 ID 不存在时返回 null
     */
    public static ItemStack buildGem(String gemId, int amount) {
        Object config = getGemConfig(gemId);
        if (config == null) return null;
        try {
            Object factory = singleton(itemFactoryClass);
            Method build = itemFactoryClass.getMethod("build", gemConfigClass, int.class);
            return (ItemStack) build.invoke(factory, config, Math.max(1, amount));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 获取 SnowyGems 已加载的全部宝石 ID（去重、保序）。 */
    public static Set<String> getGemIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (!available) return ids;
        try {
            Object registry = singleton(gemRegistryClass);
            Method all = gemRegistryClass.getMethod("all");
            Collection<?> gems = (Collection<?>) all.invoke(registry);
            if (gems == null) return ids;
            Method getId = gemConfigClass.getMethod("getId");
            for (Object gem : gems) {
                Object id = getId.invoke(gem);
                if (id != null) ids.add(String.valueOf(id));
            }
        } catch (Throwable ignored) {}
        return ids;
    }

    /**
     * 从宝石池中随机选取一个宝石 ID。
     *
     * @param pool 候选池；为 null 或空时使用全部已加载宝石
     * @return 随机宝石 ID；无可用宝石时返回 null
     */
    public static String randomGemId(Random rng, Collection<String> pool) {
        Set<String> all = getGemIds();
        if (all.isEmpty()) return null;
        List<String> candidates = new ArrayList<>();
        if (pool != null && !pool.isEmpty()) {
            for (String id : pool) {
                if (id != null && all.contains(id)) candidates.add(id);
            }
            if (candidates.isEmpty()) candidates.addAll(all); // 池无效则回退全量
        } else {
            candidates.addAll(all);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    /** 通过 SnowyGems 的 GemManager 直接给玩家背包发放真实宝石。 */
    public static boolean give(Player player, String gemId, int amount) {
        if (!available || player == null || gemId == null) return false;
        try {
            Object manager = singleton(gemManagerClass);
            Method give = gemManagerClass.getMethod("give", Player.class, String.class, int.class);
            return Boolean.TRUE.equals(give.invoke(manager, player, gemId, Math.max(1, amount)));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 判断一个物品是否是真实的 SnowyGems 宝石（读取其宝石 ID 标记）。 */
    public static boolean isGem(ItemStack item) {
        if (!available || item == null || !item.hasItemMeta()) return false;
        try {
            Object factory = singleton(itemFactoryClass);
            Method getGemId = itemFactoryClass.getMethod("getGemId", ItemStack.class);
            Object id = getGemId.invoke(factory, item);
            return id != null && !String.valueOf(id).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
