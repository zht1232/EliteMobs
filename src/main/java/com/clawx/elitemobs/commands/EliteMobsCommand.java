package com.clawx.elitemobs.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.ItemStack;
import com.clawx.elitemobs.EliteMobsPlugin;
import com.clawx.elitemobs.EliteMobManager;
import java.util.*;
import java.util.stream.Collectors;

public class EliteMobsCommand implements CommandExecutor, TabCompleter {
    private final EliteMobsPlugin plugin;
    public EliteMobsCommand(EliteMobsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { showHelp(sender); return true; }
        switch (args[0].toLowerCase()) {
            case "reload" -> { if (!has(sender,"elitemobs.reload")) return true; plugin.reload(); msg(sender,ChatColor.GREEN+"\u2714 "+ChatColor.WHITE+"\u914d\u7f6e\u5df2\u91cd\u65b0\u52a0\u8f7d\uff01"); }
            case "info" -> { if (!has(sender,"elitemobs.admin")) return true; showInfo(sender); }
            case "spawn" -> { if (!has(sender,"elitemobs.spawn")) return true; doSpawn(sender,args); }
            case "list" -> { if (!has(sender,"elitemobs.admin")) return true; msg(sender,ChatColor.GOLD+"\u2694 "+ChatColor.WHITE+"\u5f53\u524d\u6d3b\u8dc3\u7cbe\u82f1: "+ChatColor.RED+plugin.getMobManager().getEliteCount()+ChatColor.GRAY+" \u53ea"); }
            case "toggle" -> { if (!has(sender,"elitemobs.admin")) return true; plugin.getConfig().set("general.enabled",!plugin.getEliteConfig().isEnabled()); plugin.reload(); msg(sender,ChatColor.GREEN+"\u7cbe\u82f1\u751f\u6210: "+(plugin.getEliteConfig().isEnabled()?ChatColor.GREEN+"\u2705 \u5df2\u542f\u7528":ChatColor.RED+"\u274c \u5df2\u7981\u7528")); }

            // === 测试指令 ===
            case "test" -> testSpawn(sender, args);
            case "wave" -> spawnWave(sender, args);
            case "clear" -> clearElites(sender);
            case "stealtest" -> stealTest(sender);
            case "stat" -> statCheck(sender, args);
            case "boss" -> bossSpawn(sender, args);
            case "particle" -> particleTest(sender, args);
            case "gem" -> gemCmd(sender, args);
            case "rune" -> runeCmd(sender, args);

            default -> showHelp(sender);
        }
        return true;
    }

    // ---- \u5e2e\u52a9 ----
    private void showHelp(CommandSender s) {
        msg(s,ChatColor.GOLD+"\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
        msg(s,ChatColor.GOLD+"\u2694 EliteMobs v"+plugin.getDescription().getVersion()+" \u6307\u4ee4\u5e2e\u52a9");
        msg(s,ChatColor.GOLD+"\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
        msg(s,ChatColor.YELLOW+"/em reload"+ChatColor.GRAY+" \u2014 \u91cd\u65b0\u52a0\u8f7d\u914d\u7f6e\u6587\u4ef6");
        msg(s,ChatColor.YELLOW+"/em info"+ChatColor.GRAY+" \u2014 \u67e5\u770b\u63d2\u4ef6\u72b6\u6001");
        msg(s,ChatColor.YELLOW+"/em spawn <\u7c7b\u578b> [\u7b49\u7ea7] [\u804c\u4e1a]"+ChatColor.GRAY+" \u2014 \u624b\u52a8\u751f\u6210\u7cbe\u82f1\uff08\u804c\u4e1a: tank/assassin/mage/summoner\uff09");
        msg(s,ChatColor.YELLOW+"/em list"+ChatColor.GRAY+" \u2014 \u6d3b\u8dc3\u7cbe\u82f1\u6570\u91cf");
        msg(s,ChatColor.YELLOW+"/em toggle"+ChatColor.GRAY+" \u2014 \u542f\u505c\u7cbe\u82f1\u751f\u6210");
        msg(s,"");
        msg(s,ChatColor.GOLD+"\u2501\u2501\u2501 \u6d4b\u8bd5\u6307\u4ee4 \u2501\u2501\u2501");
        msg(s,ChatColor.YELLOW+"/em test [\u7c7b\u578b] [\u7b49\u7ea7] [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u6279\u91cf\u751f\u6210\u6d4b\u8bd5\u7cbe\u82f1");
        msg(s,ChatColor.YELLOW+"/em wave [\u79cd\u7c7b\u6570]"+ChatColor.GRAY+" \u2014 \u751f\u6210\u6df7\u5408\u7cbe\u82f1\u6ce2\uff08\u9ed8\u8ba43\u79cd\u00d7Lv.1/5/10\uff09");
        msg(s,ChatColor.YELLOW+"/em clear"+ChatColor.GRAY+" \u2014 \u6e05\u9664\u9644\u8fd1\u6240\u6709\u7cbe\u82f1\u602a");
        msg(s,ChatColor.YELLOW+"/em stat [\u7c7b\u578b] [\u7b49\u7ea7]"+ChatColor.GRAY+" \u2014 \u9884\u89c8\u7cbe\u82f1\u5c5e\u6027\uff08HP/\u653b/\u901f\uff09");
        msg(s,ChatColor.YELLOW+"/em stealtest"+ChatColor.GRAY+" \u2014 \u751f\u6210\u5077\u7269\u54c1\u6d4b\u8bd5\u7cbe\u82f1");
        msg(s,ChatColor.YELLOW+"/em boss <\u7c7b\u578b> [\u7b49\u7ea7]"+ChatColor.GRAY+" \u2014 \u76f4\u63a5\u751f\u6210Boss\uff08\u9ed8\u8ba4Lv.15\uff09");
        msg(s,ChatColor.YELLOW+"/em particle <\u804c\u4e1a>"+ChatColor.GRAY+" \u2014 \u6d4b\u8bd5\u804c\u4e1a\u7c92\u5b50\u7279\u6548\uff08tank/assassin/mage/summoner/boss\uff09");
        msg(s,"");
        msg(s,ChatColor.GOLD+"\u2501\u2501\u2501 \u5b9d\u77f3\u6307\u4ee4 \u2501\u2501\u2501");
        msg(s,ChatColor.YELLOW+"/em gem give <id> [\u7b49\u7ea7] [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u6307\u5b9a\u5b9d\u77f3\uff08\u5982 attack_gem/defense_gem/thunder_gem/magnet_gem\uff0c\u7b49\u7ea7 1-10\uff09");
        msg(s,ChatColor.YELLOW+"/em gem charm [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u6dec\u70bc\u4fdd\u62a4\u7b26");
        msg(s,ChatColor.YELLOW+"/em gem remover [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u5b9d\u77f3\u62c6\u5378\u5668\uff08\u94c1\u7827\u62c6\u5378\u6240\u6709\u5b9d\u77f3\uff09");
        msg(s,ChatColor.YELLOW+"/em gem test <0|100> [\u5b9d\u77f3id] [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u6307\u5b9a\u6210\u529f\u7387\u6d4b\u8bd5\u5b9d\u77f3\uff080=\u5fc5\u5931\u8d25 / 100=\u5fc5\u6210\u529f\uff09");
        msg(s,ChatColor.YELLOW+"/em gem list"+ChatColor.GRAY+" \u2014 \u5217\u51fa\u6240\u6709\u53ef\u53d1\u653e\u7684\u5b9d\u77f3");
        msg(s,"");
        msg(s,ChatColor.GOLD+"\u2501\u2501\u2501 \u7b26\u6587\u6307\u4ee4 \u2501\u2501\u2501");
        msg(s,ChatColor.YELLOW+"/em rune list"+ChatColor.GRAY+" \u2014 \u5217\u51fa\u6240\u6709\u7b26\u6587\u7c7b\u578b");
        msg(s,ChatColor.YELLOW+"/em rune give <\u7c7b\u578b> [\u7b49\u7ea7] [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u7b26\u6587\uff08HEALTH/SPEED/STRENGTH/REGEN/RESIST/FIRE\uff09");    }

    // ---- \u72b6\u6001 ----
    private void showInfo(CommandSender s) {
        msg(s,ChatColor.GOLD+"\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
        msg(s,ChatColor.GOLD+"\u2694 EliteMobs v"+plugin.getDescription().getVersion()+" \u63d2\u4ef6\u72b6\u6001");
        msg(s,ChatColor.GOLD+"\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
        msg(s,ChatColor.GRAY+"\u2022 \u6d3b\u8dc3\u7cbe\u82f1: "+ChatColor.RED+plugin.getMobManager().getEliteCount()+ChatColor.GRAY+" \u53ea");
        var cfg=plugin.getEliteConfig();
        msg(s,ChatColor.GRAY+"\u2022 \u751f\u6210\u6982\u7387: "+ChatColor.WHITE+String.format("%.1f%%",cfg.getEliteSpawnChance()*100));
        msg(s,ChatColor.GRAY+"\u2022 \u722c\u5899: "+icon(cfg.isWallClimbEnabled())+ChatColor.GRAY
                +" | \u7834\u5757: "+icon(cfg.isBlockBreakEnabled())+ChatColor.GRAY
                +" | \u5077\u7a83: "+icon(cfg.isItemStealEnabled())+ChatColor.GRAY
                +" | \u4f24\u5bb3\u6210\u957f: "+icon(cfg.isDamageScalingEnabled()));
        msg(s,ChatColor.GRAY+"\u2022 \u7cbe\u82f1\u79cd\u7c7b: "+ChatColor.WHITE+cfg.getEnabledMobTypes().size()+ChatColor.GRAY+" \u79cd");
        msg(s,ChatColor.GRAY+"\u2022 \u88c5\u5907\u6a21\u5f0f: "+ChatColor.GREEN+"\u6bcf\u90e8\u4f4d\u72ec\u7acb\u968f\u673a"+ChatColor.GRAY+" | "+ChatColor.AQUA+"\u7b49\u7ea7\u52a0\u6743");
        msg(s,ChatColor.GRAY+"\u2022 \u526f\u9b54\u6a21\u5f0f: "+ChatColor.GREEN+"\u6743\u91cd\u968f\u673a"+ChatColor.GRAY+" | "+ChatColor.AQUA+"\u7b49\u7ea7\u63d0\u5347");
    }

    // ---- \u751f\u6210 ----
    private void doSpawn(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        if (args.length<2) { msg(s,ChatColor.RED+"/em spawn <\u7c7b\u578b> [\u7b49\u7ea7]"); return; }
        EntityType t;
        try {
            t = EntityType.valueOf(args[1].toUpperCase());
        } catch(IllegalArgumentException e) {
            msg(s,ChatColor.RED+"\u274c \u672a\u77e5\u751f\u7269: "+args[1]);
            return;
        }
        if (!plugin.getEliteConfig().getEnabledMobTypes().contains(t)) {
            msg(s,ChatColor.RED+"\u274c \u8be5\u751f\u7269\u4e0d\u5728\u7cbe\u82f1\u5217\u8868\u4e2d\uff01");
            return;
        }
        int lv = -1;
        if (args.length >= 3) {
            try {
                lv = Math.max(1, Math.min(20, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                lv = -1;
            }
        }
        // 可选第4参数：指定职业 tank/assassin/mage/summoner
        com.clawx.elitemobs.ai.EliteClass cls = null;
        if (args.length >= 4) {
            try { cls = com.clawx.elitemobs.ai.EliteClass.valueOf(args[3].toUpperCase()); }
            catch (IllegalArgumentException ignored) {
                msg(s, ChatColor.RED + "\u274c \u672a\u77e5\u804c\u4e1a: " + args[3] + " \uff08tank/assassin/mage/summoner\uff09");
                return;
            }
        }
        spawnElite(p, t, lv, cls);
        msg(s, ChatColor.GREEN + "\u2705 \u5df2\u751f\u6210\u7cbe\u82f1 " + fmt(t) + ChatColor.GRAY + " [Lv." + (lv > 0 ? lv : "\u968f\u673a") + "]"
                + (cls != null ? ChatColor.AQUA + " \u804c\u4e1a: " + cls.name() : ""));
    }

    // ---- \u6d4b\u8bd5\u6279\u91cf\u751f\u6210 ----
    private void testSpawn(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        if (!has(s,"elitemobs.admin")) return;

        EntityType type = EntityType.ZOMBIE;
        if (args.length >= 2) {
            try { type = EntityType.valueOf(args[1].toUpperCase()); }
            catch (IllegalArgumentException e) { msg(s,ChatColor.RED+"\u274c \u672a\u77e5\u751f\u7269: "+args[1]); return; }
        }

        int lv = -1;
        if (args.length >= 3) {
            try { lv = Math.max(1, Math.min(20, Integer.parseInt(args[2]))); }
            catch (NumberFormatException ignored) { lv = -1; }
        }

        int count = 1;
        if (args.length >= 4) {
            try { count = Math.max(1, Math.min(20, Integer.parseInt(args[3]))); }
            catch (NumberFormatException ignored) {}
        }

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Location loc = p.getLocation().clone();
            loc.setYaw(p.getYaw());
            loc.setPitch(0);
            Entity e = p.getWorld().spawnEntity(loc, type);
            if (e instanceof LivingEntity le) {
                plugin.getMobManager().makeElite(le, lv);
                spawned++;
            }
        }
        msg(s,ChatColor.GREEN+"\u2705 \u5df2\u751f\u6210 "+spawned+" \u53ea\u7cbe\u82f1 "+fmt(type)+ChatColor.GRAY+" [Lv."+(lv>0?lv:"\u968f\u673a")+"]");
    }

    // ---- \u7cbe\u82f1\u6ce2 ----
    private void spawnWave(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        if (!has(s,"elitemobs.admin")) return;
        int kinds = 3;
        if (args.length >= 2) try { kinds = Math.max(1, Math.min(8, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}

        List<EntityType> pool = new ArrayList<>(plugin.getEliteConfig().getEnabledMobTypes());
        Collections.shuffle(pool);
        int[] levels = {1, 5, 10, 15, 20};
        int spawned = 0;
        for (int i = 0; i < Math.min(kinds, pool.size()); i++) {
            EntityType t = pool.get(i);
            for (int waveLevel : levels) {
                Location loc = p.getLocation().clone();
                loc.add((Math.random()-0.5)*4, 0, (Math.random()-0.5)*4);
                loc.setYaw(p.getYaw());
                loc.setPitch(0);
                Entity e = p.getWorld().spawnEntity(loc, t);
                if (e instanceof LivingEntity le) {
                    plugin.getMobManager().makeElite(le, waveLevel);
                    spawned++;
                }
            }
        }
        msg(s,ChatColor.GREEN+"\u26a1 \u7cbe\u82f1\u6ce2\uff01\u5df2\u751f\u6210 "+spawned+" \u53ea\u7cbe\u82f1"+ChatColor.GRAY+"\uff08"+kinds+" \u79cd \u00d7 Lv.1/5/10/15/20\uff09");
    }

    // ---- \u6e05\u7406 ----
    private void clearElites(CommandSender s) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        if (!has(s,"elitemobs.admin")) return;
        int cleared = 0;
        for (Entity e : p.getWorld().getNearbyEntities(p.getLocation(), 50, 50, 50)) {
            if (e instanceof LivingEntity le && EliteMobManager.isElite(le)) {
                le.remove();
                cleared++;
            }
        }
        msg(s,ChatColor.GREEN+"\u2705 \u5df2\u6e05\u9664\u5468\u8fb9 50 \u683c\u5185 "+cleared+" \u53ea\u7cbe\u82f1");
    }

    // ---- \u5c5e\u6027\u9884\u89c8 ----
    private void statCheck(CommandSender s, String[] args) {
        if (!has(s,"elitemobs.admin")) return;
        EntityType type = EntityType.ZOMBIE;
        if (args.length >= 2) try { type = EntityType.valueOf(args[1].toUpperCase()); } catch (IllegalArgumentException e) { msg(s,ChatColor.RED+"\u274c \u672a\u77e5: "+args[1]); return; }
        int lv = 5;
        if (args.length >= 3) try { lv = Math.max(1, Math.min(20, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) {}

        var cfg = plugin.getEliteConfig();
        var profile = cfg.getProfile(type);
        double statMin = cfg.getHealthMultiplierMin() + (profile.healthMultiplier - 1.0) + (lv * 0.05);
        double statMax = cfg.getHealthMultiplierMax() + (profile.healthMultiplier - 1.0) + (lv * 0.05);
        double hpMid = Math.min((statMin+statMax)/2, 2.5);

        double dmg = Math.min(profile.damageMultiplier + lv * 0.04, 2.5);
        double spd = Math.min(cfg.getSpeedMultiplierMin() + (cfg.getSpeedMultiplierMax()-cfg.getSpeedMultiplierMin())/2 + lv*0.008, 1.25);

        msg(s,ChatColor.GOLD+"\u2501\u2501\u2501\u2501\u2501 "+fmt(type)+" Lv."+lv+" \u5c5e\u6027\u9884\u89c8 \u2501\u2501\u2501\u2501\u2501");
        msg(s,ChatColor.GRAY+"\u2022 \u8840\u91cf\u500d\u7387: "+ChatColor.WHITE+String.format("%.2f~%.2f", Math.min(statMin,2.5), Math.min(statMax,2.5))
                +ChatColor.GRAY+" | \u4f30\u7b97 HP: "+ChatColor.RED+String.format("~%.0f", 20*hpMid));
        msg(s,ChatColor.GRAY+"\u2022 \u4f24\u5bb3\u500d\u7387: "+ChatColor.WHITE+String.format("%.2f", dmg));
        msg(s,ChatColor.GRAY+"\u2022 \u79fb\u901f\u500d\u7387: "+ChatColor.WHITE+String.format("%.2f", spd));
        msg(s,ChatColor.GRAY+"\u2022 \u53d1\u5149: "+icon(lv>=cfg.getGlowMinLevel())+ChatColor.GRAY+" | \u62a4\u7532: "+ChatColor.WHITE
                +(lv>=7?"\u94c1/\u94bb\u5168\u7532":lv>=5?"\u94c1/\u94fe\u4e09\u4ef6":lv>=3?"\u94fe\u9774\u88e4":"\u65e0"));
        msg(s,ChatColor.GRAY+"\u2022 \u836f\u6c34: "+ChatColor.WHITE
                +((lv>=9?"\u529b\u91cf+\u6297\u6027+\u901f\u5ea6+\u9632\u706b":lv>=8?"\u6297\u6027+\u901f\u5ea6+\u9632\u706b":lv>=7?"\u6297\u6027+\u901f\u5ea6":lv>=6?"\u9632\u706b":"\u65e0")));
        msg(s,ChatColor.GRAY+"\u2022 \u6389\u843d: "+ChatColor.WHITE
                +(lv>=9?"\u7a00\u6709\u53cc\u500d\u6389\u843d":lv>=7?"\u6218\u5229\u54c1\u6389\u843d":"\u7ecf\u9a8c\u5956\u52b1"));
    }

    // ---- \u5de5\u5177\u65b9\u6cd5 ----
    private Entity spawnElite(Player p, EntityType type, int level) {
        return spawnElite(p, type, level, null);
    }

    private Entity spawnElite(Player p, EntityType type, int level, com.clawx.elitemobs.ai.EliteClass cls) {
        Location loc = p.getLocation().clone();
        Entity e = p.getWorld().spawnEntity(loc, type);
        if (e instanceof LivingEntity le) {
            plugin.getMobManager().makeElite(le, level, cls);
        }
        return e;
    }

    private boolean has(CommandSender s,String p){if(!s.hasPermission(p)){msg(s,ChatColor.RED+"\u274c \u6743\u9650\u4e0d\u8db3\u3002");return false;}return true;}

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private void stealTest(CommandSender s) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        if (!has(s,"elitemobs.admin")) return;
        org.bukkit.Location loc = p.getLocation().clone().add(2, 0, 2);
        Entity e = p.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
        if (e instanceof LivingEntity le) {
            plugin.getMobManager().makeElite(le, 5);
            ((org.bukkit.entity.Mob) le).setTarget(p);
        }
        msg(s,ChatColor.GREEN+"\u2705 \u5df2\u751f\u6210\u5077\u7269\u54c1\u6d4b\u8bd5\u7cbe\u82f1\u50f5\u5c38\uff0c\u8bf7\u8ba9\u5b83\u653b\u51fb\u4f60\u6765\u89e6\u53d1\u5077\u53d6");
    }

    private void bossSpawn(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7b\u7528\u3002"); return; }
        if (!has(s,"elitemobs.admin")) return;
        if (args.length < 2) { msg(s,ChatColor.RED+"/em boss <\u7c7b\u578b> [\u7b49\u7ea7]"); return; }
        EntityType type;
        try { type = EntityType.valueOf(args[1].toUpperCase()); }
        catch (IllegalArgumentException e) { msg(s,ChatColor.RED+"\u274c \u672a\u77e5\u751f\u7269: "+args[1]); return; }
        int lv = 15;
        if (args.length >= 3) {
            try { lv = Math.max(15, Math.min(20, Integer.parseInt(args[2]))); }
            catch (NumberFormatException ignored) {}
        }
        // 生成在玩家脚下（无偏移），朝向与玩家一致
        Location loc = p.getLocation().clone();
        loc.setYaw(p.getYaw());
        loc.setPitch(0);
        Entity e = p.getWorld().spawnEntity(loc, type);
        if (e instanceof LivingEntity le) {
            plugin.getMobManager().makeElite(le, lv);
            plugin.getBossManager().forcePromoteToBoss(le, lv);
        }
        msg(s,ChatColor.DARK_RED+"\u2620 "+ChatColor.RED+"\u5df2\u751f\u6210 Boss "+ChatColor.GOLD+fmt(type)+ChatColor.GRAY+" [Lv."+lv+"]");
    }

    private void particleTest(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        if (!has(s,"elitemobs.admin")) return;
        if (args.length < 2) {
            msg(s,ChatColor.RED+"/em particle <tank|assassin|mage|summoner|boss|pillar>");
            return;
        }
        String name = args[1].toLowerCase();
        Location loc = p.getLocation();
        World world = p.getWorld();
        // 生成临时标记实体（隐形、无敌、不移动）用于绑定粒子
        Entity marker = world.spawnEntity(loc, EntityType.ARMOR_STAND);
        marker.setCustomName(ChatColor.GRAY + "\u7c92\u5b50\u6d4b\u8bd5: " + name);
        marker.setCustomNameVisible(true);
        if (marker instanceof org.bukkit.entity.ArmorStand as) {
            as.setVisible(false);
            as.setMarker(true);
            as.setInvulnerable(true);
            as.setGravity(false);
        }
        // 连续显示20次（1秒）
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        int[] tick = {0};
        final Entity m = marker;
        task[0] = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tick[0]++;
            switch (name) {
                case "mage" -> com.clawx.elitemobs.ai.EliteClassAI.drawHexagram(world, m, tick[0]);
                case "tank" -> com.clawx.elitemobs.ai.EliteClassAI.drawShieldRing(world, m, tick[0]);
                case "assassin" -> com.clawx.elitemobs.ai.EliteClassAI.drawShadowTrail(world, m, tick[0]);
                case "summoner" -> com.clawx.elitemobs.ai.EliteClassAI.drawDarkSwirl(world, m, tick[0]);
                case "boss" -> com.clawx.elitemobs.ai.EliteClassAI.drawBossAura(world, m, tick[0]);
                case "pillar" -> {
                    com.clawx.elitemobs.ai.EliteClassAI.drawSummonPillar(world, loc, plugin);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { task[0].cancel(); m.remove(); }, 40L);
                    return;
                }
                default -> { task[0].cancel(); return; }
            }
            if (tick[0] >= 20) { task[0].cancel(); m.remove(); }
        }, 0L, 3L);
        msg(s,ChatColor.GREEN+"\u2705 \u6b63\u5728\u663e\u793a\u7c92\u5b50\u7279\u6548: "+name+ChatColor.GRAY+" \uff081\u79d2\uff0c\u7ed3\u675f\u540e\u81ea\u52a8\u6e05\u7406\uff09");
    }

    // ==================== 精华(宝石)指令 ====================

    /** /em gem weapon [等级] | armor [等级] | charm [数量] */
    private void gemCmd(CommandSender s, String[] args) {
        if (!has(s,"elitemobs.admin")) return;
        if (args.length < 2) { showGemHelp(s); return; }
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "charm" -> gemGiveCharm(s, args);
            case "remover" -> gemGiveRemover(s, args);
            case "give" -> gemGiveCustom(s, args);
            case "test" -> gemGiveTest(s, args);
            case "list" -> gemListCustom(s);
            default -> showGemHelp(s);
        }
    }

    private void showGemHelp(CommandSender s) {
        msg(s,ChatColor.GOLD+"/em gem give <id> [\u7b49\u7ea7] [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u6307\u5b9a\u5b9d\u77f3\uff08\u5982 attack_gem/defense_gem/thunder_gem/magnet_gem\uff0c\u7b49\u7ea7 1-10\uff09");
        msg(s,ChatColor.GOLD+"/em gem charm [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u6dec\u70bc\u4fdd\u62a4\u7b26");
        msg(s,ChatColor.GOLD+"/em gem remover [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u5b9d\u77f3\u62c6\u5378\u5668\uff08\u94c1\u7827\u62c6\u5378\u6240\u6709\u5b9d\u77f3\uff09");
        msg(s,ChatColor.GOLD+"/em gem test <0|100> [\u5b9d\u77f3id] [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u6307\u5b9a\u6210\u529f\u7387\u7684\u6d4b\u8bd5\u5b9d\u77f3\uff080=\u5fc5\u5931\u8d25 / 100=\u5fc5\u6210\u529f\uff09");
        msg(s,ChatColor.GOLD+"/em gem list"+ChatColor.GRAY+" \u2014 \u5217\u51fa\u6240\u6709\u53ef\u53d1\u653e\u7684\u5b9d\u77f3");
    }

    /** \u5217\u51fa\u6240\u6709\u53ef\u53d1\u653e\u7684\u81ea\u5b9a\u4e49\u5b9d\u77f3 */
    private void gemListCustom(CommandSender s) {
        List<com.clawx.elitemobs.EliteConfig.CustomDrop> drops = plugin.getEliteConfig().getCustomDrops();
        if (drops.isEmpty()) {
            msg(s,ChatColor.RED+"\u274c \u6ca1\u6709\u53ef\u53d1\u653e\u7684\u81ea\u5b9a\u4e49\u5b9d\u77f3\uff08gems/*.yml \u6216 gem-drops.custom \u4e3a\u7a7a\uff09");
            return;
        }
        msg(s,ChatColor.GOLD+"\u2501\u2501\u2501 \u53ef\u53d1\u653e\u7684\u81ea\u5b9a\u4e49\u5b9d\u77f3 \u2501\u2501\u2501");
        for (var d : drops) {
            msg(s,ChatColor.YELLOW+d.id+ChatColor.GRAY+" \u2014 "+ChatColor.RESET
                    + ChatColor.translateAlternateColorCodes('&', d.name));
        }
        msg(s,ChatColor.GRAY+"\u53ef\u4ee5\u7528 /em gem give <id> \u53d1\u653e");
    }

    /** \u53d1\u653e\u81ea\u5b9a\u4e49\u5b9d\u77f3\uff08gems/*.yml \u4e2d\u5b9a\u4e49\uff09\u3002\u683c\u5f0f: /em gem give <id> [\u7b49\u7ea7] [\u6570\u91cf] */
    private void gemGiveCustom(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        if (args.length < 3) { showGemHelp(s); return; }
        String id = args[2].toLowerCase();
        com.clawx.elitemobs.EliteConfig.CustomDrop target = null;
        for (var d : plugin.getEliteConfig().getCustomDrops()) {
            if (d.id.equalsIgnoreCase(id)) { target = d; break; }
        }
        if (target == null) {
            msg(s,ChatColor.RED+"\u274c \u672a\u77e5\u5b9d\u77f3: "+args[2]);
            gemListCustom(s);
            return;
        }
        int level = 1;
        int count = 1;
        if (args.length >= 4) {
            try { level = Math.max(1, Math.min(10, Integer.parseInt(args[3]))); }
            catch (NumberFormatException ignored) {}
        }
        if (args.length >= 5) {
            count = Math.max(1, Math.min(64, parseIntSafe(args[4], 1)));
        }
        ItemStack item = target.build(level);
        item.setAmount(count);
        p.getInventory().addItem(item).values()
            .forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
        msg(s,ChatColor.GREEN+"\u2705 \u5df2\u53d1\u653e "+count+" \u4e2a: "+ChatColor.RESET
                + ChatColor.translateAlternateColorCodes('&', target.name)
                + ChatColor.GRAY+" Lv."+level);
    }

    /** 发放攻击/防御宝石（带等级与数量）。格式: /em gem weapon [等级] [数量] */
    private void gemGiveEssence(CommandSender s, String[] args, boolean weapon) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        int lvl = 5;
        int count = 1;
        if (args.length >= 3) {
            try { lvl = Math.max(1, Math.min(10, Integer.parseInt(args[2]))); }
            catch (NumberFormatException ignored) {}
        }
        if (args.length >= 4) {
            count = Math.max(1, Math.min(64, parseIntSafe(args[3], 1)));
        }
        // 统一宝石：weapon → 攻击宝石 attack_gem / armor → 防御宝石 defense_gem
        String targetId = weapon ? "attack_gem" : "defense_gem";
        com.clawx.elitemobs.EliteConfig.CustomDrop target = null;
        for (var d : plugin.getEliteConfig().getCustomDrops()) {
            if (d.id != null && d.id.equalsIgnoreCase(targetId)) { target = d; break; }
        }
        if (target == null) {
            msg(s,ChatColor.RED+"\u274c \u672a\u627e\u5230\u5b9d\u77f3\u914d\u7f6e: "+targetId+ChatColor.GRAY+"\uff08\u8bf7\u68c0\u67e5 gems/\u76ee\u5f55\uff09");
            return;
        }
        ItemStack item = target.build(lvl);
        item.setAmount(count);
        p.getInventory().addItem(item).values()
            .forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
        String typeName = weapon ? "\u653b\u51fb\u5b9d\u77f3" : "\u9632\u5fa1\u5b9d\u77f3";
        msg(s,ChatColor.GREEN+"\u2705 \u5df2\u53d1\u653e "+typeName+ChatColor.GRAY+" [Lv."+lvl+"] x"+count);
        msg(s,ChatColor.GRAY+"\u5c06\u5b9d\u77f3\u4e0e\u88c5\u5907\u653e\u5165\u94c1\u7827\u5373\u53ef\u6dec\u70bc");
    }

    /** 发放淬炼保护符。 */
    private void gemGiveCharm(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        int count = args.length >= 3 ? Math.max(1, Math.min(64, parseIntSafe(args[2], 1))) : 1;
        ItemStack charm = com.clawx.elitemobs.essence.EliteEssenceFactory.createProtectionCharm(plugin.getMessages());
        charm.setAmount(count);
        p.getInventory().addItem(charm).values()
            .forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
        msg(s,ChatColor.GREEN+"\u2705 \u5df2\u53d1\u653e "+count+" \u4e2a\u6dec\u70bc\u4fdd\u62a4\u7b26\uff01"+ChatColor.GRAY+" \u653e\u5165\u80cc\u5305\u5373\u53ef\u751f\u6548");
    }

    /** \u53d1\u653e\u5b9d\u77f3\u62c6\u5378\u5668\u3002\u683c\u5f0f: /em gem remover [\u6570\u91cf] */
    private void gemGiveRemover(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        int count = args.length >= 3 ? Math.max(1, Math.min(64, parseIntSafe(args[2], 1))) : 1;
        ItemStack remover = com.clawx.elitemobs.essence.EliteEssenceFactory.createGemRemover(plugin.getMessages());
        remover.setAmount(count);
        p.getInventory().addItem(remover).values()
            .forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
        msg(s,ChatColor.GREEN+"\u2705 \u5df2\u53d1\u653e "+count+" \u4e2a\u5b9d\u77f3\u62c6\u5378\u5668\uff01"+ChatColor.GRAY+" \u4e0e\u5df2\u6dec\u70bc\u88c5\u5907\u653e\u5165\u94c1\u7827\u5373\u53ef\u62c6\u5378");
    }

    /** \u53d1\u653e\u6307\u5b9a\u6210\u529f\u7387\u7684\u6d4b\u8bd5\u5b9d\u77f3\uff080=\u5fc5\u5931\u8d25 / 100=\u5fc5\u6210\u529f\uff09\u3002\u683c\u5f0f: /em gem test <0|100> [\u5b9d\u77f3id] [\u6570\u91cf] */
    private void gemGiveTest(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        if (args.length < 3) { showGemHelp(s); return; }
        double rate;
        try { rate = Math.max(0, Math.min(100, Double.parseDouble(args[2]))); }
        catch (NumberFormatException e) { msg(s,ChatColor.RED+"\u274c \u6210\u529f\u7387\u5fc5\u987b\u662f 0~100 \u7684\u6570\u5b57\uff08\u5982 0 \u6216 100\uff09"); return; }
        String id = args.length >= 4 ? args[3].toLowerCase() : "attack_gem";
        int count = args.length >= 5 ? Math.max(1, Math.min(64, parseIntSafe(args[4], 1))) : 1;
        com.clawx.elitemobs.EliteConfig.CustomDrop target = null;
        for (var d : plugin.getEliteConfig().getCustomDrops()) {
            if (d.id != null && d.id.equalsIgnoreCase(id)) { target = d; break; }
        }
        if (target == null) {
            msg(s,ChatColor.RED+"\u274c \u672a\u77e5\u5b9d\u77f3: "+id);
            gemListCustom(s);
            return;
        }
        double fraction = rate / 100.0;
        ItemStack item = target.build(1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(
                com.clawx.elitemobs.essence.EliteGemFactory.KEY_GEM_SUCCESS_RATE,
                org.bukkit.persistence.PersistentDataType.DOUBLE, fraction);
        // \u66f4\u65b0\u6210\u529f\u7387 lore \u884c\uff08build \u751f\u6210\u7684\u6700\u540e\u4e00\u884c\uff09
        List<String> lore = meta.getLore();
        if (lore != null && !lore.isEmpty()) {
            lore.set(lore.size() - 1, ChatColor.translateAlternateColorCodes('&',
                    "&a\u6210\u529f\u7387: " + String.format("%.0f", rate) + "% &7(\u6d4b\u8bd5)"));
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        item.setAmount(count);
        p.getInventory().addItem(item).values()
            .forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
        msg(s,ChatColor.GREEN+"\u2705 \u5df2\u53d1\u653e "+count+" \u4e2a\u6d4b\u8bd5\u5b9d\u77f3: "+ChatColor.RESET
                + ChatColor.translateAlternateColorCodes('&', target.name)
                + ChatColor.GRAY+" \u6210\u529f\u7387 "+String.format("%.0f", rate)+"%");
    }

    // ==================== 符文指令 ====================

    /** /em rune list | give <类型> [数量] */
    private void runeCmd(CommandSender s, String[] args) {
        if (!has(s,"elitemobs.admin")) return;
        if (args.length < 2) { showRuneHelp(s); return; }
        switch (args[1].toLowerCase()) {
            case "list" -> {
                msg(s,ChatColor.GOLD+"\u2501\u2501\u2501 \u53ef\u7528\u7b26\u6587 \u2501\u2501\u2501");
                for (var t : com.clawx.elitemobs.rune.EliteRuneFactory.TYPES.values()) {
                    msg(s,ChatColor.YELLOW+t.id+ChatColor.GRAY+" \u2014 "+ChatColor.RESET
                            + ChatColor.translateAlternateColorCodes('&', t.coloredName)
                            + ChatColor.GRAY+" | "
                            + ChatColor.translateAlternateColorCodes('&', t.desc)
                            + ChatColor.GRAY+" | \u7b49\u7ea7: &f1-10");
                }
                msg(s,ChatColor.GRAY+"\u7528 /em rune give <\u7c7b\u578b> [\u7b49\u7ea7] [\u6570\u91cf] \u53d1\u653e\u7279\u5b9a\u7b49\u7ea7\u7b26\u6587");
            }
            case "give" -> runeGive(s, args);
            default -> showRuneHelp(s);
        }
    }

    private void showRuneHelp(CommandSender s) {
        msg(s,ChatColor.GOLD+"/em rune list"+ChatColor.GRAY+" \u2014 \u5217\u51fa\u6240\u6709\u7b26\u6587\u7c7b\u578b");
        msg(s,ChatColor.GOLD+"/em rune give <\u7c7b\u578b> [\u7b49\u7ea7] [\u6570\u91cf]"+ChatColor.GRAY+" \u2014 \u53d1\u653e\u7b26\u6587\uff08HEALTH/SPEED/STRENGTH/REGEN/RESIST/FIRE\uff0c\u7b49\u7ea7 1-10\uff09");
        msg(s,ChatColor.GRAY+"\u5c06\u7b26\u6587\u4e0e\u5df2\u6dec\u70bc\u7684\u88c5\u5907\u653e\u5165\u94c1\u7827\u5373\u53ef\u9576\u5d4c");
    }

    private void runeGive(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { msg(s,ChatColor.RED+"\u274c \u4ec5\u9650\u73a9\u5bb6\u4f7f\u7528\u3002"); return; }
        if (args.length < 3) { showRuneHelp(s); return; }
        String type = args[2].toUpperCase();
        if (!com.clawx.elitemobs.rune.EliteRuneFactory.TYPES.containsKey(type)) {
            msg(s,ChatColor.RED+"\u274c \u672a\u77e5\u7b26\u6587\u7c7b\u578b: "+type+ChatColor.GRAY+" \u53ef\u7528: HEALTH/SPEED/STRENGTH/REGEN/RESIST/FIRE");
            return;
        }
        int level = 1;
        int count = 1;
        if (args.length >= 4) {
            try { level = Math.max(1, Math.min(10, Integer.parseInt(args[3]))); }
            catch (NumberFormatException ignored) {}
        }
        if (args.length >= 5) {
            count = Math.max(1, Math.min(64, parseIntSafe(args[4], 1)));
        }
        ItemStack rune = com.clawx.elitemobs.rune.EliteRuneFactory.createRune(type, level, plugin.getMessages());
        rune.setAmount(count);
        p.getInventory().addItem(rune).values()
            .forEach(drop -> p.getWorld().dropItemNaturally(p.getLocation(), drop));
        msg(s,ChatColor.GREEN+"\u2705 \u5df2\u53d1\u653e "+count+" \u4e2a\u7b26\u6587: "+ChatColor.RESET
                + ChatColor.translateAlternateColorCodes('&', com.clawx.elitemobs.rune.EliteRuneFactory.TYPES.get(type).coloredName)
                + ChatColor.GRAY+" Lv."+level);
    }

        private void msg(CommandSender s,String m){s.sendMessage(m);}
    private String icon(boolean b){return b?ChatColor.GREEN+"\u2705":ChatColor.RED+"\u274c";}
    private String fmt(EntityType t){String n=t.name().toLowerCase().replace('_',' ');StringBuilder sb=new StringBuilder();for(String w:n.split(" "))sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');return sb.toString().trim();}

    @Override
    public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){
        if(a.length==1)return Arrays.asList("reload","info","spawn","list","toggle","test","wave","clear","stat","stealtest","boss","particle","gem","rune").stream().filter(x->x.startsWith(a[0].toLowerCase())).collect(Collectors.toList());
        if(a.length==2&&(a[0].equalsIgnoreCase("spawn")||a[0].equalsIgnoreCase("test")||a[0].equalsIgnoreCase("stat")||a[0].equalsIgnoreCase("boss")))
            return plugin.getEliteConfig().getEnabledMobTypes().stream().map(x->x.name().toLowerCase()).filter(x->x.startsWith(a[1].toLowerCase())).collect(Collectors.toList());
        if(a.length==2&&a[0].equalsIgnoreCase("particle"))
            return Arrays.asList("tank","assassin","mage","summoner","boss","pillar");
        if(a.length==2&&a[0].equalsIgnoreCase("gem"))
            return Arrays.asList("charm","list","give","remover","test").stream().filter(x->x.startsWith(a[1].toLowerCase())).collect(Collectors.toList());
        if(a.length==3&&a[0].equalsIgnoreCase("gem")&&a[1].equalsIgnoreCase("test"))
            return Arrays.asList("0","100").stream().filter(x->x.startsWith(a[2].toLowerCase())).collect(Collectors.toList());
        if(a.length==4&&a[0].equalsIgnoreCase("gem")&&a[1].equalsIgnoreCase("test"))
            return plugin.getEliteConfig().getCustomDrops().stream().map(d->d.id).filter(x->x.toLowerCase().startsWith(a[3].toLowerCase())).collect(Collectors.toList());
        if(a.length==3&&a[0].equalsIgnoreCase("gem")&&a[1].equalsIgnoreCase("give"))
            return plugin.getEliteConfig().getCustomDrops().stream().map(d->d.id).filter(x->x.toLowerCase().startsWith(a[2].toLowerCase())).collect(Collectors.toList());
        if(a.length==4&&a[0].equalsIgnoreCase("gem")&&a[1].equalsIgnoreCase("give"))
            return Arrays.asList("1","3","5","7","10").stream().filter(x->x.startsWith(a[3].toLowerCase())).collect(Collectors.toList());
        if(a.length==5&&a[0].equalsIgnoreCase("gem")&&a[1].equalsIgnoreCase("give"))
            return Arrays.asList("1","5","10","32","64").stream().filter(x->x.startsWith(a[4].toLowerCase())).collect(Collectors.toList());
        if(a.length==2&&a[0].equalsIgnoreCase("rune"))
            return Arrays.asList("list","give").stream().filter(x->x.startsWith(a[1].toLowerCase())).collect(Collectors.toList());
        if(a.length==3&&a[0].equalsIgnoreCase("rune")&&a[1].equalsIgnoreCase("give"))
            return Arrays.asList("HEALTH","SPEED","STRENGTH","REGEN","RESIST","FIRE").stream().filter(x->x.toLowerCase().startsWith(a[2].toLowerCase())).collect(Collectors.toList());
        if(a.length==4&&a[0].equalsIgnoreCase("rune")&&a[1].equalsIgnoreCase("give"))
            return Arrays.asList("1","3","5","7","10").stream().filter(x->x.startsWith(a[3].toLowerCase())).collect(Collectors.toList());
        if(a.length==5&&a[0].equalsIgnoreCase("rune")&&a[1].equalsIgnoreCase("give"))
            return Arrays.asList("1","5","10","32","64").stream().filter(x->x.startsWith(a[4].toLowerCase())).collect(Collectors.toList());
        if(a.length==3&&(a[0].equalsIgnoreCase("spawn")||a[0].equalsIgnoreCase("test")||a[0].equalsIgnoreCase("stat")))
            return Arrays.asList("1","3","5","7","10","15","20");
        if(a.length==4&&(a[0].equalsIgnoreCase("test")))
            return Arrays.asList("1","3","5","10","20");
        return Collections.emptyList();
    }
}
