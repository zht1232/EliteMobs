package com.clawx.elitemobs.ai;

import org.bukkit.ChatColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.FileConfiguration;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.utils.StringUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ItemStealAI implements Listener {
    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();
    private final Map<UUID, Long> lastSteal = new ConcurrentHashMap<>();

    public ItemStealAI(EliteMobsPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getEliteConfig().isItemStealEnabled()) return;
        if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Mob)) return;
        if (!EliteMobManager.isElite((LivingEntity) event.getDamager())) return;

        Mob mob = (Mob) event.getDamager();
        Player player = (Player) event.getEntity();
        if (mob.isDead()) return;

        int cooldownTicks = plugin.getEliteConfig().getItemStealCooldownTicks();
        long cooldownMs = cooldownTicks * 50L;
        long now = System.currentTimeMillis();
        if (now - lastSteal.getOrDefault(mob.getUniqueId(), 0L) < cooldownMs) return;

        if (rng.nextDouble() >= plugin.getEliteConfig().getItemStealChance()) return;

        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;

        List<Integer> availableSlots = new ArrayList<>();
        if (isAir(eq.getItemInOffHand())) availableSlots.add(0);
        if (isAir(eq.getBoots())) availableSlots.add(1);
        if (isAir(eq.getLeggings())) availableSlots.add(2);
        if (isAir(eq.getChestplate())) availableSlots.add(3);
        if (isAir(eq.getHelmet())) availableSlots.add(4);

        if (availableSlots.isEmpty()) return;

        List<ItemStack> playerItems = new ArrayList<>();
        List<Runnable> removers = new ArrayList<>();

        for (int i = 0; i < 36; i++) {
            ItemStack it = player.getInventory().getItem(i);
            if (it != null && !it.getType().isAir()) {
                playerItems.add(it);
                final int slot = i;
                removers.add(() -> player.getInventory().setItem(slot, null));
            }
        }

        if (playerItems.isEmpty()) return;

        int itemIdx = rng.nextInt(playerItems.size());
        ItemStack stolen = playerItems.get(itemIdx);

        if (!plugin.getMobManager().addStolenItem(mob.getUniqueId(), stolen)) return;

        removers.get(itemIdx).run();
        lastSteal.put(mob.getUniqueId(), now);

        // 打上偷窃标记（此时物品已从玩家背包移除）：死亡归还时按标记从掉落/装备中
        // 精确移除一份，避免"装备槽掉落 + 归还"各给一份导致物品复制。
        EliteMobManager.markStolenItem(stolen);

        int slotIdx = availableSlots.get(rng.nextInt(availableSlots.size()));
        switch (slotIdx) {
            case 0: eq.setItemInOffHand(stolen); eq.setItemInOffHandDropChance(1.0f); break;
            case 1: eq.setBoots(stolen); eq.setBootsDropChance(1.0f); break;
            case 2: eq.setLeggings(stolen); eq.setLeggingsDropChance(1.0f); break;
            case 3: eq.setChestplate(stolen); eq.setChestplateDropChance(1.0f); break;
            case 4: eq.setHelmet(stolen); eq.setHelmetDropChance(1.0f); break;
        }

        String itemName = stolen.hasItemMeta() && stolen.getItemMeta().hasDisplayName()
            ? stolen.getItemMeta().getDisplayName()
            : formatMaterial(stolen.getType().name());
        String mobName = mob.getType().name().toLowerCase().replace('_', ' ');
        FileConfiguration msgs = plugin.getMessages();
        String notifyMsg = msgs != null && msgs.contains("steal.notify")
            ? msgs.getString("steal.notify")
            : "&c&l\u26a0 &e{mob} &c\u5077\u8d70\u4e86\u4f60\u7684 &f{item}&c\uff01";
        notifyMsg = notifyMsg.replace("{mob}", mobName).replace("{item}", itemName);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', notifyMsg));
    }

    private boolean isAir(ItemStack i) { return i == null || i.getType().isAir(); }

    private String formatMaterial(String name) {
        return StringUtil.formatName(name);
    }
}
