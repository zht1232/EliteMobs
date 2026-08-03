package com.clawx.elitemobs.gem;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.clawx.elitemobs.EliteMobsPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 宝石使用监听器：
 * <ul>
 *   <li>右键战利品袋 → 随机开出一颗宝石</li>
 *   <li>右键 PlayerGem（如点券兑换券）→ 直接生效</li>
 * </ul>
 */
public final class GemUseListener implements Listener {
    private final EliteMobsPlugin plugin;
    private final GemManager gemManager;
    private final GemRegistry registry;
    private final Random rng = new Random();

    public GemUseListener(EliteMobsPlugin plugin, GemManager gemManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.registry = gemManager.getRegistry();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        Player p = event.getPlayer();

        // 1. 战利品袋
        if (isLootBag(item)) {
            event.setCancelled(true);
            openLootBag(p, item);
            return;
        }

        // 2. PlayerGem（直接使用）
        GemConfig gem = findGem(item);
        if (gem != null && (gem.type == GemType.PLAYER_GEM || gem.type == GemType.RANDOM_GEM)) {
            event.setCancelled(true);
            usePlayerGem(p, item, gem);
        }
    }

    // ==================== 战利品袋 ====================

    private boolean isLootBag(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(
                new NamespacedKey(plugin, "lootbag_level"), PersistentDataType.INTEGER);
    }

    private void openLootBag(Player p, ItemStack bag) {
        int level = bag.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(plugin, "lootbag_level"), PersistentDataType.INTEGER);
        boolean boss = bag.getItemMeta().getPersistentDataContainer().getOrDefault(
                new NamespacedKey(plugin, "lootbag_boss"), PersistentDataType.BYTE, (byte) 0) == 1;

        // 从宝石池随机选一颗
        List<GemConfig> pool = new ArrayList<>(registry.all());
        if (pool.isEmpty()) {
            p.sendMessage(ChatColor.RED + "宝石库为空，请联系管理员配置 gems/*.yml");
            return;
        }
        GemConfig gem = pickGem(level, pool);
        ItemStack gemItem = gemManager.getFactory().build(gem, 1);
        if (gemItem == null) return;

        // 消耗袋子
        if (bag.getAmount() > 1) bag.setAmount(bag.getAmount() - 1);
        else p.getInventory().setItemInMainHand(null);

        // 给玩家
        p.getInventory().addItem(gemItem).values().forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
        p.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.WHITE + "战利品袋开出了 " + gem.getDisplayName() + ChatColor.WHITE + "！");
        // 开袋小特效
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        p.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT, p.getLocation().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.3);
    }

    private GemConfig pickGem(int level, List<GemConfig> pool) {
        List<GemConfig> advanced = new ArrayList<>();
        for (GemConfig g : pool) {
            if (g.id.contains("真") || g.id.contains("神")) advanced.add(g);
        }
        double r = rng.nextDouble();
        if (level >= 15 && !advanced.isEmpty() && r < 0.4) return advanced.get(rng.nextInt(advanced.size()));
        if (level >= 10 && !advanced.isEmpty() && r < 0.3) return advanced.get(rng.nextInt(advanced.size()));
        return pool.get(rng.nextInt(pool.size()));
    }

    // ==================== PlayerGem ====================

    private void usePlayerGem(Player p, ItemStack gem, GemConfig gemCfg) {
        // 随机宝石：按 RandomPool 权重抽取一条奖励
        String rewardLine = null;
        if (gemCfg.type == GemType.RANDOM_GEM && !gemCfg.randomPool.isEmpty()) {
            int total = 0;
            for (int w : gemCfg.randomPool.values()) total += w;
            int roll = rng.nextInt(total);
            int acc = 0;
            for (Map.Entry<String, Integer> e : gemCfg.randomPool.entrySet()) {
                acc += e.getValue();
                if (roll < acc) { rewardLine = e.getKey(); break; }
            }
        } else if (!gemCfg.rewards.isEmpty()) {
            rewardLine = gemCfg.rewards.get(0);
        }
        if (rewardLine == null) {
            p.sendMessage(ChatColor.RED + "该宝石没有可用的奖励");
            return;
        }

        // 消耗宝石
        if (gem.getAmount() > 1) gem.setAmount(gem.getAmount() - 1);
        else p.getInventory().setItemInMainHand(null);

        // 执行奖励（针对玩家：点券/金币等）
        applyPlayerReward(p, rewardLine, gemCfg);
        if (gemCfg.successTip != null && !"none".equals(gemCfg.successTip)) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', gemCfg.successTip));
        }
    }

    /** 应用玩家类奖励（Point/Money/MaxHealth/ExpLevel）。 */
    private void applyPlayerReward(Player p, String raw, GemConfig gemCfg) {
        String name = raw;
        java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
        int brace = raw.indexOf('{');
        if (brace >= 0) {
            name = raw.substring(0, brace).trim();
            int end = raw.lastIndexOf('}');
            if (end > brace) {
                for (String kv : raw.substring(brace + 1, end).split(";")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) args.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }
        switch (name) {
            case "Point" -> {
                int amount = parseInt(args.get("amount"), 0);
                if (com.clawx.elitemobs.EconomyHook.addPoints(p, amount)) {
                    p.sendMessage(ChatColor.AQUA + "+" + amount + " 点券");
                }
            }
            case "Money" -> {
                double amount = parseDouble(args.get("amount"), 0);
                if (com.clawx.elitemobs.EconomyHook.depositMoney(p, amount)) {
                    p.sendMessage(ChatColor.GREEN + "+$" + String.format("%.2f", amount));
                }
            }
            case "MaxHealth" -> {
                int amount = parseInt(args.get("amount"), 0);
                double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue();
                p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(max + amount);
                p.setHealth(Math.min(p.getMaxHealth(), p.getHealth()));
                p.sendMessage(ChatColor.RED + "+" + amount + " 最大生命");
            }
            case "ExpLevel" -> {
                int amount = parseInt(args.get("amount"), 0);
                p.giveExpLevels(amount);
                p.sendMessage(ChatColor.GREEN + "+" + amount + " 经验等级");
            }
            default -> p.sendMessage(ChatColor.RED + "该宝石类型不支持此奖励: " + name);
        }
    }

    private GemConfig findGem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        String id = new GemNbt(plugin).getGemId(item);
        return id == null ? null : registry.get(id);
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
}
