package com.clawx.elitemobs.gem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宝石配置模型（与 SnowyGems 的 GemConfig 字段一一对应）。
 *
 * <p>配置来源：{@code plugins/EliteMobs/gems/*.yml}，每个顶层键即宝石 ID。</p>
 *
 * <pre>
 * 生命宝石:
 *   Name: 生命宝石
 *   Require: [ARMOR]
 *   Display: "&8&l&k||&c&l生命宝石&8&l&k||"
 *   Tips: [...]
 *   Texture: &lt;base64 头颅&gt;
 *   Success: 50
 *   SuccessTip: "..."
 *   Rewards:
 *     - Attribute{name=health;operation=0;slot=auto;var=v+1;limit=5}
 * </pre>
 */
public class GemConfig {
    /** 宝石 ID（YAML 顶层键） */
    public final String id;
    public final String name;
    public final GemType type;
    /** 可镶嵌的装备类别（WEAPON/ARMOR/BOOTS/TOOL...），OR 语义 */
    public final List<String> require;
    /** 物品显示名 */
    public final String display;
    /** 物品 lore */
    public final List<String> tips;
    /** 头颅 base64 纹理（非空则强制 PLAYER_HEAD） */
    public final String texture;
    /** 不使用头颅时的物品材质 */
    public final String material;
    /** 附魔光效 */
    public final boolean glow;
    /** 成功率 1-100，默认 100 */
    public final int success;
    /** 镶嵌位权重（0=不占位，非镶嵌类） */
    public final int embed;
    /** 皮革/药水染色 */
    public final String color;
    /** 是否需"食用/饮用"而非右键 */
    public final boolean eat;
    public final String successTip;
    public final String removeTip;
    public final String failTip;
    /** 奖励函数行（与 SnowyGems 语法一致） */
    public final List<String> rewards;
    /** 随机宝石的奖励池：奖励文本 -> 权重 */
    public final Map<String, Integer> randomPool;
    public final String category;

    public GemConfig(String id, String name, GemType type, List<String> require,
                     String display, List<String> tips, String texture, String material,
                     boolean glow, int success, int embed, String color, boolean eat,
                     String successTip, String removeTip, String failTip,
                     List<String> rewards, Map<String, Integer> randomPool, String category) {
        this.id = id;
        this.name = name == null ? id : name;
        this.type = type == null ? GemType.NORMAL : type;
        this.require = require == null ? new ArrayList<>() : require;
        this.display = display;
        this.tips = tips == null ? new ArrayList<>() : tips;
        this.texture = texture;
        this.material = material;
        this.glow = glow;
        this.success = success <= 0 ? 100 : Math.min(success, 100);
        this.embed = embed;
        this.color = color;
        this.eat = eat;
        this.successTip = successTip;
        this.removeTip = removeTip;
        this.failTip = failTip;
        this.rewards = rewards == null ? new ArrayList<>() : rewards;
        this.randomPool = randomPool == null ? new LinkedHashMap<>() : randomPool;
        this.category = category;
    }

    /** 是否是镶嵌类宝石（NORMAL） */
    public boolean isEmbedGem() { return type == GemType.NORMAL; }

    /** 显示名（未配置 Display 时退化为宝石名） */
    public String getDisplayName() {
        return display != null && !display.isEmpty() ? display : name;
    }

    @Override
    public String toString() {
        return "GemConfig{id='" + id + "', name='" + name + "', type=" + type + ", success=" + success + "}";
    }
}
