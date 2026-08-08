# 《问仙录》统一命卷平台架构

> 状态：统一 API、通用数据层、V0.14 迁移预演、两阶段执行器和管理台迁移中心已在本地实现；生产迁移和写入切换尚未开始
> 最近更新：2026-08-08

## 1. 决策背景

V0.13 与 V0.14 为了快速验证不同玩法，分别形成了独立接口、Service、状态模型和数据表。V0.14 的隔离在当时保护了旧玩家数据，但不能继续演变为每个产品版本复制一套代码和表的长期模式。

当前生产组合基线：

| 部分 | 线上构建对应提交 |
|---|---|
| H5 | `9d6731c` |
| 游戏后端 | `57061d9` |
| 管理台 | `5050e3e` |

生产环境以实际部署构建为准；本地未提交 Hotfix 不属于上述基线，不得混入架构迁移提交。

当前本地落地进度：

- 已新增 `StoryController`、`StoryApplicationService`、`StoryEngineRegistry` 与稳定命卷 DTO；
- 已新增 V0.14 防腐适配器，旧表仍是权威写入来源；
- 已新增 `/api/v1/generation/*`，平台模式继续隐藏具体供应商和模型；
- H5 当前玩法请求已改走稳定 Story/Generation API，页面视图仍保留少量 `v014` 兼容命名；
- 已新增 `story_*` 通用表脚本以及命卷、回合、实体 Repository，当前尚未部署或接管玩家写入；
- 已新增 V0.14 只读预演、隔离候选、双哈希激活和管理台迁移中心，但未对生产 RDS 执行写迁移；
- V0.13 只读适配、管理台统一命卷运营查询和旧 API 停用尚未开始；
- 上述本地改造尚未部署，不改变当前生产流量。

本架构采用“统一命卷平台 + 可插拔故事引擎 + 历史档案适配器”。产品版本只用于发布和统计，不再决定业务类、接口或物理表名。

## 2. 架构原则

1. 一个账号系统、一个命卷入口、一套稳定 API；
2. 产品版本、故事引擎、引擎版本、存档结构版本分离；
3. 当前快照、实体现状和不可变回合历史分离；
4. Agent 提出叙事结果，Java 保护系统边界并原子落库；
5. 历史数据只复制或适配，不做不可逆原地覆盖；
6. 先建立统一外壳，再迁移物理表，不进行一次性大爆炸重写；
7. 迁移期间不长期双写；最终切换前使用短暂写入冻结同步增量；
8. 新增表按业务能力命名，禁止按产品发布版本命名。

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
- **注册表模式**：`StoryEngineRegistry` 按命卷保存的引擎编码和版本查找实现；
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

- `story_save.state_json`：主角、世界时间、当前位置、当前处境等核心快照；
- `story_entity`：当前物品、能力、重要人物、势力和地点事实；
- `story_memory`：跨回合需要长期保留的关键记忆；
- `story_turn`：不可变的玩家行动、剧情、补丁和模型用量；
- `story_conversation`：可重建的滚动摘要，不是事实来源；
- `story_state_correction`：玩家修正前后审计；
- `story_save_migration`：迁移来源、结果、哈希和错误；
- `story_engine_catalog`：允许创建、继续和迁移的引擎目录。

核心快照不再无限保存完整人物、物品、能力和时间线数组。API 读取命卷时由核心快照与当前实体组合成玩家视图。

## 8. 单回合流水线

```text
H5 生成 requestId 并提交 action + expectedRevision
  → 验证登录、白名单、命卷归属和额度
  → 同账号已有生成时立即返回，不在 Java 锁后排队
  → 按 requestId 检查幂等结果
  → 读取核心快照、相关实体、关键记忆和滚动摘要
  → StoryEngineRegistry 选择故事引擎
  → PromptContextAssembler 组装本轮相关上下文
  → ModelGateway 调用平台或玩家模型
  → AgentOutputParser 解析统一 StoryPatch
  → StoryStateReducer 校验引用并应用变化
  → 单事务写入快照、回合、实体、记忆、摘要和额度
  → 返回最新 revision 与能力列表
```

网络中断后，H5 使用相同 `requestId` 重试；如果服务端已经提交，直接返回已保存结果，不能生成第二回合。

## 9. Java、Agent 与 H5 边界

Java 负责：

- 账号、命卷归属、状态机、槽位和访问能力；
- requestId 幂等、并发门闩、乐观锁和事务；
- 实体稳定 ID、软删除、引用完整性和审计；
- 显式时长的结构化解析、世界时间存储；
- 模型路由、额度、密钥加密、错误协议和用量记录。

Agent 负责：

- 理解自由行动；
- 生成完整剧情单元；
- 根据已有事实判断局部得失、人物反应和合理代价；
- 提出核心状态、实体、记忆和推荐行动的结构化补丁。

H5 负责：

- 展示命卷和能力；
- 生成并复用 requestId；
- 防止输入时误触；
- 网络失败后读取服务器最新 revision；
- 只根据 `capabilities` 控制按钮，不判断产品版本。

Java 不维护精密战力、统一物价或关键词裁决。Agent 不能直接访问数据库、修改账号额度或指定任意模型地址。

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

统一输出 `StoryPatch`：场景标题、正文、实际经过时间、核心状态变化、实体补丁、关键记忆、推荐行动和滚动摘要。

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
- 当前仍未执行生产迁移，也未切换运行时写路径。

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

### V0.13 旧世承接

V0.13 原表不直接变为当前可写命卷。适配器统一展示旧档；玩家发起承接时复制姓名、性别、种族、出身、最后可靠境界和地点、重要能力、持有物品、重要人物以及关键经历摘要。旧体力、追查、旧裁决、异常货币和临时路人不迁移。原档案永远保留。

## 14. 管理台目标

“V0.14 观察”改为“命卷运营”，提供：

- 活跃命卷、今日回合、成功率、平均延迟和重试率；
- 输入、输出、缓存命中与未命中 Token；
- 按产品发布、引擎、引擎版本、Prompt、模型、密钥来源和世界筛选；
- 迁移总量、成功、失败、哈希异常和单命卷重试；
- 旧世承接数量与失败原因；
- 统一问题编号、命卷和玩家查询。

管理台平台模型配置改用中性配置名，不再使用 `v014_platform_provider`。

迁移中心已经具备第一阶段能力：动态开关、有限分页预演、多选准备、最近任务和单份哈希确认激活。
正式操作顺序必须是 RDS 备份 → 只读全量预演 → 小批准备 → 抽样核对 → 单份灰度激活；不得直接打开两个写开关后全量操作。

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
