# 《问仙录》中文数据字典

> 状态：当前命卷统一读写 `story_*`；旧产品表仅用于内部审计，不再向 H5 提供旧版档案入口
> 最近更新：2026-08-12

本文档记录表职责、关键字段、权威数据和迁移关系。每次修改表结构或关键字段时必须同步更新本文档。所有正式 MySQL 迁移脚本都必须使用中文 `COMMENT` 说明表、关键字段和重要索引用途。

## 1. 数据分层

| 分层 | 权威内容 | 主要表 |
|---|---|---|
| 账号层 | 账号、密码、会话、注册与登录 | `app_user`、`user_session`、`auth_*` |
| 运营层 | 公告、白名单、平台回合账户、配置 | `release_note`、`game_access_whitelist`、`gameplay_config`、`turn_credit_*` |
| 模型层 | 供应商、模型、加密密钥与用量 | `ai_provider_*`、`user_ai_provider_credential`、`user_model_preference`、`ai_usage_log` |
| 命卷层 | 当前状态、回合、实体、记忆与修正 | `story_*` |
| 统计层 | 产品事件、日活、日汇总与留存批次 | `analytics_*` |
| 历史层 | V0.13、V0.14 原始档案 | `game_*`、`v014_*`，迁移后只读 |

## 2. 通用约定

- 时间统一使用 `datetime(6)` 或 `timestamp(6)`，应用层按 Asia/Shanghai 展示；
- 主键使用服务端生成的稳定字符串 ID，不使用名称作为身份；
- `revision` 是乐观锁版本号，任何可写命卷更新都必须核对；
- `request_id` 是客户端幂等请求号，同一行动网络重试时必须复用；
- 删除玩家当前视图中的物品、人物或能力采用软隐藏，历史回合不删除；
- 账号最多保留四份未删除命卷；`active_slot_no` 是可复用的玩家槽位，`slot_no` 是只增审计序号；
- 玩家删除通过 `deleted_at` 逻辑删除，删除记录不再进入 H5、回合推进和当前命卷统计；
- 逻辑删除记录同时保留 `status=ARCHIVED` 作为旧后端兼容标记；新代码的权威删除边界仍是 `deleted_at`；
- JSON 字段必须有结构版本，不能依赖产品发布版本推断结构；
- 产品版本只用于发布和统计，不能决定物理表；
- 玩家 API 不返回平台密钥、平台模型、内部异常和数据库结构。
- 统计表不保存邮箱、密钥、完整 Prompt 或剧情正文，用户和命卷只保留稳定 ID；
- 统计分日与留存统一使用 Asia/Shanghai，`data_origin` 区分 `REALTIME/BACKFILL`。

### 2.1 V0.15 可信事实语义基线

命卷槽位与逻辑删除已经通过 `story_save` 中性字段落地；下表描述的可信事实语义仍优先复用现有 JSON、实体和审计结构，不代表每项语义都需要新增物理字段：

| 语义 | 当前候选承载位置 | 编码前必须确认的问题 |
|---|---|---|
| 当前世界约定 | `story_save.state_json` + `story_entity` | 是否需要结构版本升级，世界事实是否采用稳定实体 ID |
| 世界扩展/转变/修订 | `story_turn.state_patch_json` + `story_state_correction` | 修订类型、前后值和生效版本能否完整审计 |
| 客观事件 | `story_turn.state_patch_json` | 是否足以重建关键状态，不引入完整事件溯源 |
| 当前状态 | `story_save.state_json` + `story_entity` | Reducer 是否能成为唯一写入口 |
| 人物认知 | `story_memory` 或实体扩展属性 | 必须标明认知主体，不能与客观事实混用 |
| 物品数量 | `story_entity.attributes_json` | 只在正文明确数量后启用，未知数量不能伪造为 1 |
| 玩家决定检查点 | `story_turn.state_patch_json` | 用于回放与质量检查，不作为战力规则 |

正式编码前必须通过现有代码和真实 RDS 数据审计决定映射方式。若现有 JSON 与审计表能满足要求，只升级结构版本；只有无法保证一致性和查询性能时才增加中性字段或表，并另建迁移脚本。

## 3. 统计层

### `analytics_event`

不可变的产品行为事实表。当前事件包括 `USER_REGISTERED`、`SAVE_CREATED`、`TURN_SUCCEEDED`、
`CORRECTION_APPLIED`、`ENTITY_REMOVED`、`SAVE_DELETED`，以及仅供历史兼容的 `SAVE_ARCHIVED`。`event_key` 保证同一业务结果不会因网络重试重复统计；
`release_version`、`engine_code` 和 `data_origin` 仅作为分析维度。该表不与版本业务表建立外键。

### `analytics_user_day`

用户每日有效活动表，主键为 `(activity_date, user_id)`。创建命卷、成功回合、修正和整理会更新对应计数及
首次/最后活动时间；单纯登录不会写入。DAU、WAU、MAU、回流和留存都从本表计算。

### `analytics_daily_metric`

每日汇总指标表，主键为 `(metric_date, metric_code, dimension_type, dimension_value)`。指标值使用小数存储，
`calculation_version` 允许口径升级后重算。首页和趋势页优先读取本表，不能每次跨历史回合表实时聚合。

### `analytics_retention_cohort`

注册批次留存表，主键为 `(cohort_date, retention_day)`。保存批次注册人数、完成首回合人数和对应天数留存人数；
`retention_day` 首版支持 1、3、7、14。历史回算和实时采集通过 `data_origin` 区分。

## 3.1 可信回合诊断

### `story_turn_diagnostic`

可信回合的旁路诊断快照。每个 `(save_id, request_id)` 只保留一条持续更新的轨迹，记录本轮实际加载的
稳定实体、世界事实、人物认知、关键记忆、摘要使用情况、行动契约、首次/修复提案概要、独立审阅结果以及
Reducer 最终接受和提交的事实概要。

该表不是命卷事实来源，写入使用独立事务且失败不得阻断玩家回合。它禁止保存完整 Prompt、剧情正文、邮箱、
密钥和供应商原始响应；管理台诊断页只能按账号权限查询，不能从该表修改命卷。成功回合通过
`save_id + request_id` 与 `story_turn` 关联，失败回合则通过同一请求号与 `operation_error`、`ai_usage_log`
交叉定位。默认不写入，仅在灰度或定向排障实例开启 `wenxian.story.turn-diagnostics-enabled` 后记录。

| 关键字段 | 中文含义 |
|---|---|
| `status/current_stage` | 当前或最终处于装配、生成、审阅、修复、归并、提交或失败阶段 |
| `context_manifest_json` | 稳定引用清单和选择策略，不复制具体长文本 |
| `action_contract_json` | 模型解析出的实体引用、前置条件和玩家授权边界概要 |
| `proposal_trace_json` | 首次与最多一次修复提案的结构化变化概要 |
| `review_trace_json` | 独立审阅结果、拒绝原因和是否要求重写正文 |
| `commit_summary_json` | Reducer 接受及数据库成功提交的变化概要 |
| `failure_code/failure_detail` | 脱敏、截断且不含异常栈的失败定位信息 |

## 4. 继续保留的共享表

### `app_user`

账号权威主表。继续保存邮箱、账号状态、密码状态、注册时间和最后登录时间。故事引擎不得复制账号表。

### `user_session`

登录会话表。账号注销时会话应全部失效。

### `release_note` / `user_release_receipt`

版本公告和玩家阅读记录。公告中的产品版本只用于运营展示。

### `game_access_whitelist`

灰度访问名单。灰度开关影响登录和可用性，不修改命卷状态。

### `gameplay_config`

共享动态配置。长期保留的中性配置建议包括：

| 配置键 | 中文含义 |
|---|---|
| `current_release_version` | 当前产品发布版本 |
| `default_story_engine` | 新命卷默认故事引擎 |
| `default_story_engine_version` | 新命卷默认引擎版本 |
| `max_active_saves` | 每账号当前命卷槽位上限，范围 1—4，默认 4 |
| `account_public_turn_limit` | 平台赠送回合上限 |
| `account_public_turn_limit_message` | 赠送回合耗尽提示 |
| `registration_contact_text` | 玩家反馈与社群入口 |
| `story_migration_prepare_enabled` | 是否允许迁移器写入隔离候选，默认关闭 |
| `story_migration_activation_enabled` | 是否允许逐份激活候选，默认关闭 |
| `story_migration_batch_limit` | 单次预演或准备的最大命卷数，范围 1—50 |

现有 `current_game_version` 在迁移期继续兼容，完成改造后改为 `current_release_version`。现有 `v014_platform_provider/model` 改为模型层中性配置。

`account_public_turn_limit` 在回合账户切换前继续作为旧账号统一上限；切换后只作为“新账号初始赠送数量”的兼容配置来源，不能再动态改变所有旧账号余额。`user_daily_quota` 只保留旧扣减审计和期初迁移依据，不再承担目标余额。

### `turn_credit_grant`

账号平台回合的权威赠送批次。初始赠送、内测、有效反馈、运营活动和未来购买均写成独立批次，四份命卷共享。

| 关键字段 | 中文含义与约束 |
|---|---|
| `credit_class` | `SPONSORED` 为平台赠送，`PURCHASED` 为未来购买预留；首版不实现支付 |
| `grant_type` | 中性发放来源，如 `INITIAL/PLAYTEST/FEEDBACK/ACTIVITY/ADMIN/LEGACY_OPENING`；后续可扩展签到和邀请，不改账户核心 |
| `idempotency_key` | 外部业务唯一键，同一奖励重试只能生成一个批次 |
| `granted_count` | 批次最初总数，必须等于其余五类数量之和 |
| `available_count` | 当前可预占回合数 |
| `reserved_count` | 模型生成中、尚未提交正式回合的数量 |
| `consumed_count` | 已随正式剧情回合成功消费数量 |
| `expired_count/revoked_count` | 到期或被运营撤销的未用数量 |
| `status` | `ACTIVE/EXHAUSTED/EXPIRED/REVOKED` |
| `expires_at` | 可空；非空批次优先按最近到期顺序消费 |

守恒约束 `granted = available + reserved + consumed + expired + revoked` 是数据库和服务共同保护的业务不变量。

### `turn_credit_reservation`

模型调用前的跨进程临时预占。唯一键 `(user_id, save_id, request_id)` 保证同一 H5 行动网络重试不会重复占用。成功回合转为 `CONSUMED`；模型、审阅、业务或事务失败转为 `RELEASED`；超时或批次到期转为 `EXPIRED`。`save_id` 不绑定具体版本命卷外键，以便统一命卷切换期间兼容，但所有权必须由调用方在预占前校验。

### `turn_credit_ledger`

平台回合不可变审计流水。`event_key` 保证每次 `GRANT/RESERVE/RELEASE/CONSUME/EXPIRE/REVOKE` 只记录一次；三类 delta 记录可用、预占和已消费的变化，`balance_after` 保存事件完成后的账号可用余额。流水只保存稳定业务编号和非隐私原因，不保存邮箱、密钥、Prompt或剧情正文。

2026-08-12 已在共享 RDS 增量创建三张表，并使用带 `V015-CREDIT-SMOKE` 标记的测试批次验证真实 MySQL 事务；测试剩余可用余额已全部撤销。代码已支持通过 `wenxian.turn-credit.enabled` 仅在 V0.15 进程开启新账户：首次访问会从旧上限与历史成功扣减幂等生成 `LEGACY_OPENING`，后续成功回合只写新账本，失败回合释放预占。V0.14 进程保持开关关闭，继续使用旧额度路径，二者不会双写同一成功回合。

### `ai_provider_config`

模型供应商目录，保存供应商编码、展示名、服务端 Base URL、适配类型、平台密钥密文和启用状态。Base URL 只由管理台维护。

### `ai_provider_model`

供应商下的模型目录，区分平台是否可用和玩家自备是否可选。

### `user_ai_provider_credential`

玩家按供应商保存的加密密钥。只保存密文、末四位、状态、验证时间和安全错误码；账号注销时级联删除。

### `user_model_preference`（目标表）

替代 `v014_user_model_preference`，保存账号当前选择的平台/自备模式、供应商和模型。它属于账号生成偏好，不属于产品版本。

## 5. `story_save`：统一命卷主表

**职责**：保存每份命卷的核心权威快照、引擎、结构版本、来源、状态和乐观锁。

| 字段 | 中文含义 | 约束 |
|---|---|---|
| `id` | 统一命卷 ID | 主键 |
| `user_id` | 所属玩家账号 ID | 外键到 `app_user` |
| `slot_no` | 账号内只增历史序号 | 用于审计，不因删除复用 |
| `active_slot_no` | H5 当前命卷槽位 | 1—4；删除后置空并允许新命卷复用 |
| `status` | 命卷生命周期状态 | 玩家态为 `ACTIVE/ENDED`；迁移态为 `MIGRATING/MIGRATION_FAILED` |
| `world_code` | 当前世界编码 | 不使用产品版本代替 |
| `engine_code` | 故事引擎编码 | 例如 `LIGHT_NARRATIVE` |
| `engine_version` | 故事引擎规则版本 | 整数递增 |
| `state_schema_version` | `state_json` 结构版本 | 逐级迁移 |
| `source_type` | 命卷来源类型 | `NATIVE/MIGRATED_V014/FORKED_V013` |
| `source_edition` | 最初来源产品版本 | 仅用于追溯 |
| `source_save_id` | 原始旧命卷 ID | 同一来源唯一 |
| `parent_save_id` | 承接或复制来源的统一命卷 ID | 可空 |
| `state_json` | 主角、世界时间、地点和场景等核心快照 | 不保存无限实体数组和完整历史 |
| `revision` | 乐观锁版本 | 每次成功写入递增 |
| `turn_number` | 当前正式回合数 | 与最新回合一致 |
| `created_release` | 创建时产品版本 | 运营追溯 |
| `last_played_release` | 最后游玩时产品版本 | 运营追溯 |
| `created_at` | 创建时间 | 微秒精度 |
| `updated_at` | 最后变化时间 | 微秒精度 |
| `deleted_at` | 玩家逻辑删除时间 | 非空命卷不再向玩家接口返回 |
| `deleted_by/delete_reason` | 删除来源与原因编码 | 供审计使用，不保存玩家隐私文本 |

**权威性**：当前主角、世界时间、当前位置、当前处境和使用的引擎以本表为准。

`state_json._trustedProjection` 保存带结构版本的当前世界事实与人物认知投影；
`state_json._trustedScene.pendingDecision` 只在正文停于重大决定且等待玩家亲自回答时存在。
下一回合不再等待决定时必须清除该字段，旧命卷缺少此扩展时按“当前无待决定事项”读取。

重要查询索引：

- `uk_story_save_user_active_slot(user_id,active_slot_no)`：保证同账号当前槽位不重复，已删除记录允许为 `NULL`；
- `idx_story_save_user_status(user_id,deleted_at,status,updated_at)`：优先读取账号未删除命卷；
- `idx_story_save_status_turn(status,turn_number,updated_at)`：管理台只在指定生命周期内查询回合榜，避免加载全部命卷后在 Java 排序。

## 6. `story_turn`：回合事实表

**职责**：追加保存每次正式行动、正文、结构化补丁、模型路由、Token和延迟。成功写入后不可修改剧情事实。

| 字段 | 中文含义 |
|---|---|
| `id` | 回合记录 ID |
| `save_id` | 所属命卷 ID |
| `request_id` | H5 生成的幂等请求号 |
| `turn_number` | 命卷内回合序号 |
| `player_action` | 玩家确认提交的行动 |
| `narrative_text` | 本轮正文 |
| `choices_json` | 本轮结束后的推荐行动 |
| `state_patch_json` | Agent 返回并经服务端归一化的补丁 |
| `world_time_before/after` | 本轮前后世界时间 |
| `engine_code/version` | 实际处理本轮的引擎及版本 |
| `prompt_version` | 本轮 Prompt/上下文版本 |
| `provider/model` | 内部模型路由，仅管理台可见 |
| `credential_source` | `PUBLIC/USER` |
| `prompt_tokens` | 输入 Token |
| `completion_tokens` | 输出 Token |
| `cache_hit_tokens` | 命中上下文缓存 Token，供应商不返回时为 0 |
| `cache_miss_tokens` | 未命中缓存 Token，供应商不返回时为 0 |
| `latency_ms` | 模型与解析总延迟 |
| `release_version` | 产生本轮时的产品版本 |
| `created_at` | 成功提交时间 |

重要唯一约束：

- `(save_id, turn_number)` 防止同一回合号重复；
- `(save_id, request_id)` 防止网络重试重复推进。

## 7. `story_entity`：当前实体表

**职责**：保存命卷中需要跨回合保持一致的物品、能力、重要人物、势力和地点。

| 字段 | 中文含义 |
|---|---|
| `id` | 稳定实体 ID |
| `save_id` | 所属命卷 ID |
| `entity_type` | `ITEM/ABILITY/CHARACTER/FACTION/LOCATION` |
| `name` | 当前名称 |
| `status` | 当前自然状态编码或简短状态 |
| `visible` | 是否进入玩家当前列表和常规 Prompt |
| `summary` | 当前必要事实摘要 |
| `attributes_json` | 不同实体的可扩展属性 |
| `first_turn` | 首次成为正式实体的回合 |
| `last_changed_turn` | 最后变化回合 |
| `created_at/updated_at` | 创建和更新时间 |

**权威性**：当前是否持有物品、明确数量、是否掌握能力、人物生命周期、是否持续关注人物以本表为准。正文中提到但没有正式补丁的对象不能自动进入当前实体。数量未知时保持未知；一旦正式补丁确认具体数量，后续变化必须基于该数量提交。

`attributes_json._trusted` 为中性事实保留区，首版只保存 `quantity`（包含 `known/value`）、
`inPossession`、`abilityState` 和 `lifecycle`。普通实体属性与保留区在进入 Agent 上下文前必须分离；
保留区格式损坏时停止本轮，不得从 `status/summary` 自然语言猜测恢复。

**删除策略**：玩家整理记录时设置 `visible=false` 并更新状态；不物理删除，不修改历史回合。

## 8. `story_memory`：关键记忆表

**职责**：保存跨回合仍需记住的承诺、关系、事件、线索和人物认知，不代替完整回合历史。人物认知在目标结构中必须保存认知主体和证据来源，未完成该结构升级前不得把主观记忆写成客观世界事实。

| 字段 | 中文含义 |
|---|---|
| `id` | 记忆 ID |
| `save_id` | 所属命卷 ID |
| `memory_type` | 事件、承诺、关系、线索等类型 |
| `importance` | `KEY/IMPORTANT/NORMAL` |
| `title` | 记忆标题 |
| `summary` | 必要事实摘要 |
| `occurred_turn` | 发生回合 |
| `world_time` | 发生时世界时间 |
| `status` | 当前有效、已解决或仅历史 |
| `created_at` | 建立时间 |

## 9. `story_conversation`：上下文摘要表

**职责**：保存可重建的滚动摘要和已总结回合，只用于降低模型上下文成本。

| 字段 | 中文含义 |
|---|---|
| `save_id` | 命卷 ID，主键 |
| `rolling_summary` | 当前滚动摘要 |
| `summarized_through_turn` | 已总结到的回合 |
| `summary_schema_version` | 摘要结构版本 |
| `updated_at` | 更新时间 |

摘要不是权威事实；与快照、实体或回合冲突时，以后者为准。

`story_save.state_json._trustedProjection` 为当前中性投影保留区，包含独立 `schemaVersion`、
带稳定 ID 和 revision 的 `worldFacts`，以及带认知主体、对象和证据引用的 `knowledge`。
该子结构升级使用状态映射器逐级处理，不通过产品版本号建立新表。

## 10. `story_state_correction`：修正审计表

**职责**：记录玩家无回合消耗修正命卷时的指令、类型和前后值。目标修正类型至少需要区分事实修正、人物认知修正和世界设定修订；正式增加枚举前必须提供旧记录兼容方案。

| 字段 | 中文含义 |
|---|---|
| `id` | 修正记录 ID |
| `save_id/user_id` | 命卷与操作账号 |
| `instruction_text` | 玩家确认的修正说明 |
| `correction_type` | 核心状态、物品、能力、人物等分类 |
| `before_json/after_json` | 修正前后结构化值 |
| `created_at` | 修正时间 |

## 11. `story_save_migration`：迁移审计表

**职责**：保证每份旧命卷只迁移一次，并记录可重试、可核对的迁移结果。

| 字段 | 中文含义 |
|---|---|
| `id` | 迁移任务 ID |
| `source_engine/source_save_id` | 原始来源与命卷 ID |
| `target_engine/target_save_id` | 目标引擎与统一命卷 ID |
| `source_schema_version/target_schema_version` | 前后结构版本 |
| `status` | `RUNNING/READY/SUCCESS/FAILED/HASH_MISMATCH`；`READY` 表示候选已落库校验、但尚未激活 |
| `before_hash/after_hash` | 迁移前后关键数据哈希 |
| `error_message` | 失败原因，必须脱敏 |
| `started_at/completed_at` | 开始与完成时间 |

迁移分为两步：准备阶段将目标命卷保持为 `MIGRATING`，只有源数据哈希和目标回读哈希
都通过复核后才能显式激活。`HASH_MISMATCH` 候选不进入玩家可玩列表。

批量调度不新增任务队列表：`story_save_migration` 本身记录每份命卷进度，`gameplay_config` 保存准备、激活和
单次批量上限。批量准备逐份提交事务，单份失败不会回滚其他已校验候选；激活永远是单份操作并再次复验双哈希。

## 12. `story_engine_catalog`：引擎目录

**职责**：让管理台在不发版的情况下控制某个引擎是否允许创建、继续和迁移。

| 字段 | 中文含义 |
|---|---|
| `engine_code/version` | 引擎编码和版本，联合主键 |
| `display_name` | 管理台中文名称 |
| `status` | `DRAFT/GRAY/ACTIVE/READ_ONLY/RETIRED` |
| `min_schema_version` | 可读取的最低状态版本 |
| `current_schema_version` | 当前写入状态版本 |
| `new_save_enabled` | 是否允许创建新命卷 |
| `continuation_enabled` | 是否允许继续旧命卷 |
| `created_at/updated_at` | 创建和更新时间 |

## 13. 旧表迁移映射

### V0.14

| 旧数据 | 目标数据 | 策略 |
|---|---|---|
| `v014_game_save` | `story_save` | 一对一完整复制 |
| `v014_game_turn` | `story_turn` | 保留 requestId、回合、正文、Token和时间 |
| `state_json.inventory` | `story_entity(ITEM)` | 仅当前持有标记为可见 |
| `state_json.abilities` | `story_entity(ABILITY)` | 丢失能力保留但隐藏 |
| `state_json.characters` | `story_entity(CHARACTER)` | 未持续关注者隐藏 |
| `state_json.memories` | `story_memory` | 保留重要程度和世界时间 |
| `v014_conversation` | `story_conversation` | 复制滚动摘要 |
| `v014_state_correction` | `story_state_correction` | 保留前后审计 |
| `v014_user_model_preference` | `user_model_preference` | 去掉版本前缀 |

### V0.13

V0.13 属于早期测试数据，原表仅为内部排查保留。H5 不展示旧版入口，也不把这些记录计入当前四个命卷槽位。

## 14. 权威冲突顺序

系统硬规则不参与普通事实竞争，始终优先。运行时读取同一事实出现冲突时按以下顺序处理：

1. 玩家已经确认并审计的事实或世界修正；
2. 当前核心快照或当前实体；
3. 已提交的正式回合补丁；
4. 标明主体的人物认知；
5. 滚动摘要；
6. 叙事正文中的自然语言描述。

人物认知、摘要和正文不能静默覆盖客观事实。若当前投影与正式回合历史冲突，应进入一致性告警和重建流程，不允许在读取时临时猜测。

## 15. 中文注释要求

新迁移脚本必须满足：

```sql
create table story_save (
    id varchar(80) primary key comment '统一命卷ID',
    engine_code varchar(40) not null comment '故事引擎编码，与产品发布版本无关',
    revision bigint not null comment '乐观锁版本号，每次成功写入递增'
) comment='统一命卷主表';
```

重要索引前增加中文说明，解释它保护的业务约束。代码中的 Service、Engine、Repository、Migrator 和复杂状态变化必须使用中文 Javadoc 说明职责、边界和失败策略；不为简单 getter 或显而易见赋值编写复述代码的无效注释。
