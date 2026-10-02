# 项目结构与构建

当前源码版本以 src/main/resources/plugin.yml 为准（29.21.1）。仓库保存插件源码、默认配置和构建脚本；服务器配置、世界存档和运行数据库不在这里。

## 目录

| 路径 | 内容 |
| --- | --- |
| src/main/java/com/clawx/elitemobs/ | Java 源码 |
| ai/ | 职业 AI、Boss 技能和词缀 |
| spawn/ | 普通精英生成与远端 Boss 布署 |
| combat/ | 战斗监听、武器属性效果 |
| essence/、rune/ | 宝石、淬炼与符文 |
| gui/ | /emmenu 商店和管理菜单 |
| db/ | SQLite 持久化 |
| src/main/resources/ | plugin.yml、默认配置、消息和物品定义 |
| build.ps1 | 当前构建入口 |
| build-output/ | 本机生成的 classes 和 JAR，不提交 Git |
| libs/ | PlaceholderAPI 编译库 |

## 主要流程

1. Paper 启动 EliteMobsPlugin，读取配置并注册各个模块。
2. 自然刷出的怪物由 EliteSpawnHandler 判断；EliteMobManager 应用等级、职业、词缀、装备和属性。
3. ElitePersistence 保存精英/Boss 的位置与状态；区块卸载后实体移除，玩家接近时再恢复。
4. BossSpawner 按配置在玩家所在世界远处布署 Boss；BossManager 负责技能、血条和阶段。

## 构建

需要 Paper 26.2 API 和 Java 25。默认构建命令：

    .\build.ps1 -ServerPath 'G:\Minecraftserver\DaemonData\Servers\1'

JDK 不在脚本推导的位置时，使用 -JavaBin 指定其 bin 目录。脚本编译源码并生成：

    build-output/EliteMobs-29.21.1-flightless.jar

构建产物不会自动复制到服务器。部署新 JAR 后应重启服务器；日常配置修改可使用 /em reload。
重载会重建 AI 与保存间隔任务；修改 persistence.enabled 开关后需要重启服务器。

## 运行依赖

SQLite 驱动由 plugin.yml 的 libraries 项声明，Paper 会下载并加入插件类路径。首次启动需要网络或 Paper 已缓存该库。Vault、PlayerPoints、PlaceholderAPI、LuckPerms 和领地保护插件是可选依赖。

## 当前已知边界

- 当前版本不提供二段跳，也不控制玩家飞行。
- SQLite 保存精英/Boss 记录和玩家背包、末影箱内已索引的 EliteMobs 装备快照。装备变更自动同步；新版会先迁移数据库快照，物品持有者下次登录时自动套用。
- 自然精英只响应 NATURAL 刷怪原因。每区块上限统计数据库中的潜伏记录；普通记录按首次生成时间在配置时限后过期。
- 攻击宝石加成是每级 +0.5，单颗最多 +5。
- 当前每区块上限同时计算实体和 SQLite 潜伏精英；普通自然精英只从 NATURAL 刷怪事件产生，困难难度概率再乘 1.5。
- 装备快照表包含完整序列化物品、插件属性摘要和版本号。离线升级由插件迁移 SQLite 快照，玩家下次登录自动取回；新玩家物品会自动索引，不用逐人输指令。
- 武器攻击属性优先读取游戏属性修饰符；显示或修复旧装备时，读不到修饰符会使用代码中的原版武器伤害表。
- README_MineBBS.md 中较早版本的飞行记录属于历史说明，不代表当前功能。
- 仓库没有自动化测试套件；编译成功不能替代 Paper 服务器内的游戏验收。

## 文档入口

- README.md：当前功能和指令。
- DEVELOPMENT_GUIDE.md：代码职责和开发步骤。
- README_MineBBS.md：发布页面与历史版本记录。
