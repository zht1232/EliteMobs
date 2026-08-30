package com.clawx.elitemobs.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 精英/Boss 数据 SQLite 存储。
 *
 * <p>驱动使用 org.xerial:sqlite-jdbc（构建时打进 jar，含各平台原生库）。
 * 所有读写走主线程 + 单连接 + WAL + busy_timeout，避免 "database is locked"。</p>
 */
public class EliteDatabase {
    private final File dbFile;
    private Connection conn;

    public EliteDatabase(File dbFile) {
        this.dbFile = dbFile;
    }

    /** 打开数据库并建表。失败返回 false（调用方应降级为无持久化运行）。 */
    public boolean open() {
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS elite_mobs ("
                        + "record_id TEXT PRIMARY KEY,"
                        + "world TEXT NOT NULL,"
                        + "chunk_x INTEGER NOT NULL,"
                        + "chunk_z INTEGER NOT NULL,"
                        + "x REAL NOT NULL DEFAULT 0,"
                        + "y REAL NOT NULL DEFAULT 0,"
                        + "z REAL NOT NULL DEFAULT 0,"
                        + "type TEXT NOT NULL,"
                        + "level INTEGER NOT NULL DEFAULT 1,"
                        + "max_health REAL NOT NULL DEFAULT 0,"
                        + "health REAL NOT NULL DEFAULT 0,"
                        + "is_boss INTEGER NOT NULL DEFAULT 0,"
                        + "class_name TEXT,"
                        + "affixes TEXT,"
                        + "phase2 INTEGER NOT NULL DEFAULT 0,"
                        + "spawn_time INTEGER NOT NULL DEFAULT 0,"
                        + "reason TEXT,"
                        + "entity_uuid TEXT,"
                        + "updated_at INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_elite_mobs_chunk ON elite_mobs (world, chunk_x, chunk_z)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_elite_mobs_pending ON elite_mobs (entity_uuid)");
            }
            return true;
        } catch (Exception e) {
            conn = null;
            return false;
        }
    }

    public boolean isOpen() { return conn != null; }

    /** 插入或整行更新记录。 */
    public synchronized void upsert(EliteRecord r) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO elite_mobs (record_id, world, chunk_x, chunk_z, x, y, z, type, level,"
                + " max_health, health, is_boss, class_name, affixes, phase2, spawn_time, reason, entity_uuid, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, r.recordId);
            ps.setString(2, r.world);
            ps.setInt(3, r.chunkX());
            ps.setInt(4, r.chunkZ());
            ps.setDouble(5, r.x);
            ps.setDouble(6, r.y);
            ps.setDouble(7, r.z);
            ps.setString(8, r.type);
            ps.setInt(9, r.level);
            ps.setDouble(10, r.maxHealth);
            ps.setDouble(11, r.health);
            ps.setInt(12, r.boss ? 1 : 0);
            ps.setString(13, r.className);
            ps.setString(14, r.affixes);
            ps.setInt(15, r.phase2 ? 1 : 0);
            ps.setLong(16, r.spawnTime);
            ps.setString(17, r.reason);
            ps.setString(18, r.entityUuid);
            ps.setLong(19, r.updatedAt);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** 删除记录（死亡 / 手动清除）。 */
    public synchronized void delete(String recordId) {
        if (conn == null || recordId == null) return;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM elite_mobs WHERE record_id=?")) {
            ps.setString(1, recordId);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** 按已物化实体 UUID 删除（重启后内存映射丢失时的兜底）。 */
    public synchronized void deleteByEntityUuid(UUID uuid) {
        if (conn == null || uuid == null) return;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM elite_mobs WHERE entity_uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** 更新已物化实体的位置/血量（不改变挂接状态）。 */
    public synchronized void upsertLive(String recordId, String world, double x, double y, double z,
                                        double maxHealth, double health) {
        if (conn == null || recordId == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE elite_mobs SET world=?, chunk_x=?, chunk_z=?, x=?, y=?, z=?, max_health=?, health=?, updated_at=? WHERE record_id=?")) {
            ps.setString(1, world);
            ps.setInt(2, (int) Math.floor(x / 16.0));
            ps.setInt(3, (int) Math.floor(z / 16.0));
            ps.setDouble(4, x);
            ps.setDouble(5, y);
            ps.setDouble(6, z);
            ps.setDouble(7, maxHealth);
            ps.setDouble(8, health);
            ps.setLong(9, System.currentTimeMillis());
            ps.setString(10, recordId);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** 物化成功后挂接新实体 UUID，并记录实际血量。 */
    public synchronized void attach(String recordId, UUID uuid, double health, double maxHealth) {
        if (conn == null || recordId == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE elite_mobs SET entity_uuid=?, health=?, max_health=?, updated_at=? WHERE record_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, health);
            ps.setDouble(3, maxHealth);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, recordId);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** 解除挂接（实体已随区块卸载移除），回到潜伏状态。 */
    public synchronized void detach(String recordId) {
        if (conn == null || recordId == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE elite_mobs SET entity_uuid=NULL, updated_at=? WHERE record_id=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, recordId);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** 记录 Boss 是否已触发二阶段（重物化时不重复触发）。 */
    public synchronized void setPhase2(String recordId, boolean phase2) {
        if (conn == null || recordId == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE elite_mobs SET phase2=?, updated_at=? WHERE record_id=?")) {
            ps.setInt(1, phase2 ? 1 : 0);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, recordId);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** 所有潜伏记录（未物化）。 */
    public synchronized List<EliteRecord> getPending() {
        List<EliteRecord> list = new ArrayList<>();
        if (conn == null) return list;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM elite_mobs WHERE entity_uuid IS NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(read(rs));
        } catch (Exception ignored) {}
        return list;
    }

    /** 所有潜伏 Boss。 */
    public synchronized List<EliteRecord> getPendingBosses() {
        List<EliteRecord> list = new ArrayList<>();
        if (conn == null) return list;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM elite_mobs WHERE entity_uuid IS NULL AND is_boss=1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(read(rs));
        } catch (Exception ignored) {}
        return list;
    }

    /** 所有已挂接记录（entity_uuid 非空），用于周期性清理扫描。 */
    public synchronized List<EliteRecord> getAllAttached() {
        List<EliteRecord> list = new ArrayList<>();
        if (conn == null) return list;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM elite_mobs WHERE entity_uuid IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(read(rs));
        } catch (Exception ignored) {}
        return list;
    }

    /** 某区块内已挂接的记录（entity_uuid 非空），用于区块加载时的重启对账。 */
    public synchronized List<EliteRecord> getAttachedInChunk(String world, int chunkX, int chunkZ) {
        List<EliteRecord> list = new ArrayList<>();
        if (conn == null) return list;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM elite_mobs WHERE world=? AND chunk_x=? AND chunk_z=? AND entity_uuid IS NOT NULL")) {
            ps.setString(1, world);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(read(rs));
            }
        } catch (Exception ignored) {}
        return list;
    }

    /** 当前 Boss 总数（含潜伏 + 已物化），用于限制并发 Boss 数量。 */
    public synchronized int countBosses() {
        if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM elite_mobs WHERE is_boss=1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (Exception ignored) {}
        return 0;
    }

    /** 关闭连接（插件停用时调用）。 */
    public synchronized void close() {
        if (conn != null) {
            try { conn.close(); } catch (Exception ignored) {}
            conn = null;
        }
    }

    private static EliteRecord read(ResultSet rs) throws Exception {
        EliteRecord r = new EliteRecord();
        r.recordId = rs.getString("record_id");
        r.world = rs.getString("world");
        r.x = rs.getDouble("x");
        r.y = rs.getDouble("y");
        r.z = rs.getDouble("z");
        r.type = rs.getString("type");
        r.level = rs.getInt("level");
        r.maxHealth = rs.getDouble("max_health");
        r.health = rs.getDouble("health");
        r.boss = rs.getInt("is_boss") == 1;
        r.className = rs.getString("class_name");
        r.affixes = rs.getString("affixes");
        r.phase2 = rs.getInt("phase2") == 1;
        r.spawnTime = rs.getLong("spawn_time");
        r.reason = rs.getString("reason");
        r.entityUuid = rs.getString("entity_uuid");
        r.updatedAt = rs.getLong("updated_at");
        return r;
    }
}
