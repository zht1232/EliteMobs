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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
    /** 宝石商城：每页横排 7 项（10 宝石 + 拆卸器 = 11 项 → 2 页）。 */
    private static final int ITEM_PER_PAGE = 7;
    /** 生成精英：每页 3 行 × 7 个 = 21 个生物类型，放满一页减少翻页。 */
    private static final int SPAWN_PER_PAGE = 21;

    // ==================== 装饰主题（彩色边框 + 白色磨砂内部，简洁不花哨） ====================
    private static final Theme T_MAIN = new Theme(Material.PURPLE_STAINED_GLASS_PANE);
    private static final Theme T_GEM = new Theme(Material.BLUE_STAINED_GLASS_PANE);
    private static final Theme T_GEM_LV = new Theme(Material.CYAN_STAINED_GLASS_PANE);
    private static final Theme T_RUNE = new Theme(Material.PURPLE_STAINED_GLASS_PANE);
    private static final Theme T_BUY = new Theme(Material.ORANGE_STAINED_GLASS_PANE);
    private static final Theme T_INFO = new Theme(Material.YELLOW_STAINED_GLASS_PANE);
    private static final Theme T_STATS = new Theme(Material.YELLOW_STAINED_GLASS_PANE);
    private static final Theme T_ADMIN = new Theme(Material.RED_STAINED_GLASS_PANE);
    private static final Theme T_SPAWN = new Theme(Material.GREEN_STAINED_GLASS_PANE);
    private static final Theme T_CONFIG = new Theme(Material.RED_STAINED_GLASS_PANE);

    private final EliteMobsPlugin plugin;
    private final Map<UUID, String> page = new ConcurrentHashMap<>();
    private final Map<UUID, BuyState> buyState = new ConcurrentHashMap<>();
    private final Random rng = new Random();
    /** 每日符文库存：当天日期 + 随机符文列表。 */
    private LocalDate shopDate = null;
    private final List<DailyRune> dailyRunes = new ArrayList<>();
    /** 宝石每日每人限购：记录购买日期 + 当日已购数量（跨天重置）。 */
    private final Map<UUID, String> gemBuyDay = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> gemBoughtToday = new ConcurrentHashMap<>();

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
        int dailyRuneIndex = -1;  // 每日符文库存索引（-1 = 非每日符文）
    }

    /** 每日上架条目（符文或保护符）。 */
    static class DailyRune {
        String kind;   // "rune" 符文 / "protection" 保护符
        String type;   // 符文类型 或 "PROTECTION"
        int level;
        int remaining;
        DailyRune(String kind, String type, int level, int remaining) {
            this.kind = kind; this.type = type; this.level = level; this.remaining = remaining;
        }
    }

    /** 跨天刷新每日上架：从 6 种符文 + 保护符中随机 3 种，符文随机等级 1-5、数量 1-3。 */
    private void refreshDailyRunes() {
        LocalDate today = LocalDate.now();
        if (shopDate != null && shopDate.equals(today)) return;
        shopDate = today;
        dailyRunes.clear();
        List<String> pool = new ArrayList<>(RUNE_TYPES);
        pool.add("PROTECTION");   // 保护符不再直接售卖，改为每日随机上架
        Collections.shuffle(pool, rng);
        int n = Math.min(3, pool.size());
        for (int i = 0; i < n; i++) {
            String key = pool.get(i);
            if (key.equals("PROTECTION")) {
                dailyRunes.add(new DailyRune("protection", "PROTECTION", 1, 1 + rng.nextInt(3)));
            } else {
                int level = 1 + rng.nextInt(5);   // 1-5
                int count = 1 + rng.nextInt(3);   // 1-3
                dailyRunes.add(new DailyRune("rune", key, level, count));
            }
        }
    }

    /** 宝石每日每人剩余可购数（跨天重置）。 */
    private int gemLeftToday(Player p) {
        UUID id = p.getUniqueId();
        String today = LocalDate.now().toString();
        if (!today.equals(gemBuyDay.get(id))) {
            gemBuyDay.put(id, today);
            gemBoughtToday.put(id, 0);
        }
        return Math.max(0, plugin.getEliteConfig().getGemDailyLimit() - gemBoughtToday.getOrDefault(id, 0));
    }

    /** 购买数量上限（每日符文受库存限制；宝石受每日每人限购限制）。 */
    private int maxBuyAmount(Player p, BuyState st) {
        if (st.dailyRuneIndex >= 0 && st.dailyRuneIndex < dailyRunes.size()) {
            return Math.max(1, dailyRunes.get(st.dailyRuneIndex).remaining);
        }
        if (st.kind.equals("gem")) {
            return Math.max(0, Math.min(64, gemLeftToday(p)));
        }
        return 64;
    }

    /** 等级页（10 级）slot → 等级：10-14 = Lv1-5，19-23 = Lv6-10。 */
    private static int lvForSlot10(int raw) {
        if (raw >= 10 && raw <= 14) return raw - 10 + 1;
        if (raw >= 19 && raw <= 23) return raw - 19 + 6;
        return -1;
    }

    // ==================== 主菜单 ====================

    public void openMain(Player p) {
        page.put(p.getUniqueId(), "main");
        Inventory inv = base("");
        paint(inv, T_MAIN);
        inv.setItem(4, label(Material.NETHER_STAR, "&d&l✦ 精英强化菜单 ✦",
                "&7选择功能，强化你的装备",
                "&7金币 / 点券 二选一支付"));
        // 第一行：宝石 / 符文 / 状态（居中对称）
        inv.setItem(20, btn(Material.DIAMOND, "&b&l💎 宝石商城", "&7购买淬炼宝石 / 保护符 / 拆卸器"));
        inv.setItem(22, btn(Material.ENCHANTED_BOOK, "&d&l🏷 符文商城", "&7购买生命 / 移速 / 力量等符文"));
        inv.setItem(24, btn(Material.GOLD_INGOT, "&6&l📊 我的状态", "&7查看金币 / 点券余额"));
        // 第二行：说明 / 管理
        inv.setItem(30, btn(Material.PAPER, "&e&l📖 使用说明", "&7宝石 / 符文 / 合成玩法"));
        if (p.hasPermission("elitemobs.admin")) {
            inv.setItem(32, btn(Material.BARRIER, "&c&l🧰 管理", "&7发放测试物品（管理员）"));
        }
        inv.setItem(49, btn(Material.RED_WOOL, "&c✖ 关闭"));
        p.openInventory(inv);
    }

    // ==================== 宝石商城（横排） ====================

    private void openGemShop(Player p, int idx) {
        List<ShopItem> items = gemShopItems();
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) ITEM_PER_PAGE));
        idx = Math.max(0, Math.min(pages - 1, idx));
        page.put(p.getUniqueId(), "gems:" + idx);
        Inventory inv = base("&b宝石商城");
        paint(inv, T_GEM);
        inv.setItem(4, label(Material.DIAMOND, "&b&l💎 宝石商城",
                "&7点击商品选购（越高级越贵）"));
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
        paint(inv, T_GEM_LV);
        inv.setItem(4, label(Material.DIAMOND, "&b&l" + strip(gem.name),
                "&7选择等级购买", "&7今日剩余可购 &f" + gemLeftToday(p) + " &7个"));
        for (int lv = 1; lv <= 10; lv++) {
            int slot = lv <= 5 ? 10 + (lv - 1) : 19 + (lv - 6);
            inv.setItem(slot, priced(gem.build(lv),
                    plugin.getEliteConfig().getGemShopPrice(gemId), priceFactor(lv, true)));
        }
        controls(inv, false, false);
        p.openInventory(inv);
    }

    // ==================== 符文商城 ====================

    private void openRuneShop(Player p, int idx) {
        refreshDailyRunes();
        page.put(p.getUniqueId(), "rune:0");
        Inventory inv = base("&d符文商城");
        paint(inv, T_RUNE);
        inv.setItem(4, label(Material.ENCHANTED_BOOK, "&d&l🏷 符文商城 · 每日上架",
                "&7每天随机上架 3 种（符文 / 保护符，限量）", "&7符文最高 Lv.5 · 次日刷新"));
        int[] slots = {20, 22, 24};
        for (int i = 0; i < dailyRunes.size() && i < slots.length; i++) {
            DailyRune dr = dailyRunes.get(i);
            ItemStack item;
            double[] base;
            if (dr.kind.equals("protection")) {
                item = EliteEssenceFactory.createProtectionCharm(plugin.getMessages());
                base = plugin.getEliteConfig().getUtilityPrice("protection-charm");
            } else {
                item = EliteRuneFactory.createRune(dr.type, dr.level, plugin.getMessages());
                base = plugin.getEliteConfig().getRuneShopPrice(dr.type);
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add(" ");
                lore.add(priceLine(base, dr.level));
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        dr.remaining > 0 ? "&a剩余 &f" + dr.remaining + " &a个 · 点击购买" : "&c&l今日已售罄"));
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(slots[i], item);
        }
        controls(inv, false, false);
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
        paint(inv, T_BUY);
        ItemStack icon = itemFor(st);
        double[] base = priceFor(st);
        double factor = priceFactor(st.lv, st.kind.equals("gem"));
        double unit = base != null ? Math.round((st.pay.equals("points") ? base[1] : base[0]) * factor) : 0;
        double total = unit * st.amount;
        inv.setItem(4, label(Material.CHEST, "&6&l购买确认", "&7选择支付方式与数量"));
        inv.setItem(22, priced(icon, base, factor));
        // 支付方式
        inv.setItem(20, payBtn("&6金币", st.pay.equals("money"), base == null ? 0 : Math.round(base[0] * factor),
                Material.GOLD_BLOCK, Material.YELLOW_STAINED_GLASS_PANE));
        inv.setItem(24, payBtn("&d点券", st.pay.equals("points"), base == null ? 0 : Math.round(base[1] * factor),
                Material.DIAMOND_BLOCK, Material.LIGHT_BLUE_STAINED_GLASS_PANE));
        int maxAmt = maxBuyAmount(p, st);
        // 数量
        inv.setItem(29, btn(Material.RED_WOOL, "&c➖ 减少"));
        String qtyLore = "&7点击 -/+ 调整";
        if (st.kind.equals("gem")) qtyLore = "&7今日剩余可购 &f" + gemLeftToday(p) + " &7个";
        inv.setItem(31, label(Material.BOOK, "&f数量 × " + st.amount + " &7(上限 " + maxAmt + ")", qtyLore));
        inv.setItem(33, btn(Material.LIME_WOOL, "&a➕ 增加"));
        // 总价 + 确认
        inv.setItem(40, label(Material.GOLD_INGOT,
                "&e总价: " + (st.pay.equals("money") ? "&6" + fmt(total) + " 金币" : "&d" + fmt(total) + " 点券"),
                "&7" + strip(icon.hasItemMeta() ? icon.getItemMeta().getDisplayName() : "物品")
                        + " &7× &f" + st.amount));
        inv.setItem(45, btn(Material.OAK_DOOR, "&e&l◀ 返回"));
        inv.setItem(49, st.kind.equals("gem") && gemLeftToday(p) <= 0
                ? btn(Material.GRAY_WOOL, "&8今日宝石限购已用完")
                : btn(Material.GREEN_WOOL, "&a&l✔ 确认购买"));
        inv.setItem(53, btn(Material.RED_WOOL, "&c✖ 取消"));
        p.openInventory(inv);
    }

    private void confirmBuy(Player p) {
        BuyState st = buyState.get(p.getUniqueId());
        if (st == null) return;
        double[] base = priceFor(st);
        if (base == null) { p.sendMessage(ChatColor.RED + "✘ 未配置价格！"); return; }
        // 宝石每日每人限购拦截
        if (st.kind.equals("gem")) {
            int left = gemLeftToday(p);
            if (left <= 0) {
                p.sendMessage(ChatColor.RED + "✘ 今日宝石限购已用完（每天 " + plugin.getEliteConfig().getGemDailyLimit() + " 个）！");
                return;
            }
            if (st.amount > left) {
                p.sendMessage(ChatColor.RED + "✘ 今日剩余可购 " + ChatColor.GOLD + left + ChatColor.RED + " 个！");
                st.amount = left; buyState.put(p.getUniqueId(), st); renderBuy(p); return;
            }
        }
        double unit = Math.round((st.pay.equals("points") ? base[1] : base[0]) * priceFactor(st.lv, st.kind.equals("gem")));
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
        // 宝石每日每人限购计数
        if (st.kind.equals("gem")) {
            gemBoughtToday.put(p.getUniqueId(), gemBoughtToday.getOrDefault(p.getUniqueId(), 0) + st.amount);
        }
        // 每日符文：扣库存并返回符文商城
        if (st.dailyRuneIndex >= 0 && st.dailyRuneIndex < dailyRunes.size()) {
            DailyRune dr = dailyRunes.get(st.dailyRuneIndex);
            dr.remaining = Math.max(0, dr.remaining - st.amount);
            if (dr.remaining <= 0) p.sendMessage(ChatColor.GRAY + "该符文今日已售罄。");
            openRuneShop(p, 0);
            return;
        }
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
        paint(inv, T_INFO);
        inv.setItem(4, label(Material.PAPER, "&e&l📖 使用说明", "&7铁砧淬炼 · 镶嵌 · 合成玩法"));
        Object[][] tips = {
                {Material.DIAMOND, "&b✦ 宝石", "&7铁砧「装备+宝石」淬炼", "&7「宝石+宝石」合成高一级"},
                {Material.ENCHANTED_BOOK, "&d✦ 符文", "&7铁砧「装备+符文」镶嵌", "&7「符文+符文」合成高一级"},
                {Material.GOLDEN_APPLE, "&a✦ 保护符", "&7放背包，淬炼失败防降级"},
                {Material.SHEARS, "&6✦ 拆卸器", "&7铁砧「装备+拆卸器」拆下宝石/符文"},
                {Material.EXPERIENCE_BOTTLE, "&e✦ Lv.10 满级", "&7不可再合成"},
                {Material.GOLD_INGOT, "&c✦ 商城", "&7金币 / 点券 二选一支付"},
        };
        int[] slots = {10, 12, 14, 16, 18, 20};
        for (int i = 0; i < tips.length && i < slots.length; i++) {
            Object[] t = tips[i];
            inv.setItem(slots[i], label((Material) t[0], (String) t[1], (String) t[2], t.length > 3 ? (String) t[3] : ""));
        }
        controls(inv, false, false);
        p.openInventory(inv);
    }

    private void openStats(Player p) {
        page.put(p.getUniqueId(), "stats");
        Inventory inv = base("&6我的状态");
        paint(inv, T_STATS);
        inv.setItem(4, label(Material.GOLD_INGOT, "&6&l📊 我的状态", "&7余额信息"));
        inv.setItem(20, label(Material.GOLD_INGOT,
                (EconomyHook.isVaultReady() ? "&6金币 &7: &f" + fmt(EconomyHook.getMoney(p)) : "&7金币插件未启用"),
                "&7使用金币支付"));
        inv.setItem(24, label(Material.LIGHT_BLUE_DYE,
                (EconomyHook.isPlayerPointsReady() ? "&d点券 &7: &f" + EconomyHook.getPoints(p) : "&7点券插件未启用"),
                "&7使用点券支付"));
        inv.setItem(31, label(Material.DIAMOND, "&b今日宝石剩余可购",
                "&f" + gemLeftToday(p) + " &7个（每日上限 " + plugin.getEliteConfig().getGemDailyLimit() + "）"));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    // ==================== 管理（二级菜单） ====================

    private void openAdmin(Player p) {
        page.put(p.getUniqueId(), "admin");
        Inventory inv = base("&c管理");
        paint(inv, T_ADMIN);
        inv.setItem(4, label(Material.BARRIER, "&c&l🧰 管理", "&7管理员功能（免费发放）"));
        inv.setItem(19, btn(Material.DIAMOND, "&b&l💎 发放宝石", "&7选择宝石与等级免费发放"));
        inv.setItem(22, btn(Material.ENCHANTED_BOOK, "&d&l🏷 发放符文", "&7选择符文与等级免费发放"));
        inv.setItem(25, btn(Material.EMERALD, "&a&l🛡 发放保护符", "&7免费发放 1 个"));
        inv.setItem(29, btn(Material.SHEARS, "&6&l🔧 发放拆卸器", "&7免费发放 1 个"));
        inv.setItem(31, btn(Material.ZOMBIE_HEAD, "&a&l🎯 生成精英", "&7指定生物/职业/等级生成"));
        inv.setItem(33, btn(Material.BARRIER, "&c&l🧹 清除精英", "&7清除附近 50 格精英与装饰物"));
        inv.setItem(40, btn(Material.REDSTONE, "&e&l⚙ 配置设置", "&7调整生成概率/开关等配置"));
        inv.setItem(42, btn(Material.GOLD_BLOCK, "&6&l💰 商城价格", "&7调整宝石/符文/消耗品价格"));
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
        paint(inv, T_GEM);
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
        paint(inv, T_GEM_LV);
        inv.setItem(4, label(Material.DIAMOND, "&b&l" + strip(gem.name), "&7点击等级免费发放"));
        for (int lv = 1; lv <= 10; lv++) {
            ItemStack it = gem.build(lv);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add(ChatColor.translateAlternateColorCodes('&', "&a点击发放"));
                meta.setLore(lore); it.setItemMeta(meta);
            }
            int slot = lv <= 5 ? 10 + (lv - 1) : 19 + (lv - 6);
            inv.setItem(slot, it);
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
        paint(inv, T_RUNE);
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
        paint(inv, T_RUNE);
        inv.setItem(4, label(Material.ENCHANTED_BOOK, "&d&l" + EliteRuneFactory.TYPES.get(type).coloredName, "&7点击等级免费发放"));
        for (int lv = 1; lv <= 10; lv++) {
            ItemStack rune = EliteRuneFactory.createRune(type, lv, plugin.getMessages());
            ItemMeta meta = rune.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add(ChatColor.translateAlternateColorCodes('&', "&a点击发放"));
                meta.setLore(lore); rune.setItemMeta(meta);
            }
            int slot = lv <= 5 ? 10 + (lv - 1) : 19 + (lv - 6);
            inv.setItem(slot, rune);
        }
        controls(inv, false, false);
        p.openInventory(inv);
    }

    // ==================== 管理-商城价格 ====================

    /** 管理-商城价格列表（分页横排：宝石 + 符文 + 消耗品）。 */
    private void openPriceAdmin(Player p, int idx) {
        List<PriceEntry> items = priceEntries();
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) ITEM_PER_PAGE));
        idx = Math.max(0, Math.min(pages - 1, idx));
        page.put(p.getUniqueId(), "price:" + idx);
        Inventory inv = base("&6商城价格");
        paint(inv, T_CONFIG);
        inv.setItem(4, label(Material.GOLD_INGOT, "&6&l💰 商城价格",
                "&7点击商品调整基础价（金币 / 点券）"));
        int from = idx * ITEM_PER_PAGE;
        for (int i = 0; i < ITEM_PER_PAGE; i++) {
            if (from + i >= items.size()) break;
            PriceEntry e = items.get(from + i);
            ItemStack icon = e.icon.clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add(" ");
                lore.add(priceLine(priceForEntry(e.kind, e.id), 1));
                lore.add(ChatColor.translateAlternateColorCodes('&', "&e✦ 点击调整价格"));
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            inv.setItem(19 + i, icon);
        }
        controls(inv, idx > 0, idx < pages - 1);
        p.openInventory(inv);
    }

    /** 管理-单商品价格调整页（点击 +/- 即时保存）。 */
    private void openPriceEdit(Player p, String kind, String id) {
        PriceEntry entry = null;
        for (PriceEntry e : priceEntries()) {
            if (e.kind.equals(kind) && e.id.equalsIgnoreCase(id)) { entry = e; break; }
        }
        if (entry == null) return;
        page.put(p.getUniqueId(), "priceedit:" + kind + ":" + id);
        double[] pr = priceForEntry(kind, id);
        long money = pr == null ? 0 : Math.round(pr[0]);
        long points = pr == null ? 0 : Math.round(pr[1]);
        Inventory inv = base("&6调整价格");
        paint(inv, T_BUY);
        inv.setItem(4, label(Material.GOLD_INGOT, "&6&l" + strip(entry.name),
                "&7调整基础价（Lv.1）", "&7点击 +/- 即时保存"));
        // 金币行
        inv.setItem(19, label(Material.GOLD_NUGGET, "&6金币", "&7基础价（Lv.1）"));
        inv.setItem(20, btn(Material.RED_STAINED_GLASS_PANE, "&c-10000"));
        inv.setItem(21, btn(Material.RED_STAINED_GLASS_PANE, "&c-1000"));
        inv.setItem(22, label(Material.GOLD_INGOT, "&6&l" + fmt(money), "&7当前金币基础价"));
        inv.setItem(23, btn(Material.LIME_STAINED_GLASS_PANE, "&a+1000"));
        inv.setItem(24, btn(Material.LIME_STAINED_GLASS_PANE, "&a+10000"));
        // 点券行
        inv.setItem(28, label(Material.DIAMOND, "&d点券", "&7基础价（Lv.1）"));
        inv.setItem(29, btn(Material.RED_STAINED_GLASS_PANE, "&c-1000"));
        inv.setItem(30, btn(Material.RED_STAINED_GLASS_PANE, "&c-100"));
        inv.setItem(31, label(Material.DIAMOND, "&d&l" + fmt(points), "&7当前点券基础价"));
        inv.setItem(32, btn(Material.LIME_STAINED_GLASS_PANE, "&a+100"));
        inv.setItem(33, btn(Material.LIME_STAINED_GLASS_PANE, "&a+1000"));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    private void onPriceClick(Player p, String cur, int raw) {
        if (!p.hasPermission("elitemobs.admin")) return;
        int idx = Integer.parseInt(cur.split(":")[1]);
        List<PriceEntry> items = priceEntries();
        int slot = raw - 19;
        int gi = idx * ITEM_PER_PAGE + slot;
        if (slot >= 0 && slot < ITEM_PER_PAGE && gi < items.size()) {
            PriceEntry e = items.get(gi);
            openPriceEdit(p, e.kind, e.id);
        }
    }

    private void onPriceEditClick(Player p, String cur, int raw) {
        if (!p.hasPermission("elitemobs.admin")) return;
        String[] parts = cur.split(":");
        if (parts.length < 3) return;
        String kind = parts[1];
        String id = parts[2];
        double[] pr = priceForEntry(kind, id);
        if (pr == null) return;
        long money = Math.round(pr[0]);
        long points = Math.round(pr[1]);
        long dm = 0, dp = 0;
        switch (raw) {
            case 20 -> dm = -10000;
            case 21 -> dm = -1000;
            case 23 -> dm = 1000;
            case 24 -> dm = 10000;
            case 29 -> dp = -1000;
            case 30 -> dp = -100;
            case 32 -> dp = 100;
            case 33 -> dp = 1000;
            default -> { return; }
        }
        String path = kind.equals("gem") ? "shop.gem-prices"
                : kind.equals("rune") ? "shop.rune-prices" : "shop.utility-prices";
        plugin.getEliteConfig().setShopPrice(path, id, Math.max(0, money + dm), Math.max(0, points + dp));
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        openPriceEdit(p, kind, id);
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
            else if (raw == 33) { st.amount = Math.min(maxBuyAmount(p, st), st.amount + 1); buyState.put(p.getUniqueId(), st); renderBuy(p); }
            return;
        }

        // 通用控制行（上一页/下一页仅在箭头按钮存在时生效，单页菜单隐藏后点击空白不误触发）
        if (raw == 45) {
            if (isArrow(event.getView().getTopInventory(), raw)) onPrev(p, cur);
            return;
        }
        if (raw == 48) { back(p, cur); return; }
        if (raw == 49) { p.closeInventory(); return; }
        if (raw == 53) {
            if (isArrow(event.getView().getTopInventory(), raw)) onNext(p, cur);
            return;
        }

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
                else if (cur.equals("bossconfig")) onBossConfigClick(p, raw);
                else if (cur.startsWith("priceedit:")) onPriceEditClick(p, cur, raw);
                else if (cur.startsWith("price:")) onPriceClick(p, cur, raw);
            }
        }
    }

    private void onMainClick(Player p, int raw) {
        if (raw == 20) openGemShop(p, 0);
        else if (raw == 22) openRuneShop(p, 0);
        else if (raw == 24) openStats(p);
        else if (raw == 30) openInfo(p);
        else if (raw == 32 && p.hasPermission("elitemobs.admin")) openAdmin(p);
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
        else if (raw == 42) openPriceAdmin(p, 0);
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
        int lv = lvForSlot10(raw);
        if (lv < 1) return;
        String gemId = cur.substring("gemlv:".length());
        openBuy(p, "gem", gemId, lv, 0);
    }

    private void onRuneClick(Player p, String cur, int raw) {
        int[] slots = {20, 22, 24};
        for (int i = 0; i < slots.length && i < dailyRunes.size(); i++) {
            if (raw != slots[i]) continue;
            DailyRune dr = dailyRunes.get(i);
            if (dr.remaining <= 0) { p.sendMessage(ChatColor.RED + "✘ 该商品今日已售罄！"); return; }
            if (dr.kind.equals("protection")) openBuy(p, "utility", "PROTECTION-CHARM", 1, 0);
            else openBuy(p, "rune", dr.type, dr.level, 0);
            BuyState st = buyState.get(p.getUniqueId());
            if (st != null) { st.dailyRuneIndex = i; buyState.put(p.getUniqueId(), st); }
            return;
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
        int lv = lvForSlot10(raw);
        if (lv < 1) return;
        EliteConfig.CustomDrop gem = findGem(cur.substring("agemlv:".length()));
        if (gem != null) give(p, gem.build(lv), 1, strip(gem.name));
    }

    private void onARunesClick(Player p, String cur, int raw) {
        int idx = Integer.parseInt(cur.split(":")[1]);
        int slot = raw - 19;
        int ti = idx * 7 + slot;
        if (slot >= 0 && slot < 7 && ti < RUNE_TYPES.size()) openARuneLv(p, RUNE_TYPES.get(ti));
    }

    private void onARuneLvClick(Player p, String cur, int raw) {
        int lv = lvForSlot10(raw);
        if (lv < 1) return;
        String type = cur.substring("arunelv:".length());
        give(p, EliteRuneFactory.createRune(type, lv, plugin.getMessages()), 1,
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
        paint(inv, T_SPAWN);
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
        paint(inv, T_SPAWN);
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
        paint(inv, T_SPAWN);
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
        paint(inv, T_CONFIG);
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
        // Boss 布署设置入口（子页）
        inv.setItem(51, btn(Material.SKELETON_SKULL, "&e&l☠ Boss 布署", "&7布署开关 / 间隔 / 生成广播范围 / 物化距离"));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    /** 配置设置-子页：Boss 布署（开关/间隔/生成广播范围/物化距离 + 立即布署）。 */
    private void openBossConfig(Player p) {
        page.put(p.getUniqueId(), "bossconfig");
        Inventory inv = base("&cBoss 布署");
        paint(inv, T_CONFIG);
        inv.setItem(4, label(Material.SKELETON_SKULL, "&e&l☠ Boss 布署", "&7点击调整，即时保存到 config.yml"));
        EliteConfig cfg = plugin.getEliteConfig();
        // 行1：布署开关
        inv.setItem(9, label(Material.LEVER, "&fBoss 布署", "&7开启/关闭自动布署"));
        inv.setItem(11, btn(Material.LIME_STAINED_GLASS_PANE, cfg.isBossSpawnEnabled() ? "&a✔ 开" : "&c✖ 关", "&7点击切换"));
        inv.setItem(13, label(Material.BOOK, cfg.isBossSpawnEnabled() ? "&a已开启" : "&c已关闭", ""));
        // 行2：布署间隔（基础，权重动态缩放）
        inv.setItem(18, label(Material.CLOCK, "&f布署间隔", "&7基础间隔（秒），由人数/昼夜/击杀权重动态缩放"));
        inv.setItem(20, btn(Material.RED_STAINED_GLASS_PANE, "&c-5分", "&7减少 5 分钟"));
        inv.setItem(22, label(Material.BOOK, "&e" + formatSeconds(cfg.getBossSpawnBaseIntervalSeconds()), ""));
        inv.setItem(24, btn(Material.LIME_STAINED_GLASS_PANE, "&a+5分", "&7增加 5 分钟"));
        // 行3：生成广播范围
        inv.setItem(27, label(Material.ENDER_PEARL, "&f生成广播范围", "&7Boss 生成广播范围（格，-1=全服）"));
        inv.setItem(29, btn(Material.RED_STAINED_GLASS_PANE, "&c-100", "&7减少 100 格"));
        inv.setItem(31, label(Material.BOOK, cfg.getBossAnnounceRange() < 0 ? "&e全服" : "&e" + cfg.getBossAnnounceRange() + " 格", ""));
        inv.setItem(33, btn(Material.LIME_STAINED_GLASS_PANE, "&a+100", "&7增加 100 格"));
        // 行4：物化距离
        inv.setItem(36, label(Material.AMETHYST_SHARD, "&f物化距离", "&7玩家进入该范围时 Boss 现身"));
        inv.setItem(38, btn(Material.RED_STAINED_GLASS_PANE, "&c-8", "&7减少 8 格"));
        inv.setItem(40, label(Material.BOOK, "&e" + (int) cfg.getBossMaterializeDistance() + " 格", ""));
        inv.setItem(42, btn(Material.LIME_STAINED_GLASS_PANE, "&a+8", "&7增加 8 格"));
        // 立即布署一个 Boss（调试）
        inv.setItem(51, btn(Material.ZOMBIE_HEAD, "&6&l☠ 立即布署 Boss", "&7立刻在世界远端布署一个 Boss"));
        controls(inv, false, false);
        p.openInventory(inv);
    }

    private static String formatSeconds(int s) {
        if (s % 60 == 0) return (s / 60) + " 分钟";
        return s + " 秒";
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
            case 51 -> { openBossConfig(p); changed = false; }
            default -> changed = false;
        }
        if (changed) {
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
            openConfig(p);
        }
    }

    private void onBossConfigClick(Player p, int raw) {
        EliteConfig cfg = plugin.getEliteConfig();
        boolean changed = true;
        switch (raw) {
            case 9, 11 -> cfg.setBossSpawnEnabled(!cfg.isBossSpawnEnabled());
            case 20 -> cfg.setBossSpawnBaseIntervalSeconds(cfg.getBossSpawnBaseIntervalSeconds() - 300);
            case 24 -> cfg.setBossSpawnBaseIntervalSeconds(cfg.getBossSpawnBaseIntervalSeconds() + 300);
            case 29 -> cfg.setBossAnnounceRange(cfg.getBossAnnounceRange() - 100);
            case 33 -> cfg.setBossAnnounceRange(cfg.getBossAnnounceRange() + 100);
            case 38 -> cfg.setBossMaterializeDistance(cfg.getBossMaterializeDistance() - 8);
            case 42 -> cfg.setBossMaterializeDistance(cfg.getBossMaterializeDistance() + 8);
            case 51 -> {
                if (plugin.getBossSpawner() != null) {
                    plugin.getBossSpawner().tryPlanBoss();
                    p.sendMessage(ChatColor.GREEN + "\u2714 \u5df2\u5c1d\u8bd5\u5e03\u7f72 Boss\uff08\u82e5\u6570\u91cf\u5df2\u8fbe\u4e0a\u9650\u6216\u6761\u4ef6\u4e0d\u8db3\u5219\u8df3\u8fc7\uff09");
                }
                changed = false;
            }
            default -> changed = false;
        }
        if (changed) {
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
            openBossConfig(p);
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
        else if (cur.startsWith("price:")) openPriceAdmin(p, Integer.parseInt(cur.split(":")[1]) - 1);
        else back(p, cur);
    }

    private void onNext(Player p, String cur) {
        if (cur.startsWith("gems:")) openGemShop(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else if (cur.startsWith("rune:")) openRuneShop(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else if (cur.startsWith("agems:")) openAGems(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else if (cur.startsWith("arunes:")) openARunes(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else if (cur.startsWith("spawn:")) openSpawnList(p, Integer.parseInt(cur.split(":")[1]) + 1);
        else if (cur.startsWith("price:")) openPriceAdmin(p, Integer.parseInt(cur.split(":")[1]) + 1);
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
        else if (cur.equals("bossconfig")) openConfig(p);
        else if (cur.startsWith("priceedit:")) openPriceAdmin(p, 0);
        else if (cur.startsWith("price:")) openAdmin(p);
        else if (cur.equals("info") || cur.equals("stats") || cur.equals("admin")) openMain(p);
        else openMain(p);
    }

    // ==================== 数据 ====================

    static class ShopItem {
        String kind; String id; ItemStack icon;
        ShopItem(String kind, String id, ItemStack icon) { this.kind = kind; this.id = id; this.icon = icon; }
    }

    /** 价格管理条目（宝石/符文/消耗品）。 */
    static class PriceEntry {
        String kind; String id; String name; ItemStack icon;
        PriceEntry(String kind, String id, String name, ItemStack icon) {
            this.kind = kind; this.id = id; this.name = name; this.icon = icon;
        }
    }

    /** 宝石商城商品：10 宝石 + 拆卸器（保护符已改为每日随机上架，不再直接售卖）。 */
    private List<ShopItem> gemShopItems() { return gemShopItems(true); }

    private List<ShopItem> gemShopItems(boolean withUtility) {
        List<ShopItem> list = new ArrayList<>();
        for (EliteConfig.CustomDrop g : allGems()) {
            ItemStack icon = g.build(1);
            list.add(new ShopItem("gem", g.id,
                    pricedWithCap(icon, plugin.getEliteConfig().getGemShopPrice(g.id))));
        }
        if (withUtility) {
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

    /** 价格管理列表：全部宝石 + 符文 + 消耗品。 */
    private List<PriceEntry> priceEntries() {
        List<PriceEntry> list = new ArrayList<>();
        for (EliteConfig.CustomDrop g : allGems()) {
            list.add(new PriceEntry("gem", g.id, strip(g.name), g.build(1)));
        }
        for (String t : RUNE_TYPES) {
            list.add(new PriceEntry("rune", t, EliteRuneFactory.TYPES.get(t).coloredName,
                    EliteRuneFactory.createRune(t, 1, plugin.getMessages())));
        }
        list.add(new PriceEntry("utility", "PROTECTION-CHARM", "保护符",
                EliteEssenceFactory.createProtectionCharm(plugin.getMessages())));
        list.add(new PriceEntry("utility", "GEM-REMOVER", "拆卸器",
                EliteEssenceFactory.createGemRemover(plugin.getMessages())));
        return list;
    }

    private double[] priceForEntry(String kind, String id) {
        EliteConfig cfg = plugin.getEliteConfig();
        return switch (kind) {
            case "gem" -> cfg.getGemShopPrice(id);
            case "rune" -> cfg.getRuneShopPrice(id);
            case "utility" -> cfg.getUtilityPrice(id);
            default -> null;
        };
    }

    /** 价格倍率：宝石越高级越贵（系数 = 1 + N×(N-1)/10，涨幅逐级递增，Lv.10 恰好 10 倍基础价）；符文/消耗品仍为线性 ×N。 */
    private static double priceFactor(int lv, boolean gem) {
        int n = Math.max(1, lv);
        return gem ? 1 + n * (n - 1) / 10.0 : n;
    }

    /** 给商品追加价格 lore（factor 为价格倍率）。 */
    private ItemStack priced(ItemStack item, double[] base, double factor) {
        return priced(item, base, factor, "&e✦ 点击购买");
    }

    private ItemStack priced(ItemStack item, double[] base, double factor, String tip) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        lore.add(" ");
        lore.add(priceLine(base, factor));
        lore.add(ChatColor.translateAlternateColorCodes('&', tip));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** 商品图标 + 价格 lore + 满级(Lv.10)封顶提示（用于宝石商城列表）。 */
    private ItemStack pricedWithCap(ItemStack item, double[] base) {
        ItemStack out = priced(item, base, 1, "&e✦ 点击选购等级");
        if (base == null) return out;
        ItemMeta meta = out.getItemMeta();
        if (meta == null) return out;
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        lore.add(ChatColor.translateAlternateColorCodes('&',
                "&7满级 Lv.10: &6" + fmt(base[0] * priceFactor(10, true))
                        + " 金币 &7/ &d" + Math.round(base[1] * priceFactor(10, true)) + " 点券"));
        meta.setLore(lore);
        out.setItemMeta(meta);
        return out;
    }

    private String priceLine(double[] base, double factor) {
        if (base == null) return ChatColor.GRAY + "未配置价格";
        long money = Math.round(base[0] * factor);
        int points = (int) Math.round(base[1] * factor);
        String line = ChatColor.DARK_GRAY + "价格: ";
        if (money > 0) line += ChatColor.GOLD + fmt(money) + " 金币";
        if (points > 0) line += (money > 0 ? " &7或 " : "") + ChatColor.LIGHT_PURPLE + points + " 点券";
        if (money <= 0 && points <= 0) line += "免费";
        return ChatColor.translateAlternateColorCodes('&', line);
    }

    private ItemStack payBtn(String name, boolean selected, double price, Material on, Material off) {
        return label(selected ? on : off,
                (selected ? "&a● " : "&7○ ") + name + (price > 0 ? " &7(" + fmt(price) + ")" : ""),
                selected ? "&7当前支付方式" : "&7点击选择");
    }

    // ==================== 工具 ====================

    private void controls(Inventory inv, boolean canPrev, boolean canNext) {
        if (canPrev) inv.setItem(45, btn(Material.ARROW, "&a◀ 上一页"));
        inv.setItem(48, btn(Material.OAK_DOOR, "&e&l🏠 返回"));
        inv.setItem(49, btn(Material.RED_WOOL, "&c✖ 关闭"));
        if (canNext) inv.setItem(53, btn(Material.ARROW, "&a下一页 ▶"));
    }

    private static boolean isArrow(Inventory inv, int slot) {
        ItemStack it = inv == null ? null : inv.getItem(slot);
        return it != null && it.getType() == Material.ARROW;
    }

    private Inventory base(String suffix) {
        String title = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + TITLE + ChatColor.RESET;
        if (suffix != null && !suffix.isEmpty()) title += " · " + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', suffix));
        return Bukkit.createInventory(null, 54, title);
    }

    /** 彩色玻璃板边框 + 白色磨砂玻璃板内部填充（除按钮外全部玻璃板，简洁不花哨）。 */
    private void paint(Inventory inv, Theme t) {
        ItemStack frame = pane(t.frame), fill = pane(Material.WHITE_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++) inv.setItem(i, frame.clone());          // 顶部边框
        for (int i = 45; i < 54; i++) inv.setItem(i, frame.clone());        // 底部边框
        int[] cols = {9, 17, 18, 26, 27, 35, 36, 44};
        for (int c : cols) inv.setItem(c, frame.clone());                   // 左右侧边
        for (int s = 0; s < 54; s++) {
            if (inv.getItem(s) == null) inv.setItem(s, fill.clone());       // 内部空白格
        }
    }

    /** 菜单装饰主题：边框主色（内部统一白色磨砂玻璃）。 */
    private static class Theme {
        final Material frame;   // 边框主色玻璃板
        Theme(Material frame) { this.frame = frame; }
    }

    private static ItemStack pane(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) { meta.setDisplayName(" "); it.setItemMeta(meta); }
        return it;
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
