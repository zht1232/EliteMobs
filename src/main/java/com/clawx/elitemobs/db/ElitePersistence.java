package com.clawx.elitemobs.db;

import com.clawx.elitemobs.EliteConfig;
import com.clawx.elitemobs.EliteMobManager;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.ai.EliteAffix;
import com.clawx.elitemobs.ai.EliteAffixHandler;
import com.clawx.elitemobs.ai.EliteBossManager;
import com.clawx.elitemobs.ai.EliteClass;
import com.clawx.elitemobs.ai.EliteClassAI;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 精英/Boss 持久化管理器（SQLite 落地）。
 *
 * <p>生命周期：</p>
 * <ul>
 *   <li>生成（makeElite）→ registerElite：入库（挂接实体 UUID）</li>
 *   <li>区块卸载 → 实体从世界移除、数据回写（解除挂接，回到潜伏）</li>
 *   <li>玩家接近（周期扫描）→ 潜伏记录物化为实体（重新挂接）</li>
 *   <li>死亡 → 删除记录</li>
 *   <li>停服 → 全量回写 + 关闭连接</li>
 * </ul>
 *
 * <p>效果：精英/Boss 固定存在于世界坐标上，不依赖模拟距离；未加载/远距离区块
 * 不再有实体，自然不会有"走过去 Boss 消失"的问题。</p>
 */
public class ElitePersistence implements Listener {
    private final EliteMobsPlugin plugin;
    private EliteDatabase db;
    private boolean enabled = false;
    // 实体 UUID → 记录 ID（内存映射，供快速查找；重启后由 DB 的 entity_uuid 兜底）
    private final Map<UUID, String> entityToRecord = new ConcurrentHashMap<>();

    public ElitePersistence(EliteMobsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() { return enabled; }

    /** 初始化：打开数据库 + 启动周期任务。返回是否启用成功。 */
    public boolean init() {
        EliteConfig cfg = plugin.getEliteConfig();
        enabled = cfg.isPersistenceEnabled();
        if (!enabled) return false;
        db = new EliteDatabase(new File(plugin.getDataFolder(), "elitemobs.db"));
        if (!db.open()) {
            plugin.getLogger().warning("[EliteMobs] SQLite 初始化失败，持久化已禁用（其余功能不受影响）");
            db = null;
            enabled = false;
            return false;
        }
        int interval = Math.max(5, cfg.getPersistenceSaveInterval());
        // 已挂接记录清理 + 血量回写（每 save-interval 秒）
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweepAttached, 100L, interval * 20L);
        // 潜伏记录物化扫描（每 2 秒，玩家接近即现身）
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::materializeRecords, 40L, 40L);
        // 过期潜伏记录清理（每小时，防数据库无限膨胀）
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::expirePending, 72000L, 72000L);
        plugin.getLogger().info("  持久化: SQLite 已启用 (" + new File(plugin.getDataFolder(), "elitemobs.db").getName()
                + ", 记录 " + db.getPending().size() + " 潜伏 / " + db.getAllAttached().size() + " 物化, Boss " + db.countBosses() + ")");
        return true;
    }

    // ==================== 写入入口 ====================

    /** makeElite 成功后入库（幂等：同一实体只入一次）。 */
    public void registerElite(LivingEntity e) {
        if (!enabled || db == null || e == null) return;
        if (entityToRecord.containsKey(e.getUniqueId())) return;
        EliteRecord r = new EliteRecord();
        r.recordId = UUID.randomUUID().toString();
        r.world = e.getWorld().getName();
        Location loc = e.getLocation();
        r.x = loc.getX();
        r.y = loc.getY();
        r.z = loc.getZ();
        r.type = e.getType().name();
        r.level = EliteMobManager.getEliteLevel(e);
        AttributeInstance hp = e.getAttribute(Attribute.MAX_HEALTH);
        r.maxHealth = hp != null ? hp.getValue() : 0;
        r.health = e.getHealth();
        r.boss = EliteBossManager.isBoss(e);
        EliteClass cls = EliteClassAI.getEliteClass(e);
        r.className = cls != null ? cls.name() : null;
        Set<EliteAffix> affixes = EliteAffixHandler.getAffixes(e);
        if (!affixes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (EliteAffix a : affixes) {
                if (sb.length() > 0) sb.append(',');
                sb.append(a.name());
            }
            r.affixes = sb.toString();
        }
        r.spawnTime = System.currentTimeMillis();
        r.reason = "spawn";
        r.entityUuid = e.getUniqueId().toString();
        r.updatedAt = r.spawnTime;
        db.upsert(r);
        entityToRecord.put(e.getUniqueId(), r.recordId);
    }

    /** 记录该实体为 Boss（晋升/物化路径调用，更新 is_boss 标记）。 */
    public void markBoss(LivingEntity e) {
        if (!enabled || db == null || e == null) return;
        String rid = entityToRecord.get(e.getUniqueId());
        if (rid == null) return;
        EliteRecord r = new EliteRecord();
        r.recordId = rid;
        // 只更新 is_boss，其余字段保持原值：先读出再改写
        EliteRecord old = findRecord(rid);
        if (old == null) return;
        old.boss = true;
        old.updatedAt = System.currentTimeMillis();
        db.upsert(old);
    }

    /** 精英传送后刷新记录位置（EliteSpawnHandler.relocateHighLevelElite 之后调用）。 */
    public void refreshPosition(LivingEntity e) {
        if (!enabled || db == null || e == null) return;
        String rid = entityToRecord.get(e.getUniqueId());
        if (rid == null) return;
        Location loc = e.getLocation();
        db.upsertLive(rid, e.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                maxHealthOf(e), e.getHealth());
    }

    /** 删除某实体的记录（死亡由监听器处理；/em clear 等手动移除调用）。 */
    public void forgetEntity(UUID entityUuid) {
        if (entityUuid == null) return;
        String rid = entityToRecord.remove(entityUuid);
        if (enabled && db != null) {
            if (rid != null) db.delete(rid);
            else db.deleteByEntityUuid(entityUuid);
        }
    }

    /** 记录 Boss 已触发二阶段（EliteBossManager.checkPhase2 调用，重物化时不重复触发广播）。 */
    public void markPhase2(LivingEntity e) {
        if (!enabled || db == null || e == null) return;
        String rid = entityToRecord.get(e.getUniqueId());
        if (rid != null) db.setPhase2(rid, true);
    }

    /** 供 Boss 布署器直接插入潜伏记录。 */
    public void insertRecord(EliteRecord r) {
        if (enabled && db != null) db.upsert(r);
    }

    public int countBosses() { return (enabled && db != null) ? db.countBosses() : 0; }

    /** 潜伏记录数（未物化，仅在数据库）。 */
    public int countPending() { return (enabled && db != null) ? db.getPending().size() : 0; }

    /** 已物化记录数（实体存活中）。 */
    public int countAttached() { return (enabled && db != null) ? db.getAllAttached().size() : 0; }

    public List<EliteRecord> getPendingBosses() {
        return (enabled && db != null) ? db.getPendingBosses() : new ArrayList<>();
    }

    // ==================== 周期任务：清理 + 物化扫描 ====================

    /**
     * 每 save-interval 秒执行：
     * 1) 已挂接记录：实体已消失（确认其区块已加载，排除"区块数据里还存着"的情况）→ 解除挂接；
     * 2) 更新存活实体的血量/位置。
     * （物化扫描由独立任务每 2 秒执行，见 materializeRecords）
     */
    private void sweepAttached() {
        if (!enabled || db == null) return;
        // 1) 已挂接记录清理
        for (EliteRecord rec : db.getAllAttached()) {
            if (rec.entityUuid == null) continue;
            UUID eid = parseUuid(rec.entityUuid);
            if (eid == null) continue;
            Entity ent = Bukkit.getEntity(eid);
            if (ent == null) {
                // 实体不在已加载实体表中：若其区块已加载则确认为丢失（自然消失/被移除），
                // 解除挂接交给物化扫描；区块未加载则保留挂接（实体可能还在区块数据里，重启场景）
                World rw = Bukkit.getWorld(rec.world);
                if (rw != null && rw.isChunkLoaded(rec.chunkX(), rec.chunkZ())) {
                    db.detach(rec.recordId);
                    entityToRecord.remove(eid);
                }
                continue;
            }
            if (ent.isDead() || !ent.isValid()) {
                db.detach(rec.recordId);
                entityToRecord.remove(eid);
                continue;
            }
            if (!(ent instanceof LivingEntity le)) { continue; }
            Chunk c = le.getChunk();
            if (!c.isLoaded()) {
                // 实体对象还在但区块已卸载（异常状态）：回写后移除实体，回到潜伏
                Location l = le.getLocation();
                db.upsertLive(rec.recordId, le.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                        maxHealthOf(le), le.getHealth());
                db.detach(rec.recordId);
                entityToRecord.remove(eid);
                le.remove();
                continue;
            }
            Location l = le.getLocation();
            db.upsertLive(rec.recordId, le.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                    maxHealthOf(le), le.getHealth());
        }
    }

    /** 过期潜伏记录清理：普通精英/潜伏 Boss 超过时限没人接近则删除（防 DB 无限膨胀）。 */
    private void expirePending() {
        if (!enabled || db == null) return;
        EliteConfig cfg = plugin.getEliteConfig();
        long eliteExpireMs = (long) cfg.getPersistencePendingExpireHours() * 3600000L;
        long bossExpireMs = (long) cfg.getBossSpawnExpireHours() * 3600000L;
        long now = System.currentTimeMillis();
        for (EliteRecord rec : db.getPending()) {
            long idle = now - rec.updatedAt;
            long limit = rec.boss ? bossExpireMs : eliteExpireMs;
            if (idle > limit) db.delete(rec.recordId);
        }
    }

    /** 扫描潜伏记录：区块已加载且玩家在物化距离内 → 物化实体。 */
    public void materializeRecords() {
        if (!enabled || db == null) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return; // 无玩家在线不扫（性能）
        EliteConfig cfg = plugin.getEliteConfig();
        double eliteDist = cfg.getPersistenceMaterializeDistance();
        double bossDist = cfg.getBossMaterializeDistance();
        for (EliteRecord rec : db.getPending()) {
            World w = Bukkit.getWorld(rec.world);
            if (w == null) continue;
            if (!w.isChunkLoaded(rec.chunkX(), rec.chunkZ())) continue;
            Location loc = new Location(w, rec.x, rec.y, rec.z);
            double dist = rec.boss ? bossDist : eliteDist;
            double d2 = dist * dist;
            boolean near = false;
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(loc) <= d2) { near = true; break; }
            }
            if (!near) continue;
            // 普通精英：每区块数量上限（复用 max-elites-per-chunk，Boss 不受限）
            if (!rec.boss && plugin.getMobManager().countElitesInChunk(w.getChunkAt(rec.chunkX(), rec.chunkZ()))
                    >= cfg.getMaxElitesPerChunk()) continue;
            materialize(rec);
        }
    }

    /** 把潜伏记录物化为实体（必须在主线程调用）。 */
    private void materialize(EliteRecord rec) {
        World w = Bukkit.getWorld(rec.world);
        if (w == null) return;
        EntityType type;
        try {
            type = EntityType.valueOf(rec.type);
        } catch (Exception ex) {
            db.delete(rec.recordId); // 类型失效（版本变更等）→ 丢弃记录
            return;
        }
        if (!w.isChunkLoaded(rec.chunkX(), rec.chunkZ())) return;
        // 物化点安全校验：记录位置可能已被方块填埋（玩家在附近建筑）→ 就近找安全点，防窒息暴毙
        Location spawnLoc = new Location(w, rec.x, rec.y, rec.z);
        if (!spawnLoc.getBlock().isPassable() || !spawnLoc.clone().add(0, 1, 0).getBlock().isPassable()) {
            Location safe = com.clawx.elitemobs.spawn.EliteSpawnHandler.findSafeSpot(
                    w, (int) Math.floor(rec.x), (int) Math.floor(rec.z));
            if (safe != null) spawnLoc = safe;
        }
        Entity ent = w.spawnEntity(spawnLoc, type);
        if (!(ent instanceof LivingEntity le)) {
            ent.remove();
            db.delete(rec.recordId);
            return;
        }
        // 恢复职业与等级（重新应用精英状态；装备/词缀随机部分重新掷定）
        EliteClass cls = null;
        if (rec.className != null) {
            try { cls = EliteClass.valueOf(rec.className); } catch (Exception ignored) {}
        }
        plugin.getMobManager().makeElite(le, rec.level, cls);
        // makeElite 内部会 registerElite 新建一条临时记录；物化实体应归属原潜伏记录，丢弃临时记录
        forgetEntity(le.getUniqueId());
        // 恢复词缀（覆盖 makeElite 的随机掷定，保持原词缀）
        if (rec.affixes != null && !rec.affixes.isEmpty() && plugin.getAffixHandler() != null) {
            plugin.getAffixHandler().applyAffixesFromString(le, rec.affixes);
        }
        if (rec.boss) {
            plugin.getBossManager().materializeBoss(le, rec.level, rec.phase2);
        }
        // 恢复血量（保存的血量可能因重掷属性略有偏差，封顶到当前最大血量）
        AttributeInstance hp = le.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = hp != null && hp.getValue() > 0 ? hp.getValue() : 1;
        double hpToSet = rec.health > 0 ? Math.min(rec.health, maxHp) : maxHp;
        le.setHealth(Math.max(0.5, hpToSet));
        // 重新挂接
        entityToRecord.put(le.getUniqueId(), rec.recordId);
        db.attach(rec.recordId, le.getUniqueId(), le.getHealth(), maxHp);
    }

    // ==================== 事件 ====================

    /**
     * 区块加载时重启对账：记录仍挂接着旧实体 UUID，但该区块里已没有对应实体
     * （停服保存的实体因数据丢失/版本变更未恢复）→ 解除挂接，交给物化扫描重新生成。
     * 实体存在（从区块数据正常恢复）→ 保持挂接，由 revalidateChunk 重新注册，不重复生成。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled || db == null) return;
        Chunk chunk = event.getChunk();
        for (EliteRecord rec : db.getAttachedInChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ())) {
            if (rec.entityUuid == null) continue;
            UUID eid = parseUuid(rec.entityUuid);
            if (eid == null) continue;
            Entity ent = Bukkit.getEntity(eid);
            if (ent == null || ent.isDead() || !ent.isValid()) {
                db.detach(rec.recordId);
                entityToRecord.remove(eid);
            } else if (ent instanceof LivingEntity le && EliteMobManager.isElite(le)) {
                // 重启后从区块数据恢复的实体：重新纳入内存管理，
                // 后续卸载/死亡走正常流程（否则它会游离在管理外，不受"接近才现身"约束）
                entityToRecord.put(eid, rec.recordId);
            }
        }
    }

    /** 区块卸载：精英/Boss 实体从世界移除，数据回写，回到潜伏状态。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!enabled || db == null) return;
        Chunk chunk = event.getChunk();
        List<LivingEntity> managed = new ArrayList<>();
        for (Entity ent : chunk.getEntities()) {
            if (ent instanceof LivingEntity le && EliteMobManager.isElite(le)
                    && entityToRecord.containsKey(le.getUniqueId())) {
                managed.add(le);
            }
        }
        for (LivingEntity le : managed) {
            String rid = entityToRecord.remove(le.getUniqueId());
            if (rid == null) continue;
            Location l = le.getLocation();
            db.upsertLive(rid, le.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                    maxHealthOf(le), le.getHealth());
            if (EliteBossManager.isBoss(le)) {
                plugin.getBossManager().onBossDeath(le); // 清理血条/技能状态（不广播）
            }
            db.detach(rid);
            le.remove();
        }
    }

    /** 精英/Boss 死亡：删除记录 + 上报击杀活跃（供 Boss 布署频率动态调整）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEliteDeath(EntityDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!EliteMobManager.isElite(e)) return;
        // 上报击杀活跃（Boss 布署间隔会随击杀变短；与持久化开关无关）
        if (plugin.getBossSpawner() != null) plugin.getBossSpawner().recordEliteKill();
        if (!enabled || db == null) return;
        String rid = entityToRecord.remove(e.getUniqueId());
        if (rid != null) db.delete(rid);
        else db.deleteByEntityUuid(e.getUniqueId()); // 重启后内存映射丢失时的兜底
    }

    // ==================== 停服 ====================

    /** 停服：全量回写存活实体后关闭连接。 */
    public void saveAllAndClose() {
        if (!enabled || db == null) return;
        for (EliteRecord rec : db.getAllAttached()) {
            if (rec.entityUuid == null) continue;
            UUID eid = parseUuid(rec.entityUuid);
            if (eid == null) continue;
            Entity ent = Bukkit.getEntity(eid);
            if (ent instanceof LivingEntity le && le.isValid() && !le.isDead()) {
                Location l = le.getLocation();
                db.upsertLive(rec.recordId, le.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                        maxHealthOf(le), le.getHealth());
            }
        }
        db.close();
        enabled = false;
    }

    // ==================== 工具 ====================

    private EliteRecord findRecord(String recordId) {
        for (EliteRecord r : db.getPending()) if (recordId.equals(r.recordId)) return r;
        for (EliteRecord r : db.getAllAttached()) if (recordId.equals(r.recordId)) return r;
        return null;
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static double maxHealthOf(LivingEntity e) {
        AttributeInstance hp = e.getAttribute(Attribute.MAX_HEALTH);
        return hp != null ? hp.getValue() : 0;
    }
}
