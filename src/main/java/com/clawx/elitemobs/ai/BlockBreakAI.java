package com.clawx.elitemobs.ai;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitRunnable;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.EliteConfig;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public class BlockBreakAI implements Listener {
    private final EliteMobsPlugin plugin;
    private final Map<String, BrokenRecord> broken = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBreak = new ConcurrentHashMap<>();
    // ?tick????: mobId -> (blockKey -> damage)
    // ???? "world,x,y,z" ??blockKey???Location???hashCode
    private final Map<UUID, Map<String, Integer>> breakingProgress = new ConcurrentHashMap<>();

    private static final Set<Material> GRAVITY_BLOCKS = EnumSet.of(
        Material.SAND, Material.GRAVEL, Material.RED_SAND,
        Material.DRAGON_EGG, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL
    );

    // ????
    private static final Map<Material, Integer> BLOCK_HARDNESS = new HashMap<>();
    static {
        BLOCK_HARDNESS.put(Material.DIRT, 5);
        BLOCK_HARDNESS.put(Material.GRASS_BLOCK, 6);
        BLOCK_HARDNESS.put(Material.SAND, 5);
        BLOCK_HARDNESS.put(Material.GRAVEL, 6);
        BLOCK_HARDNESS.put(Material.COBBLESTONE, 20);
        BLOCK_HARDNESS.put(Material.STONE, 15);
        BLOCK_HARDNESS.put(Material.DEEPSLATE, 30);
        BLOCK_HARDNESS.put(Material.SANDSTONE, 8);
        BLOCK_HARDNESS.put(Material.OAK_PLANKS, 20);
        BLOCK_HARDNESS.put(Material.SPRUCE_PLANKS, 20);
        BLOCK_HARDNESS.put(Material.BIRCH_PLANKS, 20);
        BLOCK_HARDNESS.put(Material.JUNGLE_PLANKS, 20);
        BLOCK_HARDNESS.put(Material.ACACIA_PLANKS, 20);
        BLOCK_HARDNESS.put(Material.DARK_OAK_PLANKS, 20);
        BLOCK_HARDNESS.put(Material.GLASS, 3);
        BLOCK_HARDNESS.put(Material.IRON_ORE, 30);
        BLOCK_HARDNESS.put(Material.COAL_ORE, 30);
        BLOCK_HARDNESS.put(Material.GOLD_ORE, 30);
        BLOCK_HARDNESS.put(Material.DIAMOND_ORE, 60);
        BLOCK_HARDNESS.put(Material.REDSTONE_ORE, 30);
        BLOCK_HARDNESS.put(Material.EMERALD_ORE, 30);
        BLOCK_HARDNESS.put(Material.LAPIS_ORE, 30);
        BLOCK_HARDNESS.put(Material.OBSIDIAN, 500);
        BLOCK_HARDNESS.put(Material.NETHERRACK, 4);
        BLOCK_HARDNESS.put(Material.SOUL_SAND, 5);
    }

    public BlockBreakAI(EliteMobsPlugin plugin) {
        this.plugin = plugin;

        // ???
        new BukkitRunnable() { public void run() {
            EliteConfig cfg = plugin.getEliteConfig();
            if (!cfg.isBlockBreakEnabled()) return;

            Set<Material> breakable = cfg.getBreakableBlocks();
            if (breakable.isEmpty()) {
                breakable = EnumSet.of(Material.STONE, Material.COBBLESTONE, Material.DIRT,
                    Material.GRASS_BLOCK, Material.SANDSTONE, Material.OAK_PLANKS,
                    Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS, Material.JUNGLE_PLANKS,
                    Material.ACACIA_PLANKS, Material.DARK_OAK_PLANKS, Material.GLASS,
                    Material.IRON_ORE, Material.COAL_ORE, Material.GOLD_ORE, Material.DIAMOND_ORE);
            }

            EliteMobManager mgr = plugin.getMobManager();
            for (UUID id : mgr.getBlockBreakers()) {
                Entity ent = Bukkit.getEntity(id);
                if (!(ent instanceof Mob m) || m.isDead() || !m.isValid() || m.getTarget() == null) continue;
                if (!cfg.isWorldEnabled(m.getWorld().getName())) continue;

                int level = EliteMobManager.getEliteLevel(m);
                if (level <= 0) continue;

                World w = m.getWorld();
                boolean isNight = isNightTime(w.getTime());
                long tick = w.getFullTime();

                Block targetBlock = findBreakable(m, m.getTarget(), breakable);
                if (targetBlock == null) {
                    breakingProgress.remove(id);
                    continue;
                }
                // 领地保护：受保护区域内的方块不破坏（WorldGuard/GriefPrevention/Towny/Factions）
                if (com.clawx.elitemobs.compat.ProtectionHook.isProtected(targetBlock.getLocation())) {
                    breakingProgress.remove(id);
                    continue;
                }

                String blockKey = blockToKey(targetBlock);
                Map<String, Integer> mobProgress = breakingProgress.computeIfAbsent(id, k -> new ConcurrentHashMap<>());

                int hardness = BLOCK_HARDNESS.getOrDefault(targetBlock.getType(), 15);
                int baseDamage = Math.max(1, level * 2);
                if (isNight) baseDamage = (int)(baseDamage * 1.5);
                int damagePerTick = Math.max(1, baseDamage * 10 / Math.max(hardness, 5));

                int progress = mobProgress.getOrDefault(blockKey, 0) + damagePerTick;

                if (progress % 10 < damagePerTick) {
                    targetBlock.getWorld().spawnParticle(Particle.BLOCK,
                        targetBlock.getLocation().add(0.5, 0.5, 0.5),
                        5, 0.2, 0.2, 0.2, 0, targetBlock.getBlockData());
                }

                int threshold = getBlockBreakThreshold(hardness);
                int prevPct = (progress - damagePerTick) * 100 / Math.max(threshold, 1);
                int currPct = progress * 100 / Math.max(threshold, 1);
                if (prevPct / 25 != currPct / 25 && currPct <= 100) {
                    targetBlock.getWorld().playSound(targetBlock.getLocation(),
                        Sound.BLOCK_STONE_HIT, 0.5f + level * 0.05f, 0.8f + (currPct / 100.0f) * 0.4f);
                }

                if (progress >= threshold) {
                    targetBlock.getWorld().spawnParticle(Particle.BLOCK,
                        targetBlock.getLocation().add(0.5, 0.5, 0.5),
                        20, 0.3, 0.3, 0.3, 0, targetBlock.getBlockData());
                    targetBlock.getWorld().playSound(targetBlock.getLocation(),
                        Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f);

                    if (cfg.isRestoreBrokenBlocks()) {
                        broken.put(blockToKey(targetBlock),
                            new BrokenRecord(targetBlock.getBlockData(), tick, cfg.getBlockRestoreTicks()));
                    }
                    targetBlock.setType(Material.AIR);
                    mobProgress.remove(blockKey);
                    lastBreak.put(id, tick);
                } else {
                    mobProgress.put(blockKey, progress);
                }
            }
        }}.runTaskTimer(plugin, 20L, 5L);

        // ????
        if (plugin.getEliteConfig().isRestoreBrokenBlocks()) {
            new BukkitRunnable() { public void run() {
                long now = 0;
                Iterator<Map.Entry<String, BrokenRecord>> it = broken.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, BrokenRecord> en = it.next();
                    BrokenRecord r = en.getValue();
                    if (now == 0) {
                        String[] parts = en.getKey().split(",");
                        if (parts.length >= 4) {
                            World bw = Bukkit.getWorld(parts[0]);
                            if (bw != null) now = bw.getFullTime();
                        }
                    }
                    if (now - r.brokenTick >= r.restoreTicks) {
                        String[] parts = en.getKey().split(",");
                        if (parts.length >= 4) {
                            World bw = Bukkit.getWorld(parts[0]);
                            if (bw != null) {
                                Block b = bw.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                                if (b.getType() == Material.AIR && !GRAVITY_BLOCKS.contains(r.data.getMaterial())) {
                                    b.setBlockData(r.data);
                                    b.getWorld().spawnParticle(Particle.BLOCK,
                                        b.getLocation().add(0.5, 0.5, 0.5), 10, 0.2, 0.2, 0.2, 0, r.data);
                                    b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 1.0f, 1.0f);
                                }
                            }
                        }
                        it.remove();
                    }
                }
            }}.runTaskTimer(plugin, 20L, 20L);
        }
    }

    private String blockToKey(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private int getBlockBreakThreshold(int hardness) {
        return Math.max(20, hardness * 2);
    }

    private boolean isNightTime(long worldTime) {
        long time = worldTime % 24000;
        return time >= 13000 && time < 23000;
    }

    private Block findBreakable(Mob m, LivingEntity target, Set<Material> breakable) {
        Location ml = m.getLocation(), tl = target.getLocation();
        org.bukkit.util.Vector dir = tl.toVector().subtract(ml.toVector());
        double dist = dir.length();
        if (dist < 0.1) return null;
        dir.normalize();

        for (double d = 1.0; d < dist; d += 0.5) {
            Block b = ml.clone().add(dir.clone().multiply(d)).getBlock();
            if (breakable.contains(b.getType()) && b.getType().isSolid()
                && b.getType() != Material.BEDROCK && b.getType() != Material.SPAWNER) {
                return b;
            }
        }
        return null;
    }

    @EventHandler public void onDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof LivingEntity le && EliteMobManager.isElite(le)) {
            lastBreak.remove(le.getUniqueId());
            breakingProgress.remove(le.getUniqueId());
        }
    }

    private record BrokenRecord(BlockData data, long brokenTick, int restoreTicks) {}
}
