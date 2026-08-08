# 《问仙录》中文数据字典

> 状态：统一命卷平台结构已在本地脚本中定义；当前生产仍使用 V0.13/V0.14 原表
> 最近更新：2026-08-08

本文档记录表职责、关键字段、权威数据和迁移关系。每次修改表结构或关键字段时必须同步更新本文档。所有正式 MySQL 迁移脚本都必须使用中文 `COMMENT` 说明表、关键字段和重要索引用途。

## 1. 数据分层

| 分层 | 权威内容 | 主要表 |
|---|---|---|
| 账号层 | 账号、密码、会话、注册与登录 | `app_user`、`user_session`、`auth_*` |
| 运营层 | 公告、白名单、额度、配置 | `release_note`、`game_access_whitelist`、`gameplay_config`、`user_daily_quota` |
| 模型层 | 供应商、模型、加密密钥与用量 | `ai_provider_*`、`user_ai_provider_credential`、`user_model_preference`、`ai_usage_log` |
| 命卷层 | 当前状态、回合、实体、记忆与修正 | `story_*` |
| 历史层 | V0.13、V0.14 原始档案 | `game_*`、`v014_*`，迁移后只读 |

## 2. 通用约定

- 时间统一使用 `datetime(6)` 或 `timestamp(6)`，应用层按 Asia/Shanghai 展示；
- 主键使用服务端生成的稳定字符串 ID，不使用名称作为身份；
- `revision` 是乐观锁版本号，任何可写命卷更新都必须核对；
- `request_id` 是客户端幂等请求号，同一行动网络重试时必须复用；
- 删除玩家当前视图中的物品、人物或能力采用软隐藏，历史回合不删除；
- JSON 字段必须有结构版本，不能依赖产品发布版本推断结构；
- 产品版本只用于发布和统计，不能决定物理表；
- 玩家 API 不返回平台密钥、平台模型、内部异常和数据库结构。

## 3. 继续保留的共享表

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
| `max_active_saves` | 每账号最大存活命卷数 |
| `account_public_turn_limit` | 平台赠送回合上限 |
| `account_public_turn_limit_message` | 赠送回合耗尽提示 |
| `registration_contact_text` | 玩家反馈与社群入口 |
| `legacy_archive_enabled` | 是否展示旧世档案 |
| `legacy_fork_enabled` | 是否允许承接旧世 |

现有 `current_game_version` 在迁移期继续兼容，完成改造后改为 `current_release_version`。现有 `v014_platform_provider/model` 改为模型层中性配置。

### `ai_provider_config`

模型供应商目录，保存供应商编码、展示名、服务端 Base URL、适配类型、平台密钥密文和启用状态。Base URL 只由管理台维护。

### `ai_provider_model`

供应商下的模型目录，区分平台是否可用和玩家自备是否可选。

### `user_ai_provider_credential`

玩家按供应商保存的加密密钥。只保存密文、末四位、状态、验证时间和安全错误码；账号注销时级联删除。

### `user_model_preference`（目标表）

替代 `v014_user_model_preference`，保存账号当前选择的平台/自备模式、供应商和模型。它属于账号生成偏好，不属于产品版本。

## 4. `story_save`：统一命卷主表

**职责**：保存每份命卷的核心权威快照、引擎、结构版本、来源、状态和乐观锁。

| 字段 | 中文含义 | 约束 |
|---|---|---|
| `id` | 统一命卷 ID | 主键 |
| `user_id` | 所属玩家账号 ID | 外键到 `app_user` |
| `slot_no` | 当前账号下的展示槽位 | 只由命卷生命周期服务分配 |
| `status` | 命卷生命周期状态 | `ACTIVE/ARCHIVED/DEAD/MIGRATING/MIGRATION_FAILED` |
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

**权威性**：当前主角、世界时间、当前位置、当前处境和使用的引擎以本表为准。

## 5. `story_turn`：回合事实表

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

## 6. `story_entity`：当前实体表

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

**权威性**：当前是否持有物品、是否掌握能力、是否持续关注人物以本表为准。正文中提到但没有正式补丁的对象不能自动进入当前实体。

**删除策略**：玩家整理记录时设置 `visible=false` 并更新状态；不物理删除，不修改历史回合。

## 7. `story_memory`：关键记忆表

**职责**：保存跨回合仍需记住的承诺、关系、事件和线索，不代替完整回合历史。

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

## 8. `story_conversation`：上下文摘要表

**职责**：保存可重建的滚动摘要和已总结回合，只用于降低模型上下文成本。

| 字段 | 中文含义 |
|---|---|
| `save_id` | 命卷 ID，主键 |
| `rolling_summary` | 当前滚动摘要 |
| `summarized_through_turn` | 已总结到的回合 |
| `summary_schema_version` | 摘要结构版本 |
| `updated_at` | 更新时间 |

摘要不是权威事实；与快照、实体或回合冲突时，以后者为准。

## 9. `story_state_correction`：修正审计表

**职责**：记录玩家无回合消耗修正命卷时的指令、类型和前后值。

| 字段 | 中文含义 |
|---|---|
| `id` | 修正记录 ID |
| `save_id/user_id` | 命卷与操作账号 |
| `instruction_text` | 玩家确认的修正说明 |
| `correction_type` | 核心状态、物品、能力、人物等分类 |
| `before_json/after_json` | 修正前后结构化值 |
| `created_at` | 修正时间 |

## 10. `story_save_migration`：迁移审计表

**职责**：保证每份旧命卷只迁移一次，并记录可重试、可核对的迁移结果。

| 字段 | 中文含义 |
|---|---|
| `id` | 迁移任务 ID |
| `source_engine/source_save_id` | 原始来源与命卷 ID |
| `target_engine/target_save_id` | 目标引擎与统一命卷 ID |
| `source_schema_version/target_schema_version` | 前后结构版本 |
| `status` | `PENDING/RUNNING/SUCCESS/FAILED/HASH_MISMATCH` |
| `before_hash/after_hash` | 迁移前后关键数据哈希 |
| `error_message` | 失败原因，必须脱敏 |
| `started_at/completed_at` | 开始与完成时间 |

## 11. `story_engine_catalog`：引擎目录

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

## 12. 旧表迁移映射

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

V0.13 不完整迁入当前可写结构。统一列表通过旧版适配器读取；承接时复制可靠身份、境界、地点、重要能力、当前持有物品、重要人物和关键经历摘要。体力、追查、旧裁决、异常货币和临时人物不进入目标命卷。

## 13. 权威冲突顺序

同一事实出现冲突时按以下顺序处理：

1. 玩家已经确认并审计的命卷修正；
2. 当前核心快照或当前实体；
3. 已提交的正式回合补丁；
4. 滚动摘要；
5. 叙事正文中的自然语言描述。

摘要和正文不能静默覆盖权威快照。

## 14. 中文注释要求

新迁移脚本必须满足：

```sql
create table story_save (
    id varchar(80) primary key comment '统一命卷ID',
    engine_code varchar(40) not null comment '故事引擎编码，与产品发布版本无关',
    revision bigint not null comment '乐观锁版本号，每次成功写入递增'
) comment='统一命卷主表';
```

重要索引前增加中文说明，解释它保护的业务约束。代码中的 Service、Engine、Repository、Migrator 和复杂状态变化必须使用中文 Javadoc 说明职责、边界和失败策略；不为简单 getter 或显而易见赋值编写复述代码的无效注释。
