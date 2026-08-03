package com.clawx.elitemobs.gem;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import com.clawx.elitemobs.EliteMobsPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宝石 NBT 读写（与 SnowyGems 键名完全一致，用 Paper PDC 实现，零 NMS 反射）。
 *
 * <p>键名对应关系（SnowyGems 原版 → 本实现）：</p>
 * <ul>
 *   <li>{@code SnowyGemsGemId}   → 宝石 ID</li>
 *   <li>{@code SnowyGemsGemType} → 宝石类型 (NORMAL/PLAYER_GEM/RANDOM_GEM)</li>
 *   <li>{@code SnowyGemsEmbedded→ {gemId}:{count}} 已镶嵌宝石列表</li>
 * </ul>
 *
 * <p>物品上同时写入原版风格的 NBT 键名（namespace 固定为 elitemobs），
 * 使宝石物品在外观与数据结构上与原 SnowyGems 完全一致。</p>
 */
public final class GemNbt {
    public static final String GEM_ID = "SnowyGemsGemId";
    public static final String GEM_TYPE = "SnowyGemsGemType";
    /** 装备上已镶嵌的宝石（gemId:count 分号分隔） */
    public static final String EMBEDDED = "SnowyGemsEmbedded";

    private final EliteMobsPlugin plugin;

    public GemNbt(EliteMobsPlugin plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey key(String k) {
        return new NamespacedKey(plugin, k.toLowerCase(java.util.Locale.ROOT));
    }

    /** 给物品写入宝石 ID + 类型标记。 */
    public ItemStack mark(ItemStack item, GemConfig gem) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(key(GEM_ID), PersistentDataType.STRING, gem.id);
        pdc.set(key(GEM_TYPE), PersistentDataType.STRING, gem.type.name());
        item.setItemMeta(meta);
        return item;
    }

    /** 读取宝石 ID；非宝石返回 null。 */
    public String getGemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(key(GEM_ID), PersistentDataType.STRING);
    }

    /** 读取宝石类型；非宝石返回 null。 */
    public String getGemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(key(GEM_TYPE), PersistentDataType.STRING);
    }

    /** 判断物品是否为宝石。 */
    public boolean isGem(ItemStack item) {
        return getGemId(item) != null;
    }

    /**
     * 读取装备上已镶嵌的宝石集合：{gemId -> count}。
     */
    public Map<String, Integer> getEmbedded(ItemStack item) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (item == null || !item.hasItemMeta()) return map;
        String raw = item.getItemMeta().getPersistentDataContainer()
                .get(key(EMBEDDED), PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return map;
        for (String part : raw.split(";")) {
            if (part.isEmpty()) continue;
            String[] kv = part.split(":", 2);
            if (kv.length != 2) continue;
            try {
                map.put(kv[0], Integer.parseInt(kv[1]));
            } catch (NumberFormatException ignored) {}
        }
        return map;
    }

    /** 保存已镶嵌宝石集合（覆盖写）。 */
    public void saveEmbedded(ItemStack item, Map<String, Integer> embedded) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : embedded.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        meta.getPersistentDataContainer().set(key(EMBEDDED), PersistentDataType.STRING, sb.toString());
        item.setItemMeta(meta);
    }
}
