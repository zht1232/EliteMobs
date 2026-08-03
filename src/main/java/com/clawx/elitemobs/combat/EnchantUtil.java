package com.clawx.elitemobs.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry-based enchantment resolution for Paper 1.21+.
 * Replaces deprecated Enchantment.getByName() / Enchantment.getByKey().
 */
public final class EnchantUtil {
    private static final Map<String, Enchantment> CACHE = new HashMap<>();

    static {
        for (Enchantment ench : Registry.ENCHANTMENT) {
            String key = ench.getKey().getKey().toUpperCase();
            CACHE.put(key, ench);
        }
    }

    private EnchantUtil() {}

    public static Enchantment resolve(String name) {
        if (name == null) return null;
        return CACHE.get(name.toUpperCase());
    }

    public static Enchantment get(String name) {
        return resolve(name);
    }

    public static Map<String, Enchantment> getAll() {
        return Map.copyOf(CACHE);
    }

    /**
     * Parse enchant pool from config section.
     * Format: ENCHANT_NAME: weight  OR  ENCHANT_NAME: min-max
     */
    public static void parseEnchantPool(ConfigurationSection section, Map<Enchantment, Integer> pool) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Enchantment ench = resolve(key);
            if (ench == null) continue;
            String val = section.getString(key, "1-10");
            String[] parts = val.split("-");
            int weight = parts.length > 1 ? Integer.parseInt(parts[1]) : 10;
            pool.put(ench, weight);
        }
    }
}
