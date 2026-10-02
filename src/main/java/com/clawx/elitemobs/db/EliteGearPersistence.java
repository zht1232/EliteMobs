package com.clawx.elitemobs.db;

import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.essence.EliteEssenceUpgradeListener;
import com.clawx.elitemobs.essence.EliteGemFactory;
import com.clawx.elitemobs.rune.EliteRuneFactory;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stores full EliteMobs item snapshots and a readable state summary in SQLite.
 * Item PDC carries a stable id and schema version; future code migrations can rewrite
 * offline snapshots, then hydrate them automatically when the owner next logs in.
 */
public final class EliteGearPersistence implements Listener {
    private static final String TABLE = "elite_gear_state";

    private final EliteMobsPlugin plugin;
    private final EliteEssenceUpgradeListener gearMigrator;
    private Connection connection;
    private BukkitTask scanTask;

    public EliteGearPersistence(EliteMobsPlugin plugin, EliteEssenceUpgradeListener gearMigrator) {
        this.plugin = plugin;
        this.gearMigrator = gearMigrator;
    }

    public boolean init() {
        try {
            Class.forName("org.sqlite.JDBC");
            File file = new File(plugin.getDataFolder(), "elitemobs.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "item_id TEXT PRIMARY KEY,"
                        + "owner_uuid TEXT NOT NULL,"
                        + "container TEXT NOT NULL,"
                        + "slot INTEGER NOT NULL,"
                        + "schema_version INTEGER NOT NULL DEFAULT 0,"
                        + "revision INTEGER NOT NULL DEFAULT 1,"
                        + "state_summary TEXT NOT NULL,"
                        + "item_data BLOB NOT NULL,"
                        + "item_hash TEXT NOT NULL,"
                        + "updated_at INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_elite_gear_owner ON " + TABLE + " (owner_uuid)");
            }
            migrateStoredItems();
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            plugin.getServer().getScheduler().runTaskLater(plugin, this::scanOnlinePlayers, 1L);
            scanTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::scanOnlinePlayers, 200L, 200L);
            plugin.getLogger().info("  装备数据库: SQLite 已启用（玩家背包/末影箱中的 EliteMobs 装备自动索引）");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[EliteMobs] 装备数据库初始化失败；物品仍保存在玩家存档中: " + e.getMessage());
            closeConnection();
            return false;
        }
    }

    /** Migrate stored snapshots before players log in. */
    private void migrateStoredItems() {
        if (connection == null) return;
        List<GearRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT item_id, owner_uuid, container, slot, schema_version, revision, item_data "
                        + "FROM " + TABLE + " WHERE schema_version < ?")) {
            ps.setInt(1, EliteGemFactory.ITEM_SCHEMA_VERSION);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new GearRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getBytes(7), null, null));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[EliteMobs] 装备数据库迁移读取失败: " + e.getMessage());
            return;
        }
        int migrated = 0;
        for (GearRow row : rows) {
            try {
                ItemStack item = ItemStack.deserializeBytes(row.itemData);
                if (!gearMigrator.isEliteGearItem(item)) continue;
                gearMigrator.migrateGearItem(item);
                setSchemaVersion(item, EliteGemFactory.ITEM_SCHEMA_VERSION);
                setRevision(item, row.revision + 1);
                saveRow(row.itemId, UUID.fromString(row.ownerUuid), row.container, row.slot,
                        row.revision + 1, item);
                migrated++;
            } catch (Exception e) {
                plugin.getLogger().warning("[EliteMobs] 一件离线装备迁移失败，保留原记录 " + row.itemId + ": " + e.getMessage());
            }
        }
        if (migrated > 0) plugin.getLogger().info("  装备数据库: 已离线迁移 " + migrated + " 件旧装备");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> scanPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        scanPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleScan(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleScan(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        scheduleScan(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) scheduleScan(player);
    }

    private void scheduleScan(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> scanPlayer(player));
    }

    private void scanOnlinePlayers() {
        if (connection == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) scanPlayer(player);
    }

    private void scanPlayer(Player player) {
        if (connection == null || player == null) return;
        Set<String> seen = new HashSet<>();
        scanInventory(player, player.getInventory(), "inventory", seen);
        scanInventory(player, player.getEnderChest(), "ender_chest", seen);
    }

    private void scanInventory(Player owner, Inventory inventory, String container, Set<String> seen) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR || !gearMigrator.isEliteGearItem(item)) continue;

            String itemId = readItemId(item);
            boolean itemChanged = false;
            if (itemId == null || !seen.add(itemId)) {
                itemId = UUID.randomUUID().toString();
                setItemId(item, itemId);
                itemChanged = true;
                seen.add(itemId);
            }

            GearRow row = readRow(itemId);
            int itemSchema = readSchemaVersion(item);
            int itemRevision = readRevision(item);
            if (itemSchema > EliteGemFactory.ITEM_SCHEMA_VERSION
                    || (row != null && row.schemaVersion > EliteGemFactory.ITEM_SCHEMA_VERSION)) continue;
            if (row != null && (row.schemaVersion > itemSchema || row.revision > itemRevision)
                    && row.schemaVersion <= EliteGemFactory.ITEM_SCHEMA_VERSION) {
                try {
                    ItemStack migrated = ItemStack.deserializeBytes(row.itemData);
                    if (itemId.equals(readItemId(migrated)) && migrated.getType() == item.getType()) {
                        item = migrated;
                        setRevision(item, row.revision);
                        setSchemaVersion(item, row.schemaVersion);
                        itemChanged = true;
                    } else {
                        // Material changed in-game since the last snapshot; keep the live item
                        // and advance its revision so the stale database copy cannot replace it.
                        setRevision(item, row.revision);
                        itemChanged = true;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[EliteMobs] 装备数据库恢复失败，保留玩家当前物品: " + e.getMessage());
                }
            }
            if (readSchemaVersion(item) < EliteGemFactory.ITEM_SCHEMA_VERSION) {
                itemChanged |= gearMigrator.migrateGearItem(item);
                setSchemaVersion(item, EliteGemFactory.ITEM_SCHEMA_VERSION);
                itemChanged = true;
            }
            itemChanged |= saveIfChanged(itemId, owner.getUniqueId(), container, slot, item, row);
            if (itemChanged) inventory.setItem(slot, item);
        }
    }

    private boolean saveIfChanged(String itemId, UUID owner, String container, int slot,
                                  ItemStack item, GearRow old) {
        try {
            byte[] bytes = item.serializeAsBytes();
            String hash = sha256(bytes);
            String summary = summarize(item);
            int version = readSchemaVersion(item);
            int itemRevision = readRevision(item);
            if (old != null && old.revision > itemRevision) return false;
            int revision = old == null ? Math.max(1, itemRevision)
                    : old.revision + (old.stateSummary.equals(summary) ? 0 : 1);
            boolean pdcChanged = revision != itemRevision;
            if (pdcChanged) {
                setRevision(item, revision);
                bytes = item.serializeAsBytes();
                hash = sha256(bytes);
            }
            if (old != null && old.schemaVersion == version && old.revision == revision
                    && old.itemHash != null && old.itemHash.equals(hash)
                    && owner.toString().equals(old.ownerUuid)
                    && container.equals(old.container) && slot == old.slot) return pdcChanged;
            saveRow(itemId, owner, container, slot, revision, version, summary, bytes, hash);
            return pdcChanged;
        } catch (Exception e) {
            plugin.getLogger().warning("[EliteMobs] 装备数据库保存失败: " + e.getMessage());
            return false;
        }
    }

    private void saveRow(String itemId, UUID owner, String container, int slot, int revision, ItemStack item) throws Exception {
        byte[] bytes = item.serializeAsBytes();
        saveRow(itemId, owner, container, slot, revision, readSchemaVersion(item), summarize(item), bytes, sha256(bytes));
    }

    private void saveRow(String itemId, UUID owner, String container, int slot, int revision,
                         int schema, String summary, byte[] bytes, String hash) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO " + TABLE
                        + " (item_id, owner_uuid, container, slot, schema_version, revision, state_summary, item_data, item_hash, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, itemId);
            ps.setString(2, owner.toString());
            ps.setString(3, container);
            ps.setInt(4, slot);
            ps.setInt(5, schema);
            ps.setInt(6, revision);
            ps.setString(7, summary);
            ps.setBytes(8, bytes);
            ps.setString(9, hash);
            ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private GearRow readRow(String itemId) {
        if (connection == null) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT item_id, owner_uuid, container, slot, schema_version, revision, item_data, item_hash, state_summary "
                        + "FROM " + TABLE + " WHERE item_id=?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new GearRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getBytes(7), rs.getString(8), rs.getString(9));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[EliteMobs] 装备数据库读取失败: " + e.getMessage());
        }
        return null;
    }

    private String summarize(ItemStack item) {
        YamlConfiguration state = new YamlConfiguration();
        state.set("material", item.getType().name());
        state.set("schema-version", readSchemaVersion(item));
        state.set("refine-level", EliteGemFactory.totalGemLevel(item));
        state.set("quality", EliteGemFactory.getQuality(item));
        state.set("proficiency-stars", EliteGemFactory.getProf(item));
        state.set("proficiency-kills", EliteGemFactory.getProfKills(item));
        ItemAttributeModifiers modifiers = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        List<Map<String, Object>> attributes = new ArrayList<>();
        if (modifiers != null) {
            for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
                Map<String, Object> value = new java.util.LinkedHashMap<>();
                value.put("attribute", entry.attribute().toString());
                value.put("key", entry.modifier().getKey().toString());
                value.put("amount", entry.modifier().getAmount());
                value.put("operation", entry.modifier().getOperation().name());
                value.put("slot", entry.getGroup().toString());
                attributes.add(value);
            }
        }
        state.set("attributes", attributes);
        List<String> gems = new ArrayList<>();
        String[] ids = EliteGemFactory.getInstalledGems(item);
        int[] levels = EliteGemFactory.getInstalledGemLevels(item);
        for (int i = 0; i < ids.length; i++) if (ids[i] != null) gems.add(i + ":" + ids[i] + ":" + levels[i]);
        state.set("gems", gems);
        List<String> runes = new ArrayList<>();
        var meta = item.getItemMeta();
        if (meta != null) {
            var pdc = meta.getPersistentDataContainer();
            for (int i = 0; i < EliteRuneFactory.KEY_SLOTS.length; i++) {
                String id = pdc.get(EliteRuneFactory.KEY_SLOTS[i], PersistentDataType.STRING);
                if (id != null) {
                    Integer level = pdc.get(EliteRuneFactory.KEY_SLOT_LEVELS[i], PersistentDataType.INTEGER);
                    runes.add(i + ":" + id + ":" + (level == null ? 1 : level));
                }
            }
        }
        state.set("runes", runes);
        return state.saveToString();
    }

    private static String readItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(EliteGemFactory.KEY_ITEM_ID, PersistentDataType.STRING);
    }

    private static int readSchemaVersion(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(
                EliteGemFactory.KEY_ITEM_SCHEMA, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private static int readRevision(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(
                EliteGemFactory.KEY_ITEM_REVISION, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private static void setItemId(ItemStack item, String id) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(
                EliteGemFactory.KEY_ITEM_ID, PersistentDataType.STRING, id));
    }

    private static void setSchemaVersion(ItemStack item, int version) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(
                EliteGemFactory.KEY_ITEM_SCHEMA, PersistentDataType.INTEGER, version));
    }

    private static void setRevision(ItemStack item, int revision) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(
                EliteGemFactory.KEY_ITEM_REVISION, PersistentDataType.INTEGER, revision));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Save current snapshots before the SQLite connection closes. */
    public void close() {
        if (scanTask != null) scanTask.cancel();
        scanTask = null;
        scanOnlinePlayers();
        closeConnection();
    }

    private void closeConnection() {
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
    }

    private record GearRow(String itemId, String ownerUuid, String container, int slot,
                           int schemaVersion, int revision, byte[] itemData, String itemHash, String stateSummary) {}
}
