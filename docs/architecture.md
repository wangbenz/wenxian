# 《问仙录》V0.14 Agent 与数据架构

V0.2 的重规则文字 RPG 架构已经废弃并仅作历史实验封存。V0.14 只复用经过验证的视觉外壳和通用交互，不继承 V0.2 的战力、经济、职业、固定地图或预制内容体系。当前产品决策见 `docs/product-direction.md`。

## 1. 边界

V0.14 复用账号系统，但使用独立游戏表。V0.13 的角色、回合和上下文不写入新表；旧版本只读入口保留。这样既能重新设计玩法，又不会破坏现有账号、密码、自备密钥、公告、白名单和额度。

共享数据：`app_user`、`user_session`、`user_daily_quota`、`gameplay_config`、公告和灰度访问表。旧版继续读取 `user_ai_credential`；V0.14 使用供应商级密钥表，不覆盖旧版回滚数据。

V0.14 数据：

- `v014_game_save`：轻量权威快照、版本号、槽位、状态和乐观锁。
- `v014_game_turn`：玩家行动、正文、选择、模型补丁、时间、模型用量和幂等请求号。
- `v014_conversation`：滚动摘要与已总结回合。
- `v014_state_correction`：玩家修正前后快照和原因。
- `v014_user_model_preference`：供应商、模型和平台/玩家密钥来源。
- `ai_provider_config`：供应商白名单、服务端 Base URL、适配类型、平台密钥和开放开关。
- `ai_provider_model`：供应商下的模型目录，以及平台可用、玩家自备可选开关。
- `user_ai_provider_credential`：账号按供应商保存的加密密钥；账号注销时级联删除。

## 2. 单回合流水线

```text
H5 行动
  → Auth / 白名单 / 账号额度
  → 读取 v014_game_save（权威快照）
  → PromptContextAssembler（相关事实 + 滚动摘要 + 最近两回合）
  → ModelGateway（LangChain4j）
  → 容错解析 AiTurnEnvelope
  → V014StateReducer（时间、物品、能力、人物、记忆）
  → Java 结构校验
  → 单事务保存快照、回合和滚动摘要
  → 成功后记录平台赠送回合
```

`request_id` 在同一命卷内唯一；客户端重试不会生成第二个正式回合。快照使用 revision 乐观锁，冲突时整轮不写入。

创角前的 `CharacterRouteAgent` 是独立轻量调用：读取世界、种族、性别、姓名与可选偏好，使用关闭思考的 Flash 一次生成三条路线，不创建存档、不推进时间、不消耗游戏回合。自定世界默认由服务端开关关闭；H5 置灰只是提示，后端仍会拒绝绕过请求。

## 3. Java 与 Agent 职责

Java 负责：身份和数据归属、命卷数量、状态枚举、ID、幂等、乐观锁、事务、时间显式输入、实体生命周期、密钥安全、错误协议和审计。

Agent 负责：理解玩家意图、剧情过程、人物反应、合理得失、新实体提案、场景标题、推荐行动和滚动摘要。

Java 不再用大量关键词决定所有剧情是否“合理”，也不维护一套不完整的战力公式。Agent 不能直接写数据库、修改账号额度或决定任意模型地址。

## 4. 模型适配

业务层只依赖 `V014ModelGateway`。首版实现 `LangChain4jModelGateway`，通过 OpenAI-compatible 适配器接入 DeepSeek；供应商和模型目录已独立，后续可以在不修改命卷表的前提下增加其他适配器。

平台路由完全由管理台配置，H5 和玩家响应不返回平台供应商或模型。玩家只有在自备模式下才能选择服务端允许的供应商与模型；密钥必须与供应商一致且状态有效，不接受自定义 Base URL，防止 SSRF。自备路由失败不会回退平台路由。

以后接入其他模型时增加 Gateway/配置映射，不改命卷表和 reducer。LangChain4j 只是调用适配器，不是权威世界状态或自主 Agent 框架。

## 5. Prompt 上下文

每回合只发送必要事实：

- 世界前提与主角核心身份；
- 当前时间、地点、处境和境界；
- 当前可用物品与能力；
- 重要/相关人物；
- 关键记忆、滚动摘要和最近两回合；
- 本轮玩家行动。

已消耗/售出/遗失的物品、路人、无关旧回合和完整数据库历史不进入常规 Prompt。数据库继续保存历史，Prompt 不等于数据库。

主剧情一次调用完成，不因略短而整章重写。JSON 不完整但正文可用时采用容错结果和通用选择；完全失败时返回明确错误且不改变存档。

## 6. Agent 输出

模型返回 `AiTurnEnvelope`，核心字段包括正文、场景、地点、状态、境界、经过时间、选择、物品/能力/人物变化、记忆和滚动摘要。Prompt 同时提供数字时间和可读世界时刻；模型返回的经过时间必须与正文最后一幕一致。

所有更新按稳定 ID 或名称归并：已有实体更新生命周期，新实体由服务端分配 ID；路人不长期保存。正文结束后仍持有的物品、货币、能力和持续关系必须同时进入补丁。玩家明确时长由 Java 解析并覆盖模型猜测。

## 7. API

```text
GET    /api/v1/v014/bootstrap
POST   /api/v1/v014/character-routes
GET    /api/v1/v014/saves
POST   /api/v1/v014/saves
GET    /api/v1/v014/saves/{saveId}
DELETE /api/v1/v014/saves/{saveId}
POST   /api/v1/v014/saves/{saveId}/turns
POST   /api/v1/v014/saves/{saveId}/corrections
DELETE /api/v1/v014/saves/{saveId}/items/{itemId}?revision={revision}
DELETE /api/v1/v014/saves/{saveId}/characters/{characterId}?revision={revision}
GET    /api/v1/v014/generation-settings
PUT    /api/v1/v014/generation-settings
PUT    /api/v1/v014/generation-settings/providers/{provider}/credential
DELETE /api/v1/v014/generation-settings/providers/{provider}/credential
```

行囊与人物整理直接修改命卷 JSON 中的 `inPossession` / `tracked`，使用乐观锁并写入修正审计；不调用模型、不新增回合、不推进世界时间。旧 JSON 没有这两个字段时由记录构造器兼容推导，因此不增加 SQL 迁移。

账号继续使用 `/api/v1/auth/*`；`/api/v1/ai/*` 仅保留旧版兼容，V0.14 使用供应商级生成设置接口。错误沿用统一玩家问题协议，不向 H5 暴露 Java、数据库、平台模型、模型原始响应或密钥。

管理台新增 `/api/admin/v014/overview` 独立统计新命卷，并由 `/api/admin/generation-providers` 维护供应商级平台密钥、玩家自备开放范围和平台当前模型；真实路由仍保留在内部用量日志中。

## 8. 发布顺序

1. 备份 RDS，并执行 `docs/migrations/V0.14__light_story.sql`。
2. 部署后端并验证健康检查、新表和模型配置。
3. 部署管理台，检查 V0.14 观察页。
4. 部署 H5，先对白名单账号开放。
5. 用平台密钥分别完成创角、两次行动、显式时间、实体增减、修正和封存。
6. 管理台将 `current_game_version` 切换为 V0.14；验证后关闭白名单。

迁移只建新表，不复制或删除 V0.13 游戏数据。回滚 H5/后端时，新表保留，不能在回滚脚本中清空。
