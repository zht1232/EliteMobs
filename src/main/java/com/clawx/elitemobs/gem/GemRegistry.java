package com.clawx.elitemobs.gem;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import com.clawx.elitemobs.EliteMobsPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宝石注册表：加载 {@code plugins/EliteMobs/gems/*.yml} 中的宝石定义。
 *
 * <p>格式与 SnowyGems 的 gems 目录完全兼容：每个 YAML 文件含若干宝石条目，
 * 顶层键即宝石 ID。支持字段：Name/Type/Require/Display/Tips/Texture/Material/
 * Color/Glow/Eat/Success/Embed/SuccessTip/RemoveTip/FailTip/Rewards/RandomPool。</p>
 */
public final class GemRegistry {
    private final EliteMobsPlugin plugin;
    private final Map<String, GemConfig> gems = new ConcurrentHashMap<>();
    private final Map<String, List<String>> categories = new ConcurrentHashMap<>();

    public GemRegistry(EliteMobsPlugin plugin) {
        this.plugin = plugin;
        loadAll();
    }

    /** 重新加载全部宝石定义（/em reload 时调用）。 */
    public void reload() {
        gems.clear();
        categories.clear();
        loadAll();
    }

    private void loadAll() {
        File dir = new File(plugin.getDataFolder(), "gems");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建宝石配置目录: " + dir.getAbsolutePath());
            return;
        }
        // 首次运行：从 jar 释放默认宝石配置
        saveDefaultGems(dir);

        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            try {
                loadFile(f);
            } catch (Exception e) {
                plugin.getLogger().warning("加载宝石文件失败 " + f.getName() + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("  宝石系统: 已加载 " + gems.size() + " 种宝石");
    }

    private void saveDefaultGems(File dir) {
        String[] defaults = {"AttributeGem.yml", "EnchantGem.yml", "FunctionGem.yml",
                "ModernGem.yml", "PlayerGem.yml", "PotionGem.yml"};
        for (String name : defaults) {
            File target = new File(dir, name);
            if (target.exists()) continue;
            try {
                plugin.saveResource("gems/" + name, false);
            } catch (IllegalArgumentException ignored) {
                // jar 中无此资源，跳过
            }
        }
    }

    private void loadFile(File f) throws Exception {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        for (String id : cfg.getKeys(false)) {
            ConfigurationSection sec = cfg.getConfigurationSection(id);
            if (sec == null) continue;
            GemConfig gem = parse(id, sec);
            if (gem != null) {
                gems.put(id, gem);
                categories.computeIfAbsent(gem.category, k -> new ArrayList<>()).add(id);
            }
        }
    }

    private GemConfig parse(String id, ConfigurationSection sec) {
        List<String> require = new ArrayList<>(sec.getStringList("Require"));
        List<String> tips = new ArrayList<>(sec.getStringList("Tips"));
        List<String> rewards = new ArrayList<>(sec.getStringList("Rewards"));

        // 兼容单行写法
        if (sec.isString("Tips")) tips.add(sec.getString("Tips"));
        if (sec.isString("Rewards")) rewards.add(sec.getString("Rewards"));

        Map<String, Integer> randomPool = new LinkedHashMap<>();
        ConfigurationSection rp = sec.getConfigurationSection("RandomPool");
        if (rp != null) {
            for (String k : rp.getKeys(false)) {
                randomPool.put(k, Math.max(1, rp.getInt(k, 1)));
            }
        }

        return new GemConfig(
                id,
                sec.getString("Name", id),
                GemType.fromString(sec.getString("Type", "NORMAL")),
                require,
                sec.getString("Display"),
                tips,
                sec.getString("Texture"),
                sec.getString("Material"),
                sec.getBoolean("Glow", false),
                sec.getInt("Success", 100),
                sec.getInt("Embed", 1),
                sec.getString("Color"),
                sec.getBoolean("Eat", false),
                sec.getString("SuccessTip"),
                sec.getString("RemoveTip"),
                sec.getString("FailTip"),
                rewards,
                randomPool,
                sec.getString("Category", "default"));
    }

    /** 按 ID 获取宝石；不存在返回 null。 */
    public GemConfig get(String id) {
        return id == null ? null : gems.get(id);
    }

    /** 全部宝石 ID（保序）。 */
    public Set<String> getIds() {
        return Collections.unmodifiableSet(gems.keySet());
    }

    /** 全部宝石。 */
    public Collection<GemConfig> all() {
        return Collections.unmodifiableCollection(gems.values());
    }

    /** 某类别下的宝石 ID。 */
    public List<String> getCategory(String category) {
        return categories.getOrDefault(category, Collections.emptyList());
    }

    public int size() { return gems.size(); }
}
