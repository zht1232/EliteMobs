package com.clawx.elitemobs.db;

/**
 * 精英/Boss 持久化记录（SQLite 一行）。
 *
 * <p>核心思想：实体不常驻世界，数据入库。玩家接近时从记录"物化"出实体；
 * 区块卸载时实体移除、数据回写。这样精英/Boss 固定存在于世界坐标上，
 * 不再依赖模拟距离/玩家附近自然生成，也不会出现在未加载区块里"凭空消失"。</p>
 *
 * <p>entityUuid 为 null 表示"潜伏"状态（未物化，只有数据），非 null 表示实体已物化。</p>
 */
public class EliteRecord {
    public String recordId;      // 记录主键（与实体 UUID 独立，物化会生成新实体 UUID）
    public String world;         // 世界名
    public double x, y, z;       // 位置（物化点）
    public String type;          // EntityType 名称
    public int level;            // 精英等级
    public double maxHealth;     // 最大血量（0=物化时按实际计算）
    public double health;        // 当前血量（0=满血）
    public boolean boss;         // 是否 Boss
    public String className;     // 职业名（可空，物化时恢复）
    public String affixes;       // 词缀列表（逗号分隔，可空）
    public boolean phase2;       // 是否已触发二阶段（Boss）
    public long spawnTime;       // 记录创建时间（毫秒）
    public String reason;        // 来源：spawn / boss_scheduler / command ...
    public String entityUuid;    // 已物化实体的 UUID（null=潜伏）
    public long updatedAt;       // 最后入库时间（毫秒）

    public int chunkX() { return (int) Math.floor(x / 16.0); }
    public int chunkZ() { return (int) Math.floor(z / 16.0); }
}
