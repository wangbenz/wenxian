# 《问仙录》统一命卷平台架构

> 状态：V0.14 命卷已全量迁入通用表；V0.15 正在设计可信事实与玩家决定边界，尚未进行生产结构变更
> 最近更新：2026-08-09

## 1. 决策背景

V0.13 与 V0.14 为了快速验证不同玩法，分别形成了独立接口、Service、状态模型和数据表。V0.14 的隔离在当时保护了旧玩家数据，但不能继续演变为每个产品版本复制一套代码和表的长期模式。

当前生产组合基线：

| 部分 | 线上构建对应提交 |
|---|---|
| H5 | `9d6731c` |
| 游戏后端 | `57061d9` |
| 管理台 | `5050e3e` |

生产环境以实际部署构建为准；本地未提交 Hotfix 不属于上述基线，不得混入架构迁移提交。

当前落地进度：

- 已新增 `StoryController`、`StoryApplicationService`、`StoryEngineRegistry` 与稳定命卷 DTO；
- 已新增 V0.14 防腐适配器与通用表轻叙事运行时；通用运行时开启后同时接管创角、读取、推进、修正、整理与封存；
- 已新增 `/api/v1/generation/*`，平台模式继续隐藏具体供应商和模型；
- H5 当前玩法请求已改走稳定 Story/Generation API，页面视图仍保留少量 `v014` 兼容命名；
- 已新增 `story_*` 通用表脚本以及命卷、回合、实体 Repository；RDS 当前由通用表承接 V0.14 权威写入；
- 已完成 V0.14 只读预演、隔离候选、双哈希激活、全量迁移和迁移后核对；旧表不再参与命卷状态与存活上限判断；
- V0.13 不再转换为可写命卷，原表作为历史档案冻结；后续运营统计通过独立统计事件与日汇总解耦版本业务表；
- 通用运行时通过 `wenxian.story.persisted-runtime-enabled=true` 显式开启；关闭时可回到迁移前兼容路径排查，但不能把通用表新数据反写旧表。

本架构采用“统一命卷平台 + 可插拔故事引擎 + 历史档案适配器”。产品版本只用于发布和统计，不再决定业务类、接口或物理表名。

## 2. 架构原则

1. 一个账号系统、一个命卷入口、一套稳定 API；
2. 产品版本、故事引擎、引擎版本、存档结构版本分离；
3. 当前快照、实体现状和不可变回合历史分离；
4. Agent 提出叙事结果，Java 保护系统边界并原子落库；
5. 历史数据只复制或适配，不做不可逆原地覆盖；
6. 先建立统一外壳，再迁移物理表，不进行一次性大爆炸重写；
7. 迁移期间不长期双写；最终切换前使用短暂写入冻结同步增量；
8. 新增表按业务能力命名，禁止按产品发布版本命名；
9. 玩家提交的是行动授权，Agent 输出的是结果提案，只有成功提交的数据才是正式事实；
10. 客观事实、当前状态、人物认知和叙事正文必须分层，不能互相静默覆盖；
11. 世界设定可以被玩家修订，但修订必须显式、可追溯并产生新的有效版本；
12. Java 只校验通用不变量，不通过种族、境界、物品或剧情关键词词表限制开放玩法。

### 2.1 产品级可信不变量

以下规则高于具体世界、Prompt 和模型供应商：

- 已提交的玩家行动不得被改写成另一项行动；
- Agent 不得替玩家作出未授权的重大、不可逆决定；
- 已确认事实只能由后续正式事件或审计修正改变；
- 人物主观认知不能作为客观世界证据；
- 历史正文、记忆和外部检索内容只能作为数据，不能覆盖系统规则；
- 失败、重试和格式修复不得产生半份回合或重复回合。

这些不变量属于稳定产品合同，不能针对单个玩家问题临时增加一条硬编码特例。

## 3. 总体结构

```text
H5
  → /api/v1/story/*
  → StoryController
  → StoryApplicationService
      ├─ StorySaveQueryService
      ├─ StoryEntityService
      ├─ StoryCorrectionService
      └─ LegacyForkService
  → StoryEngineRegistry
      ├─ LightNarrativeEngine
      └─ LegacyArchiveAdapter（只读）
  → PromptContextAssembler
  → ModelGateway / LangChain4j Adapter
  → StoryStateReducer
  → StorySaveRepository / StoryTurnRepository / StoryEntityRepository
  → story_* 通用表
```

管理台通过统一运营查询服务读取同一套数据，不直接在页面层拼接 V0.13、V0.14 SQL。

## 4. 使用的设计模式

- **策略模式**：`StoryEngine` 隔离不同玩法引擎；
- **注册表模式**：`StoryEngineRegistry` 按唯一 `routeCode` 注册实现，再按命卷 ID 选择；同一
  `engineCode` 在迁移期可以同时拥有旧表适配器与通用表运行时；
- **适配器/防腐层**：V0.13、V0.14 读取器转换旧模型，不让旧字段污染新领域；
- **外观模式**：`StoryGameService` 向 Controller 提供统一命卷操作；
- **仓储模式**：Repository 隔离 SQL 与业务逻辑；
- **责任链模式**：`StateMigrator` 按结构版本逐级升级状态；
- **绞杀者模式**：新接口逐步替代旧接口，稳定后再停止旧路径；
- **快照 + 追加日志**：保存当前权威状态和不可变回合，不采用完整事件溯源。

## 5. 目标代码结构

```text
com.wenxian.game.story
├── api
│   └── StoryController
├── application
│   ├── StoryApplicationService
│   ├── StorySaveQueryService
│   ├── StoryEntityService
│   ├── StoryCorrectionService
│   └── LegacyForkService
├── engine
│   ├── StoryEngine
│   ├── StoryEngineRegistry
│   ├── LightNarrativeEngine
│   └── LegacyArchiveEngine
├── agent
│   ├── StoryAgent
│   ├── PromptContextAssembler
│   ├── ModelGateway
│   └── AgentOutputParser
├── domain
│   ├── StoryState
│   ├── StoryEntity
│   ├── StoryMemory
│   ├── StoryTurn
│   └── StoryPatch
├── reducer
│   └── StoryStateReducer
├── persistence
│   ├── StorySaveRepository
│   ├── StoryTurnRepository
│   ├── StoryEntityRepository
│   └── StoryMemoryRepository
├── legacy
│   ├── V013ArchiveReader
│   └── V014MigrationReader
└── migration
    ├── StateMigrator
    ├── V014SaveMigrator
    └── V013ForkMigrator
```

迁移完成前，新 Repository 可以临时委托现有 `V014SaveStore`，但公开 API 和应用服务不再带产品版本名。

## 6. 版本模型

| 字段 | 示例 | 变化时机 |
|---|---|---|
| `release_version` | `V0.15` | 每次产品发布 |
| `engine_code` | `LIGHT_NARRATIVE` | 采用另一套根本玩法 |
| `engine_version` | `2` | 同一玩法规则发生重大变化 |
| `state_schema_version` | `3` | 当前状态 JSON 字段发生变化 |
| `prompt_version` | `story-20260808-01` | Prompt 或上下文策略变化 |

如果 V0.15 只调整 H5、Prompt 或模型，数据表和引擎版本不变。增加状态字段只升级 `state_schema_version`。重大但仍兼容的玩法调整升级 `engine_version`。只有玩法根本不同才增加新的 `engine_code`。

## 7. 权威数据划分

- `story_turn`：不可变的玩家行动、已接受变化、剧情和模型用量，是客观事件的审计来源；
- `story_save.state_json`：由已提交回合与修正投影出的主角、世界时间、当前位置、当前处境等核心快照；
- `story_entity`：由已提交回合与修正投影出的物品、能力、重要人物、势力、地点和世界事实；
- `story_memory`：跨回合需要取回的事件、承诺、线索或人物认知，不自动等于客观事实；
- `story_conversation`：可重建的滚动摘要，不是事实来源；
- `story_state_correction`：玩家对事实、认知或世界设定进行修正的前后审计；
- `story_save_migration`：迁移来源、结果、哈希和错误；
- `story_engine_catalog`：允许创建、继续和迁移的引擎目录。

核心快照不再无限保存完整人物、物品、能力和时间线数组。API 读取命卷时由核心快照与当前实体组合成玩家视图。

### 7.1 四层事实模型

| 层级 | 含义 | 示例 | 是否可直接覆盖客观事实 |
|---|---|---|---|
| 客观事件 | 已成功提交的世界变化 | 某人死亡、赠出法器、闭关两年 | 否，只能追加后续事件或修正 |
| 当前投影 | 从事件和修正得到的现状 | 当前地点、仍持有、仍掌握 | 否，必须通过 Reducer 更新 |
| 人物认知 | 某角色知道、相信或误解的内容 | 主角以为某人已死 | 否 |
| 叙事正文 | 面向玩家的自然语言呈现 | 本回合故事文本 | 否 |

运行时优先读取当前投影；审计和重建以已提交回合与修正记录为依据。若正文、摘要与当前投影冲突，必须记录一致性错误，不得用正文静默覆盖状态。

### 7.2 动态世界协议

每份命卷拥有独立的当前世界约定：

```text
系统硬规则
  └─ 当前世界事实（模板 + 已确认扩展 + 已生效修订）
       └─ 尚未定义、允许自由生成的开放空白
```

世界变化分为三种语义：

- `EXPAND`：为开放空白增加设定；
- `TRANSFORM`：通过世界内事件改变现状；
- `REVISE`：玩家在剧情外替换已有设定。

如果玩家表达与当前设定冲突但意图不明确，回合不能擅自落库为 `TRANSFORM` 或 `REVISE`，应返回待确认问题。修订生效后保留旧版本和原因，历史正文不物理重写。

V0.15 设计阶段只确定此逻辑模型，不立即增加新表。正式编码前先核对现有 `state_json`、`story_entity` 和 `story_state_correction` 是否能以结构版本升级承载；无法满足审计要求时再提出最小迁移。

## 8. 单回合流水线

```text
H5 生成 requestId 并提交 action + expectedRevision
  → 验证登录、白名单、命卷归属和额度
  → 同账号已有生成时立即返回，不在 Java 锁后排队
  → 按 requestId 检查幂等结果
  → 读取系统规则、当前世界事实、核心快照、相关实体、人物认知、关键记忆和滚动摘要
  → StoryEngineRegistry 选择故事引擎
  → PromptContextAssembler 组装本轮相关上下文
  → ModelGateway 调用平台或玩家模型
  → AgentOutputParser 解析统一 StoryPatch
  → StoryStateReducer 校验稳定引用、决定边界和通用不变量并应用变化
  → 单事务写入快照、回合、实体、记忆、摘要和额度
  → 返回最新 revision 与能力列表
```

网络中断后，H5 使用相同 `requestId` 重试；如果服务端已经提交，直接返回已保存结果，不能生成第二回合。

## 9. Java、Agent 与 H5 边界

Java 负责：

- 账号、命卷归属、状态机、槽位和访问能力；
- requestId 幂等、并发门闩、乐观锁和事务；
- 实体稳定 ID、软删除、引用完整性、生命周期与审计；
- 显式时长的结构化解析、世界时间存储；
- 已掌握能力的可用状态、明确物品数量和归属的一致写入；
- 当前世界事实版本与显式修订的保存边界；
- 模型路由、额度、密钥加密、错误协议和用量记录。

Agent 负责：

- 理解自由行动；
- 生成完整剧情单元；
- 根据已有事实判断局部得失、人物反应和合理代价；
- 区分世界事实、人物认知和纯叙事描述；
- 在下一项重大、不可逆决定前停止；
- 提出核心状态、实体、世界事实、人物认知、记忆和推荐行动的结构化补丁。

H5 负责：

- 展示命卷和能力；
- 生成并复用 requestId；
- 防止输入时误触；
- 网络失败后读取服务器最新 revision；
- 为事实修正、认知修正和世界修订提供明确入口与结果提示；
- 只根据 `capabilities` 控制按钮，不判断产品版本。

Java 不维护精密战力、统一物价、种族内容表或关键词裁决。Agent 不能直接访问数据库、修改账号额度、指定任意模型地址，也不能将 Prompt 中的叙述当成已提交事实。

## 10. 稳定 API 契约

```text
GET    /api/v1/story/bootstrap
POST   /api/v1/story/character-routes
GET    /api/v1/story/saves
POST   /api/v1/story/saves
GET    /api/v1/story/saves/{saveId}
DELETE /api/v1/story/saves/{saveId}
POST   /api/v1/story/saves/{saveId}/turns
POST   /api/v1/story/saves/{saveId}/corrections
DELETE /api/v1/story/saves/{saveId}/entities/{entityId}
POST   /api/v1/story/saves/{saveId}/fork

GET    /api/v1/generation/settings
PUT    /api/v1/generation/settings
PUT    /api/v1/generation/providers/{provider}/credential
DELETE /api/v1/generation/providers/{provider}/credential
```

命卷摘要必须返回：

```json
{
  "saveId": "save-001",
  "mode": "PLAYABLE",
  "engineCode": "LIGHT_NARRATIVE",
  "engineVersion": 1,
  "stateSchemaVersion": 1,
  "revision": 138,
  "turnNumber": 136,
  "capabilities": ["READ", "PLAY", "ARCHIVE", "CORRECT", "REMOVE_ENTITY"]
}
```

V0.13 只读档案通过同一摘要返回 `mode=ARCHIVE`，并提供 `READ`、`FORK` 能力。H5 不读取 `source_edition` 决定交互。

API 路径中的 `v1` 是接口契约主版本，只有出现无法兼容的请求/响应格式时才升级到 `v2`，与产品 V0.15 无关。

### 创建命卷

```json
POST /api/v1/story/saves
{
  "worldCode": "XUANXU",
  "playerName": "陆沉",
  "race": "人族",
  "gender": "男",
  "origin": "山村少年",
  "opening": "雨夜来到青岐城外",
  "storyPreference": "自由探索"
}
```

引擎编码和引擎版本由服务端当前目录决定，H5 不允许自行指定未开放引擎。

### 推进回合

```json
POST /api/v1/story/saves/{saveId}/turns
{
  "requestId": "turn-7f81...",
  "expectedRevision": 138,
  "action": "先回客栈查看青纹令"
}
```

`expectedRevision` 必须与服务端当前修订号一致。兼容期由旧引擎入口在账号锁内部校验，不能在适配器先读后写；统一命卷表接管后由数据库乐观锁和回合事务再次校验。

### 修正命卷

```json
POST /api/v1/story/saves/{saveId}/corrections
{
  "expectedRevision": 138,
  "instruction": "当前地点应为青岐城北门"
}
```

修正属于显式状态写入，也必须携带当前修订号；失败时不得覆盖较新的命卷。

成功响应返回完整当前视图、最新 `revision`、回合号和 capabilities。`requestId` 已提交时返回同一结果；正式统一引擎应在同账号另一轮仍在生成时返回可重试的 `TURN_IN_PROGRESS`。兼容引擎至少必须用账号锁与 `revision` 保证并发请求只能成功一次，不得覆盖较新的命卷。

### 承接旧世

```json
POST /api/v1/story/saves/{archiveId}/fork
{
  "requestId": "fork-4a62...",
  "confirmation": "承接旧世"
}
```

响应返回新命卷 ID、迁移摘要和仍然可读的原档案 ID。承接失败不得创建半份新命卷。

### 错误协议

```json
{
  "code": "TURN_IN_PROGRESS",
  "category": "STATE",
  "title": "上一轮仍在推演",
  "message": "请等待上一轮完成后同步命卷。",
  "suggestions": ["等待片刻后刷新"],
  "retryable": true,
  "turnConsumed": false,
  "stateChanged": false,
  "referenceId": "WX-..."
}
```

业务拒绝、模型失败、密钥失效、状态冲突和系统故障都使用同一协议，并明确是否消耗回合、是否改变存档。

## 11. Agent 输出契约

统一输出 `StoryPatch`：场景标题、正文、实际经过时间、核心状态变化、实体补丁、世界事实补丁、人物认知补丁、关键记忆、决定检查点、推荐行动和滚动摘要。

其中：

- `worldFactPatches` 只能表示 `EXPAND/TRANSFORM/REVISE` 三种语义；
- `knowledgePatches` 必须标明认知主体，不能冒充客观事实；
- `decisionCheckpoint` 表示剧情是否已经来到下一项重大决定，正文不得越过该点继续替玩家行动；
- 所有 `UPDATE/HIDE/RESTORE` 必须引用本轮上下文中提供的稳定 ID；
- Agent 生成的名称和自然语言不能作为关联旧实体的唯一依据。

实体补丁操作只允许：

- `CREATE`：创建正式实体，由服务端分配稳定 ID；
- `UPDATE`：引用已提供的实体 ID 更新现状；
- `HIDE`：移出当前行囊或关注，不删除历史；
- `RESTORE`：明确剧情重新获得或重新关注后恢复。

无法绑定现有实体时，解析器不得随机覆盖另一条记录。结构不完整时允许一次纯格式修复；修复仍失败则整轮不写入、不扣额度。

## 12. 存档结构迁移

```java
/** 将同一故事引擎的一版状态安全升级到下一版。 */
public interface StateMigrator {
    String engineCode();
    int fromVersion();
    int toVersion();
    JsonNode migrate(JsonNode source);
}
```

旧状态必须逐级迁移，例如 `V1 → V2 → V3`，不能跳过中间版本。正式发布前后台批量迁移，读取时迁移只作为遗漏兜底。每次迁移记录原始哈希、新哈希、状态和错误，失败不得覆盖原状态。

## 13. V0.13 与 V0.14 兼容

### V0.14 完整迁移

- `v014_game_save` → `story_save`；
- `v014_game_turn` → `story_turn`；
- `state_json.inventory/abilities/characters` → `story_entity`；
- `state_json.memories` → `story_memory`；
- `v014_conversation` → `story_conversation`；
- `v014_state_correction` → `story_state_correction`；
- `v014_user_model_preference` → `user_model_preference`。

迁移后新回合只写通用表，旧表保留只读。

当前已落地只读预演、两阶段单命卷迁移器和受控批量调度，但未开放生产写路径：

- `V014MigrationSourceReader` 只读取旧命卷、回合、摘要和修正记录，不依赖目标写仓储；
- `V014MigrationPreviewService` 在内存中拆分核心状态、实体和记忆，校验回合顺序、世界时间与 JSON；
- 预演产物是完整的可落库候选快照，正式迁移不允许重写另一套转换逻辑；
- 每份报告给出 `READY`、`WARNING` 或 `INVALID` 结论，以及源数据和候选目标数据的 SHA-256 哈希；
- 旧快照无法证明的实体首次出现回合、记忆发生回合必须标记为推定，不得伪造成精确历史；
- 预演测试必须证明旧表与 `story_*` 表均未发生写入；
- `V014MigrationExecutionService.prepare` 在一个事务内写入 `MIGRATING` 候选、全量回读并比对哈希，
  通过后只将迁移任务标记为 `READY`；
- 玩家命卷查询默认排除 `MIGRATING` 和 `MIGRATION_FAILED`，候选不会因为已写入数据库而提前可玩；
- `activate` 会重新预演旧命卷，并重新回读候选命卷；源哈希或目标哈希任一变化，
  任务进入 `HASH_MISMATCH` 且候选保持隔离；
- 两个阶段都不修改 V0.14 旧表；`V014MigrationBatchService` 使用稳定 ID 游标分页预演，批量准备逐份独立事务，
  不提供一键全量激活；
- `story_migration_prepare_enabled` 与 `story_migration_activation_enabled` 是彼此独立且默认关闭的运行开关，
  `story_migration_batch_limit` 只限制单次请求规模；
- 激活只能逐份执行，管理台必须提交刚刚核对的 `before_hash` 和 `after_hash`，执行器仍会再次读取源与候选复验；
- 管理台不复制迁移规则，也不直接写命卷。它通过独立服务令牌调用游戏后端 `/internal/story-migrations/v014/*`；
  游戏后端和管理台必须配置相同的 `WENXIAN_MIGRATION_ADMIN_TOKEN`，未配置时接口保持不可用；
- 当前仍未执行全量迁移；只激活 1 份测试候选验证运行时，其余玩家尚未切换写路径。

迁移期运行时使用两个不同的注册路由，但它们对外仍属于同一个 `LIGHT_NARRATIVE` 故事玩法：

- `LIGHT_NARRATIVE_V014`：默认创角与未迁移命卷的旧表兼容适配器；
- `LIGHT_NARRATIVE_STORY`：只处理 `story-*` 的通用表运行时，默认关闭；
- 只有通用运行时显式开启且迁移任务为 `SUCCESS` 后，兼容适配器才从列表中隐藏对应旧源命卷；
  单纯部署代码但保持开关关闭时，玩家仍看到旧命卷，不会出现角色空窗；
- 通用运行时直接写 `story_save/story_turn/story_entity/story_memory/story_conversation`，失败不得回退写旧表；
- 当前通用运行时暂时复用已验证的 V0.14 Agent 与轻状态归并器，数据库、事务和稳定 API 已与 V0.14 表解耦。

2026-08-08 首次 RDS 只读体检基线：911 份 V0.14 命卷、约 2.9 万回合中，核心状态 JSON、
状态回合数、回合序列、世界时间和滚动摘要均未发现结构异常；43 份命卷尚无正式回合。
发现 4 个旧回合的 `memory_patch_json` 不是严格合法 JSON：其中 3 个可通过确定性格式规则恢复并标记
`TURN_PATCH_REPAIRED`；另 1 个存在歧义，采用回合级 `TURN_PATCH_QUARANTINED`，候选补丁只保存旧表定位、
原文哈希和原因码，原始内容继续保留在只读旧表。正式迁移不得静默改成空对象，也不得调用模型重写历史；
单条历史补丁隔离不应阻断当前权威命卷迁移。
实体平均约 30 个、单档最高 989 个，迁移可保留隐藏历史实体，但运行时上下文和玩家列表只能加载可见实体。

全量复核统一使用 `V014MigrationFullAuditRdsReadOnlyTest`。该入口必须由
`WENXIAN_RDS_FULL_AUDIT=true` 显式开启，并要求单独提供 RDS URL、账号和密码；测试会同时设置 JDBC 只读连接、
MySQL `REPEATABLE READ` 与 `READ ONLY` 事务，在同一数据库快照中按稳定 ID 游标扫描全部命卷。报告默认写入本地
`backend/target/v014-migration-audit.json`，只包含命卷 ID、结论、问题码、数量和哈希，不包含邮箱、剧情正文或完整状态。
执行命令：

```bash
cd backend
WENXIAN_RDS_FULL_AUDIT=true \
WENXIAN_RDS_URL='jdbc:mysql://数据库地址:3306/wenxian?...' \
WENXIAN_RDS_USER='只读账号' \
WENXIAN_RDS_PASSWORD='数据库密码' \
mvn -Dtest=V014MigrationFullAuditRdsReadOnlyTest test
```

报告属于本地审计产物，不得提交仓库。若扫描总数与事务快照内源命卷总数不一致，测试必须失败；任何 `INVALID`、
`ERROR` 或新增问题码都必须先人工核对，不能直接进入批量准备阶段。

2026-08-08 全量只读复核结果：一致性快照内 920/920 份命卷全部完成预演，共 29,352 回合、27,910 个实体、
23,071 条记忆；未发现 `INVALID` 或 `ERROR`。全部命卷显示为 `WARNING` 是因为旧结构没有保存实体首次出现回合，
其中 799 份还包含按世界时间推定发生回合的记忆；另包含前述 3 个确定性修复补丁和 1 个隔离补丁，没有新增问题码。
通过京东云 SSH 通道逐命卷复用正式预演逻辑耗时约 16.5 分钟，因此该入口只用于离线发布审计，不接入管理台在线请求。

2026-08-08 小批准备检查点：在仓库外完成受影响结构与 `gameplay_config` 备份后，RDS 应用
`story-platform-v1.sql`，旧表仍保留 922 份 V0.14 命卷。仅对测试账号的 3 份命卷执行 `prepare`，结果全部为
`READY`；对应候选保持 `MIGRATING`，源/目标回合数分别为 9/9、30/30、0/0，落库回读哈希均一致。
操作结束后 `story_migration_prepare_enabled=false`、`story_migration_activation_enabled=false`；未部署临时服务、
未激活候选、未修改 V0.14 源命卷，也未改变玩家当前读写路径。

2026-08-08 单命卷激活与运行时闸门检查点：仅将上述测试账号槽位 1 候选标记为 `SUCCESS/ACTIVE`，
双哈希与 9/9 回合数量仍一致，另外两份保持 `READY/MIGRATING`，两个迁移开关恢复为关闭。旧构建的稳定 API
无法路由 `story-*`，因此没有继续激活。随后新增默认关闭的通用表运行时和迁移期双路由；只有构建通过全部测试并以
`wenxian.story.persisted-runtime-enabled=true` 启动的隔离实例，才允许读取或推进该测试命卷。该检查点不等于生产切流。

同日 RDS 运行时冒烟：隔离实例成功将稳定命卷列表去重为 3 份，已激活命卷可从通用表还原 3 件物品、1 项能力、
2 名人物和 16 条有效记忆；随后使用真实模型推进 1 回，目标命卷变为第 10 回/revision 2，生成正文 1060 字并新增
1 条记忆。旧源命卷仍为第 9 回/revision 13，旧回合表仍为 9 条，证明新写入没有回流旧表。幂等入口已补充测试，
同一 requestId 的重试必须先返回已提交结果，再检查旧 revision。

运行时冒烟通过后，另外两份只读候选按相同双哈希流程激活。测试账号最终列表为 1 份可玩通用命卷与 2 份只读
通用档案，回合数分别为 10、30、0，旧表副本全部从稳定列表隐藏；重复提交上述真实回合仍返回第 10 回/revision 2，
`story_turn` 保持 10 条且相同 requestId 仅 1 条。最终迁移任务为 `SUCCESS=3`，准备与激活开关均恢复关闭。

### V0.13 旧世承接

V0.13 原表不直接变为当前可写命卷。适配器统一展示旧档；玩家发起承接时复制姓名、性别、种族、出身、最后可靠境界和地点、重要能力、持有物品、重要人物以及关键经历摘要。旧体力、追查、旧裁决、异常货币和临时路人不迁移。原档案永远保留。

## 14. 管理台与独立统计层

管理台不再把注册、命卷、回合、Token、版本和最近生成全部堆在一个接口，也不再用跨版本业务表 `UNION`
作为长期统计方式。统计采用三层结构：

1. `analytics_event` 追加记录注册、命卷创建、成功回合、修正、整理和封存等产品事实；
2. `analytics_user_day` 保存玩家每天是否活跃及有效行为次数；
3. `analytics_daily_metric` 与 `analytics_retention_cohort` 保存日汇总和留存批次，管理台默认只读汇总。

`ai_usage_log` 继续作为单次模型请求事实，`operation_error` 继续作为最终玩家问题事实。模型请求、最终失败和成功回合
必须分别统计：一次回合的重试可能产生多次模型请求，但最多产生一个成功回合。

### 指标口径

- 展示和分日统一使用 `Asia/Shanghai`；
- 活跃行为包括创建命卷、成功推进、修正命卷和整理当前实体，单纯登录不算活跃；
- 次日、3日、7日和14日留存按注册后的对应自然日是否存在活跃行为计算；
- 历史回填标记 `BACKFILL`，上线后旁路采集标记 `REALTIME`，页面不得将两种精度伪装成同一事实；
- 近15分钟只能称“近15分钟活跃”，没有心跳时不得称为在线玩家；
- Token 以供应商返回值为准，未知值必须保留来源说明。

### 页面边界

- **今日总览**：新增、活跃、成功/失败回合、Token、延迟、七日趋势和告警；
- **增长与留存**：激活漏斗、DAU/WAU/MAU、留存批次和回流；
- **玩法行为**：回合深度、命卷生命周期、推荐/自由行动、修正和整理；
- **生成服务**：模型请求、成功回合、Token、缓存、重试、失败率和 P95 延迟；
- **问题中心**：问题编号、受影响玩家、公开提示和内部诊断；
- **玩家档案**：账号、当前/历史命卷、回合、用量和问题的单账号视图。

版本是统计维度而不是页面或物理表。“V0.14 观察”在新页面就绪后移除；历史版本只通过独立的历史回算口径查看。
平台模型配置使用中性配置名，不再使用 `v014_platform_provider`。

### 性能与失败边界

命卷事务提交后发布轻量领域事件，统计监听器在独立执行器中落库；统计失败只记录日志，不得影响 H5 响应。
当前日每五分钟增量汇总，已结束日期每日封账并允许幂等重算。首页只读取日汇总和少量当前事实，目标是常规数据量下
服务端响应低于 1 秒。首次启用统计平台时可从统一 `story_*` 事实表执行一次幂等历史回算；写入
`BACKFILL_COMPLETED` 后，管理台启动与每日任务只刷新统计汇总，不再反复扫描旧版业务表。详细回合、模型请求和
错误列表必须分页，不能阻塞首页。

### 历史回算与切换

RDS 只读盘点显示账号注册最早可追溯至 2026-07-25，逐次模型用量最早可追溯至 2026-07-27；
`auth_registration` 仅覆盖 2026-07-30 之后的 1,578 个账号，因此更早注册批次使用 `app_user.registered_at`
并标记历史回算。旧 `game_*`、`v014_*` 与现 `story_*` 回合只在一次性回填任务中读取，完成后管理台不再直接查询旧表。

玩家支持类查询与运营统计严格分流：玩家档案、当前/封存命卷、回合明细和当前回合榜读取统一
`story_save/story_turn`；趋势、留存、模型消耗与异常指标读取 `analytics_*` 和 `ai_usage_log`。管理台前端不得自行
拼接多套版本表，也不得把封存命卷计入当前排行榜。

问题中心分为两层：近七日待处理队列由服务端按稳定错误编码聚合次数、影响玩家数、最高 HTTP 状态和最近发生时间；
问题明细按编号、邮箱、角色、命卷或错误编码在数据库过滤。前端不得只抓取固定数量的最近记录后自行推断全局高频问题。

新旧统计先并行核对七天，通过指标差异审计后再切换默认入口；统计源开关只影响管理台，不影响玩家游戏。

2026-08-08 RDS 验证：旁路新增 4 张 `analytics_*` 表后完成首次历史回算，共形成 3,018 条玩家日事实和
99 条留存批次记录。成功回合 `107,374`、创建命卷 `3,409`、封存 `1,265`、命卷修正 `694`、
整理实体 `11` 均与源业务表逐项一致。真实管理接口响应约为 64–682 毫秒；首次回算中发现并修正旧命卷
创建时间兜底与 V0.14 封存迁移时间失真，未修改任何玩家业务表。

迁移中心继续保持动态开关、有限分页预演、多选准备、最近任务和单份哈希确认激活。正式迁移顺序仍是
RDS 备份 → 只读全量预演 → 小批准备 → 抽样核对 → 单份灰度激活。

## 15. 实施阶段

1. **基线与设计**：冻结普通功能，记录生产构建，完成架构、数据字典和迁移映射；
2. **统一外壳**：新增 Story API、应用服务和领域接口，底层仍委托 V0.14 存储；
3. **H5 切换**：使用稳定 API 和 capabilities，旧档入口仍可使用；
4. **通用数据**：新增 `story_*` 表，后台迁移 V0.14 并核对；
5. **短暂切换**：写入冻结、同步增量、切换通用表、真实模型冒烟；
6. **管理台改造**：统一统计和迁移中心；
7. **旧世承接**：接入 V0.13 只读档案与复制转换；
8. **收尾**：停用版本化 API，旧表保留只读，不立即删除。

每个阶段必须独立可测试、可提交、可回滚，禁止将全部步骤合成一次发布。

## 16. 发布与回滚

数据库变更遵循 Expand → Migrate → Switch → Observe → Contract：

1. 先新增结构和兼容代码；
2. 后台复制、校验并记录迁移；
3. 最终切换前短暂限制写入并同步增量；
4. 灰度账号完成真实模型冒烟；
5. 观察成功率、重复回合、Token、延迟和错误；
6. 稳定一个发布周期后再停止旧路径。

回滚时切回旧接口和旧表；新表保留排查，不把新数据反向覆盖旧表。任何迁移前必须有 RDS 备份和逐表数量核对。

## 17. 验收标准

- 不再新增以产品版本命名的业务类、稳定 API 和表；
- H5 不再使用 V0.13/V0.14 条件决定主流程；
- V0.14 命卷与回合完整迁移，数量和关键哈希一致；
- V0.13 在统一列表可读，并能复制承接但不覆盖旧档；
- 网络重试不会重复推进；
- 物品、能力和人物获得、变化、隐藏长期一致；
- V0.15 普通发布不需要新增游戏表；
- 状态结构升级有逐级迁移测试；
- 管理台可按发布、引擎、Prompt 和模型观察效果。

V0.15 可信闭环进入编码前还必须建立六组真实回放用例：人物死亡、能力使用、物品数量与归属、显式时间跳跃、玩家重大决定边界、事实与世界修订。每组都要同时验证模型正文、结构化补丁、数据库现状、历史审计和 H5 展示，不能只测接口返回成功。

## 18. V0.15 代码审计结论与开发顺序

### 可以复用的现有底座

- `story_save/story_turn/story_entity/story_memory/story_state_correction` 已经提供统一命卷、追加回合、实体和修正审计；
- `request_id`、`revision`、账号生成门闩和单事务提交已经具备；
- `attributes_json` 与 `state_patch_json` 可以先承载世界事实、生命周期、数量是否明确和决定检查点；
- 模型网关、平台/自备路由和统一错误协议可以保留；
- H5 已经使用稳定 Story API，不需要再次建立产品版本接口。

### 不能继续打补丁的现状

- `PersistedLightNarrativeEngine` 仍直接依赖 `V014StoryAgent`、`V014StateReducer` 和 `V014State`；
- Agent 补丁没有稳定实体 ID，Reducer 仍主要按名称关联物品、能力和人物；
- 未返回物品数量时会默认创建为 1，无法表达“持有若干但数量未知”；
- 修正命卷仍通过地点、境界、物品等正则和关键词识别，不能表达事实/认知/世界三类修正；
- 世界只保存一份静态简介，没有当前世界事实和修订版本；
- 记忆没有认知主体，容易把“某人相信”写成“世界真实发生”；
- Agent 输出没有重大决定检查点，系统也无法判断正文是否越权；
- 保存的 `state_patch_json` 是模型原始结构，不足以证明 Reducer 最终接受了哪些变化。

因此 V0.15 不在 `V014*` 类上继续增加业务分支。现有 V0.14 实现保持可运行，新的中性领域组件逐步替换其内部依赖；每一步都由相同 Story API 和通用表承接。

### 第一批开发顺序

1. **冻结真实问题回放**：从玩家问题中抽取匿名化事实夹具，不把邮箱、完整 Prompt 或原始隐私写入仓库；
2. **建立中性 `StoryPatch`**：补充稳定实体 ID、世界事实、人物认知、决定检查点和明确/未知数量；
3. **建立 `PromptContextAssembler`**：按行动只提供相关客观事实、认知、近期回合和开放世界约定；
4. **建立 `StoryStateReducer`**：只按稳定 ID 和通用生命周期归并，保存“最终接受补丁”而不是模型原始补丁；
5. **重做修正协议**：先让 Agent 解析修正意图，再由 Reducer 对明确目标执行，不再使用剧情关键词词表；
6. **补充 H5 确认与回看**：世界冲突或重大设定变更需要确认，错误信息必须说明未消耗回合和未改变命卷；
7. **真实联调**：六组纵向用例分别用平台模型与自备模型运行，多轮核对数据库和页面；
8. **灰度决定**：通过后再决定是否升级 `engine_version/state_schema_version`，以及是否需要最小数据库迁移。

第一批默认不新增业务表。若人物认知需要支持多个主体和证据链，现有 `story_memory` 无法可靠承载时，再单独评审增加中性字段；不得把主体 ID 拼进摘要文本临时凑合。

### 首批真实回放验收矩阵

| 场景 | 回放前权威事实 | 玩家行动 | 必须成立 | 必须禁止 |
|---|---|---|---|---|
| 死亡人物 | 重要人物为已死亡，具有稳定 ID | 再次寻找或提及此人 | 可以追忆、调查遗物或发现有依据的新线索 | 无复生依据却作为活人对话 |
| 已掌握能力 | 能力状态可用，具有稳定 ID | 明确施展该能力 | Agent 能读取并按该 ID 更新近况 | 回答“尚未掌握”或重复创建同名能力 |
| 物品数量 | 数量明确为 3，或明确标为未知 | 使用、赠予或出售一部分 | 明确数量正确扣减；未知数量保持未知直到被确认 | 缺省当作 1、负数、使用后数量不变 |
| 显式时间 | 当前世界时间已知 | 闭关三年或赶路一月 | 未中断则完整推进；中断则返回实际时长和原因 | 无解释只增加一天或正文时间与落库时间不同 |
| 重大决定 | 玩家尚未承诺阵营或不可逆行为 | 只调查、询问或听取条件 | 正文停在需要决定的位置并返回选项 | 自动效忠、杀人、献祭、结为道侣或进入不可返回之地 |
| 世界修订 | 当前世界已有一条生效设定 | 明确扩展、转变或修订该设定 | 新版本后续生效，旧版本和理由可审计 | 静默改写旧正文、把冲突设定同时当真 |

回放夹具只保存完成测试所需的匿名化结构化事实和最短必要文本。每次修改 Prompt、解析器或 Reducer，都必须重新执行这六组回放；只有正文、最终接受补丁、数据库投影、修正审计和 H5 展示全部一致才算通过。
