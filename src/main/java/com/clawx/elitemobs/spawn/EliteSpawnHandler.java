package com.clawx.elitemobs.spawn;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.configuration.file.FileConfiguration;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.EliteConfig;
import java.util.Random;

public class EliteSpawnHandler implements Listener {
    private final EliteMobsPlugin plugin;
    private final Random rng = new Random();

    public EliteSpawnHandler(EliteMobsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!plugin.getEliteConfig().isEnabled()) return;
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL && reason != CreatureSpawnEvent.SpawnReason.DEFAULT && reason != CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        LivingEntity entity = event.getEntity();
        EliteConfig config = plugin.getEliteConfig();
        if (!config.isWorldEnabled(entity.getWorld().getName())) return;
        if (!config.getEnabledMobTypes().contains(entity.getType())) return;
        if (entity.getLocation().getBlockY() < config.getMinSpawnY()) return;
        Chunk chunk = entity.getLocation().getChunk();
        if (plugin.getMobManager().countElitesInChunk(chunk) >= config.getMaxElitesPerChunk()) return;
        if (entity instanceof Animals || entity instanceof WaterMob || entity instanceof Ambient || entity instanceof Allay || entity instanceof Villager) return;
        if (entity instanceof Wither || entity instanceof EnderDragon) return;
        double chance = config.getEliteSpawnChance();
        switch (entity.getWorld().getDifficulty()) {
            case EASY -> chance *= 0.5; case NORMAL -> chance *= 1.0; case HARD -> chance *= 1.5;
        }
        // ???????
        long time = entity.getWorld().getTime() % 24000;
        if (time >= 13000 && time < 23000 && config.isNightEnhancementEnabled()) {
            chance *= config.getNightSpawnMultiplier();
        }
        double dist = entity.getLocation().distance(entity.getWorld().getSpawnLocation());
        chance += Math.min(dist * 0.0001, 0.05);
        if (rng.nextDouble() >= chance) return;
        plugin.getMobManager().makeElite(entity);

        // 尝试晋升为Boss（Lv.15+）
        int level = EliteMobManager.getEliteLevel(entity);
        plugin.getBossManager().tryPromoteToBoss(entity, level);

        // 高等级精英生成广播
        announceSpawn(entity);
    }

    private void announceSpawn(LivingEntity entity) {
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isSpawnAnnounceEnabled()) return;
        int level = EliteMobManager.getEliteLevel(entity);
        if (level < cfg.getSpawnAnnounceMinLevel()) return;

        FileConfiguration msgs = plugin.getMessages();
        String template = msgs != null && msgs.contains("announce-spawn")
            ? msgs.getString("announce-spawn")
            : "&c\u26a0 &e{elite_name}&c \u5728 &7{world}&c \u51fa\u73b0\u4e86\uff01";

        String typeName = entity.getType().name().toLowerCase().replace('_', ' ');
        String displayName = entity.getCustomName() != null ? entity.getCustomName() : typeName;
        String msg = ChatColor.translateAlternateColorCodes('&',
            template.replace("{elite_name}", displayName)
                    .replace("{type}", typeName)
                    .replace("{level}", String.valueOf(level))
                    .replace("{world}", entity.getWorld().getName()));

        int range = cfg.getSpawnAnnounceRange();
        if (range > 0) {
            Location loc = entity.getLocation();
            for (Player p : entity.getWorld().getPlayers()) {
                if (p.getLocation().distance(loc) <= range) p.sendMessage(msg);
            }
        } else {
            Bukkit.broadcastMessage(msg);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof LivingEntity le && EliteMobManager.isElite(le)) plugin.getMobManager().handleEliteDeath(le.getUniqueId());
    }
}
