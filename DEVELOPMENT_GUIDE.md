# EliteMobs 插件开发文档

## 项目概述

**EliteMobs** 是一个 Minecraft Paper 服务器插件（1.21+），用于生成具有特殊能力的精英怪物。

### 核心功能
- 精英怪物生成系统（1-20级）
- 职业系统（坦克/刺客/法师/召唤师）
- Boss 系统（Lv.15+ 可晋升）
- 宝石掉落系统（支持 SnowyGems 集成）
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
│   ├── SnowyGemsFactory.java         # SnowyGems 宝石工厂
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
│   ├── spawn/
│   │   └── EliteSpawnHandler.java    # 生成处理
│   └── commands/
│       └── EliteMobsCommand.java     # 指令系统
├── src/main/resources/
│   ├── plugin.yml                    # 插件配置
│   ├── config.yml                    # 用户配置
│   └── messages.yml                  # 消息配置
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

#### 4. 宝石掉落系统
支持三种模式（在 config.yml 中配置）：
- **snowygems**：使用 SnowyGems 兼容宝石（需要 SnowyGems 插件）
- **custom**：使用自定义宝石（独立运行，功能简化）
- **disabled**：不掉落宝石

#### 5. AI 行为系统
- **爬墙 AI**：蜘蛛类怪物可以攀爬墙壁
- **破块 AI**：僵尸类怪物可以破坏方块
- **偷窃 AI**：精英怪可以偷取玩家装备

#### 6. 指令系统
```bash
/em spawn <类型> [等级]     # 生成精英怪
/em boss <类型> [等级]      # 生成 Boss
/em gem <类型> [等级] [数量] # 获取宝石
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
  mode: snowygems            # snowygems / custom / disabled
  drops:
    enabled: true
    level-1-3: 0.15
    level-4-6: 0.35
    level-7-9: 0.55
    level-10: 0.85

# 生成广播
general:
  spawn-announce:
    enabled: true
    min-level: 7
    announce-range: -1       # -1 = 全服广播
```

## 与 SnowyGems 集成

### 集成方式
EliteMobs 掉落 SnowyGems 兼容的宝石，玩家使用 SnowyGems 的 `/sgem embed` 指令进行镶嵌。

### 工作流程
1. 精英怪/Boss 被击杀
2. 掉落 SnowyGems 兼容宝石
3. 玩家使用 `/sgem embed` 打开镶嵌台
4. 将宝石镶嵌到装备上

### 支持的宝石类型
- 攻击宝石（attack）
- 防御宝石（defense）
- 速度宝石（speed）
- 生命宝石（health）
- 锋利宝石（sharpness）
- 保护宝石（protection）
- 耐久宝石（unbreaking）
- 效率宝石（efficiency）

## 独立运行模式

如果不安装 SnowyGems，可以将 `gem-drops.mode` 设置为 `custom`：
- 掉落自定义宝石（绿宝石）
- 宝石仅作为收集品
- 需要配合其他插件实现镶嵌功能

## 技术细节

### 使用的 API
- Paper API 1.21+
- Bukkit Inventory API
- Bukkit Attribute API
- Bukkit Particle API
- Bukkit Boss Bar API

### 性能优化
- 使用 ConcurrentHashMap 存储精英怪数据
- 粒子效果使用 spawnParticleSafe 安全调用
- AI 行为使用 BukkitRunnable 定时任务

### 兼容性
- 支持 Paper 1.21+ / Paper 26.x
- 支持 Folia（folia-supported: true）
- 需要 Java 21+

## 已知问题

### 已修复
- ✅ GUI 拖拽物品问题（已移除自定义 GUI，改用 SnowyGems）
- ✅ 附魔等级上限问题（已移除限制）
- ✅ 宝石掉落模式切换（已添加配置选项）

### 待改进
- 自定义宝石的实际功能实现
- 更多职业类型
- 更多 Boss 技能

## 编译方法

```bash
# Windows
compile.bat

# 生成的 JAR 文件
EliteMobs-28.0.0.jar
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

### v28.0.0（当前版本）
- 移除 SnowyGems 依赖，改为内置宝石系统
- 宝石外观/value 样式与 SnowyGems 完全一致（gems/*.yml 配置）
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
- SnowyGems 集成
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
- 版本：28.0.0
- 适用：Paper 1.21+ / Paper 26.x

---

**文档生成时间**：2026-08-03
**最后更新**：2026-08-03
