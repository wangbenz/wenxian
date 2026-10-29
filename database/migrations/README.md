# 数据库迁移

这里保存共享 MySQL 数据库的增量迁移脚本。`apps/game-api` 和 `apps/admin-api` 中的 `schema.sql` 只负责全新环境建表，
不能替代已有 RDS 的迁移。正式 profile 已关闭 Spring SQL 初始化，应用启动不执行 `schema.sql`。

数据清理、故事重置和旧表退役的只读影响脚本位于 `database/maintenance/data-lifecycle`；账号日志归属、历史文本脱敏和活动回填资料位于
`database/maintenance/account-lifecycle`。它们不是迁移，不会按本清单自动执行；受控写入和物理删除必须经过影响报告、隔离副本演练和单独授权。

## 1. 基本规则

1. 执行前确认目标环境、当前表结构、数据量、写入状态和最近可恢复点。
2. 先查询 `information_schema`，不得根据文件名、代码实体或本地 schema 猜测线上字段。
3. 脚本必须说明目的、前置条件、幂等性和数据影响，不得静默删除玩家数据。
4. 不可重复执行的脚本只能在受控停写窗口执行，并先在代表性副本完整演练。
5. 执行后记录脚本哈希、环境、操作者、时间、影响行数和验证结果，不记录账号密码、密钥或玩家正文。
6. 产品版本、状态结构版本、Prompt 版本、模型版本和迁移顺序互不推断。
7. 运行时代码不能根据 `game_*`、`v014_*` 等历史表名前缀选择业务链。

当前仓库没有可证明全部历史脚本已执行的统一迁移账本。2026-08-29 的生产结构观察已移入
[`docs/evidence/production-audits/2026-08-29/migration-structure-observation.md`](../../docs/evidence/production-audits/2026-08-29/migration-structure-observation.md)；
它不能代替下一次执行前的 preflight。

## 2. 两种初始化路径

### 2.1 全新环境

全新空库不回放全部历史增量脚本。顺序是：

1. 人工审核并执行 `apps/game-api/src/main/resources/schema.sql`；
2. 再执行 `apps/admin-api/src/main/resources/schema.sql`，补齐管理台专用结构；
3. 核对两份 schema 中重复的共享表定义一致；
4. 显式初始化必要的 `gameplay_config`、模型目录和管理员账号；
5. 使用 `prod` profile 启动两个后端，确认 `spring.sql.init.mode=never`；
6. 运行共享 Story v2 fixture、健康检查和最小真实链路。

### 2.2 已有环境

已有数据库只执行结构检查后判定为“缺失”的增量脚本。禁止把本目录按文件名排序后全部执行，因为历史文件名不是全局版本号，
部分脚本非幂等，且 `story-platform-v1.sql` 的当前仓库快照已经包含后来生命周期增量中的部分字段。

## 3. 全局依赖图

以下是逻辑依赖，不是要求当前生产全部执行。

```text
历史账号/运行基础
  ├─ V0.13__multi_save.sql                    # 历史一次性脚本，不再重跑
  ├─ V0.13_1__operation_errors.sql
  ├─ V0.14__light_story.sql                   # 提供 ai_provider_* / user_ai_provider_credential
  ├─ V0.14__fix_registration_time.sql
  └─ auth-platform-v1__password-columns.sql   # 替代 game-api 启动期 ALTER TABLE
       └─ auth-platform-v1_1__email-verification.sql

V0.14__light_story.sql
  └─ story-platform-v1.sql
       ├─ V0.14_2__expand_story_entity_status.sql
       ├─ story-platform-v1_1__admin-query-index.sql
       ├─ story-platform-v1_2__save-lifecycle.sql
       ├─ story-platform-v1_3__turn-credit.sql
       ├─ story-platform-v1_4__turn-diagnostic.sql
       ├─ story-platform-v1_5__entity-alias.sql
       └─ story-platform-v1_7__state-schema-attribution.sql

story-platform-v1.sql
  └─ model-platform-v1.sql
       ├─ model-platform-v1_1__story-correction-route.sql
       ├─ model-platform-v1_2__preference-sync.sql
       ├─ model-platform-v1_3__retire-legacy-runtime.sql
       ├─ model-director-output-default.sql   # 授权后取消全局导演旧2200上限；不改已发布档位
       ├─ model-observability-v1.sql
       └─ model-catalog-v2.sql
            └─ service-plan-v1.sql
                 └─ service-plan-v1_1__runtime-selection.sql

story-platform-v1_3__turn-credit.sql
  + service-plan-v1.sql
  └─ story-platform-v1_6__inference-point-ledger.sql

analytics-platform-v1.sql
  + story-platform-v1.sql
  + model-platform-v1.sql
  └─ analytics-platform-v1_1__byok-conversion.sql

admin schema
  └─ admin-history-v1.sql

model-observability-v1.sql
  + admin schema
  └─ account-lifecycle-v1.sql
       └─ account-lifecycle-v1_1__observability.sql
```

## 4. 迁移清单

回滚类别：`R1` 表示优先回退应用并保留新增结构，确认无新写入后才考虑删列/删表；`R2` 表示可在核对执行计划后删除新增索引；
`R3` 表示必须先保存受影响行，失败时从行级备份恢复或追加前向修复；`R4` 表示关联账本/回填，只能在停写和完整快照下恢复或前向修复；
`R5` 表示历史一次性重构，不提供现场 down SQL，只能恢复演练过的整库/表级快照。任何回滚都不能丢弃脚本执行后产生的新玩家写入。

| 脚本 | 用途 | 重复执行 | 回滚 | 关键前置条件 |
| --- | --- | --- | --- | --- |
| `V0.13__multi_save.sql` | 历史多命卷与旧统计/访问结构 | 否 | R5 | 旧 `game_*` 结构；仅历史恢复使用 |
| `V0.13_1__operation_errors.sql` | 玩家问题编号和运营问题表 | 建表可重复，索引需检查 | R1/R2 | `app_user`/旧运行基础 |
| `V0.14__light_story.sql` | V0.14 命卷、供应商、模型和玩家凭据 | 部分可重复 | R3/R5 | 旧账号和额度表 |
| `V0.14__fix_registration_time.sql` | 修复注册时间字段更新语义 | 否 | R3 | 先从备份确认历史注册时间 |
| `auth-platform-v1__password-columns.sql` | 显式补齐密码哈希、设置时间和注册网络摘要 | 是 | R1 | `app_user` 已存在 |
| `auth-platform-v1_1__email-verification.sql` | 注册邮箱验证码挑战、频率与一次性消费状态 | 是，仅建表 | R1 | 认证基础结构、邮件发送网关和外部摘要密钥 |
| `story-platform-v1.sql` | 统一 Story 基础表 | 建表可重复，种子需核对 | R1/R3 | `app_user`、`ai_provider_model` 已存在 |
| `V0.14_2__expand_story_entity_status.sql` | 扩大统一实体自然状态字段 | 否 | R3 | `story_entity` 已存在且字段尚未扩展 |
| `story-platform-v1_1__admin-query-index.sql` | 当前命卷排行榜索引 | 否 | R2 | `story_save` 已存在且索引不存在 |
| `story-platform-v1_2__save-lifecycle.sql` | 活跃槽位与逻辑删除字段/回填 | 否 | R3/R4 | 旧版 `story_save` 尚无这些列和索引 |
| `story-platform-v1_3__turn-credit.sql` | 赠送、预占和流水第一阶段 | 建表可重复，期初发放另做 | R1/R4 | `app_user` 已存在 |
| `story-platform-v1_4__turn-diagnostic.sql` | 可信回合诊断 | 是 | R1 | `story_save`、`story_turn` 已存在 |
| `story-platform-v1_5__entity-alias.sql` | 稳定实体别名 | 是 | R1 | `story_entity` 已存在 |
| `story-platform-v1_7__state-schema-attribution.sql` | 统一 v2 引擎目录并给回合补丁、修正快照标记状态结构版本 | 是；历史列保持可空 | R1 | `story_turn`、`story_state_correction`、`story_engine_catalog` 已存在 |
| `model-platform-v1.sql` | 中性模型字段、任务路由和偏好接续 | 结构可重复，数据接续需核对 | R1/R3 | `ai_provider_model`、`user_model_preference`、V0.14 偏好存在 |
| `model-platform-v1_1__story-correction-route.sql` | 修正任务路由 | 是 | R3 | 中性模型目录和任务路由表 |
| `model-platform-v1_2__preference-sync.sql` | 接续更新时间更晚的历史偏好 | 是 | R3 | 新旧偏好表同时存在 |
| `model-platform-v1_3__retire-legacy-runtime.sql` | 接续历史平台密钥、玩家密钥和偏好 | 是，执行前仍需影响报告 | R3 | 新旧模型/凭据表同时存在 |
| `model-observability-v1.sql` | 调用 request/user/attempt/fallback 字段与索引 | 是 | R1/R2 | `ai_usage_log`；大表需维护窗口 |
| `model-director-output-default.sql` | 仅将全局导演旧 2200 输出上限设为 NULL | 是，仅命中仍为 2200 的行 | R3，保留原配置及执行命中记录 | `ai_model_task_route` 已存在；确认实际使用全局路由，已发布档位须走新版本发布 |
| `model-catalog-v2.sql` | 模型配置版本、并发限制和受控验证表 | 是 | R1 | 中性模型平台完成 |
| `service-plan-v1.sql` | 三档方案版本、档位、任务路由和模型快照 | 建表可重复 | R1 | `ai_model_validation_run` 已存在 |
| `service-plan-v1_1__runtime-selection.sql` | 玩家稳定档位偏好 | 是 | R1 | `service-plan-v1.sql` |
| `story-platform-v1_6__inference-point-ledger.sql` | 多批次推演点预占和历史分摊 | 否 | R4 | turn-credit 与 service-plan 均完成；最近可恢复点有效 |
| `analytics-platform-v1.sql` | 独立统计事件、日事实和留存结构 | 是 | R1 | `app_user` 已存在 |
| `analytics-platform-v1_1__byok-conversion.sql` | BYOK 接入事实回填和查询索引 | 否 | R2/R4 | analytics、story、model 三条基线均完成 |
| `admin-history-v1.sql` | 管理操作记录历史查询索引 | 否 | R2 | `admin_operation_log` 已存在且索引缺失 |
| `account-lifecycle-v1.sql` | 账号活动、预通知、删除任务、匿名审计及日志归属字段 | 是，仅结构 | R1/R2 | `model-observability-v1.sql` 已补 `request_id`；账号/错误日志表存在 |
| `account-lifecycle-v1_1__observability.sql` | 匿名生命周期事件和不含账号列表的调度运行证据 | 是，仅建表 | R1 | `account-lifecycle-v1.sql` 的生命周期结构已完成 |
| `world-package-registry-v1.sql` | 世界包身份、不可变修订、素材原件、发布幂等、玩家安装、范围/可用性/默认指针与审计 | 否，部分 DDL 成功后核对并仅继续缺失语句 | R1 | MySQL 8.0.16+，app_user.id 类型与排序规则一致；不改 story_save、不发布或启用内容 |
| `world-package-owner-constraint-compat.sql` | 已有注册表移除冲突的 owner CHECK，保留或恢复所有者外键 ON DELETE SET NULL | 是，按元数据跳过已修正结构；中断后先核对再续跑 | R1，保留兼容结构，不恢复冲突 CHECK | world_package、app_user 已存在；核对字段、排序规则及 fk_world_package_owner，暂停世界包写入和账号删除后执行 |

世界包的注册表与旧命卷只读 preflight 位于 `database/maintenance/world-package`。第一份脚本先执行 information_schema 部分，
完成结构核对后再单独执行注册表查询；旧命卷脚本只输出聚合，不回填绑定。阶段 B 不创建命卷修订 FK。
修正后的新建脚本与 game-api schema 已移除 `ck_world_package_official_owner`，保留所有者删除置空与 `ck_world_scope`。
已有环境不能用新建 schema 覆盖升级，应按 [`world-package-owner-constraint-compat.sql`](world-package-owner-constraint-compat.sql)
的前置条件执行显式约束修正；若原建表在第一条即失败且表不存在，应使用修正后的新建脚本，不执行兼容升级。
兼容脚本按元数据条件移除冲突 CHECK；仅在外键删除动作错误时 DROP，外键缺失时 ADD SET NULL，已正确的结构直接跳过。
MySQL 不允许同一个 ALTER 对同名外键 DROP/ADD，因此修正由多条会隐式提交的 DDL 完成；执行期间暂停世界包写入和账号删除，
中断后核对已完成结构再续跑，确认外键恢复后才能恢复这些写入。脚本不修改内容、默认项或命卷，也不触碰 scope CHECK。
官方 owner 为空由受控登记服务保证，玩家发布仍检查 USER 来源和当前所有者。

独立发布、启用、设默认和 `verify` 完整性巡检入口见 `docs/architecture.md` 第 4.3 节。运行读取已发布快照并复核
原始哈希、身份、合同和能力，不读取规范原文或素材；规范内容与快照对应关系及素材原件由独立巡检复核。
开发首次 GLOBAL scope、启用、默认和审计在发布完成后的同一初始化事务提交，已有空 scope 不自动补默认；
这些是应用用例，不由迁移脚本自动执行。上线前须在代表性 MySQL 副本演练外键、排序规则和并发行为。

### 4.1 通用 preflight 与 dry-run 证据

每份候选脚本先在副本保存以下证据；真实表名、字段和索引清单从脚本解析后填写，不能直接复制示例占位符：

```sql
select table_name, table_rows, data_length, index_length
from information_schema.tables
where table_schema = database() and table_name in ('<target_table>');

select table_name, column_name, column_type, is_nullable, column_default, extra
from information_schema.columns
where table_schema = database() and table_name in ('<target_table>')
order by table_name, ordinal_position;

select table_name, index_name, non_unique, seq_in_index, column_name
from information_schema.statistics
where table_schema = database() and table_name in ('<target_table>')
order by table_name, index_name, seq_in_index;
```

涉及回填的脚本还要在事务外先执行等价 `SELECT count(*)` 和分组统计，记录预计影响行数；在副本执行后重复同一统计，并做外键孤儿、
唯一键冲突、账本守恒和关键业务读取验证。MySQL DDL 可能隐式提交，不能把 `START TRANSACTION + ROLLBACK` 当成 DDL dry-run；
dry-run 指在可丢弃的代表性副本按正式命令、正式账号权限和正式顺序完整执行，再实际演练 R1—R5 对应的恢复路径。

## 5. 当前已知危险点

1. `story-platform-v1.sql` 当前快照已经包含 `active_slot_no/deleted_at` 等字段，而
   `story-platform-v1_2__save-lifecycle.sql` 仍会直接新增这些字段。不能在同一数据库盲目连续执行。
2. `V0.13__multi_save.sql` 是历史一次性脚本，重复执行会遇到已有列/索引，并可能把发布配置重新写成旧值。
3. `story-platform-v1_6__inference-point-ledger.sql` 和 `analytics-platform-v1_1__byok-conversion.sql` 明确非幂等。
4. `schema.sql` 中使用 `create table if not exists` 不能升级已存在表，也不能证明约束、索引和注释一致。
5. game-api 和 admin-api 的重复共享表声明必须保持同形；当前新增
   `user_service_tier_preference` 已同步到两份新环境 schema 和 game-api H2 schema。

这些问题在任何生产迁移演练前必须转化为 preflight 检查或新的兼容迁移，不能依赖人工“看起来已经执行过”。


2026-08-29 的单次发布顺序已作为日期证据归档；任何新发布都应在
[`docs/core-readiness.md`](../../docs/core-readiness.md) 中重新锁定范围，而不是复用旧清单。

## 6. 每次执行记录

每个环境至少记录：

```text
环境：
数据库版本：
执行前 schema 指纹：
脚本路径与 SHA-256：
开始/结束时间：
执行人：
是否停写：
执行前备份与恢复验证：
影响表和影响行数：
执行后验证 SQL：
应用 commit / release：
异常与处理：
```

该记录只保存结构和统计，不复制玩家邮箱、剧情正文、密钥密文或其他真实隐私数据。
