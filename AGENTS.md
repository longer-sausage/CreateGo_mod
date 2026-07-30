---
description:
alwaysApply: true
---

# AGENTS.md

> **重要：必须使用中文交流。** 所有回复、文档、提交信息均使用简体中文。代码中的变量名、函数名使用英文。注释格式为英文+中文翻译。

本文件为 AI Agent 在本仓库中工作时提供指导。

---

## 项目概述

本项目是游戏 Minecraft 的 KubeJS 项目，用于客制化整合包机械动力向前冲 (CreateGo)。

## KubeJS 概述

Find out more info on the website: https://kubejs.com/

Directory information:

assets - Acts as a resource pack, you can put any client resources in here, like textures, models, etc. Example: assets/kubejs/textures/item/test_item.png
data - Acts as a datapack, you can put any server resources in here, like loot tables, functions, etc. Example: data/kubejs/loot_tables/blocks/test_block.json

startup_scripts - Scripts that get loaded once during game startup - Used for adding items and other things that can only happen while the game is loading (Can be reloaded with /kubejs reload_startup_scripts, but it may not work!)
server_scripts - Scripts that get loaded every time server resources reload - Used for modifying recipes, tags, loot tables, and handling server events (Can be reloaded with /reload)
client_scripts - Scripts that get loaded every time client resources reload - Used for JEI events, tooltips and other client side things (Can be reloaded with F3+T)

config - KubeJS config storage. This is also the only directory that scripts can access other than world directory
exported - Data dumps like texture atlases end up here

You can find type-specific logs in logs/kubejs/ directory

## 开发原则

- 自定义命名空间使用 `CreateGo`
- 通过直接读取 `mods` 目录下文件以及联网搜索获取最完全的 API
- 代码尽可能简洁
- 代码无需考虑向后兼容，新功能完全覆盖旧功能
- 尽可能复用已有代码
- 除非用户要求，默认删除所有测试接口、调试代码、注释掉的代码块、未使用的函数和导入
- 用户可以在游戏里完成任意操作，需要游戏内的操作时向用户提出要求
- 用户的要求可能不止在当前目录开发，可能包括修改 `FTB Quests` 等模组的配置等
- 如需要使用某些未安装的模组等，可以向用户提出要求

## 命名规范

- **物品命名** — Java 类采用大驼峰命名法 `物品英文名+Item`（如 `MapEditorItem`、`NpcEditorItem`），注册变量采用大写蛇形命名（如 `MAP_EDITOR_ITEM`、`NPC_EDITOR_ITEM`），注册 ID（物品 ID）采用物品英文名的蛇形命名法 `snake_case`（如 `map_editor`、`npc_editor`，不带 `_item` 后缀）。
- **实体命名** — Java 类采用大驼峰命名法 `实体英文名+Entity`（如 `NpcEntity`），注册变量采用大写蛇形命名（如 `NPC_ENTITY`），注册 ID（实体 ID）采用实体英文名的蛇形命名法 `snake_case`（如 `npc`，不带 `_entity` 后缀）。
- **本地化键** — 本地化键统一采用英文名的蛇形命名法（snake_case）。例如：`item.creatego.map_editor`、`item.creatego.npc_editor`、`entity.creatego.npc`。

## 注释规范

所有代码必须写注释，遵循 Google 代码注释风格。注释格式为英文+中文翻译。：

- **文件头注释** — 说明文件用途、作者、日期
- **函数/方法注释** — 说明功能、参数、返回值、可能的异常
- **类注释** — 说明职责、核心属性、使用方式
- **行内注释** — 解释非显而易见的逻辑，不要复述代码本身

## 日志规范

- **禁止聊天栏输出**：严禁在游戏聊天栏（`displayClientMessage` / `sendSystemMessage`）中输出日志或调试消息，所有日志必须输出到标准 Java 日志框架。
- **日志框架**：Java 代码统一使用 SLF4J（`org.slf4j.Logger` / `LoggerFactory.getLogger(ClassName.class)`）。
- **日志级别定义**：
  - `DEBUG`：高频排查或详细流向追踪（如分块上传接收、状态同步推送）。
  - `INFO`：关键生命周期事件与关键成功操作（如模组初始化、地图创建/加载/退出/删除、蓝图与皮肤保存提交、会话建立/注销）。
  - `WARN`：非致命异常或非法玩家操作（如无权限使用编辑器、在非隔离维度使用 NPC 编辑器、试图编辑不存在的地图）。
  - `ERROR`：系统级故障或操作失败（如 IO 写入失败、结构/皮肤格式解析失败、维度注册超时）。
- **日志格式与上下文**：
  - 服务端日志尽可能包含触发玩家或实体信息（如 `[玩家: {}]`、`[地图: {}]`）。
  - 使用 SLF4J 占位符 `{}`, 记录异常时需传入 Exception 或具体的 Message。