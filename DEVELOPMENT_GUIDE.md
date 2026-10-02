# EliteMobs 开发指南（29.21.2）

## 项目用途

这是给 Paper 服务器使用的自定义 EliteMobs 插件。普通精英只从原版自然刷怪事件中抽取；Boss 由独立任务在远处布署，玩家接近后才生成实体。精英、Boss 和已索引的玩家装备快照保存在插件自己的 SQLite 文件中。

## 主要代码

| 位置 | 负责内容 |
| --- | --- |
| EliteMobsPlugin.java | 插件启动、关闭、重载和模块注册 |
| EliteConfig.java | 读取 config.yml |
| EliteMobManager.java | 精英属性、装备、职业和状态 |
| spawn/EliteSpawnHandler.java | 普通精英生成 |
| spawn/BossSpawner.java | Boss 布署与坐标提示 |
| db/ElitePersistence.java | 精英/Boss 的保存、区块卸载和恢复 |
| ai/ | 怪物职业、词缀与 Boss 技能 |
| combat/ | 战斗伤害和武器强化 |
| essence/、rune/ | 宝石、淬炼与符文 |
| gui/ | /emmenu 商店和管理菜单 |

武器真实攻击属性来自 Minecraft 的属性修饰符。淬炼说明和旧下界合金装备修复在读不到属性时仍会回退到代码内的原版伤害表。

## 配置和数据

- src/main/resources/config.yml：插件默认设置；服务器实际设置位于 plugins/EliteMobs/config.yml。
- mobs.yml：各类怪物的属性配置。
- gems/：宝石定义。
- messages.yml：可自定义的玩家提示。插件启动和 /em reload 不会覆盖已有文件。
- elitemobs.db：运行时 SQLite 数据库，不要提交到源码仓库。
- elite_gear_state：记录装备完整 ItemStack 快照、属性/宝石摘要和数据版本。背包和末影箱会自动索引；离线快照可在插件升级时迁移，玩家下次登录自动取回，不需要逐人执行修复命令。尚未登录并建立快照的旧装备，会在该玩家首次登录时自动索引和迁移。

## 依赖

- 目标环境：Paper 1.21+；当前构建针对 Paper 26.2 和 Java 25。
- SQLite 驱动在 plugin.yml 中声明，由 Paper 加入插件类路径。首次启动需要网络下载或已有缓存。
- Vault、PlayerPoints、PlaceholderAPI、LuckPerms 和领地插件为可选依赖；未安装时相关功能会跳过。

## 构建和部署

在 PowerShell 中运行：

    .\build.ps1 -ServerPath 'G:\Minecraftserver\DaemonData\Servers\1'

默认 Java 路径从服务器目录推导。JDK 在其他位置时增加 -JavaBin 参数。产物写入 build-output/EliteMobs-29.21.2-flightless.jar。

构建只生成 JAR，不会自动复制到服务器。部署新 JAR 后重启服务器；修改配置后使用 /em reload。该命令会重建 AI 和保存间隔任务；修改 persistence.enabled 开关仍需重启服务器。

## 文档入口

- README.md：功能、指令和玩家配置说明。
- README_MineBBS.md：发布页面与历史更新记录。29.17 及以前的飞行和二段跳记录只代表旧版本。
- PROJECT_STRUCTURE.md：目录、构建和版本管理说明。
- plugin.yml：插件版本、Paper 依赖和指令权限。
- config.yml：新安装时复制的默认配置。

仓库没有单独的自动化测试套件。改动后至少应确认 PowerShell 构建成功；Paper 事件和实际游戏表现仍需在服务器上验收。
