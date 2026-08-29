package com.clawx.elitemobs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import com.clawx.elitemobs.ai.WallClimbAI;
import com.clawx.elitemobs.ai.BlockBreakAI;
import com.clawx.elitemobs.ai.ItemStealAI;
import com.clawx.elitemobs.ai.EliteClassAI;
import com.clawx.elitemobs.ai.EliteBossManager;
import com.clawx.elitemobs.ai.EliteAffixHandler;
import com.clawx.elitemobs.combat.DamageScaler;
import com.clawx.elitemobs.combat.WeaponEnhancer;
import com.clawx.elitemobs.essence.EliteEssenceUpgradeListener;
import com.clawx.elitemobs.spawn.EliteSpawnHandler;
import com.clawx.elitemobs.commands.EliteMobsCommand;
import java.io.File;
import java.util.Objects;

public final class EliteMobsPlugin extends JavaPlugin {
    private static EliteMobsPlugin instance;
    private EliteConfig eliteConfig;
    private EliteMobManager mobManager;
    private DamageScaler damageScaler;
    private WeaponEnhancer weaponEnhancer;
    private WallClimbAI wallClimbAI;
    private BlockBreakAI blockBreakAI;
    private ItemStealAI itemStealAI;
    private EliteClassAI eliteClassAI;
    private EliteBossManager bossManager;
    private EliteAffixHandler affixHandler;
    private EliteCombatListener combatListener;
    private EliteEssenceUpgradeListener essenceListener;
    private org.bukkit.configuration.file.FileConfiguration messages;

    /** 启动 ASCII Art Banner（纯 ASCII：/ \ | _ 等符号，78 列 x 5 行，避免 GBK 控制台乱码）。 */
    private static final String[] STARTUP_BANNER = {
            " ______    _        _    _____    _____    __  __     ____     _        ____  ",
            "|  ____|  | |      | |  |_   _|  |  ___|  |  \\/  |   / __ \\   | |      / ___| ",
            "| |__     | |      | |    | |    | |__    | \\  / |  | |  | |  | |__    \\___ \\ ",
            "|  __|    | |__    | |    | |    |  __|   | |\\/| |  | |__| |  | '_ \\    ___) |",
            "|_____|   |____|   |_|    |_|    |_|      |_|  |_|   \\____/   |_.__/   |____/ ",
    };

    @Override public void onEnable() {
        instance = this;
        long start = System.currentTimeMillis();
        saveDefaultConfig();
        reloadConfig();
        loadMessages();
        saveDefaultGems();
        saveDefaultMobs();
        EconomyHook.init();
        eliteConfig = new EliteConfig(this);
        mobManager = new EliteMobManager(this);
        damageScaler = new DamageScaler(this);
        weaponEnhancer = new WeaponEnhancer(this);
        wallClimbAI = new WallClimbAI(this);
        blockBreakAI = new BlockBreakAI(this);
        itemStealAI = new ItemStealAI(this);
        eliteClassAI = new EliteClassAI(this);
        bossManager = new EliteBossManager(this);
        getServer().getPluginManager().registerEvents(bossManager, this); // Boss 技能事件（跳跃扑击等）
        affixHandler = new EliteAffixHandler(this);
        essenceListener = new EliteEssenceUpgradeListener(this);
        getServer().getPluginManager().registerEvents(essenceListener, this);
        com.clawx.elitemobs.rune.EliteRuneListener runeListener = new com.clawx.elitemobs.rune.EliteRuneListener(this);
        getServer().getPluginManager().registerEvents(runeListener, this);
        runeListener.startRunePotionTask();
        getServer().getPluginManager().registerEvents(affixHandler, this);
        getServer().getPluginManager().registerEvents(new EliteSpawnHandler(this), this);
        combatListener = new EliteCombatListener(this);
        getServer().getPluginManager().registerEvents(combatListener, this);
        combatListener.startSetBonusTask();
        combatListener.startMagnetTask();
        combatListener.startTargetTask();
        combatListener.startDoubleJumpTask();
        getServer().getPluginManager().registerEvents(wallClimbAI, this);
        getServer().getPluginManager().registerEvents(blockBreakAI, this);
        getServer().getPluginManager().registerEvents(itemStealAI, this);
        getServer().getPluginManager().registerEvents(eliteClassAI, this);
        getServer().getPluginManager().registerEvents(damageScaler, this);
        mobManager.startVanillaNightBoostTask();
        com.clawx.elitemobs.ai.WorldAIListener worldAI = new com.clawx.elitemobs.ai.WorldAIListener(this);
        getServer().getPluginManager().registerEvents(worldAI, this);
        worldAI.startTask();
        Objects.requireNonNull(getCommand("elitemobs")).setExecutor(new EliteMobsCommand(this));
        com.clawx.elitemobs.gui.EliteMenu eliteMenu = new com.clawx.elitemobs.gui.EliteMenu(this);
        getServer().getPluginManager().registerEvents(eliteMenu, this);
        Objects.requireNonNull(getCommand("emmenu")).setExecutor(eliteMenu);
        startAITask();
        registerPapi();
        long elapsed = System.currentTimeMillis() - start;
        for (String line : STARTUP_BANNER) getLogger().info(line);
        getLogger().info("========== EliteMobs v" + getDescription().getVersion() + " ==========");
        getLogger().info("  状态: 已启用 | 耗时: " + elapsed + "ms");
        getLogger().info("  精英种类: " + eliteConfig.getEnabledMobTypes().size() + " 个 | 生成概率: " + String.format("%.1f%%", eliteConfig.getEliteSpawnChance()*100));
        getLogger().info("  爬墙: " + yn(eliteConfig.isWallClimbEnabled())
                + " | 破块: " + yn(eliteConfig.isBlockBreakEnabled())
                + " | 偷窃: " + yn(eliteConfig.isItemStealEnabled())
                + " | 伤害成长: " + yn(eliteConfig.isDamageScalingEnabled()));
        getLogger().info("  兼容: WorldGuard/GriefPrevention/Towny/Factions/MythicMobs/Essentials/PlaceholderAPI/LuckPerms");
        if (eliteConfig.isLuckPermsEnabled())
            getLogger().info("  LuckPerms: " + eliteConfig.getLuckPermsGroups().size() + " 个组");
        getLogger().info("  掉落模式: " + eliteConfig.getGemDropMode());
        getLogger().info("  经济: Vault " + (EconomyHook.isVaultReady() ? ChatColor.GREEN + "已连接" : ChatColor.RED + "未安装")
                + ChatColor.RESET + " | PlayerPoints " + (EconomyHook.isPlayerPointsReady() ? ChatColor.GREEN + "已连接" : ChatColor.RED + "未安装"));
        getLogger().info("  作者: crystalkingdom团队 | Paper 1.21+ | JDK 21");
        getLogger().info("==================================================");
    }

    @Override public void onDisable() { getLogger().info("EliteMobs 已停用。"); }

    private void startAITask() {
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!eliteConfig.isEnabled()) return;
            mobManager.tickAllEliteMobs();
        }, 20L, eliteConfig.getAITickInterval());
    }

    public void reload() { reloadConfig(); loadMessages(); EconomyHook.init(); eliteConfig = new EliteConfig(this); damageScaler.reload(); weaponEnhancer.reload(); com.clawx.elitemobs.compat.ProtectionHook.reset(); }

    private void loadMessages() {
        saveResource("messages.yml", true);
        File file = new File(getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(file);
    }

    /** 首次启动时复制 gems/*.yml 默认宝石配置（不覆盖用户已有文件）。 */
    private void saveDefaultGems() {
        File dir = new File(getDataFolder(), "gems");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        for (String name : new String[]{"attack_gem.yml", "defense_gem.yml", "rare_skull.yml", "magnet_gem.yml", "thunder_gem.yml", "knockback_gem.yml", "double_jump_gem.yml", "lifesteal_gem.yml", "unbreaking_gem.yml", "fire_aspect_gem.yml"}) {
            File f = new File(dir, name);
            if (!f.exists()) saveResource("gems/" + name, false);
        }
    }

    /** 首次启动时复制 mobs.yml 默认怪物定义（不覆盖用户已有文件）。 */
    private void saveDefaultMobs() {
        File f = new File(getDataFolder(), "mobs.yml");
        if (!f.exists()) saveResource("mobs.yml", false);
    }

    public org.bukkit.configuration.file.FileConfiguration getMessages() { return messages; }

    public static EliteMobsPlugin getInstance() { return instance; }
    public EliteConfig getEliteConfig() { return eliteConfig; }
    public EliteMobManager getMobManager() { return mobManager; }
    public DamageScaler getDamageScaler() { return damageScaler; }
    public WeaponEnhancer getWeaponEnhancer() { return weaponEnhancer; }
    public WallClimbAI getWallClimbAI() { return wallClimbAI; }
    public BlockBreakAI getBlockBreakAI() { return blockBreakAI; }
    public ItemStealAI getItemStealAI() { return itemStealAI; }
    public EliteClassAI getEliteClassAI() { return eliteClassAI; }
    public EliteBossManager getBossManager() { return bossManager; }
    public EliteAffixHandler getAffixHandler() { return affixHandler; }
    public EliteCombatListener getCombatListener() { return combatListener; }
    public EliteEssenceUpgradeListener getEssenceListener() { return essenceListener; }

    /** 注册 PlaceholderAPI 占位符（软依赖，未装 PAPI 时静默跳过） */
    private void registerPapi() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        try {
            new ElitePapiExpansion(this).register();
            getLogger().info("  PlaceholderAPI 占位符已注册 (%elitemobs_*)");
        } catch (Throwable t) {
            getLogger().warning("PlaceholderAPI 注册失败: " + t.getMessage());
        }
    }

    private static String yn(boolean b) { return b ? ChatColor.GREEN + "ON" + ChatColor.RESET : ChatColor.RED + "OFF" + ChatColor.RESET; }
}
