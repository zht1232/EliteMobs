package com.clawx.elitemobs.spawn;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
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
        // 刷怪笼刷出的怪不精英化（保护刷怪笼式刷怪塔，general.exclude-spawner-mobs）
        if (config.isExcludeSpawnerMobs() && reason == CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        if (entity.getLocation().getBlockY() < config.getMinSpawnY()) return;
        Chunk chunk = entity.getLocation().getChunk();
        if (plugin.getMobManager().countElitesInChunk(chunk) >= config.getMaxElitesPerChunk()) return;
        // 生成前距离检查：离玩家太近的区块不精英化（保护刷怪塔/铁机/基地）
        double minPlayerDist = config.getSpawnMinPlayerDist();
        if (minPlayerDist > 0) {
            double minDistSq = minPlayerDist * minPlayerDist;
            Location loc = entity.getLocation();
            for (Player p : entity.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(loc) < minDistSq) return;
            }
        }
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
        // 满月夜生成率额外提升（月相系统，借鉴原版 MoonPhaseDetector）
        if (time >= 13000 && time < 23000 && config.isMoonPhaseEnabled()
                && com.clawx.elitemobs.utils.MoonPhaseDetector.isFullMoon(entity.getWorld())) {
            chance *= config.getFullMoonSpawnMultiplier();
        }
        // 注意：不再按"距世界出生点"加距离加成——它会盖过 elite-spawn-chance（+0.05 封顶远超 0.01），
        // 导致玩家调低概率后野外精英依然很多。概率严格由 elite-spawn-chance × 难度 × 夜间 × 满月决定。
        if (rng.nextDouble() >= chance) return;
        plugin.getMobManager().makeElite(entity);

        // 高等级精英生成到更远的地方（等级越高要求离玩家越远，避免刷在玩家/基地旁）
        int level = EliteMobManager.getEliteLevel(entity);
        relocateHighLevelElite(entity, level);
        // 精英已入库（makeElite 时创建记录）；传送后刷新记录位置，保证物化点与实体一致
        if (plugin.getPersistence() != null) plugin.getPersistence().refreshPosition(entity);

        // 高等级精英生成广播（Boss 不再由玩家附近精英晋升，改由 BossSpawner 在世界远端布署、玩家接近时物化）
        announceSpawn(entity);
    }

    /**
     * 高等级精英生成到更远的地方：要求距最近玩家 >= min-dist-base + 等级*dist-per-level（封顶 max-dist）。
     * 距离不足时沿"玩家→精英"方向外推并传送到安全位置，避免高级怪刷在玩家/基地旁。
     */
    private void relocateHighLevelElite(LivingEntity entity, int level) {
        EliteConfig cfg = plugin.getEliteConfig();
        double target = Math.min(cfg.getSpawnDistBase() + level * cfg.getSpawnDistPerLevel(), cfg.getSpawnDistMax());

        Location loc = entity.getLocation();
        Player nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Player p : entity.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(loc);
            if (d < nearestSq) { nearestSq = d; nearest = p; }
        }
        if (nearest == null) return;
        double current = Math.sqrt(nearestSq);
        if (current >= target) return; // 已够远，无需移动

        Location pl = nearest.getLocation();
        double dx = loc.getX() - pl.getX();
        double dz = loc.getZ() - pl.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) { // 与玩家几乎重叠：随机取一个方向外推
            dx = rng.nextDouble() - 0.5;
            dz = rng.nextDouble() - 0.5;
            len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.05) return;
        }
        double nx = dx / len, nz = dz / len;
        int tx = (int) Math.floor(pl.getX() + nx * target);
        int tz = (int) Math.floor(pl.getZ() + nz * target);

        Location spot = findSafeSpot(entity.getWorld(), tx, tz);
        if (spot != null) entity.teleport(spot);
    }

    /** 在 (x,z) 附近找一个实体能站立的 2 格高空间，找不到返回 null。
     *  地狱有天花板：getHighestBlockYAt 会返回天花板块，需从下往上找地表（避开天花板/岩浆）；
     *  主世界/末地保持从高处向下找。 */
    public static Location findSafeSpot(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            int minY = Math.max(world.getMinHeight(), 0);
            int maxY = world.getMaxHeight() - 2;
            for (int y = minY + 1; y < maxY; y++) {  // 从 minY+1 开始，保证 y-1 不越界
                Block ground = world.getBlockAt(x, y - 1, z);
                if (!ground.isPassable() && !ground.isLiquid()
                        && world.getBlockAt(x, y, z).isPassable()
                        && world.getBlockAt(x, y + 1, z).isPassable()) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
            return null;
        }
        int top = world.getHighestBlockYAt(x, z);
        for (int y = top + 1; y >= Math.max(world.getMinHeight(), top - 6); y--) {
            Block ground = world.getBlockAt(x, y - 1, z);
            if (!ground.isLiquid()
                    && world.getBlockAt(x, y, z).isPassable()
                    && world.getBlockAt(x, y + 1, z).isPassable()) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private void announceSpawn(LivingEntity entity) {
        EliteConfig cfg = plugin.getEliteConfig();
        if (!cfg.isSpawnAnnounceEnabled()) return;
        int level = EliteMobManager.getEliteLevel(entity);
        if (level < cfg.getSpawnAnnounceMinLevel()) return;

        FileConfiguration msgs = plugin.getMessages();
        String template = msgs != null && msgs.contains("messages.announce-spawn")
            ? msgs.getString("messages.announce-spawn")
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
        // handleEliteDeath 已在 EliteCombatListener.onEliteDeath 中调用，此处不再重复
    }
}
