# EliteMobs 插件开发文档

## 项目概述

**EliteMobs** 是一个 Minecraft Paper 服务器插件（1.21+），用于生成具有特殊能力的精英怪物。

### 核心功能
- 精英怪物生成系统（1-20级）
- 职业系统（坦克/刺客/法师/召唤师）
- Boss 系统（Lv.15+ 可晋升）
- 内置宝石系统（铁砧淬炼）
- 伤害缩放机制
- 装备生成系统

## 文件结构

```
EliteMobs-Project/
├── src/main/java/com/clawx/elitemobs/
│   ├── EliteMobsPlugin.java          # 主插件类
│   ├── EliteConfig.java              # 配置管理
│   ├── EliteMobManager.java          # 精英怪管理
│   ├── EliteCombatListener.java      # 战斗监听器
│   ├── ai/
│   │   ├── EliteClass.java           # 职业枚举
│   │   ├── EliteClassAI.java         # 职业 AI
│   │   ├── EliteBossManager.java     # Boss 管理
│   │   ├── WallClimbAI.java          # 爬墙 AI
│   │   ├── BlockBreakAI.java         # 破块 AI
│   │   └── ItemStealAI.java          # 偷窃 AI
│   ├── combat/
│   │   ├── DamageScaler.java         # 伤害缩放
│   │   ├── WeaponEnhancer.java       # 武器强化
│   │   └── EnchantUtil.java          # 附魔工具
│   ├── gem/
│   │   ├── GemRegistry.java          # 宝石注册表（加载 gems/*.yml）
│   │   ├── GemConfig.java            # 宝石配置模型
│   │   ├── GemType.java              # 宝石类型枚举
│   │   ├── GemItemFactory.java       # 宝石物品构建（头颅纹理/Display/Tips）
│   │   ├── GemRewards.java           # 奖励函数解析执行（Attribute/Enchant 等）
│   │   ├── GemManager.java           # 淬炼核心（成功率/降级/保护符/特效）
│   │   ├── GemAnvilListener.java     # 铁砧淬炼监听器
│   │   ├── GemUseListener.java       # 战利品袋/PlayerGem 使用
│   │   └── GemNbt.java               # 宝石 NBT 读写（PDC）
│   ├── spawn/
│   │   └── EliteSpawnHandler.java    # 生成处理
│   └── commands/
│       └── EliteMobsCommand.java     # 指令系统
├── src/main/resources/
│   ├── plugin.yml                    # 插件配置
│   ├── config.yml                    # 用户配置
│   ├── messages.yml                  # 消息配置
│   └── gems/
│       ├── weapon-gems.yml           # 武器宝石
│       ├── armor-gems.yml            # 护甲宝石
│       ├── enchant-gems.yml          # 附魔宝石
│       └── special-gems.yml          # 特殊宝石（保护符/无限/修复）
└── compile.bat                       # 编译脚本
```

## 当前状态

### 已完成功能

#### 1. 精英怪生成系统
- 等级范围：1-20 级
- 生成概率：5%（可配置）
- 等级分布：
  - 85% 概率：1-10 级
  - 12% 概率：11-14 级
  - 3% 概率：15-20 级

#### 2. 职业系统
四种职业，每种有独特能力：
- **坦克**：1.5x 血量，击退免疫，嘲讽光环
- **刺客**：1.4x 速度，隐身能力，30% 暴击率
- **法师**：抗性提升，远程火球/药水攻击
- **召唤师**：1.3x 血量，召唤小怪护卫

#### 3. Boss 系统
- 触发条件：Lv.15+ 精英怪
- 晋升概率：5%-30%（随等级提升）
- Boss 特性：
  - 3x 血量
  - 抗性 III + 速度 II
  - 专属 Boss 血条
  - 特殊技能：冲击波/治愈/召唤

#### 4. 内置宝石系统（铁砧淬炼）
宝石配置在 `plugins/EliteMobs/gems/*.yml`（分 4 类文件）：
- **weapon-gems.yml**：攻击宝石等（加武器攻击力）
- **armor-gems.yml**：保护宝石/生命宝石/速度宝石（护甲套装减伤）
- **enchant-gems.yml**：耐久/时运/锋利/保护附魔宝石
- **special-gems.yml**：淬炼保护符/无限宝石/修复宝石

淬炼玩法：将「装备 + 宝石」放入铁砧，结果槽显示成功率预览，点击淬炼。
成功应用宝石效果 + 烟花庆祝；失败宝石消失、装备可能降级（保护符可避免）。

#### 5. AI 行为系统
- **爬墙 AI**：蜘蛛类怪物可以攀爬墙壁
- **破块 AI**：僵尸类怪物可以破坏方块
- **偷窃 AI**：精英怪可以偷取玩家装备

#### 6. 指令系统
```bash
/em spawn <类型> [等级]     # 生成精英怪
/em boss <类型> [等级]      # 生成 Boss
/em gem list                # 列出所有宝石
/em gem give <ID> [数量]    # 发放宝石
/em gem bag [数量]          # 发放战利品袋
/em info                    # 查看插件状态
/em reload                  # 重载配置
/em particle <职业>         # 测试粒子特效
```

## 配置说明

### config.yml 关键配置

```yaml
# 基本设置
general:
  enabled: true
  elite-spawn-chance: 0.05    # 5% 生成概率
  max-elites-per-chunk: 2
  
# 宝石掉落模式
gem-drops:
  mode: custom            # custom / disabled
  gems:
    level-1-3: 0.15
    level-4-6: 0.35
    level-7-9: 0.55
    level-10: 0.85
  lootbag:
    enabled: true
    chance: 0.10
    boss-chance: 0.50

# 生成广播
general:
  spawn-announce:
    enabled: true
    min-level: 7
    announce-range: -1       # -1 = 全服广播
```

## 技术细节

### 使用的 API
- Paper API 26.x
- Bukkit Inventory API
- Bukkit Attribute API（AttributeModifier + EquipmentSlotGroup）
- Bukkit Particle API
- Bukkit Boss Bar API
- PersistentDataContainer（宝石 NBT 存储）

### 性能优化
- 使用 ConcurrentHashMap 存储精英怪数据
- 粒子效果使用 spawnParticleSafe 安全调用
- AI 行为使用 BukkitRunnable 定时任务

### 兼容性
- 支持 Paper 1.21+ / Paper 26.x
- 需要 Java 25（paper-api 26.2 为 Java 25 编译）

## 已知问题

### 已修复
- ✅ 套装加成 armor_lv 实际生效
- ✅ 铁砧淬炼提示纸颜色显示
- ✅ 宝石物品头颅纹理丢失（build 流程覆盖 meta）
- ✅ 淬炼槽位兼容两种放法

### 待改进
- 宝石拆卸系统
- 宝石合成/进阶
- 更多职业类型
- 更多 Boss 技能

## 编译方法

```bash
# Windows
compile.bat

# 生成的 JAR 文件
EliteMobs-29.0.0.jar
```

## 依赖插件

### 必需
- Paper 1.21+ 或 Paper 26.x

### 可选
- **WorldGuard**：区域保护
- **GriefPrevention**：领地保护
- **Towny**：城镇保护
- **Vault**：经济系统
- **LuckPerms**：权限组加成

> 注：v28.0.0 起已移除 SnowyGems 依赖，宝石系统改为插件内置实现
（配置在 `plugins/EliteMobs/gems/*.yml`，铁砧淬炼玩法）。

## 开发历史

### v29.0.0（当前版本）
- 宝石统一淬炼系统重构：多宝石槽 + 独立等级，槽位按宝石等级之和解锁
- 淬炼成功率基于宝石自身等级，成功等级 +1
- 宝石改为头颅外观，Lore 显示品质/等级/成功率
- 修复多次淬炼覆盖伤害、失败降级名字/Lore 错乱、移速数值、伤害显示口径
- 符文改为通用（任意符文可镶嵌任意已淬炼装备）
- 新增宝石拆卸器（铁砧拆卸所有宝石，返还宝石等级流失）
- 指令精简与中文修正

### v28.0.0
- 移除 SnowyGems 依赖，改为内置宝石系统
- 宝石配置拆分 4 文件（weapon/armor/enchant/special）
- 铁砧淬炼玩法：装备+宝石 → 成功率预览 → 点击淬炼
- 淬炼成功烟花粒子 + 庆祝音效
- 修复套装加成 armor_lv 永不生效的 Bug
- 新增战利品袋掉落、宝石按等级段掉落

### v27.0.0
- 精英词缀系统（8 种词缀）
- 自定义掉落 / 经济奖励 / PlaceholderAPI

### v26.2.1
- 完整的职业系统
- Boss 系统
- ~~SnowyGems 集成~~（v28.0.0 已移除）
- 宝石掉落配置
- 性能优化

### 早期版本
- 基础精英怪生成
- AI 行为系统
- 伤害缩放
- 装备生成

## 未来计划

1. **宝石功能增强**
   - 宝石拆卸系统
   - 宝石合成/进阶
   - /em gem 管理指令

2. **更多职业**
   - 弓箭手
   - 治疗者
   - 狂战士

3. **更多 Boss 技能**
   - 瞬移
   - 分身
   - 元素攻击

4. **GUI 系统**
   - 精英怪信息查看
   - 宝石管理界面

## 联系信息

- 作者：ClawX
- 版本：29.0.0
- 适用：Paper 1.21+ / Paper 26.x

---

**文档生成时间**：2026-08-03
**最后更新**：2026-08-03
