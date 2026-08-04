package com.clawx.elitemobs.rune;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;

import com.clawx.elitemobs.EconomyHook;
import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.EliteMobsPlugin;

import java.util.*;

/**
 * 符文系统监听器 —— 独立于淬炼。
 *
 * <p>玩家把「已淬炼的装备 + 符文」放入铁砧：</p>
 * <ul>
 *   <li>符文槽数量由装备淬炼等级决定（Lv1-3=1槽 / 4-6=2槽 / 7-9=3槽 / 10+=4槽）</li>
 *   <li>镶嵌消耗：金币 + 点券 + 经验值（可配置）</li>
 *   <li>属性符文（生命/移速）直接写入装备 AttributeModifier</li>
 *   <li>药水符文通过穿戴检测施加持续效果</li>
 * </ul>
 */
public class EliteRuneListener implements Listener {
    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();
    private final NamespacedKey HINT;

    public EliteRuneListener(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        this.HINT = new NamespacedKey(plugin, "rune_hint");
    }

    // ==================== 铁砧预览 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack equip = inv.getItem(0);
        ItemStack rune = inv.getItem(1);
        if (equip == null || rune == null) return;
        // 装备必须是已淬炼的（有升级等级），且槽位未满
        if (!isUpgraded(equip) || !EliteRuneFactory.isRune(rune)) return;
        // 符文类型必须与装备匹配（武器符文只能上武器，护甲符文只能上护甲）
        String rType = EliteRuneFactory.getRuneType(rune);
        if (rType == null || !EliteRuneFactory.canFit(equip, rType)) return;
        if (hasFreeSlot(equip)) {
            event.setResult(createHintPaper());
            inv.setItem(2, createHintPaper());
            inv.setRepairCost(1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory inv)) return;
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getRawSlot() == 2) {
            ItemStack equip = inv.getItem(0);
            ItemStack rune = inv.getItem(1);
            if (equip == null || rune == null) return;
            if (!isUpgraded(equip) || !EliteRuneFactory.isRune(rune)) return;
            if (!hasFreeSlot(equip)) { p.sendMessage(ChatColor.RED + "✘ 符文槽已满！"); return; }
            event.setCancelled(true);
            inv.setItem(2, null);
            doInstallRune(p, inv, equip, rune);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvilClose(InventoryCloseEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        ItemStack r = event.getInventory().getItem(2);
        if (r != null && r.getType() == Material.PAPER && r.hasItemMeta()
                && r.getItemMeta().getPersistentDataContainer().has(HINT, PersistentDataType.BYTE)) {
            event.getInventory().setItem(2, null);
        }
    }

    /** 执行符文镶嵌（消耗金币+点券+经验）。 */
    private void doInstallRune(Player p, AnvilInventory inv, ItemStack equip, ItemStack rune) {
        // 需要：金币 / 点券 / 经验（软依赖缺失时跳过对应项）
        double moneyCost = plugin.getEliteConfig().getRuneMoneyCost();
        int pointsCost = plugin.getEliteConfig().getRunePointsCost();
        int xpCost = plugin.getEliteConfig().getRuneXpCost();

        if (moneyCost > 0 && EconomyHook.isVaultReady() && EconomyHook.getMoney(p) < moneyCost) {
            p.sendMessage(ChatColor.RED + "✘ 金币不足！需要 " + ChatColor.GOLD + moneyCost + ChatColor.RED + " 金币");
            return;
        }
        if (pointsCost > 0 && EconomyHook.isPlayerPointsReady() && EconomyHook.getPoints(p) < pointsCost) {
            p.sendMessage(ChatColor.RED + "✘ 点券不足！需要 " + ChatColor.GOLD + pointsCost + ChatColor.RED + " 点券");
            return;
        }
        if (xpCost > 0 && p.getLevel() < xpCost) {
            p.sendMessage(ChatColor.RED + "✘ 经验不足！需要 " + ChatColor.GOLD + xpCost + ChatColor.RED + " 级经验");
            return;
        }

        String runeType = EliteRuneFactory.getRuneType(rune);
        if (runeType == null) return;
        int runeLevel = EliteRuneFactory.getRuneLevel(rune);
        int slot = EliteRuneFactory.installRune(equip, runeType, runeLevel);
        if (slot < 0) { p.sendMessage(ChatColor.RED + "✘ 符文槽已满！"); return; }

        // 扣除消耗
        if (moneyCost > 0) EconomyHook.withdrawMoney(p, moneyCost);
        if (pointsCost > 0) EconomyHook.takePoints(p, pointsCost);
        if (xpCost > 0) p.setLevel(p.getLevel() - xpCost);

        // 立即应用属性符文 + 刷新符文槽 Lore
        applyAttributeRunes(equip);
        refreshRuneLore(equip);

        // 写入装备并刷新
        inv.setItem(0, equip);
        // 消耗符文
        ItemStack rem = inv.getItem(1);
        if (rem != null) {
            if (rem.getAmount() > 1) { rem.setAmount(rem.getAmount() - 1); inv.setItem(1, rem); }
            else inv.setItem(1, null);
        }

        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                plugin.getMessages().getString("rune.installed",
                        "&e&l✦ &f已镶嵌 &b{rune}&f！"))
                .replace("{rune}", runeType));
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
    }

    /** 刷新装备 Lore 中的符文槽行（替换旧符文槽行，无则追加）。 */
    private void refreshRuneLore(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return;
        ItemMeta meta = equip.getItemMeta();
        if (meta == null) return;
        int totalLevel = com.clawx.elitemobs.essence.EliteGemFactory.totalGemLevel(equip);
        int slots = com.clawx.elitemobs.essence.EliteGemFactory.runeSlotsForTotalLevel(totalLevel);
        if (slots <= 0) return;

        var pdc = meta.getPersistentDataContainer();
        int used = 0;
        for (int i = 0; i < slots; i++) {
            if (pdc.has(com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS[i],
                    PersistentDataType.STRING)) used++;
        }

        // 构造新的符文槽行（标题 + 每符文一行 + 空槽行）
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getMessages().getString("essence-upgrade.lore.rune-title", "&d✦ 符文槽&7 ({used}/{max})")
                        .replace("{used}", String.valueOf(used))
                        .replace("{max}", String.valueOf(slots)));
        List<String> runeLines = new ArrayList<>();
        runeLines.add(title);
        for (int i = 0; i < slots; i++) {
            String type = pdc.get(com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS[i],
                    PersistentDataType.STRING);
            if (type != null) {
                var t = com.clawx.elitemobs.rune.EliteRuneFactory.TYPES.get(type);
                Integer lvI = pdc.get(com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOT_LEVELS[i],
                        PersistentDataType.INTEGER);
                int rlvl = lvI == null ? 1 : Math.max(1, Math.min(10, lvI));
                runeLines.add(ChatColor.translateAlternateColorCodes('&',
                        plugin.getMessages().getString("essence-upgrade.lore.rune-line", "   &e◆ {rune} &7{effect}")
                                .replace("{rune}", t != null
                                        ? t.coloredName + " &7Lv." + rlvl + " " + ChatColor.GRAY + t.icon
                                        : ChatColor.WHITE + type)
                                .replace("{effect}", t != null
                                        ? "&7→ &f" + com.clawx.elitemobs.rune.EliteRuneFactory.effectFor(t, rlvl)
                                        : "")));
            } else {
                runeLines.add(ChatColor.translateAlternateColorCodes('&',
                        plugin.getMessages().getString("essence-upgrade.lore.rune-empty", "   &8◇ 空槽")));
            }
        }

        // 替换已有符文槽段（从含"符文槽"的行开始到下一个分隔线或结尾），否则追加
        List<String> lore = new ArrayList<>(meta.getLore() != null ? meta.getLore() : new ArrayList<>());
        int start = -1;
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).contains("符文槽")) { start = i; break; }
        }
        if (start >= 0) {
            // 移除旧符文槽段（标题 + 每符文/空槽行，直到下一个分隔线 `-` 或结尾）
            int end = start;
            while (end < lore.size()) {
                String line = lore.get(end);
                if (end > start && (line.contains("\u2501") || line.contains("\u2500"))) break;
                end++;
            }
            lore.subList(start, end).clear();
            lore.addAll(start, runeLines);
        } else {
            lore.addAll(runeLines);
        }
        meta.setLore(lore);
        equip.setItemMeta(meta);
    }

    // ==================== 效果应用 ====================

    /** 属性符文（生命/移速）直接写 AttributeModifier；药水符文由定时任务施加。 */
    private void applyAttributeRunes(ItemStack equip) {
        if (equip == null || !equip.hasItemMeta()) return;
        String[] runes = EliteRuneFactory.getInstalledRunes(equip);
        int[] levels = getInstalledRuneLevels(equip);
        double hp = 0, speed = 0;
        for (int i = 0; i < runes.length; i++) {
            String r = runes[i];
            int lv = levels[i];
            if ("HEALTH".equals(r)) hp += com.clawx.elitemobs.rune.EliteRuneFactory.healthBonus(lv);
            if ("SPEED".equals(r)) speed += com.clawx.elitemobs.rune.EliteRuneFactory.speedBonus(lv);
        }
        // 生命
        applyModifier(equip, Attribute.MAX_HEALTH, hp, new NamespacedKey(plugin, "elite_rune_health"),
                EquipmentSlotGroup.ANY);
        // 移速
        applyModifier(equip, Attribute.MOVEMENT_SPEED, speed, new NamespacedKey(plugin, "elite_rune_speed"),
                EquipmentSlotGroup.ANY);
    }

    /** 读取装备符文槽的等级数组（与 getInstalledRunes 对应；旧数据无等级键按 1）。 */
    private int[] getInstalledRuneLevels(ItemStack equip) {
        var pdc = equip.getItemMeta().getPersistentDataContainer();
        int[] levels = new int[com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOTS.length];
        for (int i = 0; i < levels.length; i++) {
            Integer lv = pdc.get(com.clawx.elitemobs.rune.EliteRuneFactory.KEY_SLOT_LEVELS[i],
                    PersistentDataType.INTEGER);
            levels[i] = lv == null ? 1 : Math.max(1, Math.min(10, lv));
        }
        return levels;
    }

    private void applyModifier(ItemStack item, Attribute attr, double value, NamespacedKey key, EquipmentSlotGroup group) {
        ItemAttributeModifiers existing = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        if (existing != null) {
            for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
                if (e.attribute() == attr && e.modifier().getKey().equals(key)) continue;
                builder.addModifier(e.attribute(), e.modifier(), e.getGroup());
            }
        }
        if (value != 0) {
            AttributeModifier mod = new AttributeModifier(key, value,
                    AttributeModifier.Operation.ADD_NUMBER, group);
            builder.addModifier(attr, mod, group);
        }
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    /** 穿戴检测：每 2 秒为穿戴装备的玩家施加药水符文效果。 */
    public void startRunePotionTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.isDead() || !p.isOnline()) continue;
                for (ItemStack armor : new ItemStack[]{p.getInventory().getHelmet(),
                        p.getInventory().getChestplate(), p.getInventory().getLeggings(), p.getInventory().getBoots()}) {
                    if (armor == null || !armor.hasItemMeta()) continue;
                    String[] runes = EliteRuneFactory.getInstalledRunes(armor);
                    int[] levels = getInstalledRuneLevels(armor);
                    for (int i = 0; i < runes.length; i++) {
                        String r = runes[i];
                        if (r == null) continue;
                        applyPotionRune(p, r, levels[i]);
                    }
                }
                // 主手武器上的符文也生效
                ItemStack main = p.getInventory().getItemInMainHand();
                if (main != null && main.hasItemMeta()) {
                    String[] runes = EliteRuneFactory.getInstalledRunes(main);
                    int[] levels = getInstalledRuneLevels(main);
                    for (int i = 0; i < runes.length; i++) {
                        String r = runes[i];
                        if (r == null) continue;
                        applyPotionRune(p, r, levels[i]);
                    }
                }
            }
        }, 40L, 40L);
    }

    /** 按符文等级施加药水效果（等级决定药水强度等级）。 */
    private void applyPotionRune(Player p, String rune, int level) {
        int amp = com.clawx.elitemobs.rune.EliteRuneFactory.potionAmplifier(level);
        switch (rune) {
            case "STRENGTH" -> p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 80, amp, true, false));
            case "REGEN" -> p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, amp, true, false));
            case "RESIST" -> p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 80, amp, true, false));
            case "FIRE" -> p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 80, 0, true, false));
            default -> {}
        }
    }

    // ==================== 工具 ====================

    private boolean isUpgraded(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        // 装备已镶嵌任意宝石（gem 槽位）即视为已强化，可镶嵌符文
        String[] gems = com.clawx.elitemobs.essence.EliteGemFactory.getInstalledGems(item);
        for (String g : gems) if (g != null) return true;
        return false;
    }

    private boolean hasFreeSlot(ItemStack equip) {
        int totalLevel = com.clawx.elitemobs.essence.EliteGemFactory.totalGemLevel(equip);
        int capacity = com.clawx.elitemobs.essence.EliteGemFactory.runeSlotsForTotalLevel(totalLevel);
        if (capacity <= 0) return false;
        String[] runes = EliteRuneFactory.getInstalledRunes(equip);
        for (int i = 0; i < capacity; i++) {
            if (runes[i] == null) return true;
        }
        return false;
    }

    private int getUpgradeLevel(ItemStack equip) {
        return com.clawx.elitemobs.essence.EliteGemFactory.totalGemLevel(equip);
    }

    private ItemStack createHintPaper() {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta == null) return paper;
        meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "✦ 点击镶嵌符文");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "将符文镶嵌到装备符文槽");
        lore.add(ChatColor.DARK_GRAY + "消耗: 金币/点券/经验");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(HINT, PersistentDataType.BYTE, (byte) 1);
        paper.setItemMeta(meta);
        return paper;
    }
}
