# 账号生命周期受控维护脚本

本目录包含会修改历史数据的 M4 演练脚本，不是自动发布迁移。仓库内保存脚本不代表授权在共享 RDS 执行。

正式执行顺序：

1. 用只读账号执行 `../data-lifecycle/00-production-snapshot.sql`、`03-retention-bootstrap-preflight.sql` 和
   `06-log-attribution-preflight.sql`，记录执行时间、脚本哈希和聚合数量；
2. 在代表性副本执行 `database/migrations/account-lifecycle-v1.sql`；
3. 根据同一副本的最新聚合填写每份脚本顶部的 `@expected_*`，再把 `@execute_confirmed` 改为脚本要求的精确值；
4. 依次演练 `01-ai-usage-attribution.sql`、`02-historical-email-redaction.sql`、
   `03-retention-state-bootstrap.sql`；
5. 重跑只读 preflight，验证 `UNRESOLVED=0`、邮箱样式文本为 0、活动状态只覆盖 ACTIVE 账号且没有通知或删除任务；
6. 验证备份恢复后，另行申请生产迁移、历史治理和活动回填授权。三个动作应分别记录影响行数，不与通知启用或账号删除同批。

安全约束：

- 脚本默认通过 `SIGNAL` 停止；缺少确认字符串或任一预计数量变化时不会执行更新；
- 不导出邮箱、账号 ID、命卷 ID、错误正文或剧情；
- 模型日志匿名化会清空孤立 `save_id` 并用固定错误类别替换旧错误细节，只保留任务、模型、Token、耗时和时间等统计维度；
- 活动回填只 `insert` 缺失状态，不覆盖应用已经写入的 REALTIME 活动；
- 本目录没有删除账号、命卷或旧表的 SQL。
