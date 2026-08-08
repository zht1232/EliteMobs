package com.clawx.elitemobs.essence;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;

import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.EliteMobsPlugin;

import java.util.*;

/**
 * 铁砧淬炼监听器 —— 严格按原版 EliteEssenceUpgradeListener 逻辑实现。
 *
 * <p>玩家把「装备 + 精华(宝石)」放入铁砧：</p>
 * <ul>
 *   <li>武器精华 → 武器淬炼（攻击力 = 等级² × 系数，成功/失败/降级/销毁）</li>
 *   <li>护甲精华 → 护甲淬炼（减伤 = 等级 × 系数，写入 armor_lv）</li>
 *   <li>保护符 → 失败时防降级/防销毁</li>
 * </ul>
 *
 * <p>成功率：{@code random < baseRate + (等级-1)*perLevel}（min maxRate）。
 * 失败降级：Lv1-3 降1级 / 4-6 降2级 / 7-9 降3级 / 10+ 降4级，降到0销毁。
 * 提示消息使用 messages.yml 的 essence-upgrade.* / armor-upgrade.*。</p>
 */
public class EliteEssenceUpgradeListener implements Listener {
    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();

    // 武器
    private final NamespacedKey LK;   // 等级
    private final NamespacedKey DK;   // 伤害加成
    private final NamespacedKey UK;   // 已升级标记
    private final NamespacedKey ORIG_LORE; // 原版 Lore（还原用）
    private final NamespacedKey ORIG_NAME; // 原版显示名（还原用）
    private final NamespacedKey KEY_GLOW;  // 淬炼光效标记
    // 护甲
    private final NamespacedKey ALK;  // armor_lv 等级
    private final NamespacedKey ADK;  // armor_dr 减伤
    private final NamespacedKey AUK;  // armor_upgraded
    private final NamespacedKey ABAK; // armor_base
    private final NamespacedKey ORIG_LORE_A; // 护甲原版 Lore（还原用）
    private final NamespacedKey ORIG_NAME_A; // 护甲原版显示名（还原用）
    private final NamespacedKey KEY_GLOW_A;  // 护甲淬炼光效标记

    public EliteEssenceUpgradeListener(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        this.LK = new NamespacedKey(plugin, "rl");
        this.DK = new NamespacedKey(plugin, "db");
        this.UK = new NamespacedKey(plugin, "upgraded");
        this.ORIG_LORE = new NamespacedKey(plugin, "orig_lore");
        this.ORIG_NAME = new NamespacedKey(plugin, "orig_name");
        this.KEY_GLOW = new NamespacedKey(plugin, "elite_glow");
        this.ALK = new NamespacedKey(plugin, "armor_lv");
        this.ADK = new NamespacedKey(plugin, "armor_dr");
        this.AUK = new NamespacedKey(plugin, "armor_upgraded");
        this.ABAK = new NamespacedKey(plugin, "armor_base");
        this.ORIG_LORE_A = new NamespacedKey(plugin, "orig_lore_a");
        this.ORIG_NAME_A = new NamespacedKey(plugin, "orig_name_a");
        this.KEY_GLOW_A = new NamespacedKey(plugin, "elite_glow_a");
    }

    // ==================== 铁砧预览 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack first = inv.getItem(0);   // 装备
        ItemStack second = inv.getItem(1);  // 宝石
        if (first == null || second == null) return;

        // 宝石 + 宝石：同类型同级合成高一级
        if (plugin.getEliteConfig().isGemCombineEnabled()
                && EliteGemFactory.isGem(first) && EliteGemFactory.isGem(second)) {
            String g0 = EliteGemFactory.getGemId(first);
            String g1 = EliteGemFactory.getGemId(second);
            int l0 = EliteGemFactory.getGemLevel(first);
            int l1 = EliteGemFactory.getGemLevel(second);
            if (g0 != null && g0.equalsIgnoreCase(g1) && l0 == l1 && l0 < 10) {
                ItemStack result = buildGemOfLevel(g0, l0 + 1);
                if (result != null) {
                    event.setResult(result);
                    inv.setItem(2, result);
                    inv.setRepairCost(1);
                    return;
                }
            }
            clearHint(inv);
            return;
        }

        // 武器/护甲 + 任意宝石
        if ((isWeapon(first) || isArmor(first)) && EliteGemFactory.isGem(second)) {
            event.setResult(createHintPaper());
            inv.setItem(2, createHintPaper());
            inv.setRepairCost(1);
            return;
        }
        // 已淬炼装备（有宝石）+ 拆卸器：拆卸所有宝石
        if (hasAnyGem(first) && EliteEssenceFactory.isGemRemover(second)) {
            event.setResult(createHintPaper());
            inv.setItem(2, createHintPaper());
            inv.setRepairCost(1);
            return;
        }
        // 清除残留提示纸
        clearHint(inv);
    }

    // ==================== 点击铁砧 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory inv)) return;
        if (!(event.getWhoClicked() instanceof Player p)) return;

        // 点击输入槽时刷新预览
        if (event.getRawSlot() == 0 || event.getRawSlot() == 1) {
            plugin.getServer().getScheduler().runTask(plugin, () -> updateAnvilResult(inv));
            return;
        }
        // 点击结果槽（2）：执行淬炼
        if (event.getRawSlot() != 2) return;

        ItemStack equip = inv.getItem(0);
        ItemStack gem = inv.getItem(1);
        if (equip == null || gem == null) return;

        // 宝石 + 宝石：合成高一级
        if (plugin.getEliteConfig().isGemCombineEnabled()
                && EliteGemFactory.isGem(equip) && EliteGemFactory.isGem(gem)) {
            event.setCancelled(true);
            inv.setItem(2, null);
            doCombineGems(p, inv, equip, gem);
            return;
        }

        boolean hasCharm = playerHasCharm(p);

        if ((isWeapon(equip) || isArmor(equip)) && EliteGemFactory.isGem(gem)) {
            event.setCancelled(true);
            inv.setItem(2, null);
            doGemUpgrade(p, inv, equip, gem, hasCharm);
            return;
        }
        // 已淬炼装备（有宝石）+ 拆卸器：拆卸所有宝石
        if (hasAnyGem(equip) && EliteEssenceFactory.isGemRemover(gem)) {
            event.setCancelled(true);
            inv.setItem(2, null);
            doGemRemoveAll(p, inv, equip);
        }
    }

    // ==================== 关闭清理 ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvilClose(InventoryCloseEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        clearHint(event.getInventory());
    }

    // ==================== 统一宝石淬炼 ====================

    /** 宝石合成：消耗 2 个同类型同级宝石，返还 1 个高一级。 */
    private void doCombineGems(Player p, AnvilInventory inv, ItemStack a, ItemStack b) {
        String g0 = EliteGemFactory.getGemId(a);
        String g1 = EliteGemFactory.getGemId(b);
        int l0 = EliteGemFactory.getGemLevel(a);
        int l1 = EliteGemFactory.getGemLevel(b);
        if (g0 == null || !g0.equalsIgnoreCase(g1) || l0 != l1 || l0 >= 10) {
            p.sendMessage(ChatColor.RED + "✘ 需要两个相同类型且相同等级的宝石才能合成！");
            return;
        }
        consumeOne(inv, 0);
        consumeOne(inv, 1);
        ItemStack result = buildGemOfLevel(g0, l0 + 1);
        if (result == null) return;
        p.getInventory().addItem(result).values()
            .forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
        p.sendMessage(ChatColor.GREEN + "✦ 合成成功！宝石 Lv." + (l0 + 1));
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
    }

    /** 消耗铁砧某个输入槽的 1 个物品。 */
    private void consumeOne(AnvilInventory inv, int slot) {
        ItemStack it = inv.getItem(slot);
        if (it == null) return;
        if (it.getAmount() > 1) { it.setAmount(it.getAmount() - 1); inv.setItem(slot, it); }
        else inv.setItem(slot, null);
    }

    /** 按等级构建宝石物品（用于合成/发放）。 */
    private ItemStack buildGemOfLevel(String gemId, int level) {
        for (var d : plugin.getEliteConfig().getCustomDrops()) {
            if (d.id != null && d.id.equalsIgnoreCase(gemId)) {
                return d.build(level);
            }
        }
        return null;
    }

    /**
     * 统一宝石淬炼：任意宝石（gems/*.yml）淬炼到装备。
     * - 同种宝石已存在 → 该宝石等级+1（升级）
     * - 同种宝石不存在 → 放入空宝石槽（初始等级 = 宝石自身等级）
     * - 成功率 = baseRate + (宝石等级-1)*perLevel（封顶 maxRate）
     * - 失败 → 宝石消耗 + 该宝石降级（等级越高掉越多）；保护符防降级但宝石仍消耗
     */
    private void doGemUpgrade(Player player, Inventory inv, ItemStack equip, ItemStack gem, boolean hasCharm) {
        FileConfiguration msgs = plugin.getMessages();
        var cfg = plugin.getEliteConfig();

        String gemId = EliteGemFactory.getGemId(gem);
        String effect = EliteGemFactory.getGemEffect(gem);
        int gemLevel = EliteGemFactory.getGemLevel(gem);
        if (gemId == null || effect == null) return;

        // 宝石类型与装备匹配检查
        if (!gemFitsEquip(equip, effect)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&c✘ 此宝石无法淬炼到该装备上！"));
            return;
        }

        // 当前宝石等级之和（决定槽位上限）
        int totalLevel = EliteGemFactory.totalGemLevel(equip);
        int maxGemSlots = EliteGemFactory.gemSlotsForLevel(totalLevel);

        // 查找同种宝石槽 或 空槽
        int slot = EliteGemFactory.findGemSlot(equip, gemId);
        int curGemLevel = 1;
        if (slot >= 0) {
            curGemLevel = EliteGemFactory.getInstalledGemLevels(equip)[slot];
        } else {
            int empty = EliteGemFactory.findEmptyGemSlot(equip);
            if (empty < 0) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&c✘ 宝石槽已满！"));
                return;
            }
            if (empty >= maxGemSlots) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&c✘ 宝石槽未解锁！需要提升宝石等级之和"));
                return;
            }
            slot = empty;
            curGemLevel = 0; // 新宝石
        }

        // 该宝石已满级：提示并返回（不消耗宝石）
        if (curGemLevel >= 10) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&e该宝石已达满级（Lv.10），无需再淬炼！"));
            return;
        }

        // 成功率: 基于宝石自身等级；测试宝石可用 gem_success_rate 覆盖（0=必失败 / 1=必成功）
        double rate = EliteGemFactory.getGemSuccessRate(gem);
        if (rate < 0) {
            rate = Math.min(cfg.getEssenceUpgradeBaseRate() + (gemLevel - 1) * cfg.getEssenceUpgradePerLevel(),
                    cfg.getEssenceUpgradeMaxRate());
        }
        boolean ok = rng.nextDouble() < rate;

        // 首次放入宝石时保存原版 Lore 与显示名（供还原）
        ItemMeta meta = equip.getItemMeta();
        if (meta != null) {
            boolean isWeapon = isWeapon(equip);
            boolean firstTime = isWeapon
                    ? !meta.getPersistentDataContainer().has(UK, PersistentDataType.BYTE)
                    : !meta.getPersistentDataContainer().has(AUK, PersistentDataType.BYTE);
            if (firstTime) {
                List<String> orig = meta.getLore();
                if (orig != null) {
                    meta.getPersistentDataContainer().set(isWeapon ? ORIG_LORE : ORIG_LORE_A,
                            PersistentDataType.LIST.strings(), new ArrayList<>(orig));
                }
                if (meta.hasDisplayName()) {
                    meta.getPersistentDataContainer().set(isWeapon ? ORIG_NAME : ORIG_NAME_A,
                            PersistentDataType.STRING, meta.getDisplayName());
                }
            }
            meta.getPersistentDataContainer().set(isWeapon ? UK : AUK, PersistentDataType.BYTE, (byte) 1);
        }

        if (ok) {
            // 成功：装备上该宝石等级 +1（新宝石从 Lv.1 开始；宝石自身等级只影响成功率）
            int newGemLevel = Math.min(10, curGemLevel + 1);
            EliteGemFactory.setGemSlot(equip, slot, gemId, newGemLevel);
        } else if (hasCharm) {
            // 失败但有保护符：防降级，但宝石仍消耗
            consumeCharmFromPlayer(player);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    isWeapon(equip)
                            ? msg(msgs, "essence-upgrade.protected", "&e&l✦ 保护符生效！武器未降级")
                            : msg(msgs, "armor-upgrade.protected", "&e&l✦ 保护符生效！护甲未降级")));
            playSuccess(player);
            consume(inv);
            return;
        } else if (totalLevel == 0) {
            // 首次淬炼失败：装备被摧毁（原版：Lv.0 淬炼失败销毁装备）
            consume(inv);
            playFail(player);
            inv.setItem(0, null);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    isWeapon(equip)
                            ? msg(msgs, "essence-upgrade.destroyed", "&c&l✘ 武器已摧毁！")
                            : msg(msgs, "armor-upgrade.destroyed", "&c&l✘ 护甲已摧毁！")));
            return;
        } else if (curGemLevel == 0) {
            // 已镶嵌其他宝石时放入新宝石失败：宝石消耗，无宝石可降级
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&c&l✘ 淬炼失败！宝石已消耗"));
            consume(inv);
            playFail(player);
            return;
        } else {
            // 失败降级：1-3降1 / 4-6降2 / 7-9降3 / 10+降4（降到0 = 移除该宝石）
            int drop = curGemLevel >= 10 ? 4 : curGemLevel >= 7 ? 3 : curGemLevel >= 4 ? 2 : 1;
            int newGemLevel = Math.max(0, curGemLevel - drop);
            if (newGemLevel <= 0) {
                EliteGemFactory.clearGemSlot(equip, slot);
            } else {
                EliteGemFactory.setGemSlot(equip, slot, gemId, newGemLevel);
            }
        }

        // 重新应用所有宝石效果（攻击/防御/击退/雷电）
        applyAllGemEffects(equip);

        int totalAfter = EliteGemFactory.totalGemLevel(equip);
        if (totalAfter <= 0) {
            // 所有宝石耗尽：还原为原版装备（名字/Lore/光效/加成/符文全部还原）
            revertToOriginal(equip);
            ItemStack reverted = equip;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                inv.setItem(0, reverted);
                consume(inv);
            });
            playFail(player);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &c&l✘ &4&l淬炼失败！ &c&l✘"));
            if (curGemLevel > 0) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "  &7宝石等级损失: &c-" + dropFor(curGemLevel)));
            }
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &7装备已还原为原版（宝石耗尽）"));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &8&m------------------------------------&r"));
            return;
        }

        // 刷新 Lore（核心属性 + 宝石槽 + 符文槽）
        rebuildLore(equip, msgs);

        // 设置精英物品显示名
        String baseName = getItemDisplayName(equip);
        String titleName = ChatColor.translateAlternateColorCodes('&',
                (isWeapon(equip)
                        ? msg(msgs, "essence-upgrade.display-name", "&6&l精英 &e&l{name} &7[&bLv.{lvl}&7]")
                        : msg(msgs, "armor-upgrade.display-name", "&9&l精英 &e&l{name} &7[&bLv.{lvl}&7]"))
                        .replace("{name}", baseName)
                        .replace("{lvl}", String.valueOf(totalAfter)));
        ItemMeta m2 = equip.getItemMeta();
        if (m2 != null) m2.setDisplayName(titleName);
        equip.setItemMeta(m2);

        // 淬炼成功后附魔光效（仅当装备无其他附魔时添加；用 HIDE_UNBREAKABLE 只隐藏耐久附魔，不隐藏玩家后续附魔）
        if (ok && equip.getItemMeta() != null
                && equip.getItemMeta().getEnchants().isEmpty()) {
            ItemMeta em = equip.getItemMeta();
            em.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            em.getPersistentDataContainer().set(isWeapon(equip) ? KEY_GLOW : KEY_GLOW_A,
                    PersistentDataType.BYTE, (byte) 1);
            equip.setItemMeta(em);
        }

        // 刷新铁砧 + 消耗宝石 + 特效 + 提示
        ItemStack finalEquip = equip;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            inv.setItem(0, finalEquip);
            consume(inv);
        });

        if (ok) {
            playSuccess(player);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &6&l✦ &e&l淬炼成功！ &6&l✦"));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &7你的 &f" + itemName(equip) + " &7的 &b" + gemId + " &7升至 &a&l"
                            + Math.min(10, curGemLevel + 1) + " &7级！"));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &8&m------------------------------------&r"));
        } else {
            playFail(player);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &c&l✘ &4&l淬炼失败！ &c&l✘"));
            if (curGemLevel > 0) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "  &7宝石等级损失: &c-" + dropFor(curGemLevel)));
            }
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &8&m------------------------------------&r"));
        }
    }

    /** 拆卸所有宝石与符文：宝石每颗等级 X → 返还 X 颗宝石（每颗 Lv.max(1,X-1)），符文按自身等级返还。 */
    private void doGemRemoveAll(Player player, Inventory inv, ItemStack equip) {
        String[] ids = EliteGemFactory.getInstalledGems(equip);
        int[] lvs = EliteGemFactory.getInstalledGemLevels(equip);

        // 收集所有宝石（id + 等级）
        List<String> outIds = new ArrayList<>();
        List<Integer> outLvs = new ArrayList<>();
        for (int i = 0; i < EliteGemFactory.MAX_GEM_SLOTS; i++) {
            if (ids[i] != null) {
                outIds.add(ids[i]);
                outLvs.add(lvs[i]);
            }
        }
        // 收集所有符文（类型 + 等级）
        List<String> runeTypes = new ArrayList<>();
        List<Integer> runeLvs = new ArrayList<>();
        if (equip != null && equip.hasItemMeta()) {
            var rpdc = equip.getItemMeta().getPersistentDataContainer();
            for (int i = 0; i < com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS.length; i++) {
                String type = rpdc.get(com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS[i],
                        PersistentDataType.STRING);
                if (type != null) {
                    Integer lvI = rpdc.get(com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOT_LEVELS[i],
                            PersistentDataType.INTEGER);
                    runeTypes.add(type);
                    runeLvs.add(lvI == null ? 1 : Math.max(1, Math.min(10, lvI)));
                }
            }
        }
        if (outIds.isEmpty() && runeTypes.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&c✘ 该装备没有可拆卸的宝石/符文！"));
            return;
        }

        // 还原装备（清空全部宝石/符文槽/属性加成/名字/Lore/光效）
        revertToOriginal(equip);

        // 返还宝石：X 颗，每颗等级 max(1, X-1)（流失1级，最低1级）
        int total = 0;
        for (int k = 0; k < outIds.size(); k++) {
            String gemId = outIds.get(k);
            int x = outLvs.get(k);
            int outLevel = Math.max(1, x - 1);
            var drop = findGemDrop(gemId);
            if (drop == null) continue;
            int count = x;
            while (count > 0) {
                int batch = Math.min(count, 64);
                ItemStack gem = drop.build(outLevel);
                gem.setAmount(batch);
                player.getInventory().addItem(gem).values().forEach(
                        d -> player.getWorld().dropItemNaturally(player.getLocation(), d));
                count -= batch;
                total += batch;
            }
        }
        // 返还符文：按自身等级（符文几级装几级，不流失）
        int runeTotal = 0;
        for (int k = 0; k < runeTypes.size(); k++) {
            ItemStack rune = com.clawx.elitemobs.rune.EliteRuneFactory.createRune(
                    runeTypes.get(k), runeLvs.get(k), plugin.getMessages());
            player.getInventory().addItem(rune).values().forEach(
                    d -> player.getWorld().dropItemNaturally(player.getLocation(), d));
            runeTotal++;
        }

        // 刷新铁砧：还原后的装备放回 slot0 + 消耗拆卸器
        ItemStack finalEquip = equip;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            inv.setItem(0, finalEquip);
            consume(inv);
        });

        playSuccess(player);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "  &d&l✦ &f宝石拆卸成功！ &d&l✦"));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "  &7已拆卸并返还 &e" + total + " &7颗宝石（等级流失）"
                        + (runeTotal > 0 ? "，&d" + runeTotal + " &7颗符文" : "")));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "  &7装备已还原为原版（属性/名字/Lore/光效已清除）"));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "  &8&m------------------------------------&r"));
    }

    /** 装备上是否已镶嵌任意宝石。 */
    private boolean hasAnyGem(ItemStack equip) {
        String[] ids = EliteGemFactory.getInstalledGems(equip);
        for (String id : ids) if (id != null) return true;
        return false;
    }

    /** 按宝石 id 查找宝石定义（CustomDrop）。 */
    private com.clawx.elitemobs.EliteConfig.CustomDrop findGemDrop(String gemId) {
        for (var d : plugin.getEliteConfig().getCustomDrops()) {
            if (d.id != null && d.id.equalsIgnoreCase(gemId)) return d;
        }
        return null;
    }

    /** 宝石是否匹配装备类型（attack/knockback/thunder/rare/doublejump→武器；defense→护甲；magnet→两者均可）。 */
    private boolean gemFitsEquip(ItemStack equip, String effect) {
        boolean weapon = isWeapon(equip);
        return switch (effect == null ? "" : effect.toLowerCase()) {
            case "attack", "knockback", "thunder", "rare", "doublejump", "lifesteal", "fire_aspect" -> weapon;
            case "defense" -> isArmor(equip);
            case "magnet", "unbreaking" -> true;   // 磁力/耐久宝石：武器/护甲均可
            default -> true;
        };
    }

    /** 读取武器当前实际攻击伤害：Minecraft 实际伤害 = 玩家基础 1 + 所有 ATTACK_DAMAGE ADD_NUMBER modifier 之和（含淬炼加成），与游戏工具提示/实际伤害一致。 */
    /** 读取武器当前实际攻击伤害：游戏工具条显示的总攻击 = 基础 1 + 修饰符之和（与真实伤害一致）。
     *  优先读物品 DataComponent 中的真实修饰符（排除淬炼自带的 elite_damage），
     *  读不到（如纯原版默认组件）时回退到原版伤害表；再叠加已装攻击宝石加成。
     *  兼容非标准/自定义武器（如矛类，其修饰符在 DataComponent 中可读）。 */
    private double getActualWeaponDamage(ItemStack item) {
        if (item == null) return 0.0;
        double dmg = 0.0;
        boolean found = false;
        NamespacedKey eliteKey = new NamespacedKey(plugin, "elite_damage");
        ItemAttributeModifiers am = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (am != null) {
            for (ItemAttributeModifiers.Entry e : am.modifiers()) {
                if (e.attribute() == Attribute.ATTACK_DAMAGE
                        && e.modifier().getOperation() == AttributeModifier.Operation.ADD_NUMBER
                        && !e.modifier().getKey().equals(eliteKey)) {
                    dmg += e.modifier().getAmount();
                    found = true;
                }
            }
        }
        // 第二层：DataComponent 读不到时尝试 Bukkit API（可能返回默认修饰符，兼容自定义武器）
        if (!found && item.hasItemMeta()) {
            var mods = item.getItemMeta().getAttributeModifiers(Attribute.ATTACK_DAMAGE);
            if (mods != null) {
                for (AttributeModifier m : mods) {
                    if (m.getOperation() == AttributeModifier.Operation.ADD_NUMBER
                            && !m.getKey().equals(eliteKey)) {
                        dmg += m.getAmount();
                        found = true;
                    }
                }
            }
        }
        // 第三层：仍读不到时回退到原版伤害表（表值已含基础 1，无需再加）
        if (found) {
            // 工具条总攻击 = 基础 1 + 修饰符之和
            dmg += 1.0;
        } else {
            dmg = getVanillaBaseDamage(item.getType());
        }
        // 加上已装攻击宝石加成
        String[] ids = EliteGemFactory.getInstalledGems(item);
        int[] lvs = EliteGemFactory.getInstalledGemLevels(item);
        for (int i = 0; i < EliteGemFactory.MAX_GEM_SLOTS; i++) {
            if (ids[i] != null && "attack".equals(gemEffectFor(ids[i]))) {
                dmg += EliteGemFactory.attackBonus(lvs[i]);
            }
        }
        return dmg;
    }

    /** 根据等级返回失败降级数。 */
    private int dropFor(int level) {
        return level >= 10 ? 4 : level >= 7 ? 3 : level >= 4 ? 2 : 1;
    }

    /** 汇总装备上所有宝石，按各自等级应用效果（攻击→攻击力 / 防御→护甲 / 击退→击退 / 雷电→概率）。 */
    private void applyAllGemEffects(ItemStack equip) {
        String[] ids = EliteGemFactory.getInstalledGems(equip);
        int[] lvs = EliteGemFactory.getInstalledGemLevels(equip);
        double attack = 0, defense = 0, speed = 0;
        int knockback = 0;
        for (int i = 0; i < EliteGemFactory.MAX_GEM_SLOTS; i++) {
            if (ids[i] == null) continue;
            String eff = gemEffectFor(ids[i]);
            if (eff == null) continue;
            switch (eff) {
                case "attack" -> attack += EliteGemFactory.attackBonus(lvs[i]);
                case "defense" -> defense += EliteGemFactory.defenseBonus(lvs[i]);
                case "knockback" -> knockback = Math.max(knockback, EliteGemFactory.knockbackLevel(lvs[i]));
                // 迅捷宝石已改为移速符文；speed 保留用于下方以 0 清除旧数据的移速 modifier
                default -> {}
            }
        }
        // 武器：攻击力；护甲：减伤（全量重建，避免累积）
        if (isWeapon(equip)) {
            setWeaponAttack(equip, attack);
        } else if (isArmor(equip)) {
            setArmorDefense(equip, defense);
        }
        // 移速宝石（写入 MOVEMENT_SPEED modifier，key=elite_speed，全量重建避免累积）
        applyModifierSafe(equip, Attribute.MOVEMENT_SPEED, speed, new NamespacedKey(plugin, "elite_speed"));
        // 击退宝石等级写入 PDC（攻击时读取）+ 护甲同步套装等级 + 武器隐藏原版属性提示（避免伤害重复显示）
        ItemMeta meta = equip.getItemMeta();
        if (meta != null) {
            var pdc = meta.getPersistentDataContainer();
            pdc.set(new NamespacedKey(plugin, "gem_knockback"), PersistentDataType.INTEGER, knockback);
            if (isArmor(equip)) {
                // 同步护甲套装等级（套装加成系统依赖 armor_lv；淬炼后按宝石总等级写入）
                pdc.set(EliteMobManager.ARMOR_LV_KEY, PersistentDataType.INTEGER,
                        EliteGemFactory.totalGemLevel(equip));
            }
            equip.setItemMeta(meta);
        }
    }

    /** 装备上所有宝石耗尽时：还原为原版（名字/Lore/光效/加成/符文槽全部还原）。 */
    private void revertToOriginal(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return;
        boolean isW = isWeapon(equip);
        ItemMeta meta = equip.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();

        // 还原显示名
        String origName = pdc.get(isW ? ORIG_NAME : ORIG_NAME_A, PersistentDataType.STRING);
        if (origName != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', origName));
        } else {
            meta.setDisplayName(null);
        }
        // 还原 Lore
        List<String> origLore = pdc.get(isW ? ORIG_LORE : ORIG_LORE_A, PersistentDataType.LIST.strings());
        if (origLore != null) {
            meta.setLore(origLore);
        } else {
            meta.setLore(null);
        }
        // 移除淬炼光效（我们添加的 UNBREAKING）
        if (pdc.has(isW ? KEY_GLOW : KEY_GLOW_A, PersistentDataType.BYTE)) {
            meta.removeEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING);
            meta.removeItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
            pdc.remove(isW ? KEY_GLOW : KEY_GLOW_A);
        }
        meta.removeItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        // 移除升级/还原标记
        pdc.remove(isW ? UK : AUK);
        pdc.remove(isW ? ORIG_LORE : ORIG_LORE_A);
        pdc.remove(isW ? ORIG_NAME : ORIG_NAME_A);
        pdc.remove(new NamespacedKey(plugin, "gem_knockback"));
        pdc.remove(EliteMobManager.ARMOR_LV_KEY);
        // 清空宝石槽与符文槽残留
        for (int i = 0; i < EliteGemFactory.MAX_GEM_SLOTS; i++) {
            pdc.remove(EliteGemFactory.KEY_GEM_SLOTS[i]);
            pdc.remove(EliteGemFactory.KEY_GEM_SLOT_LEVELS[i]);
        }
        for (NamespacedKey k : com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS) pdc.remove(k);
        for (NamespacedKey k : com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOT_LEVELS) pdc.remove(k);
        equip.setItemMeta(meta);
        // 移除本插件添加的全部属性加成（淬炼攻击/防御/移速 + 符文生命/移速），避免拆卸/还原后残留
        removePluginAttributeModifiers(equip);
    }

    /** 移除装备上本插件添加的全部属性修饰符（淬炼攻击/防御/移速 + 符文生命/移速），用于拆卸/还原时清理。 */
    private void removePluginAttributeModifiers(ItemStack item) {
        ItemAttributeModifiers existing = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (existing == null) return;
        Set<String> keyPrefixes = Set.of(
                "elite_damage", "elite_armor", "elite_speed",
                "elite_rune_health", "elite_rune_speed");
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
            String k = e.modifier().getKey().getKey();
            // 精确匹配或前缀匹配（含带后缀的新版 key，如 elite_rune_health_head）
            if (keyPrefixes.stream().anyMatch(prefix -> k.equals(prefix) || k.startsWith(prefix + "_"))) continue;
            builder.addModifier(e.attribute(), e.modifier(), e.getGroup());
        }
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    /** 安全写入属性 modifier（按 key 全量替换，避免累积）。 */
    private void applyModifierSafe(ItemStack item, Attribute attr, double value, NamespacedKey key) {
        ItemAttributeModifiers existing = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        // 无现成 modifier 可保留且无写入值时不动，避免空组件覆盖原生/默认属性
        if (value == 0 && (existing == null || existing.modifiers().isEmpty())) return;
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        if (existing != null) {
            for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
                if (e.attribute() == attr && e.modifier().getKey().equals(key)) continue;
                builder.addModifier(e.attribute(), e.modifier(), e.getGroup());
            }
        }
        if (value != 0) {
            AttributeModifier mod = new AttributeModifier(key, value,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY);
            builder.addModifier(attr, mod, EquipmentSlotGroup.ANY);
        }
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    /** 全量重建武器攻击 modifier：保留原生 modifier 不动，仅替换 elite_damage（不累积、也不覆盖原生）。 */
    private void setWeaponAttack(ItemStack weapon, double bonus) {
        NamespacedKey eliteDamageKey = new NamespacedKey(plugin, "elite_damage");
        ItemAttributeModifiers existing = weapon.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        // 无现成 modifier 可保留且无加成时不动，避免写入空组件覆盖原生/默认属性（指令/自定义装备）
        if (bonus <= 0 && (existing == null || existing.modifiers().isEmpty())) return;
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        if (existing != null) {
            for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
                // 跳过旧 elite_damage（本次将被新加成替换）
                if (e.attribute() == Attribute.ATTACK_DAMAGE
                        && e.modifier().getKey().equals(eliteDamageKey)) continue;
                // 原生攻击 modifier 及所有其他 modifier 原样保留（多次淬炼不覆盖原生伤害）
                builder.addModifier(e.attribute(), e.modifier(), e.getGroup());
            }
        }
        if (bonus > 0) {
            // 使用 MAINHAND 组：与武器原生 modifier 一致，游戏会把原生+加成合并显示为一行“在主手时 X 攻击伤害”，不再出现重复的“手持时 +Y”行
            AttributeModifier mod = new AttributeModifier(eliteDamageKey, bonus,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
            builder.addModifier(Attribute.ATTACK_DAMAGE, mod, EquipmentSlotGroup.MAINHAND);
        }
        weapon.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    /** 全量重建护甲减伤 modifier：保留原生 modifier 不动，仅替换 elite_armor（不累积、也不覆盖原生）。 */
    private void setArmorDefense(ItemStack armor, double bonus) {
        EquipmentSlotGroup group = slotGroupFor(armor.getType());
        NamespacedKey eliteArmorKey = new NamespacedKey(plugin, "elite_armor");
        ItemAttributeModifiers existing = armor.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        // 无现成 modifier 可保留且无加成时不动，避免写入空组件覆盖原生/默认属性（指令/自定义装备）
        if (bonus <= 0 && (existing == null || existing.modifiers().isEmpty())) return;
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        if (existing != null) {
            for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
                // 跳过旧 elite_armor（本次将被新加成替换）
                if (e.attribute() == Attribute.ARMOR
                        && e.modifier().getKey().equals(eliteArmorKey)) continue;
                // 原生护甲 modifier 及所有其他 modifier 原样保留
                builder.addModifier(e.attribute(), e.modifier(), e.getGroup());
            }
        }
        if (bonus > 0) {
            AttributeModifier mod = new AttributeModifier(eliteArmorKey, bonus,
                    AttributeModifier.Operation.ADD_NUMBER, group);
            builder.addModifier(Attribute.ARMOR, mod, group);
        }
        armor.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    /** 根据宝石 id 返回效果类型（委托到 EliteConfig 缓存）。 */
    private String gemEffectFor(String gemId) {
        return plugin.getEliteConfig().gemEffectFor(gemId);
    }

    /** 重建装备 Lore：核心属性 + 宝石槽 + 符文槽（保留原美感）。 */
    private void rebuildLore(ItemStack equip, FileConfiguration msgs) {
        if (equip == null) return;
        ItemMeta meta = equip.getItemMeta();
        if (meta == null) return;

        String sep = ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "essence-upgrade.lore.separator", "&8&m------------------------------------"));
        List<String> lore = new ArrayList<>();
        lore.add(sep);

        // 核心属性
        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "essence-upgrade.lore.stat-title", "&b❖ 核心属性")));
        if (isWeapon(equip)) {
            // 直接读取当前实际攻击伤害（含淬炼加成），与游戏工具提示/实际伤害严格一致（修复矛等非标准武器显示不符）
            double totalDmg = getActualWeaponDamage(equip);
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    msg(msgs, "essence-upgrade.lore.attack", "   &7攻击力&8：&c+{total} &4❤")
                            .replace("{total}", String.format("%.1f", totalDmg))));
        } else if (isArmor(equip)) {
            double def = gemDefenseTotal(equip);
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    msg(msgs, "armor-upgrade.lore.defense", "   &7减伤&8：&b{reduction}%")
                            .replace("{reduction}", String.format("%.1f", def))));
        }
        lore.add(sep);

        // 宝石槽显示
        appendGemSlotsLore(equip, lore, msgs);
        lore.add(sep);

        // 符文槽显示（数量由宝石等级之和决定）
        int totalLevel = EliteGemFactory.totalGemLevel(equip);
        int runeSlots = EliteGemFactory.runeSlotsForTotalLevel(totalLevel);
        if (runeSlots > 0) {
            appendRuneSlotsLore(meta, lore, runeSlots);
            lore.add(sep);
        }
        meta.setLore(lore);
        equip.setItemMeta(meta);
    }

    /** 护甲 Lore 显示的总体减伤：原版护甲减伤 + 防御宝石减伤（装非防御宝石也显示原版减伤，不再为 0）。 */
    private double gemDefenseTotal(ItemStack equip) {
        double vanilla = Math.min(20, vanillaArmorPoints(equip.getType())) / 25.0 * 100.0;
        String[] ids = EliteGemFactory.getInstalledGems(equip);
        int[] lvs = EliteGemFactory.getInstalledGemLevels(equip);
        double sum = 0;
        for (int i = 0; i < EliteGemFactory.MAX_GEM_SLOTS; i++) {
            if (ids[i] != null && "defense".equals(gemEffectFor(ids[i]))) {
                sum += EliteGemFactory.defenseBonus(lvs[i]);
            }
        }
        return Math.min(80.0, vanilla + sum);
    }

    /** 原版护甲点的护甲值（armor points），非护甲返回 0。 */
    private static int vanillaArmorPoints(Material m) {
        return switch (m) {
            case LEATHER_HELMET, LEATHER_BOOTS, GOLDEN_BOOTS, CHAINMAIL_BOOTS -> 1;
            case GOLDEN_HELMET, IRON_HELMET, TURTLE_HELMET, LEATHER_LEGGINGS, IRON_BOOTS -> 2;
            case CHAINMAIL_HELMET, DIAMOND_HELMET, NETHERITE_HELMET, LEATHER_CHESTPLATE,
                 GOLDEN_LEGGINGS, DIAMOND_BOOTS, NETHERITE_BOOTS -> 3;
            case CHAINMAIL_LEGGINGS -> 4;
            case GOLDEN_CHESTPLATE, CHAINMAIL_CHESTPLATE, IRON_LEGGINGS -> 5;
            case IRON_CHESTPLATE, DIAMOND_LEGGINGS, NETHERITE_LEGGINGS -> 6;
            case DIAMOND_CHESTPLATE, NETHERITE_CHESTPLATE -> 8;
            default -> 0;
        };
    }

    /** 向 Lore 追加宝石槽显示：标题(已用/总数) + 每颗宝石一行（名称+等级+效果）。 */
    private void appendGemSlotsLore(ItemStack equip, List<String> lore, FileConfiguration msgs) {
        if (equip == null || !equip.hasItemMeta()) return;
        String[] ids = EliteGemFactory.getInstalledGems(equip);
        int[] lvs = EliteGemFactory.getInstalledGemLevels(equip);
        int totalLevel = EliteGemFactory.totalGemLevel(equip);
        int slots = EliteGemFactory.gemSlotsForLevel(totalLevel);
        int used = 0;
        // 统计所有已装宝石（含降级后被锁定的槽位——宝石仍生效，必须显示）
        for (int i = 0; i < EliteGemFactory.MAX_GEM_SLOTS; i++) if (ids[i] != null) used++;

        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "essence-upgrade.lore.gem-title", "&e✦ 宝石槽&7 ({used}/{max})")
                        .replace("{used}", String.valueOf(used))
                        .replace("{max}", String.valueOf(slots))));
        // 遍历全部槽位：所有已装宝石都要显示；空槽只在当前容量内显示
        for (int i = 0; i < EliteGemFactory.MAX_GEM_SLOTS; i++) {
            if (ids[i] == null) {
                if (i < slots) {
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            msg(msgs, "essence-upgrade.lore.gem-empty", "   &8◇ 空槽")));
                }
                continue;
            }
            String eff = gemEffectFor(ids[i]);
            String gemName = gemDisplayName(ids[i]);
            String effectDesc = switch (eff == null ? "" : eff) {
                case "attack" -> "&7→ &c攻击力 +" + String.format("%.1f", EliteGemFactory.attackBonus(lvs[i]));
                case "defense" -> "&7→ &b减伤 +" + String.format("%.1f", EliteGemFactory.defenseBonus(lvs[i]));
                case "knockback" -> "&7→ &f击退 Lv." + EliteGemFactory.knockbackLevel(lvs[i]);
                case "thunder" -> "&7→ &e雷电 " + String.format("%.0f%%", EliteGemFactory.thunderChance(lvs[i]) * 100);
                case "magnet" -> "&7→ &b磁力拾取 &f+" + EliteGemFactory.magnetRadius(lvs[i]) + " &7格";
                case "doublejump" -> "&7→ &a二段跳 &f蓄力" + String.format("%.1f", EliteGemFactory.jumpCooldown(lvs[i]) / 1000.0) + "s";
                case "rare" -> "&7→ &6稀有";
                default -> "";
            };
            // 超出当前容量的槽位标注"锁定"（宝石仍生效）
            String lock = i >= slots ? " &8(槽位锁定)" : "";
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    msg(msgs, "essence-upgrade.lore.gem-line", "   &e◆ {gem} &7{effect}")
                            .replace("{gem}", gemName + " &7Lv." + lvs[i])
                            .replace("{effect}", effectDesc + lock)));
        }
    }

    /** 获取宝石的显示名（来自 CustomDrop.name）。 */
    private String gemDisplayName(String gemId) {
        for (var d : plugin.getEliteConfig().getCustomDrops()) {
            if (d.id != null && d.id.equalsIgnoreCase(gemId) && d.name != null) {
                return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', d.name));
            }
        }
        return gemId;
    }

    // ==================== DataComponent 应用（原版逻辑） ====================

    /** 武器攻击力 modifier：把淬炼加成合并进原生攻击值（先扣除旧加成再叠加，避免累积/失败不回退）。 */
    private void applyWeaponDataComponent(ItemStack weapon, double bonus, double oldBonus) {
        if (bonus <= 0 && oldBonus <= 0) return;
        NamespacedKey eliteDamageKey = new NamespacedKey(plugin, "elite_damage");

        ItemAttributeModifiers existing = weapon.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        boolean merged = false;
        if (existing != null) {
            for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
                // 跳过旧 elite_damage（将被合并或替换）
                if (e.attribute() == Attribute.ATTACK_DAMAGE
                        && e.modifier().getKey().equals(eliteDamageKey)) continue;
                // 合并进原生攻击 modifier（ADD_NUMBER）：先减去旧加成，再加新加成
                if (!merged && e.attribute() == Attribute.ATTACK_DAMAGE
                        && e.modifier().getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                    double base = Math.max(0, e.modifier().getAmount() - oldBonus);
                    double mergedVal = base + bonus;
                    AttributeModifier mergedMod = new AttributeModifier(e.modifier().getKey(),
                            mergedVal, AttributeModifier.Operation.ADD_NUMBER, e.getGroup());
                    builder.addModifier(e.attribute(), mergedMod, e.getGroup());
                    merged = true;
                    continue;
                }
                builder.addModifier(e.attribute(), e.modifier(), e.getGroup());
            }
        }
        // 无原生攻击 modifier 时兜底添加 elite_damage
        if (!merged && bonus > 0) {
            AttributeModifier mod = new AttributeModifier(eliteDamageKey, bonus,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HAND);
            builder.addModifier(Attribute.ATTACK_DAMAGE, mod, EquipmentSlotGroup.HAND);
        }
        weapon.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    /** 护甲减伤 modifier：把淬炼加成合并进原生护甲值（先扣除旧加成再叠加，避免累积/失败不回退）。 */
    private void applyArmorDataComponent(ItemStack armor, double reduction, Material mat, double oldReduction) {
        if (reduction <= 0 && oldReduction <= 0) return;
        EquipmentSlotGroup group = slotGroupFor(mat);
        NamespacedKey eliteArmorKey = new NamespacedKey(plugin, "elite_armor");

        ItemAttributeModifiers existing = armor.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        boolean merged = false;
        if (existing != null) {
            for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
                // 跳过旧 elite_armor（将被合并或替换）
                if (e.attribute() == Attribute.ARMOR
                        && e.modifier().getKey().equals(eliteArmorKey)) continue;
                // 合并进原生护甲 modifier（ADD_NUMBER）：先减去旧加成，再加新加成
                if (!merged && e.attribute() == Attribute.ARMOR
                        && e.modifier().getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                    double base = Math.max(0, e.modifier().getAmount() - oldReduction);
                    double mergedVal = base + reduction;
                    AttributeModifier mergedMod = new AttributeModifier(e.modifier().getKey(),
                            mergedVal, AttributeModifier.Operation.ADD_NUMBER, e.getGroup());
                    builder.addModifier(e.attribute(), mergedMod, e.getGroup());
                    merged = true;
                    continue;
                }
                builder.addModifier(e.attribute(), e.modifier(), e.getGroup());
            }
        }
        // 无原生护甲 modifier 时兜底添加 elite_armor
        if (!merged && reduction > 0) {
            AttributeModifier mod = new AttributeModifier(eliteArmorKey, reduction,
                    AttributeModifier.Operation.ADD_NUMBER, group);
            builder.addModifier(Attribute.ARMOR, mod, group);
        }
        armor.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    private EquipmentSlotGroup slotGroupFor(Material mat) {
        String n = mat.name();
        if (n.contains("HELMET")) return EquipmentSlotGroup.HEAD;
        if (n.contains("CHESTPLATE")) return EquipmentSlotGroup.CHEST;
        if (n.contains("LEGGINGS")) return EquipmentSlotGroup.LEGS;
        if (n.contains("BOOTS")) return EquipmentSlotGroup.FEET;
        return EquipmentSlotGroup.ARMOR;
    }

    // ==================== 粒子 / 音效（原版） ====================

    private void playSuccess(Player p) {
        // 15 个 FIREWORK 粒子（原版）
        for (int i = 0; i < 15; i++) {
            p.getWorld().spawnParticle(Particle.FIREWORK, p.getLocation()
                    .add(rng.nextDouble() - 0.5, 1 + rng.nextDouble(), rng.nextDouble() - 0.5), 1);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        p.getWorld().playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        // 烟花（黄/橙/红 BURST）
        try {
            Firework fw = (Firework) p.getWorld().spawnEntity(p.getLocation().add(0, 1, 0), org.bukkit.entity.EntityType.FIREWORK_ROCKET);
            FireworkMeta fm = fw.getFireworkMeta();
            fm.addEffect(org.bukkit.FireworkEffect.builder()
                    .with(org.bukkit.FireworkEffect.Type.BURST)
                    .withColor(Color.YELLOW, Color.ORANGE, Color.RED)
                    .build());
            fm.setPower(1);
            fw.setFireworkMeta(fm);
        } catch (Exception ignored) {}
    }

    private void playFail(Player p) {
        // 12 个 SMOKE 粒子（原版）
        for (int i = 0; i < 12; i++) {
            p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation()
                    .add(rng.nextDouble() - 0.5, 1 + rng.nextDouble(), rng.nextDouble() - 0.5), 1);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
    }

    // ==================== 聊天提示（原版 send 系列） ====================

    private void sendWeaponSuccess(Player p, ItemStack item, int lvl, double bonus, double delta, double total) {
        FileConfiguration msgs = plugin.getMessages();
        String name = itemName(item);
        p.sendMessage("");
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(msgs, "essence-upgrade.success.title", "  &6&l✦ &e&l淬炼成功！ &6&l✦")));
        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "essence-upgrade.success.desc", "  &7你的 &f{name} &7已淬炼至 &a&l{lvl} &7级！")
                        .replace("{name}", name).replace("{lvl}", String.valueOf(lvl))));
        p.sendMessage("");
        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "essence-upgrade.success.stats", "  &7▶ &f总攻击力 &7: &c{total} &7(↑ &a+{bonus}&7)")
                        .replace("{total}", String.format("%.1f", total))
                        .replace("{bonus}", String.format("%.1f", bonus))));
        p.sendMessage("");
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(msgs, "essence-upgrade.success.footer", "  &8&m------------------------------------&r")));
        p.sendMessage("");
    }

    private void sendWeaponFail(Player p, ItemStack item, int lvl, int oldLvl) {
        FileConfiguration msgs = plugin.getMessages();
        String name = itemName(item);
        p.sendMessage("");
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(msgs, "essence-upgrade.fail.title", "  &c&l✘ &4&l淬炼失败！ &c&l✘")));
        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "essence-upgrade.fail.desc", "  &7你的 &f{name} &7降至 &c&l{lvl}")
                        .replace("{name}", name).replace("{lvl}", String.valueOf(lvl))));
        if (oldLvl > 0) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &7等级损失: &c-" + (oldLvl - lvl)));
        }
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(msgs, "essence-upgrade.fail.footer", "  &8&m------------------------------------&r")));
        p.sendMessage("");
    }

    private void sendArmorSuccess(Player p, ItemStack item, int lvl, double reduction) {
        FileConfiguration msgs = plugin.getMessages();
        String name = itemName(item);
        p.sendMessage("");
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(msgs, "armor-upgrade.success.title", "  &9&l✦ &b&l护甲淬炼成功！ &9&l✦")));
        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "armor-upgrade.success.desc", "  &7你的 &f{name} &7已淬炼至 &a&l{lvl} &7级！")
                        .replace("{name}", name).replace("{lvl}", String.valueOf(lvl))));
        p.sendMessage("");
        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "armor-upgrade.success.stats", "  &7▶ &f减伤 &7: &b{reduction}%")
                        .replace("{reduction}", String.format("%.1f", reduction))));
        p.sendMessage("");
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(msgs, "armor-upgrade.success.footer", "  &8&m------------------------------------&r")));
        p.sendMessage("");
    }

    private void sendArmorFail(Player p, ItemStack item, int lvl, int oldLvl) {
        FileConfiguration msgs = plugin.getMessages();
        String name = itemName(item);
        p.sendMessage("");
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(msgs, "armor-upgrade.fail.title", "  &c&l✘ &4&l护甲淬炼失败！ &c&l✘")));
        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                msg(msgs, "armor-upgrade.fail.desc", "  &7你的 &f{name} &7降至 &c&l{lvl}")
                        .replace("{name}", name).replace("{lvl}", String.valueOf(lvl))));
        if (oldLvl > 0) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &7等级损失: &c-" + (oldLvl - lvl)));
        }
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(msgs, "armor-upgrade.fail.footer", "  &8&m------------------------------------&r")));
        p.sendMessage("");
    }

    // ==================== 工具方法（原版） ====================

    private String itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return item.getItemMeta().getDisplayName();
        return item.getType().name();
    }

    private boolean isWeapon(ItemStack item) {
        if (item == null) return false;
        String n = item.getType().name();
        return n.contains("SWORD") || n.contains("AXE") || n.contains("BOW")
                || n.contains("CROSSBOW") || n.contains("SPEAR") || n.contains("LANCE")
                || n.contains("HALBERD") || n.contains("TRIDENT") || n.contains("MACE")
                || n.contains("HAMMER") || n.contains("DAGGER") || n.contains("SCYTHE");
    }

    private boolean isArmor(ItemStack item) {
        if (item == null) return false;
        String n = item.getType().name();
        return n.contains("HELMET") || n.contains("CHESTPLATE") || n.contains("LEGGINGS") || n.contains("BOOTS");
    }

    private void updateAnvilResult(Inventory inv) {
        ItemStack first = inv.getItem(0);
        ItemStack second = inv.getItem(1);
        if (first == null || second == null) return;
        if ((isWeapon(first) || isArmor(first)) && EliteGemFactory.isGem(second)) {
            inv.setItem(2, createHintPaper());
            if (inv instanceof AnvilInventory ai) ai.setRepairCost(1);
            return;
        }
        if (hasAnyGem(first) && EliteEssenceFactory.isGemRemover(second)) {
            inv.setItem(2, createHintPaper());
            if (inv instanceof AnvilInventory ai) ai.setRepairCost(1);
            return;
        }
        clearHint(inv);
    }

    private ItemStack createHintPaper() {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta == null) return paper;
        meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "✦ 点击进行淬炼");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "点击结果取出即可淬炼");
        lore.add(ChatColor.DARK_GRAY + "失败时装备可能降级/销毁");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "upgrade_hint"),
                PersistentDataType.BYTE, (byte) 1);
        paper.setItemMeta(meta);
        return paper;
    }

    private void clearHint(org.bukkit.inventory.Inventory inv) {
        ItemStack r = inv.getItem(2);
        if (r == null || r.getType() != Material.PAPER || !r.hasItemMeta()) return;
        if (r.getItemMeta().getPersistentDataContainer().has(
                new NamespacedKey(plugin, "upgrade_hint"), PersistentDataType.BYTE)) {
            inv.setItem(2, null);
        }
    }

    private void validateEssence(Inventory inv) {
        // 精华消耗后刷新：铁砧内精华可能被 consume 清空，这里无需重建
    }

    private void validateArmorEssence(Inventory inv) {
        // 同上
    }

    /** 消耗铁砧槽1中的一颗精华。 */
    private void consume(Inventory inv) {
        ItemStack essence = inv.getItem(1);
        if (essence == null) return;
        if (essence.getAmount() > 1) {
            essence.setAmount(essence.getAmount() - 1);
            inv.setItem(1, essence);
        } else {
            inv.setItem(1, null);
        }
    }

    private boolean playerHasCharm(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && EliteEssenceFactory.isProtectionCharm(item)) return true;
        }
        return false;
    }

    private void consumeCharmFromPlayer(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (item == null || !EliteEssenceFactory.isProtectionCharm(item)) continue;
            if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
            else p.getInventory().setItem(i, null);
            return;
        }
    }

    // ==================== 基准值表（原版） ====================

    private static double getVanillaBaseDamage(Material m) {
        return switch (m) {
            case WOODEN_SWORD, GOLDEN_SWORD -> 4.0;
            case STONE_SWORD, COPPER_SWORD -> 5.0;
            case IRON_SWORD -> 6.0;
            case DIAMOND_SWORD -> 7.0;
            case NETHERITE_SWORD -> 8.0;
            case WOODEN_AXE -> 7.0;
            case STONE_AXE -> 9.0;
            case COPPER_AXE -> 8.0;
            case IRON_AXE, DIAMOND_AXE -> 9.0;
            case NETHERITE_AXE -> 10.0;
            case WOODEN_PICKAXE, GOLDEN_PICKAXE -> 2.0;
            case STONE_PICKAXE, COPPER_PICKAXE -> 3.0;
            case IRON_PICKAXE -> 4.0;
            case DIAMOND_PICKAXE -> 5.0;
            case NETHERITE_PICKAXE -> 6.0;
            case WOODEN_SHOVEL, GOLDEN_SHOVEL -> 2.5;
            case STONE_SHOVEL, COPPER_SHOVEL -> 3.5;
            case IRON_SHOVEL -> 4.5;
            case DIAMOND_SHOVEL -> 5.5;
            case NETHERITE_SHOVEL -> 6.5;
            case WOODEN_HOE, STONE_HOE, COPPER_HOE -> 5.0;
            case IRON_HOE -> 6.0;
            case DIAMOND_HOE -> 7.0;
            case NETHERITE_HOE -> 8.0;
            case TRIDENT -> 9.0;
            case MACE -> 6.0;
            default -> 0.0;
        };
    }

    /** 获取物品显示名（有自定义名返回自定义名，否则返回原版中文名）。 */
    private String getItemDisplayName(ItemStack item) {
        if (item == null) return "";
        if (!item.hasItemMeta()) return fmt(item.getType());
        ItemMeta im = item.getItemMeta();
        var pdc = im.getPersistentDataContainer();
        // 优先使用首次淬炼时保存的原版显示名（避免二次淬炼时前缀剥除出错导致名字为空）
        String origName = pdc.get(isWeapon(item) ? ORIG_NAME : ORIG_NAME_A, PersistentDataType.STRING);
        if (origName != null && !origName.isEmpty()) {
            return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', origName));
        }
        // 兜底：从当前显示名剥离精英前缀（只去掉 "精英 " 前缀与 " [Lv.X]" 后缀，保留原名）
        if (im.hasDisplayName()) {
            String dn = ChatColor.stripColor(im.getDisplayName());
            dn = dn.replaceFirst("^精英\\s*", "");
            dn = dn.replaceAll("\\s*\\[[^\\]]*Lv\\.[0-9]+\\s*\\]", "").trim();
            if (!dn.isEmpty()) return dn;
        }
        return fmt(item.getType());
    }

    /** 物品材质的中文显示名。 */
    private static String fmt(Material m) {
        return switch (m) {
            case WOODEN_SWORD -> "木剑"; case GOLDEN_SWORD -> "金剑"; case STONE_SWORD -> "石剑";
            case COPPER_SWORD -> "铜剑"; case IRON_SWORD -> "铁剑"; case DIAMOND_SWORD -> "钻石剑";
            case NETHERITE_SWORD -> "下界合金剑";
            case WOODEN_AXE -> "木斧"; case GOLDEN_AXE -> "金斧"; case STONE_AXE -> "石斧";
            case COPPER_AXE -> "铜斧"; case IRON_AXE -> "铁斧"; case DIAMOND_AXE -> "钻石斧";
            case NETHERITE_AXE -> "下界合金斧";
            case WOODEN_HOE -> "木锄"; case GOLDEN_HOE -> "金锄"; case STONE_HOE -> "石锄";
            case COPPER_HOE -> "铜锄"; case IRON_HOE -> "铁锄"; case DIAMOND_HOE -> "钻石锄";
            case NETHERITE_HOE -> "下界合金锄";
            case WOODEN_PICKAXE -> "木镐"; case GOLDEN_PICKAXE -> "金镐"; case STONE_PICKAXE -> "石镐";
            case COPPER_PICKAXE -> "铜镐"; case IRON_PICKAXE -> "铁镐"; case DIAMOND_PICKAXE -> "钻石镐";
            case NETHERITE_PICKAXE -> "下界合金镐";
            case WOODEN_SHOVEL -> "木锹"; case GOLDEN_SHOVEL -> "金锹"; case STONE_SHOVEL -> "石锹";
            case COPPER_SHOVEL -> "铜锹"; case IRON_SHOVEL -> "铁锹"; case DIAMOND_SHOVEL -> "钻石锹";
            case NETHERITE_SHOVEL -> "下界合金锹";
            case TRIDENT -> "三叉戟"; case MACE -> "重锤"; case BOW -> "弓"; case CROSSBOW -> "弩";
            default -> {
                String n = m.name().toLowerCase().replace('_', ' ');
                String[] parts = n.split(" ");
                StringBuilder sb = new StringBuilder();
                for (String w : parts) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
                yield sb.toString().trim();
            }
        };
    }

    private static double getVanillaBaseArmor(Material m) {
        String n = m.name();
        if (n.contains("NETHERITE")) {
            if (n.contains("HELMET")) return 3.0;
            if (n.contains("CHESTPLATE")) return 8.0;
            if (n.contains("LEGGINGS")) return 6.0;
            if (n.contains("BOOTS")) return 3.0;
        } else if (n.contains("DIAMOND")) {
            if (n.contains("HELMET")) return 3.0;
            if (n.contains("CHESTPLATE")) return 8.0;
            if (n.contains("LEGGINGS")) return 6.0;
            if (n.contains("BOOTS")) return 3.0;
        } else if (n.contains("IRON")) {
            if (n.contains("HELMET")) return 2.0;
            if (n.contains("CHESTPLATE")) return 6.0;
            if (n.contains("LEGGINGS")) return 5.0;
            if (n.contains("BOOTS")) return 2.0;
        } else if (n.contains("CHAINMAIL")) {
            if (n.contains("HELMET")) return 2.0;
            if (n.contains("CHESTPLATE")) return 5.0;
            if (n.contains("LEGGINGS")) return 4.0;
            if (n.contains("BOOTS")) return 1.0;
        } else if (n.contains("GOLDEN")) {
            if (n.contains("HELMET")) return 2.0;
            if (n.contains("CHESTPLATE")) return 5.0;
            if (n.contains("LEGGINGS")) return 3.0;
            if (n.contains("BOOTS")) return 1.0;
        } else if (n.contains("LEATHER")) {
            if (n.contains("HELMET")) return 1.0;
            if (n.contains("CHESTPLATE")) return 3.0;
            if (n.contains("LEGGINGS")) return 2.0;
            if (n.contains("BOOTS")) return 1.0;
        }
        return 0.0;
    }

    private static String msg(FileConfiguration msgs, String key, String def) {
        return (msgs != null && msgs.contains(key)) ? msgs.getString(key) : def;
    }

    // ==================== 符文槽 Lore ====================

    /** 向 Lore 追加符文槽显示：标题(已装/容量) + 所有已装符文一行（含锁定槽位），空槽仅在容量内显示。 */
    private void appendRuneSlotsLore(ItemMeta meta, List<String> lore, int slots) {
        if (slots <= 0) return;
        if (slots > com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS.length) {
            slots = com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS.length;
        }

        var pdc = meta.getPersistentDataContainer();
        int used = 0;
        // 统计所有已装符文（含降级后被锁定的槽位——符文仍生效，必须显示）
        for (int i = 0; i < com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS.length; i++) {
            if (pdc.has(com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS[i],
                    PersistentDataType.STRING)) used++;
        }

        // 标题：✦ 符文槽 (已装/容量)
        lore.add(ChatColor.translateAlternateColorCodes('&',
                msg(plugin.getMessages(), "essence-upgrade.lore.rune-title", "&d✦ 符文槽&7 ({used}/{max})")
                        .replace("{used}", String.valueOf(used))
                        .replace("{max}", String.valueOf(slots))));

        for (int i = 0; i < com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS.length; i++) {
            String type = pdc.get(com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS[i],
                    PersistentDataType.STRING);
            if (type != null) {
                var t = com.clawx.elitemobs.rune.EliteRuneFactory.TYPES.get(type);
                Integer lvI = pdc.get(com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOT_LEVELS[i],
                        PersistentDataType.INTEGER);
                int rlvl = lvI == null ? 1 : Math.max(1, Math.min(10, lvI));
                // 超出当前容量的槽位标注"锁定"（符文仍生效）
                String lock = i >= slots ? " &8(槽位锁定)" : "";
                String line = ChatColor.translateAlternateColorCodes('&',
                        msg(plugin.getMessages(), "essence-upgrade.lore.rune-line", "   &e◆ {rune} &7{effect}")
                                .replace("{rune}", t != null
                                        ? t.coloredName + " &7Lv." + rlvl + " " + ChatColor.GRAY + t.icon
                                        : ChatColor.WHITE + type)
                                .replace("{effect}", (t != null ? "&7→ &f" + t.effect : "") + lock));
                lore.add(line);
            } else {
                if (i < slots) {
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            msg(plugin.getMessages(), "essence-upgrade.lore.rune-empty", "   &8◇ 空槽")));
                }
            }
        }
    }

    // ==================== 精英怪特效（原版） ====================

    /** 精英苦力怕爆炸：螺旋粒子 + 音效。 */
    public void startCreeperWatcher() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {}, 20L, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreeperExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof LivingEntity le) || !EliteMobManager.isElite(le)) return;
        Location loc = event.getLocation().clone();
        for (int i = 0; i < 30; i++) {
            double angle = Math.random() * Math.PI * 2;
            EliteMobManager.spawnParticleSafe(loc.getWorld(), Particle.EXPLOSION,
                    loc.clone().add(Math.cos(angle) * (0.3 + Math.random() * 1.5),
                            0.5 + Math.random() * 0.5,
                            Math.sin(angle) * (0.3 + Math.random() * 1.5)), 1);
        }
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLightningDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.LIGHTNING) return;
        if (!(event.getEntity() instanceof LivingEntity le) || !EliteMobManager.isElite(le)) return;
        double health = le.getHealth();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // 闪电击中精英后短暂治疗（原版逻辑）
            if (!le.isDead() && le.isValid()) {
                le.setHealth(Math.min(le.getMaxHealth(), health + 1.0));
            }
        });
    }
}
