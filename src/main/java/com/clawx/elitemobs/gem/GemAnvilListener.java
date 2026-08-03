package com.clawx.elitemobs.gem;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.clawx.elitemobs.EliteMobsPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 铁砧淬炼监听器：玩家把「装备 + 宝石」放入铁砧，结果槽显示淬炼预览
 * （含成功率），点击结果槽即执行淬炼。
 *
 * <p>逻辑参考 SnowyGems 的清晰方式：每个宝石用独立的 Attribute/Enchant
 * Rewards 应用（UUID modifier），而非原版那套 PDC 存储 + DataComponent
 * 重建的冗余逻辑。</p>
 */
public final class GemAnvilListener implements Listener {
    private final EliteMobsPlugin plugin;
    private final GemManager gemManager;
    private final GemRegistry registry;
    private final GemNbt nbt;
    private final NamespacedKey HINT_KEY;

    public GemAnvilListener(EliteMobsPlugin plugin, GemManager gemManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.registry = gemManager.getRegistry();
        this.nbt = new GemNbt(plugin);
        this.HINT_KEY = new NamespacedKey(plugin, "gem_anvil_hint");
    }

    // ==================== 预览 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack equip = inv.getItem(0);   // 装备
        ItemStack gem = inv.getItem(1);      // 宝石

        GemConfig gemCfg = findGem(gem);
        if (gemCfg == null || !gemCfg.isEmbedGem()) {
            clearHint(inv);
            return;
        }
        if (equip == null || equip.getType() == Material.AIR || !gemManager.canEmbed(equip, gemCfg)) {
            clearHint(inv);
            return;
        }

        // 显示淬炼预览（含成功率），免费淬炼
        ItemStack hint = createHintPaper(gemCfg);
        event.setResult(hint);
        inv.setItem(2, hint);
    }

    // ==================== 点击结果槽 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilClick(InventoryClickEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        if (!(event.getWhoClicked() instanceof Player p)) return;
        AnvilInventory inv = (AnvilInventory) event.getInventory();

        // 点击结果槽（2）：执行淬炼
        if (event.getRawSlot() != 2) return;

        ItemStack equip = inv.getItem(0);
        ItemStack gem = inv.getItem(1);
        GemConfig gemCfg = findGem(gem);
        if (gemCfg == null || !gemCfg.isEmbedGem()) return;
        if (equip == null || equip.getType() == Material.AIR || !gemManager.canEmbed(equip, gemCfg)) return;

        // 取消默认取走行为，改为执行淬炼
        event.setCancelled(true);
        inv.setItem(2, null);

        gemManager.embed(p, equip, gem, gemCfg);
        p.updateInventory();
    }

    // ==================== 关闭清理 ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvilClose(InventoryCloseEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        clearHint(event.getInventory());
    }

    // ==================== 工具 ====================

    /** 从物品槽读取宝石配置；非宝石返回 null。 */
    private GemConfig findGem(ItemStack gem) {
        if (gem == null || gem.getType() == Material.AIR) return null;
        String id = nbt.getGemId(gem);
        return id == null ? null : registry.get(id);
    }

    private ItemStack createHintPaper(GemConfig gem) {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta == null) return paper;
        meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "✦ 淬炼预览");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "宝石: " + ChatColor.WHITE + gem.getDisplayName());
        lore.add(ChatColor.GRAY + "成功率: " + ChatColor.GOLD + gem.success + "%");
        lore.add("");
        lore.add(ChatColor.YELLOW + "点击结果取出即可淬炼");
        lore.add(ChatColor.DARK_GRAY + "失败时宝石消失，装备可能降级");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(HINT_KEY, PersistentDataType.BYTE, (byte) 1);
        paper.setItemMeta(meta);
        return paper;
    }

    private void clearHint(org.bukkit.inventory.Inventory inv) {
        ItemStack r = inv.getItem(2);
        if (r == null || r.getType() == Material.AIR || !r.hasItemMeta()) return;
        if (r.getItemMeta().getPersistentDataContainer().has(HINT_KEY, PersistentDataType.BYTE)) {
            inv.setItem(2, null);
        }
    }
}
