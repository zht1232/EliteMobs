<div align="center">

# ⚔️ EliteMobs

**高度可定制的精英怪插件 · Paper 1.21+ / Paper 26.x · Java 25**

为生存服务器打造的精英怪系统：等级 / 职业 / Boss / 词缀 AI、铁砧宝石淬炼、符文镶嵌、掉落与经济闭环。

</div>

---

## ✨ 功能总览

### 🎯 精英怪核心
- **等级系统**：精英怪 1–20 级（85% → 1-10 / 12% → 11-14 / 3% → 15-20，可晋升 Boss）
- **生成距离**：等级越高的精英生成离最近玩家越远（`general.spawn-distance` 可配置），避免高级怪刷在玩家/基地旁
- **职业系统**：4 种职业，各有专属 AI 与粒子特效
  - 🛡️ 坦克：血量加成、击退免疫、减伤光环
  - 🗡️ 刺客：速度加成、隐身、暴击、残影
  - 🔮 法师：抗性、远程火球/药水、脚下六芒星法阵
  - 👹 召唤师：召唤护卫、暗能漩涡
- **Boss 系统**：Lv.15+ 精英可晋升 Boss
  - 3 倍血量、专属血条（80 格内可见）
  - **生成全服广播（含坐标）**
  - **第二阶段**：血量低于阈值（默认 50%）时全服广播 + 狂暴强化（力量/速度/抗性）+ 血条变紫 + 闪电特效
  - 技能：冲击波 / 治愈 / 召唤护卫
- **AI 行为**：爬墙、破块、偷窃（偷玩家装备，死亡可掉落返还）
- **强制索敌**：精英每 1 秒主动锁定范围内最近玩家（`general.target-range`），
  玩家拥有 `elitemobs.bypass` 权限或跑出索敌范围则自动换目标——可应对"玩家免疫追击"类插件的干扰
- **套装加成**：按 4 件护甲 `armor_lv` 之和判定（减伤/速度/再生，阈值与强度可配置）
- **精英词缀**：8 种词缀（火焰/冰霜/荆棘/吸血/狂暴/分裂/瞬移/雷链），权重可配置

### 💠 宝石系统（铁砧淬炼）

**6 种宝石**（`plugins/EliteMobs/gems/*.yml`，头颅外观，Lv.1-10）：

| 宝石 | 效果 | 适用 |
|------|------|------|
| 🗡 攻击宝石 `attack_gem` | 攻击力 + 等级² × 0.5 | 武器 |
| 🛡 防御宝石 `defense_gem` | 护甲减伤 + 等级 × 1.5（上限 15） | 护甲 |
| ⚡ 雷电宝石 `thunder_gem` | 攻击概率召雷（8% + 7%/级，上限 85%） | 武器 |
| 💨 击退宝石 `knockback_gem` | 稳定击退（力量 0.4 + 等级×0.12） | 武器 |
| 🧲 磁力宝石 `magnet_gem` | 自动拾取掉落物（距离 3 + 等级，上限 12 格） | 武器/护甲 |
| 💎 稀有战利品 `rare_skull` | 无加成（收藏） | 武器 |

**淬炼机制**（仿原版 EliteEssenceUpgrade）：
- **槽位解锁**：宝石槽 1/2/3/4 于宝石等级和 0+/3+/6+/10+；符文槽 0/1/2/3/4 于 0/1+/4+/8+/12+
- **成功率** = `35% + (宝石等级-1) × 4.5%`，封顶 95%，**由宝石自身等级决定**
- 成功：该宝石 +1 级（新宝石从 Lv.1 起）；失败：宝石消耗 + 降级（1-3降1/4-6降2/7-9降3/10+降4）
- **首次淬炼失败会摧毁装备**；淬炼总等级归零还原成原版后，再失败也会摧毁
- **淬炼保护符**：失败防降级/防摧毁（符 + 宝石仍消耗）
- **宝石拆卸器**：铁砧一次拆下全部宝石与符文——宝石按 Lv.X 返还 X 颗 Lv.X-1，符文按自身等级返还
- **测试宝石**：`/em gem test <0|100> [宝石] [数量]` 发放必失败/必成功宝石，方便测试降级与销毁

### 🎴 符文系统（6 种，头颅外观）
符文"几级装几级、不可合并"，槽位由宝石等级之和解锁，镶嵌消耗金币 + 点券 + 经验（可配置）。

| 符文 | 效果 |
|------|------|
| ❤ 生命 `HEALTH` | 最大生命 +4 × Lv |
| ⚡ 移速 `SPEED` | 移速 +5% × Lv |
| ⚔ 力量 `STRENGTH` | 力量 I~IV |
| 💚 再生 `REGEN` | 再生 I~IV |
| 🛡 抗性 `RESIST` | 抗性 I~IV |
| 🔥 火焰 `FIRE` | 抗火 |

### 💎 掉落系统
掉落优先级 **宝石 > 保护符 >>> 符文**：
- **宝石（必掉）**：击败精英必掉宝石，颗数随等级提升 `1 + 等级/3`（Lv1→1 … Lv18→7，Boss 额外 +1），每颗从 `gems/*.yml` 按权重随机（可能不同种类）
- **保护符**：`gem-drops.charm-drop-chance`（默认 8%，Boss ×1.5）
- **符文**：`rune.drops.chance`（默认 2%，Boss ×2），符文等级 = `base + 精英等级/divisor`（可配置）

### 💰 经济奖励
击杀精英发放奖励（Vault + PlayerPoints，软依赖）：金币 / 点券 / **连杀加成** / **LuckPerms 组倍率**（掉落、经验、奖励）。

### 🔌 PlaceholderAPI
安装后自动注册 `%elitemobs_*` 占位符（见下方列表）。

---

## 📋 指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/em reload` | `elitemobs.reload` | 重载配置 |
| `/em info` | `elitemobs.admin` | 查看插件状态 |
| `/em spawn <类型> [等级]` | `elitemobs.spawn` | 手动生成精英 |
| `/em list` | `elitemobs.admin` | 活跃精英数量 |
| `/em toggle` | `elitemobs.admin` | 开关精英生成 |
| `/em test [类型] [等级] [数量]` | `elitemobs.admin` | 批量生成测试 |
| `/em wave [种类数]` | `elitemobs.admin` | 生成精英波 |
| `/em clear` | `elitemobs.admin` | 清除附近精英 |
| `/em stat [类型] [等级]` | `elitemobs.admin` | 预览精英属性 |
| `/em stealtest` | `elitemobs.admin` | 偷窃行为测试 |
| `/em boss <类型> [等级]` | `elitemobs.admin` | 生成 Boss |
| `/em particle <职业>` | `elitemobs.admin` | 测试职业粒子 |
| `/em gem list` | `elitemobs.admin` | 列出所有宝石 |
| `/em gem give <id> [等级] [数量]` | `elitemobs.admin` | 发放宝石（Tab 补全） |
| `/em gem charm [数量]` | `elitemobs.admin` | 发放淬炼保护符 |
| `/em gem remover [数量]` | `elitemobs.admin` | 发放宝石拆卸器 |
| `/em gem test <0\|100> [id] [数量]` | `elitemobs.admin` | 发放指定成功率测试宝石 |
| `/em rune list` | `elitemobs.admin` | 列出符文类型 |
| `/em rune give <类型> [等级] [数量]` | `elitemobs.admin` | 发放符文 |

---

## 🔑 权限

| 权限 | 默认 | 说明 |
|------|------|------|
| `elitemobs.use` | ✅ true | 使用基础指令 |
| `elitemobs.reload` | op | 重载配置 |
| `elitemobs.admin` | op | 管理/测试指令 |
| `elitemobs.spawn` | op | 手动生成 |
| `elitemobs.bypass` | ❌ false | 精英怪不锁定该玩家 |

---

## 📦 依赖

### 必需
- **Paper 1.21+ / Paper 26.x**
- **Java 25**

### 可选（软依赖，未装自动跳过）
| 插件 | 用途 |
|------|------|
| Vault + 经济插件 | 击杀金币奖励 |
| PlayerPoints | 击杀点券奖励 |
| PlaceholderAPI | `%elitemobs_*` 占位符 |
| LuckPerms | 权限组掉落/奖励/经验加成 |
| WorldGuard / GriefPrevention / Towny / Factions | 区域保护 |
| MythicMobs / mcMMO | 兼容 |

---

## ⚙️ 配置

配置文件：`plugins/EliteMobs/config.yml`

### 基础与索敌
```yaml
general:
  enabled: true
  elite-spawn-chance: 0.01      # 精英生成概率
  max-elites-per-chunk: 2
  enabled-mob-types: []          # 空 = 使用默认生物列表
  target-range: 16              # 精英主动索敌范围（格），应对免疫追击插件
```

### 护甲套装加成
```yaml
armor-set-bonus:                # 判定: 4 件护甲 armor_lv 之和
  enabled: true
  reduction-per-level: 2.0      # 每点套装等级减伤 %
  max-reduction: 20.0           # 减伤封顶 %
  speed-level: 6                # 套装等级达此值获得速度 I
  regen-level: 10               # 套装等级达此值获得再生 I + 粒子
```

### Boss 第二阶段
```yaml
boss-phase2:
  enabled: true
  hp-ratio: 0.5                 # 血量低于 50% 触发第二阶段（广播+狂暴+血条变紫）
```

### 掉落（宝石必掉 > 保护符 >>> 符文）
```yaml
gem-drops:
  mode: custom                  # custom / disabled
  drops:
    enabled: true               # 总开关（关闭则宝石/保护符/符文全不掉）
  charm-drop-chance: 0.08       # 保护符掉落概率（8%）
  # 宝石必掉: 颗数 = 1 + 精英等级/3（Boss +1），每颗权重随机
rune:
  install-cost:
    money: 1000.0               # 符文镶嵌消耗
    points: 50
    xp-levels: 30
  drops:
    enabled: true
    chance: 0.02                # 符文掉落概率（2%，Boss ×2）
    level-base: 1               # 符文掉落等级公式: base + 精英等级/divisor
    level-divisor: 3
    max-level: 10
```

### 淬炼成功率
```yaml
essence-upgrade:
  base-rate: 0.35               # 基础成功率（Lv.1 宝石）
  per-level: 0.045              # 每级宝石额外成功率
  max-rate: 0.95                # 成功率上限
  weapon-damage-multiplier: 0.5 # 武器淬炼攻击力系数（等级²×系数）
  armor-bonus-per-level: 1.5    # 护甲每级减伤
  armor-max-bonus: 15.0         # 护甲减伤上限
```

### 宝石配置（gems/*.yml）
每个文件 = 一颗宝石，复制改名即可新增。字段：`id / material / name / lore / effect / max-level / texture / glow / chance / amount-min / amount-max / mob-types / enchants / potion-type / potion-amplifier / potion-duration`

```yaml
id: attack_gem
material: PLAYER_HEAD
texture: "eyJ0ZXh0dXJlcyI6..."
name: "&c&l攻击宝石"
effect: attack                 # attack / defense / thunder / knockback / magnet / rare
max-level: 10
lore:
  - "&7攻击力 = 等级² × 0.5"
glow: true
chance:
  level-1-3: 0.15
  level-4-6: 0.35
  level-7-9: 0.55
  level-10: 0.85
amount-min: 1
amount-max: 1
```

---

## 🔌 PlaceholderAPI 占位符

| 占位符 | 说明 |
|--------|------|
| `%elitemobs_elite_count%` | 当前在线精英怪数量 |
| `%elitemobs_drop_mode%` | 当前掉落模式 |
| `%elitemobs_player_combo%` | 玩家当前连杀数 |
| `%elitemobs_player_money%` | 玩家金币余额 |
| `%elitemobs_player_points%` | 玩家点券余额 |

---

## 🚀 安装

1. 下载 `EliteMobs-29.2.0.jar`
2. 放入服务器 `plugins/` 文件夹
3. 重启服务器（或 `/reload`）
4. 首次启动自动生成 `config.yml`、`mobs.yml`、`messages.yml` 与 `gems/*.yml`
5. 按需修改配置后执行 `/em reload`

---

## 📝 更新日志

### v29.2.0
- 🎯 **新增二段跳宝石**：空中双击空格二段跳（向前冲 + 向上跳），等级越高蓄力越快（冷却 3s → 0.4s）
- 🐛 **修复广播关不掉根因**：服务器 config.yml 是 GBK 编码导致 Bukkit 读取失败（配置全失效用默认值）→ 已转 UTF-8；普通精英广播 `spawn-announce.enabled`、Boss 广播独立开关 `boss-alert`（默认开）
- 🐛 修复：死亡归还被偷物品双份（掉落+背包）、掉落物耐火范围收窄、精英血条 NPE
- 🎨 修复职业粒子拖尾（法师/坦克/召唤师：降低频率+减量+召唤师换短命粒子）
- ⚡ 雷电宝石额外伤害延迟执行（避免击杀瞬间掉落异常）
- 🛡 掉落物耐火/岩浆：精英掉落物不再被火烧毁
- 🌙 夜间强化排除名单补全（村民/铁傀儡/雪傀儡/悦灵/盔甲架/驯服宠物不再被强化）
- 🧹 移除未实现的战利品袋死配置（gem-drops.lootbag）
- 🔧 编译脚本适配 paper-api 26.2.build.60-beta

### v29.1.0
- 🐛 **修复精英不掉宝石**：掉落改为必掉，颗数 = 1 + 精英等级/3（Boss +1），每颗权重随机、可能不同种
- 🎯 **生成距离**：等级越高的精英生成离最近玩家越远（`general.spawn-distance`，可配置），避免高级怪刷在玩家/基地旁
- ⚡ **闪电收敛**：普通精英生成不再召唤真实闪电（不再破坏/引燃基地），闪电特效仅保留给 Boss 晋升
- � 修复：Boss 双倍掉落、雷电宝石连环闪电（防重入）、偷窃开关失效、精英识别（metadata+PDC 双判）
- 🌙 **夜间强化**：精英 + 原版怪夜间力量/速度提升（`features.night-enhancement.vanilla-mobs` 可关）
- ✅ 死亡归还被偷物品（`features.item-steal.return-on-death`）
- ✅ 词缀/职业 PDC 持久化：区块卸载重载后不丢失
- ✅ 领地保护兼容（WorldGuard/GriefPrevention/Towny/Factions）：破块 AI 不拆玩家建筑
- ✅ 武器强化开关生效、广播文案可自定义（messages.yml）、雷电宝石真闪电不引燃方块
- ✅ 击杀金币奖励提升（per-level 5 → 8，Boss ×3）- ✅ **新增二段跳宝石**：空中双击空格二段跳，等级越高蓄力越快（冷却 3s → 0.4s）- �🔧 编译脚本适配 paper-api 26.2.build.60-beta

### v29.0.0
- ✅ **宝石统一淬炼系统重构**（6 种宝石：攻击/防御/雷电/击退/磁力/稀有）
  - 多宝石槽 + 独立等级，宝石/符文槽数量按宝石等级之和解锁
  - 淬炼成功率基于宝石自身等级，成功 +1 级；失败降级；首次淬炼失败摧毁装备
  - 宝石均为头颅外观，Lore 显示品质 / 等级 / 成功率
- ✅ **符文改头颅**：6 种符文（生命/移速/力量/再生/抗性/火焰），主题纹理，"几级装几级、不可合并"
- ✅ **新增磁力宝石**：自动拾取，距离随等级提升（上限 12 格）
- ✅ **新增宝石拆卸器**：一次拆卸全部宝石 + 符文并返还；修复拆卸/还原后属性残留
- ✅ **新增测试宝石指令** `/em gem test <0|100>`
- ✅ **Boss 第二阶段** + 生成广播含坐标
- ✅ **护甲套装加成可配置**（`armor-set-bonus`），默认阈值调高
- ✅ **强制索敌**（`general.target-range`）：应对"玩家免疫追击"插件；目标跑远自动换目标
- ✅ **掉落重做**：宝石必掉（颗数随精英等级提升，Boss 更多）+ 保护符 + 符文（宝石 > 保护符 >>> 符文）
- ✅ 修复：淬炼覆盖原生伤害 / 名字·Lore 错乱 / 攻击显示与工具条不一致（含自定义武器）/ 槽位 Lore 显示锁定宝石

### v28.0.0
- ✅ 内置宝石系统（自研铁砧淬炼，移除 SnowyGems 依赖）
- ✅ 修复精英护甲套装加成实际生效
- ✅ 掉落增强：宝石按等级段掉落
