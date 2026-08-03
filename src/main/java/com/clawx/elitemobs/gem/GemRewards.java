package com.clawx.elitemobs.gem;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.gem.GemNbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 奖励函数解析与执行（语法与 SnowyGems 完全一致）。
 *
 * <p>支持：Attribute / Enchant / LoreAdd / LoreVar / Name / Unbreakable /
 * Durability / ItemFlag / MaxHealth / ExpLevel / Point / Money / Empty /
 * Conditional。标记 {@code $onRemove} 表示仅拆卸时执行。</p>
 */
public final class GemRewards {

    private static final String PREFIX = "elitemobs";

    /** 执行结果：成功 / 失败 */
    public static final class Result {
        public final boolean ok;
        public final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
        public static Result ok() { return new Result(true, null); }
        public static Result ok(String msg) { return new Result(true, msg); }
        public static Result fail(String msg) { return new Result(false, msg); }
    }

    private final EliteMobsPlugin plugin;
    private final GemNbt nbt;

    public GemRewards(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        this.nbt = new GemNbt(plugin);
    }

    /**
     * 在装备上应用宝石的全部奖励（镶嵌成功时调用）。
     *
     * @param target 目标装备
     * @param gem    宝石
     * @return 执行结果
     */
    public Result apply(ItemStack target, GemConfig gem) {
        boolean anyApplied = false;
        for (String line : gem.rewards) {
            String raw = line.trim();
            if (raw.isEmpty()) continue;
            // $onRemove 仅拆卸时执行，镶嵌阶段跳过
            if (raw.contains("$onRemove")) continue;
            if (raw.startsWith("$")) continue;
            Result r = applySingle(target, raw, gem);
            if (r.ok) anyApplied = true;
        }
        // 记录镶嵌信息
        if (anyApplied) {
            Map<String, Integer> embedded = nbt.getEmbedded(target);
            embedded.merge(gem.id, 1, Integer::sum);
            nbt.saveEmbedded(target, embedded);
        }
        return anyApplied ? Result.ok() : Result.fail("该宝石无法应用到当前装备");
    }

    /** 应用单条奖励函数行。 */
    private Result applySingle(ItemStack target, String raw, GemConfig gem) {
        String name = raw;
        Map<String, String> args = new LinkedHashMap<>();
        int brace = raw.indexOf('{');
        if (brace >= 0) {
            name = raw.substring(0, brace).trim();
            int end = raw.lastIndexOf('}');
            if (end > brace) {
                String body = raw.substring(brace + 1, end);
                for (String kv : body.split(";")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        args.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                    }
                }
            }
        }

        switch (name) {
            case "Attribute":
                return applyAttribute(target, args);
            case "Enchant":
                return applyEnchant(target, args);
            case "LoreAdd":
                return applyLoreAdd(target, args, false);
            case "LoreVar":
                return applyLoreAdd(target, args, true);
            case "Name":
                return applyName(target, args);
            case "Unbreakable":
                return applyUnbreakable(target);
            case "Durability":
                return applyDurability(target, args);
            case "ItemFlag":
                return Result.ok(); // 隐藏标记，Paper 上不产生可见副作用
            case "Empty":
                return Result.ok();
            case "MaxHealth":
            case "ExpLevel":
            case "Point":
            case "Money":
            case "Conditional":
                return Result.fail("仅 PlayerGem 支持: " + name);
            default:
                return Result.fail("未知奖励函数: " + name);
        }
    }

    // ---- Attribute{name=;operation=;slot=;var=;limit=} ----

    private Result applyAttribute(ItemStack target, Map<String, String> args) {
        String attrName = args.get("name");
        if (attrName == null) return Result.fail("Attribute 缺少 name");
        Attribute attr = resolveAttribute(attrName);
        if (attr == null) return Result.fail("未知属性: " + attrName);

        int operation = parseInt(args.get("operation"), 0);
        double delta = parseDelta(args.get("var"), 1.0);
        String slotStr = args.getOrDefault("slot", "auto");
        double limit = parseDouble(args.get("limit"), Double.MAX_VALUE);

        // 计算当前值：从已存在 modifier 中累加同 name 的变体（简化：仅读 limit 判断）
        double current = getCurrentAttrDelta(target, attr, gemName(args));
        double next = current + delta;
        if (limit != Double.MAX_VALUE && ((delta > 0 && next > limit) || (delta < 0 && next < limit))) {
            return Result.fail("已达上限");
        }

        EquipmentSlotGroup group = resolveGroup(slotStr, target);
        // Paper 1.20.5+ 使用 NamespacedKey 构造（唯一键保证可叠加/可移除）
        NamespacedKey modKey = new NamespacedKey(PREFIX, "gem_" + gemName(args) + "_" + (long) (Math.random() * 1_000_000));
        AttributeModifier mod = new AttributeModifier(
                modKey,
                delta,
                operation == 0 ? AttributeModifier.Operation.ADD_NUMBER
                        : operation == 1 ? AttributeModifier.Operation.ADD_SCALAR
                        : AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                group);
        ItemMeta meta = target.getItemMeta();
        if (meta == null) return Result.fail("物品无 meta");
        meta.addAttributeModifier(attr, mod);
        target.setItemMeta(meta);
        return Result.ok();
    }

    private double getCurrentAttrDelta(ItemStack item, Attribute attr, String prefix) {
        if (!item.hasItemMeta()) return 0;
        double sum = 0;
        for (AttributeModifier m : item.getItemMeta().getAttributeModifiers(attr)) {
            String name = m.getKey().getKey();
            if (name != null && name.startsWith("gem_")) sum += m.getAmount();
        }
        return sum;
    }

    // ---- Enchant{name=;limit=} ----

    private Result applyEnchant(ItemStack target, Map<String, String> args) {
        String enchName = args.get("name");
        if (enchName == null) return Result.fail("Enchant 缺少 name");
        Enchantment ench = resolveEnchant(enchName);
        if (ench == null) return Result.fail("未知附魔: " + enchName);
        try {
            if (!ench.canEnchantItem(target) && !enchName.equalsIgnoreCase("UNBREAKING")) {
                // 兼容不可附魔物品：允许但通过 unsafe
            }
        } catch (Exception ignored) {}
        int limit = parseInt(args.get("limit"), 10);
        int current = target.getEnchantmentLevel(ench);
        if (current >= limit) return Result.fail("附魔已达上限");
        int level = Math.min(limit, current + 1);
        target.addUnsafeEnchantment(ench, level);
        return Result.ok();
    }

    // ---- LoreAdd{lore=;mode=append|prepend;locator=;limit=} / LoreVar ----

    private Result applyLoreAdd(ItemStack target, Map<String, String> args, boolean isVar) {
        String lore = args.get("lore");
        if (lore == null) return Result.fail("LoreAdd 缺少 lore");
        lore = ChatColor.translateAlternateColorCodes('&', lore);
        ItemMeta meta = target.getItemMeta();
        if (meta == null) return Result.fail("物品无 meta");
        List<String> loreList = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        if (isVar) {
            String locator = args.getOrDefault("locator", lore);
            int limit = parseInt(args.get("limit"), Integer.MAX_VALUE);
            // 已有同 locator 的行则自增，否则追加
            int existing = -1;
            for (int i = 0; i < loreList.size(); i++) {
                if (loreList.get(i).contains(locator)) { existing = i; break; }
            }
            if (existing >= 0) {
                int cur = extractNumber(loreList.get(existing));
                if (cur >= limit) return Result.fail("已达上限");
                String var = args.get("var");
                int delta = var != null ? (int) Math.round(parseDelta(var, 1.0)) : 1;
                String newLine = lore.replace("{value}", String.valueOf(cur + delta));
                loreList.set(existing, newLine);
            } else {
                String var = args.get("var");
                int delta = var != null ? (int) Math.round(parseDelta(var, 1.0)) : 1;
                loreList.add(lore.replace("{value}", String.valueOf(delta)));
            }
        } else {
            String mode = args.getOrDefault("mode", "append");
            if ("prepend".equalsIgnoreCase(mode)) loreList.add(0, lore);
            else loreList.add(lore);
        }
        meta.setLore(loreList);
        target.setItemMeta(meta);
        return Result.ok();
    }

    // ---- Name{name=} ----

    private Result applyName(ItemStack target, Map<String, String> args) {
        String name = args.get("name");
        if (name == null) return Result.fail("Name 缺少 name");
        ItemMeta meta = target.getItemMeta();
        if (meta == null) return Result.fail("物品无 meta");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        target.setItemMeta(meta);
        return Result.ok();
    }

    private Result applyUnbreakable(ItemStack target) {
        ItemMeta meta = target.getItemMeta();
        if (meta == null) return Result.fail("物品无 meta");
        meta.setUnbreakable(true);
        target.setItemMeta(meta);
        return Result.ok();
    }

    private Result applyDurability(ItemStack target, Map<String, String> args) {
        int amount = parseInt(args.get("amount"), 0);
        short max = target.getType().getMaxDurability();
        if (max <= 0) return Result.fail("该物品无耐久");
        short dmg = target.getDurability();
        short repaired = (short) Math.max(0, dmg - amount);
        target.setDurability(repaired);
        return Result.ok();
    }

    // ==================== 工具方法 ====================

    private String gemName(Map<String, String> args) {
        String n = args.get("name");
        return n == null ? "gem" : n.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return (int) Math.round(Double.parseDouble(s)); }
        catch (NumberFormatException e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return def; }
    }

    /** 解析 var=v+1 / v-0.1 / v*1.2 形式的增量表达式。 */
    private static double parseDelta(String var, double def) {
        if (var == null) return def;
        try {
            String v = var.replace("v", "").trim();
            if (v.isEmpty()) return def;
            if (v.startsWith("+")) return Double.parseDouble(v.substring(1));
            if (v.startsWith("-")) return -Double.parseDouble(v.substring(1));
            if (v.startsWith("*")) return Double.parseDouble(v.substring(1));
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int extractNumber(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Attribute resolveAttribute(String name) {
        if (name == null) return null;
        String key = name.toUpperCase(Locale.ROOT);
        return switch (key) {
            case "HEALTH", "MAX_HEALTH" -> Attribute.MAX_HEALTH;
            case "MOVE", "MOVEMENT", "SPEED", "MOVEMENT_SPEED" -> Attribute.MOVEMENT_SPEED;
            case "DAMAGE", "ATTACK", "ATTACK_DAMAGE" -> Attribute.ATTACK_DAMAGE;
            case "ARMOR" -> Attribute.ARMOR;
            case "ARMOR_TOUGHNESS" -> Attribute.ARMOR_TOUGHNESS;
            case "KNOCKBACK", "KNOCKBACK_RESISTANCE" -> Attribute.KNOCKBACK_RESISTANCE;
            case "LUCK" -> Attribute.LUCK;
            case "SCALE", "SIZE" -> Attribute.SCALE;
            case "ATTACK_SPEED" -> Attribute.ATTACK_SPEED;
            case "ATTACK_KNOCKBACK" -> Attribute.ATTACK_KNOCKBACK;
            case "FLYING_SPEED" -> Attribute.FLYING_SPEED;
            case "FALL_DAMAGE_MULTIPLIER" -> Attribute.FALL_DAMAGE_MULTIPLIER;
            case "MAX_ABSORPTION" -> Attribute.MAX_ABSORPTION;
            case "JUMP_STRENGTH" -> Attribute.JUMP_STRENGTH;
            default -> {
                // 通过 Registry 按 key 查找（Paper 现代 API，避免旧 valueOf 的注解校验）
                String attempt = key.startsWith("GENERIC_") ? key.substring("GENERIC_".length()) : key;
                try {
                    for (Attribute a : org.bukkit.Registry.ATTRIBUTE) {
                        if (a.getKey().getKey().equalsIgnoreCase(attempt)
                                || a.getKey().getKey().equalsIgnoreCase(key)) {
                            yield a;
                        }
                    }
                    yield null;
                } catch (Throwable t) { yield null; }
            }
        };
    }

    private static Enchantment resolveEnchant(String name) {
        String key = name.toUpperCase(Locale.ROOT);
        // 尝试 registry 查找（Paper 1.21+）
        try {
            for (Enchantment e : org.bukkit.Registry.ENCHANTMENT) {
                if (e.getKey().getKey().equalsIgnoreCase(name)
                        || e.getKey().getKey().replace("_", "").equalsIgnoreCase(key.replace("_", ""))
                        || e.getKey().getKey().equalsIgnoreCase(key.toLowerCase(Locale.ROOT))) {
                    return e;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static EquipmentSlotGroup resolveGroup(String slot, ItemStack item) {
        if (slot == null || slot.equals("auto")) {
            String type = item.getType().name();
            if (type.contains("HELMET")) return EquipmentSlotGroup.HEAD;
            if (type.contains("CHESTPLATE")) return EquipmentSlotGroup.CHEST;
            if (type.contains("LEGGINGS")) return EquipmentSlotGroup.LEGS;
            if (type.contains("BOOTS")) return EquipmentSlotGroup.FEET;
            return EquipmentSlotGroup.HAND;
        }
        return switch (slot.toLowerCase(Locale.ROOT)) {
            case "head" -> EquipmentSlotGroup.HEAD;
            case "chest" -> EquipmentSlotGroup.CHEST;
            case "legs" -> EquipmentSlotGroup.LEGS;
            case "feet" -> EquipmentSlotGroup.FEET;
            case "off_hand", "offhand" -> EquipmentSlotGroup.OFFHAND;
            case "armor" -> EquipmentSlotGroup.ARMOR;
            case "any" -> EquipmentSlotGroup.ANY;
            case "any_hand" -> EquipmentSlotGroup.ANY;
            default -> EquipmentSlotGroup.HAND;
        };
    }
}
