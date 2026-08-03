package com.clawx.elitemobs;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI 扩展占位符（软依赖，装了 PAPI 才生效）。
 *
 * <p>可用占位符（标识符 elitemobs）：</p>
 * <ul>
 *   <li>{@code %elitemobs_elite_count%}       当前服务器在线精英怪数量</li>
 *   <li>{@code %elitemobs_drop_mode%}         当前掉落模式 (custom/disabled)</li>
 *   <li>{@code %elitemobs_player_combo%}      玩家当前连杀数</li>
 *   <li>{@code %elitemobs_player_money%}      玩家金币余额 (Vault)</li>
 *   <li>{@code %elitemobs_player_points%}     玩家点券余额 (PlayerPoints)</li>
 * </ul>
 */
public class ElitePapiExpansion extends PlaceholderExpansion {

    private final EliteMobsPlugin plugin;

    public ElitePapiExpansion(EliteMobsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "elitemobs";
    }

    @Override
    public String getAuthor() {
        return "ClawX";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return null;
        switch (params.toLowerCase()) {
            case "elite_count":
                return String.valueOf(plugin.getMobManager().getEliteCount());
            case "drop_mode":
                return plugin.getEliteConfig().getGemDropMode();
            case "player_combo":
                if (player != null) return String.valueOf(plugin.getCombatListener().getPlayerCombo(player.getUniqueId()));
                return "0";
            case "player_money":
                if (player != null) return String.format("%.2f", EconomyHook.getMoney(player));
                return "0";
            case "player_points":
                if (player != null) return String.valueOf(EconomyHook.getPoints(player));
                return "0";
            default:
                return null;
        }
    }
}
