package com.clawx.elitemobs.gem;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;

import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 宝石核心管理器：铁砧淬炼（镶嵌）、Require 匹配、成功/失败判定、庆祝特效。
 *
 * <p>玩法：玩家把「装备」与「宝石」放入铁砧，点击确认即可淬炼。
 * 成功 → 应用宝石 Rewards + 烟花庆祝；失败 → 宝石消耗，装备有概率降级
 * （携带淬炼保护符可避免降级/销毁）。</p>
 */
public final class GemManager {
    private final EliteMobsPlugin plugin;
    private final GemRegistry registry;
    private final GemItemFactory factory;
    private final GemRewards rewards;
    private final GemNbt nbt;
    private final Random rng = new Random();

    public GemManager(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        this.registry = new GemRegistry(plugin);
        this.factory = new GemItemFactory(plugin);
        this.rewards = new GemRewards(plugin);
        this.nbt = new GemNbt(plugin);
    }

    public GemRegistry getRegistry() { return registry; }
    public GemItemFactory getFactory() { return factory; }
    public GemRewards getRewards() { return rewards; }

    // ==================== Require 匹配 ====================

    /**
     * 判断宝石是否能镶嵌到该物品上（Require 语义：命中任意一条即可）。
     */
    public boolean canEmbed(ItemStack target, GemConfig gem) {
        if (target == null || target.getType() == Material.AIR) return false;
        if (gem.require == null || gem.require.isEmpty()) return true;
        String type = target.getType().name();
        for (String req : gem.require) {
            String r = req.trim().toUpperCase(Locale.ROOT);
            if (r.isEmpty()) continue;
            if (r.equals("NOTHING")) return true;
            if (matchRequire(type, target, r)) return true;
        }
        return false;
    }

    private boolean matchRequire(String type, ItemStack item, String req) {
        // 精确材质
        if (type.equals(req)) return true;
        // 前缀/后缀通配：_SWORD 结尾 / COPPER_ 开头
        if (req.endsWith("_*") && type.startsWith(req.substring(0, req.length() - 1))) return true;
        if (req.startsWith("*") && type.endsWith(req.substring(1))) return true;
        // 类别
        return switch (req) {
            case "WEAPON" -> isWeapon(type);
            case "SWORD" -> type.endsWith("_SWORD") || type.equals("SWORD");
            case "AXE" -> type.endsWith("_AXE");
            case "PICKAXE" -> type.endsWith("_PICKAXE");
            case "SHOVEL" -> type.endsWith("_SHOVEL");
            case "HOE" -> type.endsWith("_HOE");
            case "TOOL" -> isTool(type);
            case "ARMOR" -> isArmor(type);
            case "HELMET" -> type.endsWith("_HELMET");
            case "CHESTPLATE" -> type.endsWith("_CHESTPLATE");
            case "LEGGINGS" -> type.endsWith("_LEGGINGS");
            case "BOOTS" -> type.endsWith("_BOOTS");
            case "SHIELD" -> type.equals("SHIELD");
            case "BOW", "RANGED" -> type.equals("BOW") || type.equals("CROSSBOW") || type.equals("TRIDENT");
            case "FISHING_ROD" -> type.equals("FISHING_ROD");
            case "FLINT_AND_STEEL" -> type.equals("FLINT_AND_STEEL");
            default -> false;
        };
    }

    private boolean isWeapon(String t) {
        return t.endsWith("_SWORD") || t.endsWith("_AXE") || t.equals("TRIDENT")
                || t.equals("BOW") || t.equals("CROSSBOW") || t.equals("MACE")
                || t.endsWith("_PICKAXE");
    }

    private boolean isTool(String t) {
        return t.endsWith("_PICKAXE") || t.endsWith("_SHOVEL") || t.endsWith("_HOE")
                || t.endsWith("_AXE") || t.equals("FISHING_ROD") || t.equals("SHEARS")
                || t.equals("FLINT_AND_STEEL");
    }

    private boolean isArmor(String t) {
        return t.endsWith("_HELMET") || t.endsWith("_CHESTPLATE")
                || t.endsWith("_LEGGINGS") || t.endsWith("_BOOTS")
                || t.endsWith("_HELMET") || t.equals("ELYTRA");
    }

    // ==================== 铁砧淬炼 ====================

    /**
     * 执行一次淬炼。
     *
     * @param player 玩家
     * @param target 目标装备（会被修改）
     * @param gem    宝石
     * @return 是否成功
     */
    public boolean embed(Player player, ItemStack target, ItemStack gem, GemConfig gemCfg) {
        if (gemCfg == null || !gemCfg.isEmbedGem()) return false;
        if (!canEmbed(target, gemCfg)) {
            player.sendMessage(ChatColor.RED + "✘ " + ChatColor.WHITE + "该宝石无法镶嵌到此物品上！");
            return false;
        }

        // 成功率判定
        int success = gemCfg.success;
        boolean ok = rng.nextInt(100) < success;

        // 消耗一颗宝石
        if (gem.getAmount() > 1) gem.setAmount(gem.getAmount() - 1);
        else gem.setAmount(0);

        if (ok) {
            GemRewards.Result r = rewards.apply(target, gemCfg);
            if (!r.ok) {
                // 奖励应用失败（如已达上限），退还宝石
                gem.setAmount(gem.getAmount() + 1);
                player.sendMessage(ChatColor.RED + "✘ " + ChatColor.WHITE + r.message);
                return false;
            }
            playCelebration(player, true, target);
            String tip = gemCfg.successTip != null ? gemCfg.successTip
                    : ChatColor.GREEN + "✓ " + ChatColor.WHITE + "淬炼成功！" + gemCfg.getDisplayName() + ChatColor.WHITE + " 已镶嵌";
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', tip));
            // 护甲淬炼：写入套装等级键（armor_lv），使套装加成生效
            if (isArmor(target.getType().name())) {
                writeArmorLevel(target, gemCfg);
            }
            return true;
        } else {
            playCelebration(player, false, target);
            // 失败：护甲有概率降级，携带保护符可避免
            if (!hasProtectionCharm(player)) {
                downgrade(target);
            }
            String tip = gemCfg.failTip != null ? gemCfg.failTip
                    : ChatColor.RED + "✘ " + ChatColor.WHITE + "淬炼失败！宝石消失了";
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', tip));
            return false;
        }
    }

    private void writeArmorLevel(ItemStack armor, GemConfig gem) {
        // 淬炼成功一次护甲，armor_lv 累加（等级影响套装加成）
        var meta = armor.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        int cur = pdc.getOrDefault(EliteMobManager.ARMOR_LV_KEY,
                org.bukkit.persistence.PersistentDataType.INTEGER, 0);
        pdc.set(EliteMobManager.ARMOR_LV_KEY,
                org.bukkit.persistence.PersistentDataType.INTEGER, cur + 1);
        armor.setItemMeta(meta);
    }

    private boolean hasProtectionCharm(Player player) {
        // 淬炼保护符：放在背包中失败时免降级，一次性消耗
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (item.hasItemMeta() && item.getItemMeta().getDisplayName() != null
                    && item.getItemMeta().getDisplayName().contains("淬炼保护符")) {
                item.setAmount(item.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    /** 失败降级：护甲/武器淬炼等级 -1（若有），否则扣除 1 点耐久。 */
    private void downgrade(ItemStack target) {
        // 先尝试降低 armor_lv
        var meta = target.getItemMeta();
        if (meta != null) {
            var pdc = meta.getPersistentDataContainer();
            int cur = pdc.getOrDefault(EliteMobManager.ARMOR_LV_KEY,
                    org.bukkit.persistence.PersistentDataType.INTEGER, 0);
            if (cur > 0) {
                pdc.set(EliteMobManager.ARMOR_LV_KEY,
                        org.bukkit.persistence.PersistentDataType.INTEGER, cur - 1);
                target.setItemMeta(meta);
                return;
            }
        }
        // 无等级则掉耐久
        short max = target.getType().getMaxDurability();
        if (max > 0) {
            target.setDurability((short) Math.min(max - 1, target.getDurability() + 10));
        }
    }

    // ==================== 庆祝特效 ====================

    /**
     * 淬炼完成特效：成功 = 大型烟花 + 全息粒子 + 庆祝音效；
     * 失败 = 淡色烟雾 + 失败音效。
     */
    public void playCelebration(Player player, boolean success, ItemStack target) {
        Location loc = player.getLocation().add(0, 1.2, 0);
        if (success) {
            // 烟花
            for (int i = 0; i < 3; i++) {
                Firework fw = player.getWorld().spawn(loc.clone().add(
                        (rng.nextDouble() - 0.5) * 2, rng.nextDouble(), (rng.nextDouble() - 0.5) * 2),
                        Firework.class);
                FireworkMeta fm = fw.getFireworkMeta();
                Color c = switch (rng.nextInt(5)) {
                    case 0 -> Color.fromRGB(255, 60, 60);
                    case 1 -> Color.fromRGB(255, 200, 60);
                    case 2 -> Color.fromRGB(80, 200, 255);
                    case 3 -> Color.fromRGB(120, 255, 120);
                    default -> Color.fromRGB(255, 80, 255);
                };
                fm.addEffect(FireworkEffect.builder().withColor(c)
                        .with(FireworkEffect.Type.BURST).trail(true).build());
                fm.setPower(1);
                fw.setFireworkMeta(fm);
                fw.setShotAtAngle(true);
                fw.detonate();
            }
            // 粒子 + 音效
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 40, 0.4, 0.6, 0.4, 0.1);
            player.getWorld().spawnParticle(Particle.ENCHANT, loc, 60, 0.5, 0.5, 0.5, 0.5);
            player.getWorld().spawnParticle(Particle.FIREWORK, loc, 50, 0.4, 0.6, 0.4, 0.2);
            player.getWorld().playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            player.getWorld().playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } else {
            player.getWorld().spawnParticle(Particle.SMOKE, loc, 30, 0.3, 0.3, 0.3, 0.02);
            player.getWorld().spawnParticle(Particle.LAVA, loc, 10, 0.3, 0.3, 0.3, 0.05);
            player.getWorld().playSound(loc, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.8f);
        }
    }
}
