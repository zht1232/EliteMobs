<div align="center">

# ⚔️ EliteMobs

**高度可定制的精英怪插件 · Paper 1.21+ / Paper 26.x**

为生存服务器打造的精英怪系统：随机词缀、职业 AI、Boss、自定义掉落、经济奖励与占位符支持。

</div>

---

## ✨ 功能总览

### 🎯 精英怪核心
- **等级系统**：精英怪 1–20 级，随等级成长
  - 85% → 1–10 级 · 12% → 11–14 级 · 3% → 15–20 级（可晋升 Boss）
- **职业系统**：4 种职业，各有专属 AI 与特效
  - 🛡️ 坦克：血量加成、击退免疫、护盾环
  - 🗡️ 刺客：速度加成、隐身、暴击、残影
  - 🔮 法师：抗性、远程火球/药水、脚下六芒星法阵
  - 👹 召唤师：血量加成、召唤护卫、黑暗漩涡
- **Boss 系统**：15 级以上精英可晋升 Boss
  - 3 倍血量 + 抗性/速度、专属血条（80 格内可见）
  - 技能：冲击波 / 治愈 / 召唤护卫
- **AI 行为**：爬墙（蜘蛛类）、破块（僵尸类）、偷窃（偷玩家装备）
- **套装效果**：2 件加速、4 件生命恢复 + 减伤

### 🔥 精英词缀系统
精英怪生成时随机获得 **1–2 个词缀**，改变外观（名字后缀 `[火焰]`）与战斗方式：

| 词缀 | 效果 |
|------|------|
| 🔥 火焰 | 周期性点燃周围玩家 |
| ❄️ 冰霜 | 攻击附带减速 |
| 🌿 荆棘 | 近战反伤 |
| 🩸 吸血 | 攻击时回复生命 |
| 💢 狂暴 | 低血时攻击力提升 |
| 👻 分裂 | 死亡时分裂小精英 |
| ✨ 瞬移 | 周期闪现到玩家身后 |
| ⚡ 雷链 | 周期电击周围玩家 |

> 词缀权重可在 `config.yml` 中调整，不想要的词缀权重设 `0` 即可移除。

### 💎 掉落系统
`gem-drops.mode` 支持两种模式：
- **`custom`**（默认）：服主完全自主配置精英怪掉落物，支持材质 / 名称 / Lore / 附魔 / 光效 / **自定义头颅纹理** / **药水效果** / **按生物类型限定** / 各等级段概率 / 数量
- **`disabled`**：不掉落

### 💠 内置宝石系统（铁砧淬炼）
- **宝石来源**：精英怪按等级段掉落宝石 + 战利品袋（右键开出），或管理员用 `/em gem give` 发放
- **宝石配置**：`plugins/EliteMobs/gems/*.yml`（分 weapon / armor / enchant / special 四类）
- **淬炼玩法**：将「装备 + 宝石」放入铁砧 → 结果槽显示成功率预览 → 点击淬炼
  - 成功：宝石生效（武器攻击 / 护甲套装减伤 / 附魔 / 无限耐久等）+ 烟花庆祝
  - 失败：宝石消失，装备可能降级（携带「淬炼保护符」可避免）

### 💰 经济奖励
击杀精英怪发放奖励（Vault + PlayerPoints，均为软依赖）：
- **金币**（Vault）：`base + per-level × 等级`，Boss × 倍数
- **点券**（PlayerPoints）：同上
- **连杀系统**：连续击杀精英，奖励逐步提升（可配置最高倍数、死亡清零）
- **LuckPerms**：`loot-multiplier` 同时作用于金币/点券/掉落/经验

### 🔌 PlaceholderAPI
安装 PlaceholderAPI 后自动注册 `%elitemobs_*` 占位符（见下方列表）。

---

## 📋 指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/em spawn <类型> [等级]` | `elitemobs.spawn` | 手动生成精英怪 |
| `/em boss <类型> [等级]` | `elitemobs.admin` | 在脚下生成 Boss |
| `/em gem list` | `elitemobs.admin` | 列出所有宝石 |
| `/em gem give <ID> [数量]` | `elitemobs.admin` | 发放宝石（Tab 补全 ID） |
| `/em gem bag [数量]` | `elitemobs.admin` | 发放战利品袋 |
| `/em particle <职业>` | `elitemobs.admin` | 测试职业粒子特效 |
| `/em test [类型] [等级] [数量]` | `elitemobs.admin` | 批量生成测试 |
| `/em wave [种类数]` | `elitemobs.admin` | 生成精英波 |
| `/em clear` | `elitemobs.admin` | 清除附近精英怪 |
| `/em stat [类型] [等级]` | `elitemobs.admin` | 预览精英属性 |
| `/em stealtest` | `elitemobs.admin` | 偷窃行为测试 |
| `/em reload` | `elitemobs.reload` | 重载配置 |
| `/em list` | `elitemobs.admin` | 查看活跃精英数量 |
| `/em toggle` | `elitemobs.admin` | 开关精英生成 |
| `/em info` | `elitemobs.admin` | 查看插件状态 |

---

## 🔑 权限

| 权限 | 默认 | 说明 |
|------|------|------|
| `elitemobs.use` | ✅ true | 使用基础指令 |
| `elitemobs.reload` | op | 重载配置 |
| `elitemobs.admin` | op | 管理/测试指令 |
| `elitemobs.spawn` | op | 手动生成 |
| `elitemobs.bypass` | ❌ false | 精英怪不攻击该玩家 |

---

## 📦 依赖

### 必需
- **Paper 1.21+ / Paper 26.x**
- **Java 21+**

### 可选（均为软依赖，未装自动跳过）
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

### 基础
```yaml
general:
  enabled: true
  elite-spawn-chance: 0.01      # 精英生成概率
  max-elites-per-chunk: 2
  enabled-mob-types: []          # 空 = 使用默认生物列表
```

### 精英词缀
```yaml
elite-affixes:
  enabled: true
  chance: 0.8                   # 获得词缀概率
  min-affixes: 1
  max-affixes: 2
  weights:
    FIRE_AURA: 10               # 权重设为 0 可移除该词缀
    FROST: 10
    THORNS: 8
    LIFESTEAL: 6
    BERSERK: 8
    SPLIT: 5
    BLINK: 6
    CHAIN: 8
```

### 掉落模式
```yaml
gem-drops:
  mode: custom                  # custom / disabled
  custom:
    - id: attack_gem
      material: EMERALD
      name: "&c攻击宝石"
      lore:
        - "&7攻击力 +2"
      enchants:
        SHARPNESS: 2
      glow: true
      chance:                   # 各等级段掉落概率
        level-1-3: 0.15
        level-4-6: 0.35
        level-7-9: 0.55
        level-10: 0.85
      amount-min: 1
      amount-max: 1
    # 自定义头颅纹理
    - id: rare_skull
      material: PLAYER_HEAD
      texture: "eyJ0ZXh0dXJlcyI6..."
      name: "&6稀有战利品"
      mob-types: [ZOMBIE, SKELETON]   # 限定生物
    # 药水掉落
    - id: speed_potion
      material: POTION
      potion-type: SPEED
      potion-amplifier: 1
      potion-duration: 300
```

### 经济奖励
```yaml
loot:
  rewards:
    money:
      enabled: true
      base: 0.0
      per-level: 5.0
      boss-multiplier: 3.0
    points:
      enabled: true
      base: 0
      per-level: 1
      boss-multiplier: 3.0
    combo:                       # 连杀加成
      enabled: true
      max-multiplier: 3.0
      per-kill: 0.1
      reset-on-death: true
```

---

## 🔌 PlaceholderAPI 占位符

| 占位符 | 说明 |
|--------|------|
| `%elitemobs_elite_count%` | 当前在线精英怪数量 |
| `%elitemobs_drop_mode%` | 当前掉落模式 |
| `%elitemobs_drop_mode%` | 当前掉落模式 |
| `%elitemobs_player_combo%` | 玩家当前连杀数 |
| `%elitemobs_player_money%` | 玩家金币余额 |
| `%elitemobs_player_points%` | 玩家点券余额 |

---

## 🚀 安装

1. 下载 `EliteMobs-28.0.0.jar`
2. 放入服务器 `plugins/` 文件夹
3. 重启服务器（或 `/reload`）
4. 首次启动自动生成 `config.yml`
5. 按需修改配置后执行 `/em reload`

---

## 📝 更新日志

### v28.0.0
- ✅ **内置宝石系统**：移除 SnowyGems 依赖，自研宝石 + 铁砧淬炼玩法
  - 配置在 `plugins/EliteMobs/gems/*.yml`，支持属性/附魔/药水/战利品等宝石
  - **铁砧淬炼**：装备+宝石 → 成功率预览 → 点击淬炼
  - 淬炼成功：烟花粒子 + 庆祝音效
- ✅ 修复：精英护甲套装加成（armor_lv）实际生效
- ✅ 掉落增强：宝石按等级段掉落 + 战利品袋（右键开袋）
- ✅ 保留：custom 自定义掉落 / Vault 金币 / PlayerPoints 点券 / PlaceholderAPI

### v27.0.0
- ✅ 精英词缀系统（8 种词缀，可配置权重）
- ✅ 掉落系统：`custom` 自定义掉落（头颅纹理/药水/按生物限定）
- ✅ 经济奖励：Vault 金币 + PlayerPoints 点券 + 连杀系统 + LuckPerms 倍数
- ✅ PlaceholderAPI 占位符扩展
- ✅ 职业系统、Boss 系统、AI 行为、套装效果、粒子特效
- ✅ 修复 Paper 26.x 兼容性（粒子 API 等）

---

## 🐛 反馈

遇到问题请提供：
1. 服务器版本（Paper 版本号）
2. 相关日志片段（`logs/latest.log`）
3. 复现步骤

---

**作者**：ClawX  
**适用**：Paper 1.21+ / Paper 26.x · Java 21+  
**许可**：保留所有权利
