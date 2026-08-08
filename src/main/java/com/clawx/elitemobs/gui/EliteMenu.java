package com.clawx.elitemobs.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.clawx.elitemobs.EconomyHook;
import com.clawx.elitemobs.EliteConfig;
import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.ai.EliteClass;
import com.clawx.elitemobs.essence.EliteEssenceFactory;
import com.clawx.elitemobs.rune.EliteRuneFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 精英强化菜单（/emmenu）—— 多级箱子 GUI。
 *
 * <p>主界面 → 二级（宝石商城/符文商城/我的状态/使用说明/管理）→ 三级购买页
 * （选择支付方式 + 购买颗数）。价格在 config shop 段配置（未配置用内置默认）；
 * 金币（Vault）与点券（PlayerPoints）任选其一支付。</p>
 */
public class EliteMenu implements Listener, CommandExecutor {

    private static final String TITLE = "精英强化菜单";
    private static final List<String> RUNE_TYPES = List.of(
            "HEALTH", "SPEED", "STRENGTH", "REGEN", "RESIST", "FIRE");
    private static final int RUNE_PER_PAGE = 3;
    /** 宝石商城：每页横排 7 项（10 宝石 + 保护符 + 拆卸器 = 12 项 → 2 页）。 */
    private static final int ITEM_PER_PAGE = 7;
    /** 生成精英：每页 3 行 × 7 个 = 21 个生物类型，放满一页减少翻页。 */
    private static final int SPAWN_PER_PAGE = 21;

    private final EliteMobsPlugin plugin;
    private final Map<UUID, String> page = new ConcurrentHashMap<>();
    private final Map<UUID, BuyState> buyState = new ConcurrentHashMap<>();

    public EliteMenu(EliteMobsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(ChatColor.RED + "仅限玩家使用。"); return true; }
        openMain(p);
        return true;
    }

    /** 待购商品状态（三级购买页）。 */
    static class BuyState {
        String kind;      // gem / rune / utility
        String id;
        int lv;           // utility 为 1
        String pay = "money";
        int amount = 1;
        int backIdx = 0;
    }

    // ==================== 主菜单 ====================

    public void openMain(Player p) {
        page.put(p.getUniqueId(), "main");
        Inventory inv = base("");
        gradFrame(inv);
        inv.setItem(4, label(Material.NAME_TAG, "&b&l✦ 精英强化菜单 ✦",
                "&7选择功能，强化你的装备", "&7金币/点券二选一支付"));
        // 功能按钮
        inv.setItem(19, btn(Material.DIAMOND, "&b&l💎 宝石商城", "&7购买淬炼宝石 / 保护符 / 拆卸器"));
        inv.setItem(22, btn(Material.ENCHANTED_BOOK, "&d&l🏷 符文商城", "&7购买生命/移速/力量等符文"));
        inv.setItem(25, btn(Material.GOLD_INGOT, "&6&l📊 我的状态", "&7查看金币 / 点券余额"));
        inv.setItem(29, btn(Material.PAPER, "&e&l📖 使用说明", "&7宝石 / 符文 / 合成玩法"));
        if (p.hasPermission("elitemobs.admin")) {
            inv.setItem(31, btn(Material.BARRIER, "&c&l🧰 管理", "&7发放测试物品（管理员）"));
        }
        // 装饰点缀
        inv.setItem(40, decor(Material.NETHER_STAR));
        inv.setItem(13, decor(Material.EMERALD_BLOCK));
        inv.setItem(33, decor(Material.BEACON));
        inv.setItem(49, btn(Material.RED_STAINED_GLASS_PANE, "&c✖ 关闭"));
        p.openInventory(inv);
    }

    // ==================== 宝石商城（横排） ====================

    private void openGemShop(Player p, int idx) {
        List<ShopItem> items = gemShopItems();
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) ITEM_PER_PAGE));
        idx = Math.max(0, Math.min(pages - 1, idx));
        page.put(p.getUniqueId(), "gems:" + idx);
        Inventory inv = base("&b宝石商城");
        gradFrame(inv, Material.BLUE_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.DIAMOND, "&b&l💎 宝石商城",
                "&7点击商品选购（价格 = 基础 × 等级）"));
        int from = idx * ITEM_PER_PAGE;
        for (int i = 0; i < ITEM_PER_PAGE; i++) {
            if (from + i >= items.size()) break;
            ShopItem si = items.get(from + i);
            inv.setItem(19 + i, si.icon);
        }
        inv.setItem(13, label(Material.BOOK, "&7页 " + (idx + 1) + "/" + pages, ""));
        controls(inv, idx > 0, idx < pages - 1);
        p.openInventory(inv);
    }

    /** 宝石等级页（Lv.1-10）。 */
    private void openGemLv(Player p, String gemId) {
        EliteConfig.CustomDrop gem = findGem(gemId);
        if (gem == null) return;
        page.put(p.getUniqueId(), "gemlv:" + gemId);
        Inventory inv = base("&b" + strip(gem.name));
        gradFrame(inv, Material.CYAN_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.DIAMOND, "&b&l" + strip(gem.name), "&7选择等级购买"));
        for (int lv = 1; lv <= 10; lv++) {
            inv.setItem(9 + lv - 1, priced(gem.build(lv),
                    plugin.getEliteConfig().getGemShopPrice(gemId), lv));
        }
        controls(inv, false, false);
        p.openInventory(inv);
    }

    // ==================== 符文商城 ====================

    private void openRuneShop(Player p, int idx) {
        int pages = Math.max(1, (int) Math.ceil(RUNE_TYPES.size() / (double) RUNE_PER_PAGE));
        idx = Math.max(0, Math.min(pages - 1, idx));
        page.put(p.getUniqueId(), "rune:" + idx);
        Inventory inv = base("&d符文商城");
        gradFrame(inv, Material.PURPLE_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.ENCHANTED_BOOK, "&d&l🏷 符文商城",
                "&7每页 3 种符文 × 全部等级"));
        int from = idx * RUNE_PER_PAGE;
        for (int i = 0; i < RUNE_PER_PAGE; i++) {
            int ti = from + i;
            if (ti >= RUNE_TYPES.size()) break;
            String type = RUNE_TYPES.get(ti);
            int baseSlot = 9 + i * 10;
            for (int lv = 1; lv <= 10; lv++) {
                inv.setItem(baseSlot + lv - 1, priced(EliteRuneFactory.createRune(type, lv, plugin.getMessages()),
                        plugin.getEliteConfig().getRuneShopPrice(type), lv));
            }
        }
        inv.setItem(13, label(Material.BOOK, "&7页 " + (idx + 1) + "/" + pages, ""));
        controls(inv, idx > 0, idx < pages - 1);
        p.openInventory(inv);
    }

    // ==================== 三级购买页 ====================

    private void openBuy(Player p, String kind, String id, int lv, int backIdx) {
        BuyState st = new BuyState();
        st.kind = kind; st.id = id; st.lv = lv; st.backIdx = backIdx;
        buyState.put(p.getUniqueId(), st);
        page.put(p.getUniqueId(), "buy");
        renderBuy(p);
    }

    private void renderBuy(Player p) {
        BuyState st = buyState.get(p.getUniqueId());
        if (st == null) return;
        Inventory inv = base("&6购买确认");
        gradFrame(inv, Material.ORANGE_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        ItemStack icon = itemFor(st);
        double[] base = priceFor(st);
        double unit = base != null ? (st.pay.equals("points") ? base[1] : base[0]) * st.lv : 0;
        double total = unit * st.amount;
        inv.setItem(4, label(Material.CHEST, "&6&l购买确认", "&7选择支付方式与数量"));
        inv.setItem(22, priced(icon, base, st.lv));
        // 支付方式
        inv.setItem(20, payBtn("&6金币", st.pay.equals("money"), base == null ? 0 : base[0] * st.lv));
        inv.setItem(24, payBtn("&d点券", st.pay.equals("points"), base == null ? 0 : base[1] * st.lv));
        // 数量
        inv.setItem(29, btn(Material.RED_STAINED_GLASS_PANE, "&c➖ 减少"));
        inv.setItem(31, label(Material.BOOK, "&f数量 × " + st.amount, "&7点击 -/+ 调整"));
        inv.setItem(33, btn(Material.LIME_STAINED_GLASS_PANE, "&a➕ 增加"));
        // 总价 + 确认
        inv.setItem(40, label(Material.GOLD_INGOT,
                "&e总价: " + (st.pay.equals("money") ? "&6" + fmt(total) + " 金币" : "&d" + fmt(total) + " 点券"),
                "&7" + strip(icon.hasItemMeta() ? icon.getItemMeta().getDisplayName() : "物品")
                        + " &7× &f" + st.amount));
        inv.setItem(45, btn(Material.OAK_DOOR, "&e&l◀ 返回"));
        inv.setItem(49, btn(Material.EMERALD_BLOCK, "&a&l✔ 确认购买"));
        inv.setItem(53, btn(Material.RED_STAINED_GLASS_PANE, "&c✖ 关闭"));
        p.openInventory(inv);
    }

    private void confirmBuy(Player p) {
        BuyState st = buyState.get(p.getUniqueId());
        if (st == null) return;
        double[] base = priceFor(st);
        if (base == null) { p.sendMessage(ChatColor.RED + "✘ 未配置价格！"); return; }
        double unit = (st.pay.equals("points") ? base[1] : base[0]) * st.lv;
        double total = unit * st.amount;
        if (st.pay.equals("money")) {
            if (!EconomyHook.isVaultReady()) { p.sendMessage(ChatColor.RED + "✘ Vault 金币不可用！"); return; }
            if (EconomyHook.getMoney(p) < total) { p.sendMessage(ChatColor.RED + "✘ 金币不足！需要 " + ChatColor.GOLD + fmt(total)); return; }
            EconomyHook.withdrawMoney(p, total);
        } else {
            if (!EconomyHook.isPlayerPointsReady()) { p.sendMessage(ChatColor.RED + "✘ PlayerPoints 点券不可用！"); return; }
            if (EconomyHook.getPoints(p) < total) { p.sendMessage(ChatColor.RED + "✘ 点券不足！需要 " + ChatColor.LIGHT_PURPLE + fmt(total)); return; }
            EconomyHook.takePoints(p, (int) total);
        }
        ItemStack item = itemFor(st);
        int left = st.amount;
        while (left > 0) {
            ItemStack give = item.clone();
            int n = Math.min(left, item.getMaxStackSize());
            give.setAmount(n);
            p.getInventory().addItem(give).values().forEach(d -> p.getWorld().dropItemNaturally(p.getLocation(), d));
            left -= n;
        }
        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&e&l✦ &f购买成功 &6" + strip(item.getItemMeta().getDisplayName()) + " &7× " + st.amount
                + " &7(" + (st.pay.equals("money") ? "金币" : "点券") + ")"));
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        // 返回商品页
        renderBuy(p);
    }

    private ItemStack itemFor(BuyState st) {
        switch (st.kind) {
            case "gem": { EliteConfig.CustomDrop g = findGem(st.id); return g != null ? g.build(st.lv) : new ItemStack(Material.BARRIER); }
            case "rune": return EliteRuneFactory.createRune(st.id, st.lv, plugin.getMessages());
            case "utility": return utilityItem(st.id);
            default: return new ItemStack(Material.BARRIER);
        }
    }

    private double[] priceFor(BuyState st) {
        EliteConfig cfg = plugin.getEliteConfig();
        return switch (st.kind) {
            case "gem" -> cfg.getGemShopPrice(st.id);
            case "rune" -> cfg.getRuneShopPrice(st.id);
            case "utility" -> cfg.getUtilityPrice(st.id);
            default -> null;
        };
    }

    // ==================== 使用说明 / 我的状态 ====================

    private void openInfo(Player p) {
        page.put(p.getUniqueId(), "info");
        Inventory inv = base("&e使用说明");
        gradFrame(inv, Material.YELLOW_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.PAPER, "&e&l📖 使用说明", ""));
        String[] lines = {
                "&b✦ 宝石：&7铁砧「装备+宝石」淬炼；「宝石+宝石」同级合成高一级",
                "&b✦ 符文：&7铁砧「装备+符文」镶嵌；「符文+符文」同级合成高一级",
                "&b✦ 保护符：&7放在背包，淬炼失败防降级",
                "&b✦ 拆卸器：&7铁砧「已淬炼装备+拆卸器」拆下所有宝石/符文",
                "&b✦ Lv.10 满级 &7不可再合成",
                "&b✦ 商城：&7金币 / 点券 二选一支付",
        };
        for (int i = 0; i < lines.length; i++) inv.setItem(10 + i * 2, label(Material.BOOK, lines[i], ""));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    private void openStats(Player p) {
        page.put(p.getUniqueId(), "stats");
        Inventory inv = base("&6我的状态");
        gradFrame(inv, Material.YELLOW_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.GOLD_INGOT, "&6&l📊 我的状态", ""));
        inv.setItem(20, label(Material.GOLD_INGOT,
                (EconomyHook.isVaultReady() ? "&6金币 &7: &f" + fmt(EconomyHook.getMoney(p)) : "&7金币插件未启用"), ""));
        inv.setItem(22, label(Material.LIGHT_BLUE_DYE,
                (EconomyHook.isPlayerPointsReady() ? "&d点券 &7: &f" + EconomyHook.getPoints(p) : "&7点券插件未启用"), ""));
        inv.setItem(24, label(Material.BOOK, "&7提示", "&7金币不足时购买会自动用点券"));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    // ==================== 管理（二级菜单） ====================

    private void openAdmin(Player p) {
        page.put(p.getUniqueId(), "admin");
        Inventory inv = base("&c管理");
        gradFrame(inv, Material.RED_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.BARRIER, "&c&l🧰 管理", "&7管理员功能（免费发放）"));
        inv.setItem(19, btn(Material.DIAMOND, "&b&l💎 发放宝石", "&7选择宝石与等级免费发放"));
        inv.setItem(22, btn(Material.ENCHANTED_BOOK, "&d&l🏷 发放符文", "&7选择符文与等级免费发放"));
        inv.setItem(25, btn(Material.EMERALD, "&a&l🛡 发放保护符", "&7免费发放 1 个"));
        inv.setItem(29, btn(Material.SHEARS, "&6&l🔧 发放拆卸器", "&7免费发放 1 个"));
        inv.setItem(31, btn(Material.ZOMBIE_HEAD, "&a&l🎯 生成精英", "&7指定生物/职业/等级生成"));
        inv.setItem(33, btn(Material.BARRIER, "&c&l🧹 清除精英", "&7清除附近 50 格精英与装饰物"));
        inv.setItem(40, btn(Material.REDSTONE, "&e&l⚙ 配置设置", "&7调整生成概率/开关等配置"));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    /** 管理-宝石列表（横排）。 */
    private void openAGems(Player p, int idx) {
        List<ShopItem> items = gemShopItems(false); // 只宝石
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) ITEM_PER_PAGE));
        idx = Math.max(0, Math.min(pages - 1, idx));
        page.put(p.getUniqueId(), "agems:" + idx);
        Inventory inv = base("&b发放宝石");
        gradFrame(inv, Material.BLUE_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.DIAMOND, "&b&l💎 发放宝石", "&7点击选择宝石"));
        int from = idx * ITEM_PER_PAGE;
        for (int i = 0; i < ITEM_PER_PAGE; i++) {
            if (from + i >= items.size()) break;
            inv.setItem(19 + i, items.get(from + i).icon);
        }
        controls(inv, idx > 0, idx < pages - 1);
        p.openInventory(inv);
    }

    private void openAGemLv(Player p, String gemId) {
        EliteConfig.CustomDrop gem = findGem(gemId);
        if (gem == null) return;
        page.put(p.getUniqueId(), "agemlv:" + gemId);
        Inventory inv = base("&b发放 " + strip(gem.name));
        gradFrame(inv, Material.CYAN_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.DIAMOND, "&b&l" + strip(gem.name), "&7点击等级免费发放"));
        for (int lv = 1; lv <= 10; lv++) {
            ItemStack it = gem.build(lv);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add(ChatColor.translateAlternateColorCodes('&', "&a点击发放"));
                meta.setLore(lore); it.setItemMeta(meta);
            }
            inv.setItem(9 + lv - 1, it);
        }
        controls(inv, false, false);
        p.openInventory(inv);
    }

    /** 管理-符文列表。 */
    private void openARunes(Player p, int idx) {
        int pages = Math.max(1, (int) Math.ceil(RUNE_TYPES.size() / 7.0));
        idx = Math.max(0, Math.min(pages - 1, idx));
        page.put(p.getUniqueId(), "arunes:" + idx);
        Inventory inv = base("&d发放符文");
        gradFrame(inv, Material.PURPLE_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.ENCHANTED_BOOK, "&d&l🏷 发放符文", "&7点击选择符文"));
        int from = idx * 7;
        for (int i = 0; i < 7; i++) {
            int ti = from + i;
            if (ti >= RUNE_TYPES.size()) break;
            ItemStack rune = EliteRuneFactory.createRune(RUNE_TYPES.get(ti), 1, plugin.getMessages());
            ItemMeta meta = rune.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add(ChatColor.translateAlternateColorCodes('&', "&a点击选择等级"));
                meta.setLore(lore); rune.setItemMeta(meta);
            }
            inv.setItem(19 + i, rune);
        }
        controls(inv, idx > 0, idx < pages - 1);
        p.openInventory(inv);
    }

    private void openARuneLv(Player p, String type) {
        page.put(p.getUniqueId(), "arunelv:" + type);
        Inventory inv = base("&d发放 " + EliteRuneFactory.TYPES.get(type).coloredName);
        gradFrame(inv, Material.PURPLE_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.ENCHANTED_BOOK, "&d&l" + EliteRuneFactory.TYPES.get(type).coloredName, "&7点击等级免费发放"));
        for (int lv = 1; lv <= 10; lv++) {
            ItemStack rune = EliteRuneFactory.createRune(type, lv, plugin.getMessages());
            ItemMeta meta = rune.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add(ChatColor.translateAlternateColorCodes('&', "&a点击发放"));
                meta.setLore(lore); rune.setItemMeta(meta);
            }
            inv.setItem(9 + lv - 1, rune);
        }
        controls(inv, false, false);
        p.openInventory(inv);
    }

    // ==================== 点击处理 ====================

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getView().getTopInventory() == null) return;
        if (!ChatColor.stripColor(event.getView().getTitle()).startsWith(strip(TITLE))) return;
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= 54) return;
        String cur = page.getOrDefault(p.getUniqueId(), "main");

        // 三级购买页：45 返回 / 49 确认 / 53 关闭 + 支付方式 / 数量
        if (cur.equals("buy")) {
            if (raw == 45) { back(p, cur); return; }
            if (raw == 49) { confirmBuy(p); return; }
            if (raw == 53) { p.closeInventory(); return; }
            BuyState st = buyState.get(p.getUniqueId());
            if (st == null) return;
            if (raw == 20) { st.pay = "money"; buyState.put(p.getUniqueId(), st); renderBuy(p); }
            else if (raw == 24) { st.pay = "points"; buyState.put(p.getUniqueId(), st); renderBuy(p); }
            else if (raw == 29) { st.amount = Math.max(1, st.amount - 1); buyState.put(p.getUniqueId(), st); renderBuy(p); }
            else if (raw == 33) { st.amount = Math.min(64, st.amount + 1); buyState.put(p.getUniqueId(), st); renderBuy(p); }
            return;
        }

        // 通用控制行
        if (raw == 45) { onPrev(p, cur); return; }
        if (raw == 48) { back(p, cur); return; }
        if (raw == 49) { p.closeInventory(); return; }
        if (raw == 53) { onNext(p, cur); return; }

        switch (cur) {
            case "main" -> onMainClick(p, raw);
            case "admin" -> onAdminClick(p, raw);
            case "info", "stats" -> { /* 只读 */ }
            default -> {
                if (cur.startsWith("gems:")) onGemShopClick(p, cur, raw);
                else if (cur.startsWith("gemlv:")) onGemLvClick(p, cur, raw);
                else if (cur.startsWith("rune:")) onRuneClick(p, cur, raw);
                else if (cur.startsWith("agems:")) onAGemsClick(p, cur, raw);
                else if (cur.startsWith("agemlv:")) onAGemLvClick(p, cur, raw);
                else if (cur.startsWith("arunes:")) onARunesClick(p, cur, raw);
                else if (cur.startsWith("arunelv:")) onARuneLvClick(p, cur, raw);
                else if (cur.startsWith("spawn:")) onSpawnListClick(p, cur, raw);
                else if (cur.startsWith("spawncls:")) onSpawnClsClick(p, cur, raw);
                else if (cur.startsWith("spawnlv:")) onSpawnLvClick(p, cur, raw);
                else if (cur.equals("config")) onConfigClick(p, raw);
            }
        }
    }

    private void onMainClick(Player p, int raw) {
        if (raw == 19) openGemShop(p, 0);
        else if (raw == 22) openRuneShop(p, 0);
        else if (raw == 25) openStats(p);
        else if (raw == 29) openInfo(p);
        else if (raw == 31 && p.hasPermission("elitemobs.admin")) openAdmin(p);
    }

    private void onAdminClick(Player p, int raw) {
        if (!p.hasPermission("elitemobs.admin")) return;
        if (raw == 19) openAGems(p, 0);
        else if (raw == 22) openARunes(p, 0);
        else if (raw == 25) give(p, EliteEssenceFactory.createProtectionCharm(plugin.getMessages()), 1, "保护符");
        else if (raw == 29) give(p, EliteEssenceFactory.createGemRemover(plugin.getMessages()), 1, "拆卸器");
        else if (raw == 31) openSpawnList(p, 0);
        else if (raw == 33) clearNearby(p);
        else if (raw == 40) openConfig(p);
    }

    private void onGemShopClick(Player p, String cur, int raw) {
        int idx = Integer.parseInt(cur.split(":")[1]);
        List<ShopItem> items = gemShopItems();
        int from = idx * ITEM_PER_PAGE;
        int slot = raw - 19;
        if (slot < 0 || slot >= ITEM_PER_PAGE) return;
        int gi = from + slot;
        if (gi >= items.size()) return;
        ShopItem si = items.get(gi);
        if (si.kind.equals("gem")) openGemLv(p, si.id);
        else openBuy(p, "utility", si.id, 1, idx);
    }

    private void onGemLvClick(Player p, String cur, int raw) {
        if (raw < 9 || raw > 18) return;
        String gemId = cur.substring("gemlv:".length());
        int lv = raw - 9 + 1;
        openBuy(p, "gem", gemId, lv, 0);
    }

    private void onRuneClick(Player p, String cur, int raw) {
        int idx = Integer.parseInt(cur.split(":")[1]);
        for (int i = 0; i < RUNE_PER_PAGE; i++) {
            int ti = idx * RUNE_PER_PAGE + i;
            if (ti >= RUNE_TYPES.size()) break;
            int baseSlot = 9 + i * 10;
            if (raw >= baseSlot && raw <= baseSlot + 9) {
                int lv = raw - baseSlot + 1;
                openBuy(p, "rune", RUNE_TYPES.get(ti), lv, idx);
                return;
            }
        }
    }

    private void onAGemsClick(Player p, String cur, int raw) {
        int idx = Integer.parseInt(cur.split(":")[1]);
        List<ShopItem> items = gemShopItems(false);
        int slot = raw - 19;
        int gi = idx * ITEM_PER_PAGE + slot;
        if (slot >= 0 && slot < ITEM_PER_PAGE && gi < items.size()) {
            openAGemLv(p, items.get(gi).id);
        }
    }

    private void onAGemLvClick(Player p, String cur, int raw) {
        if (raw < 9 || raw > 18) return;
        EliteConfig.CustomDrop gem = findGem(cur.substring("agemlv:".length()));
        if (gem != null) give(p, gem.build(raw - 9 + 1), 1, strip(gem.name));
    }

    private void onARunesClick(Player p, String cur, int raw) {
        int idx = Integer.parseInt(cur.split(":")[1]);
        int slot = raw - 19;
        int ti = idx * 7 + slot;
        if (slot >= 0 && slot < 7 && ti < RUNE_TYPES.size()) openARuneLv(p, RUNE_TYPES.get(ti));
    }

    private void onARuneLvClick(Player p, String cur, int raw) {
        if (raw < 9 || raw > 18) return;
        String type = cur.substring("arunelv:".length());
        give(p, EliteRuneFactory.createRune(type, raw - 9 + 1, plugin.getMessages()), 1,
                EliteRuneFactory.TYPES.get(type).coloredName);
    }

    private void give(Player p, ItemStack item, int amount, String name) {
        item.setAmount(amount);
        p.getInventory().addItem(item).values()
            .forEach(d -> p.getWorld().dropItemNaturally(p.getLocation(), d));
        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&a✔ 已发放 &f" + name + " &7× " + amount));
    }

    // ==================== 管理：生成精英 / 清除 / 配置设置 ====================

    /** 生成精英：生物类型选择（分页横排）。 */
    private void openSpawnList(Player p, int idx) {
        List<EntityType> types = new ArrayList<>(plugin.getEliteConfig().getEnabledMobTypes());
        int pages = Math.max(1, (int) Math.ceil(types.size() / (double) SPAWN_PER_PAGE));
        idx = Math.max(0, Math.min(pages - 1, idx));
        page.put(p.getUniqueId(), "spawn:" + idx);
        Inventory inv = base("&a生成精英");
        gradFrame(inv, Material.GREEN_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.ZOMBIE_HEAD, "&a&l🎯 生成精英", "&7选择生物类型（一页放满）"));
        int from = idx * SPAWN_PER_PAGE;
        int[] rowStarts = {10, 19, 28};
        for (int i = 0; i < SPAWN_PER_PAGE; i++) {
            if (from + i >= types.size()) break;
            EntityType t = types.get(from + i);
            int slot = rowStarts[i / 7] + (i % 7);
            inv.setItem(slot, label(Material.NAME_TAG, "&f" + formatName(t.name()), "&7点击选择职业"));
        }
        inv.setItem(37, label(Material.BOOK, "&7页 " + (idx + 1) + "/" + pages, ""));
        controls(inv, idx > 0, idx < pages - 1);
        p.openInventory(inv);
    }

    /** 生成精英：职业选择。 */
    private void openSpawnCls(Player p, String type) {
        page.put(p.getUniqueId(), "spawncls:" + type);
        Inventory inv = base("&a选择职业");
        gradFrame(inv, Material.GREEN_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.ZOMBIE_HEAD, "&a&l选择职业", "&7生物: &f" + formatName(type)));
        inv.setItem(20, btn(Material.SHIELD, "&b🛡 坦克"));
        inv.setItem(22, btn(Material.IRON_SWORD, "&e🗡 刺客"));
        inv.setItem(24, btn(Material.BOOK, "&d🔮 法师"));
        inv.setItem(29, btn(Material.ENDER_EYE, "&5👹 召唤师"));
        inv.setItem(33, btn(Material.NAME_TAG, "&f随机职业"));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    /** 生成精英：等级选择（Lv.1-20 + 随机）。 */
    private void openSpawnLv(Player p, String type, String cls) {
        page.put(p.getUniqueId(), "spawnlv:" + type + ":" + cls);
        Inventory inv = base("&a选择等级");
        gradFrame(inv, Material.GREEN_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.ZOMBIE_HEAD, "&a&l选择等级",
                "&7" + formatName(type) + " · 职业: " + cls));
        // Lv.1-20 用中间 3 行排列（10-16 / 19-25 / 28-33），避开侧边玻璃
        int[] rowStarts = {10, 19, 28};
        for (int i = 0; i < 20; i++) {
            int slot = rowStarts[i / 7] + (i % 7);
            inv.setItem(slot, btn(Material.NAME_TAG, "&fLv." + (i + 1)));
        }
        inv.setItem(40, btn(Material.EXPERIENCE_BOTTLE, "&f随机等级"));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    /** 清除附近 50 格精英与装饰物。 */
    private void clearNearby(Player p) {
        int cleared = 0, decor = 0;
        NamespacedKey decorKey = new NamespacedKey("elitemobs", "decor_owner");
        NamespacedKey bossDecorKey = new NamespacedKey("elitemobs", "boss_decor");
        for (Entity e : p.getWorld().getNearbyEntities(p.getLocation(), 50, 50, 50)) {
            if (e instanceof LivingEntity le && EliteMobManager.isElite(le)) { le.remove(); cleared++; }
            else if (e instanceof org.bukkit.entity.Item it
                    && (it.getPersistentDataContainer().has(decorKey, PersistentDataType.STRING)
                        || it.getPersistentDataContainer().has(bossDecorKey, PersistentDataType.BOOLEAN))) {
                it.remove(); decor++;
            }
        }
        p.sendMessage(ChatColor.GREEN + "✔ 已清除附近精英 " + cleared + " 只"
                + (decor > 0 ? "，装饰物 " + decor + " 个" : ""));
        openAdmin(p);
    }

    /** 配置设置页。 */
    private void openConfig(Player p) {
        page.put(p.getUniqueId(), "config");
        Inventory inv = base("&e配置设置");
        gradFrame(inv, Material.REDSTONE_BLOCK, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, label(Material.REDSTONE, "&e&l⚙ 配置设置", "&7点击调整，即时保存到 config.yml"));
        EliteConfig cfg = plugin.getEliteConfig();
        // 行1：精英总开关
        inv.setItem(9, label(Material.LEVER, "&f精英总开关", "&7开启/关闭精英生成"));
        inv.setItem(11, btn(Material.LIME_STAINED_GLASS_PANE, cfg.isEnabled() ? "&a✔ 开" : "&c✖ 关", "&7点击切换"));
        inv.setItem(13, label(Material.BOOK, cfg.isEnabled() ? "&a已开启" : "&c已关闭", ""));
        // 行2：生成概率
        inv.setItem(18, label(Material.NAME_TAG, "&f生成概率", "&7敌对生物成为精英的几率"));
        inv.setItem(20, btn(Material.RED_STAINED_GLASS_PANE, "&c-1%", "&7减少 1%"));
        inv.setItem(22, label(Material.BOOK, String.format("&e%.2f%%", cfg.getEliteSpawnChance() * 100), ""));
        inv.setItem(24, btn(Material.LIME_STAINED_GLASS_PANE, "&a+1%", "&7增加 1%"));
        // 行3：夜间强化
        inv.setItem(27, label(Material.CLOCK, "&f夜间强化", "&7夜间精英更危险"));
        inv.setItem(29, btn(Material.LIME_STAINED_GLASS_PANE, cfg.isNightEnhancementEnabled() ? "&a✔ 开" : "&c✖ 关", "&7点击切换"));
        inv.setItem(31, label(Material.BOOK, cfg.isNightEnhancementEnabled() ? "&a已开启" : "&c已关闭", ""));
        // 行4：词缀概率
        inv.setItem(36, label(Material.ENCHANTED_BOOK, "&f词缀概率", "&7精英获得词缀的几率"));
        inv.setItem(38, btn(Material.RED_STAINED_GLASS_PANE, "&c-5%", "&7减少 5%"));
        inv.setItem(40, label(Material.BOOK, String.format("&e%.0f%%", cfg.getAffixChance() * 100), ""));
        inv.setItem(42, btn(Material.LIME_STAINED_GLASS_PANE, "&a+5%", "&7增加 5%"));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    private void onSpawnListClick(Player p, String cur, int raw) {
        int idx = Integer.parseInt(cur.split(":")[1]);
        List<EntityType> types = new ArrayList<>(plugin.getEliteConfig().getEnabledMobTypes());
        int gi = idx * SPAWN_PER_PAGE + spawnSlotToIndex(raw);
        if (spawnSlotToIndex(raw) >= 0 && gi < types.size()) {
            openSpawnCls(p, types.get(gi).name());
        }
    }

    /** 生成精英生物页 slot → 页内索引（对应 10-16 / 19-25 / 28-34 布局）。 */
    private static int spawnSlotToIndex(int raw) {
        int[] rowStarts = {10, 19, 28};
        for (int row = 0; row < rowStarts.length; row++) {
            int col = raw - rowStarts[row];
            if (col >= 0 && col < 7) return row * 7 + col;
        }
        return -1;
    }

    private void onSpawnClsClick(Player p, String cur, int raw) {
        String type = cur.substring("spawncls:".length());
        String cls = null;
        if (raw == 20) cls = "TANK";
        else if (raw == 22) cls = "ASSASSIN";
        else if (raw == 24) cls = "MAGE";
        else if (raw == 29) cls = "SUMMONER";
        else if (raw == 33) cls = "RANDOM";
        else return;
        openSpawnLv(p, type, cls);
    }

    private void onSpawnLvClick(Player p, String cur, int raw) {
        String[] parts = cur.split(":");
        String type = parts[1];
        String cls = parts[2];
        EliteClass ec = null;
        if (!cls.equals("RANDOM")) {
            try { ec = EliteClass.valueOf(cls); } catch (IllegalArgumentException ignored) {}
        }
        int lv = -1;
        if (raw == 40) lv = -1;
        else {
            lv = lvForSlot(raw);
            if (lv == -1) return;
        }
        try {
            spawnEliteAt(p, EntityType.valueOf(type), lv, ec);
        } catch (IllegalArgumentException ex) {
            p.sendMessage(ChatColor.RED + "✘ 未知生物类型!");
        }
        openSpawnLv(p, type, cls);
    }

    /** 生成精英等级页 slot → 等级（对应 10-16 / 19-25 / 28-33 布局）。 */
    private static int lvForSlot(int raw) {
        int[] rowStarts = {10, 19, 28};
        for (int row = 0; row < rowStarts.length; row++) {
            int col = raw - rowStarts[row];
            if (col >= 0 && col < 7) return row * 7 + col + 1;
        }
        return -1;
    }

    private void spawnEliteAt(Player p, EntityType type, int lv, EliteClass cls) {
        Location loc = p.getLocation().clone();
        Entity e = p.getWorld().spawnEntity(loc, type);
        if (e instanceof LivingEntity le) plugin.getMobManager().makeElite(le, lv, cls);
        p.sendMessage(ChatColor.GREEN + "✔ 已生成精英 " + formatName(type.name())
                + ChatColor.GRAY + " [Lv." + (lv > 0 ? lv : "随机") + "]"
                + (cls != null ? ChatColor.AQUA + " " + cls.name() : ""));
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
    }

    private void onConfigClick(Player p, int raw) {
        EliteConfig cfg = plugin.getEliteConfig();
        boolean changed = true;
        switch (raw) {
            case 9, 11 -> cfg.setEnabled(!cfg.isEnabled());
            case 20 -> cfg.setEliteSpawnChance(cfg.getEliteSpawnChance() - 0.01);
            case 24 -> cfg.setEliteSpawnChance(cfg.getEliteSpawnChance() + 0.01);
            case 27, 29 -> cfg.setNightEnhancementEnabled(!cfg.isNightEnhancementEnabled());
            case 38 -> cfg.setAffixChance(cfg.getAffixChance() - 0.05);
            case 42 -> cfg.setAffixChance(cfg.getAffixChance() + 0.05);
            default -> changed = false;
        }
        if (changed) {
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
            openConfig(p);
        }
    }

    private static String formatName(String s) {
        return com.clawx.elitemobs.utils.StringUtil.formatName(s);
    }

    // ==================== 翻页 / 返回 ====================

    private void onPrev(Player p, String cur) {
        if (cur.startsWith("gems:")) openGemShop(p, Integer.parseInt(cur.split(":")[1]) - 1);
        else if (cur.startsWith("rune:")) openRuneShop(p, Integer.parseInt(cur.split(":")[1]) - 1);
        else if (cur.startsWith("agems:")) openAGems(p, Integer.parseInt(cur.split(":")[1]) - 1);
        else if (cur.startsWith("arunes:")) openARunes(p, Integer.parseInt(cur.split(":")[1]) - 1);
        else if (cur.startsWith("spawn:")) openSpawnList(p, Integer.parseInt(cur.split(":")[1]) - 1);
        else back(p, cur);
    }

    private void onNext(Player p, String cur) {
        if (cur.startsWith("gems:")) openGemShop(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else if (cur.startsWith("rune:")) openRuneShop(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else if (cur.startsWith("agems:")) openAGems(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else if (cur.startsWith("arunes:")) openARunes(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else if (cur.startsWith("spawn:")) openSpawnList(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else back(p, cur);
    }

    private void back(Player p, String cur) {
        if (cur.startsWith("gems:")) openMain(p);
        else if (cur.startsWith("gemlv:")) openGemShop(p, 0);
        else if (cur.startsWith("rune:")) openMain(p);
        else if (cur.equals("buy")) {
            BuyState st = buyState.get(p.getUniqueId());
            if (st != null) {
                if (st.kind.equals("gem")) openGemLv(p, st.id);
                else if (st.kind.equals("rune")) openRuneShop(p, st.backIdx);
                else openGemShop(p, st.backIdx);
            } else openMain(p);
        }
        else if (cur.startsWith("agems:")) openAdmin(p);
        else if (cur.startsWith("agemlv:")) openAGems(p, 0);
        else if (cur.startsWith("arunes:")) openAdmin(p);
        else if (cur.startsWith("arunelv:")) openARunes(p, 0);
        else if (cur.startsWith("spawn:")) openAdmin(p);
        else if (cur.startsWith("spawncls:")) openSpawnList(p, 0);
        else if (cur.startsWith("spawnlv:")) openSpawnCls(p, cur.split(":")[1]);
        else if (cur.equals("config")) openAdmin(p);
        else if (cur.equals("info") || cur.equals("stats") || cur.equals("admin")) openMain(p);
        else openMain(p);
    }

    // ==================== 数据 ====================

    static class ShopItem {
        String kind; String id; ItemStack icon;
        ShopItem(String kind, String id, ItemStack icon) { this.kind = kind; this.id = id; this.icon = icon; }
    }

    /** 宝石商城商品：10 宝石 + 保护符 + 拆卸器。 */
    private List<ShopItem> gemShopItems() { return gemShopItems(true); }

    private List<ShopItem> gemShopItems(boolean withUtility) {
        List<ShopItem> list = new ArrayList<>();
        for (EliteConfig.CustomDrop g : allGems()) {
            ItemStack icon = g.build(1);
            list.add(new ShopItem("gem", g.id,
                    priced(icon, plugin.getEliteConfig().getGemShopPrice(g.id), 1, "&e✦ 点击选购等级")));
        }
        if (withUtility) {
            list.add(new ShopItem("utility", "PROTECTION-CHARM",
                    priced(EliteEssenceFactory.createProtectionCharm(plugin.getMessages()),
                            plugin.getEliteConfig().getUtilityPrice("protection-charm"), 1)));
            list.add(new ShopItem("utility", "GEM-REMOVER",
                    priced(EliteEssenceFactory.createGemRemover(plugin.getMessages()),
                            plugin.getEliteConfig().getUtilityPrice("gem-remover"), 1)));
        }
        return list;
    }

    private ItemStack utilityItem(String key) {
        return key.equalsIgnoreCase("PROTECTION-CHARM")
                ? EliteEssenceFactory.createProtectionCharm(plugin.getMessages())
                : EliteEssenceFactory.createGemRemover(plugin.getMessages());
    }

    private List<EliteConfig.CustomDrop> allGems() {
        return plugin.getEliteConfig().getCustomDrops().stream()
                .filter(d -> d.effect != null && !d.effect.isEmpty())
                .collect(Collectors.toList());
    }

    private EliteConfig.CustomDrop findGem(String id) {
        for (EliteConfig.CustomDrop d : allGems()) {
            if (d.id != null && d.id.equalsIgnoreCase(id)) return d;
        }
        return null;
    }

    /** 给商品追加价格 lore。 */
    private ItemStack priced(ItemStack item, double[] base, int lv) {
        return priced(item, base, lv, "&e✦ 点击购买");
    }

    private ItemStack priced(ItemStack item, double[] base, int lv, String tip) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        lore.add(" ");
        lore.add(priceLine(base, lv));
        lore.add(ChatColor.translateAlternateColorCodes('&', tip));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String priceLine(double[] base, int lv) {
        if (base == null) return ChatColor.GRAY + "未配置价格";
        int m = Math.max(1, lv);
        double money = base[0] * m;
        int points = (int) (base[1] * m);
        String line = ChatColor.DARK_GRAY + "价格: ";
        if (money > 0) line += ChatColor.GOLD + fmt(money) + " 金币";
        if (points > 0) line += (money > 0 ? " &7或 " : "") + ChatColor.LIGHT_PURPLE + points + " 点券";
        if (money <= 0 && points <= 0) line += "免费";
        return ChatColor.translateAlternateColorCodes('&', line);
    }

    private ItemStack payBtn(String name, boolean selected, double price) {
        Material mat = selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        return label(mat, (selected ? "&a● " : "&7○ ") + name + (price > 0 ? " &7(" + fmt(price) + ")" : ""),
                selected ? "&7当前支付方式" : "&7点击选择");
    }

    // ==================== 工具 ====================

    private void controls(Inventory inv, boolean canPrev, boolean canNext) {
        inv.setItem(45, btn(canPrev ? Material.ARROW : Material.GRAY_DYE,
                canPrev ? "&a◀ 上一页" : "&8(无上一页)"));
        inv.setItem(48, btn(Material.OAK_DOOR, "&e&l🏠 返回"));
        inv.setItem(49, btn(Material.RED_STAINED_GLASS_PANE, "&c✖ 关闭"));
        inv.setItem(53, btn(canNext ? Material.ARROW : Material.GRAY_DYE,
                canNext ? "&a下一页 ▶" : "&8(无下一页)"));
    }

    private Inventory base(String suffix) {
        String title = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + TITLE + ChatColor.RESET;
        if (suffix != null && !suffix.isEmpty()) title += " · " + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', suffix));
        return Bukkit.createInventory(null, 54, title);
    }

    /** 渐变边框（顶部 + 底部 + 侧边），比单色更美观。 */
    private void gradFrame(Inventory inv) {
        gradFrame(inv, Material.PURPLE_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
    }

    private void gradFrame(Inventory inv, Material top, Material side) {
        ItemStack t = pane(top), s = pane(side);
        for (int i = 0; i < 9; i++) inv.setItem(i, t.clone());
        for (int i = 45; i < 54; i++) inv.setItem(i, s.clone());
        int[] cols = {9, 17, 18, 26, 27, 35, 36, 44};
        for (int c : cols) inv.setItem(c, s.clone());
    }

    private static ItemStack pane(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) { meta.setDisplayName(" "); it.setItemMeta(meta); }
        return it;
    }

    private static ItemStack decor(Material mat) {
        return label(mat, "&8✦", "");
    }

    private static ItemStack btn(Material mat, String name, String... loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (loreLines.length > 0) {
                List<String> lore = new ArrayList<>();
                for (String l : loreLines) lore.add(ChatColor.translateAlternateColorCodes('&', l));
                meta.setLore(lore);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack label(Material mat, String name, String... loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (loreLines.length > 0) {
                List<String> lore = new ArrayList<>();
                for (String l : loreLines) {
                    if (l != null && !l.isEmpty()) lore.add(ChatColor.translateAlternateColorCodes('&', l));
                }
                meta.setLore(lore);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static String strip(String s) {
        return s == null ? "" : ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s));
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }
}
