package com.clawx.elitemobs.ai;

import com.clawx.elitemobs.EliteMobsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;

/**
 * Reclaims stale Bukkit flight flags left behind when the former flight plugin is absent.
 * Creative/spectator, EssentialsX flight permission, and the server's PJ flight gear remain
 * in control. An unauthorized player already airborne is allowed to land before de-arming.
 */
public final class EliteFlightManager implements Listener {
    private final EliteMobsPlugin plugin;

    public EliteFlightManager(EliteMobsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (plugin.getServer().getPluginManager().isPluginEnabled("SweetFlight")) {
            plugin.getLogger().info("  飞行状态: SweetFlight 已加载，交由 SweetFlight 管理");
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcileOnlinePlayers, 20L, 20L);
        plugin.getLogger().info("  飞行状态: 已启用残留状态回收（保留 Essentials/PJ 飞行许可）");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> reconcile(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (!event.isFlying() || canUseFlight(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().setAllowFlight(false);
    }

    private void reconcileOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) reconcile(player);
    }

    private void reconcile(Player player) {
        if (player == null || !player.isOnline() || canUseFlight(player) || player.isFlying()) return;
        if (player.getAllowFlight()) player.setAllowFlight(false);
    }

    private boolean canUseFlight(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return true;
        if (player.hasPermission("essentials.fly")) return true;
        return hasPjFlightGear(player);
    }

    private boolean hasPjFlightGear(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        ItemStack boots = player.getInventory().getBoots();
        if (loreContains(chest, "翅膀", "WINGS", "升空", "LIFT")) return true;
        if (loreContains(boots, "反重力", "ANTIGRAVITY")) return true;
        return loreContains(player.getInventory().getHelmet(), "磁力", "MAGNETIC")
                && loreContains(chest, "磁力", "MAGNETIC")
                && loreContains(player.getInventory().getLeggings(), "磁力", "MAGNETIC")
                && loreContains(boots, "磁力", "MAGNETIC");
    }

    private boolean loreContains(ItemStack item, String... words) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta == null ? null : meta.getLore();
        if (lore == null) return false;
        for (String raw : lore) {
            String line = ChatColor.stripColor(raw);
            if (line == null) continue;
            String normalized = line.toLowerCase(Locale.ROOT);
            for (String word : words) {
                if (normalized.contains(word.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }
}
