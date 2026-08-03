package com.clawx.elitemobs.gem;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.gem.GemNbt;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 宝石物品构建器。产出物品的显示名（Display 边框）、lore（Tips）、
 * 头颅纹理（Texture）、光效（Glow）与 SnowyGems 完全一致。
 */
public final class GemItemFactory {
    private final EliteMobsPlugin plugin;
    private final GemNbt nbt;

    public GemItemFactory(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        this.nbt = new GemNbt(plugin);
    }

    /**
     * 构建宝石物品。
     *
     * @param gem    宝石配置
     * @param amount 数量
     * @return 宝石 ItemStack（失败时返回 null）
     */
    public ItemStack build(GemConfig gem, int amount) {
        ItemStack stack;
        if (gem.texture != null && !gem.texture.isEmpty()) {
            stack = new ItemStack(Material.PLAYER_HEAD);
            applySkullTexture(stack, gem.texture);
        } else if (gem.material != null && !gem.material.isEmpty()) {
            try {
                stack = new ItemStack(Material.valueOf(gem.material.toUpperCase()), amount);
            } catch (IllegalArgumentException e) {
                stack = new ItemStack(Material.PAPER, amount);
            }
        } else {
            stack = new ItemStack(Material.PAPER, amount);
        }
        stack.setAmount(Math.max(1, amount));

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        meta.setDisplayName(translate(gem.getDisplayName()));
        List<String> lore = new ArrayList<>();
        for (String t : gem.tips) lore.add(translate(t));
        meta.setLore(lore);

        // 光效
        if (gem.glow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        // 染色（皮革/药水）
        if (gem.color != null && !gem.color.isEmpty() && meta instanceof LeatherArmorMeta lam) {
            try {
                lam.setColor(Color.fromRGB(Integer.parseInt(gem.color.replace("#", ""), 16)));
            } catch (NumberFormatException ignored) {}
        }
        if (gem.color != null && !gem.color.isEmpty() && meta instanceof PotionMeta) {
            try {
                ((PotionMeta) meta).setColor(Color.fromRGB(Integer.parseInt(gem.color.replace("#", ""), 16)));
            } catch (NumberFormatException ignored) {}
        }
        stack.setItemMeta(meta);

        // 写宝石 NBT 标记
        nbt.mark(stack, gem);
        return stack;
    }

    /**
     * 构建战利品袋：开袋随机获得宝石（放在背包右键使用）。
     * 用 PDC 标记袋内可用宝石池的等级段。
     *
     * @param level 掉落来源精英等级（决定袋内宝石品质倾向）
     * @param boss  是否 Boss 掉落（Boss 袋品质更高）
     */
    public ItemStack buildLootBag(int level, boolean boss) {
        ItemStack bag = new ItemStack(Material.CHEST);
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return null;
        String quality = boss ? ChatColor.GOLD + "" + ChatColor.BOLD + "稀有" : ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "精英";
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&6&l⚔ 精英战利品袋 (" + quality + "&6&l)"));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "右键打开，随机获得一颗宝石");
        lore.add(ChatColor.GRAY + "来源等级: " + ChatColor.WHITE + "Lv." + level + (boss ? " (Boss)" : ""));
        lore.add("");
        lore.add(ChatColor.YELLOW + "右键点击打开");
        meta.setLore(lore);
        // 用 PDC 记录袋内品质倾向
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, "lootbag_level"),
                org.bukkit.persistence.PersistentDataType.INTEGER, level);
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, "lootbag_boss"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) (boss ? 1 : 0));
        bag.setItemMeta(meta);
        return bag;
    }

    private void applySkullTexture(ItemStack stack, String base64) {
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta == null) return;
        try {
            PlayerProfile profile = plugin.getServer().createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            byte[] decoded = Base64.getDecoder().decode(base64);
            String json = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            // 从 JSON 中提取 texture url
            String url = extractTextureUrl(json);
            if (url != null) {
                textures.setSkin(URI.create(url).toURL());
            }
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
            stack.setItemMeta(meta);
        } catch (Exception e) {
            // 纹理解析失败时静默降级为普通头颅
        }
    }

    private String extractTextureUrl(String json) {
        // {"textures":{"SKIN":{"url":"http://..."}}}
        int idx = json.indexOf("\"url\":\"");
        if (idx < 0) return null;
        int start = idx + "\"url\":\"".length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static String translate(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
