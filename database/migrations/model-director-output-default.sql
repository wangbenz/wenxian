-- 取消全局导演任务历史默认的 2200 Token 输出限制。
-- 前置：ai_model_task_route 已按 model-platform-v1.sql 建立。
-- 仅在明确授权的目标环境执行；应用启动不会自动执行本脚本。
-- 执行前记录 TURN_DIRECTOR 路由的 maximum_output_tokens 与 updated_at，供本次变更回滚。
-- 单条 UPDATE 原子执行，可由迁移执行器纳入事务；只命中仍为 2200 的导演任务。
-- 已为 NULL、其他自定义上限、其他任务、模型目录及发布方案快照均不改变。
-- NULL 表示请求不传 max_tokens，由供应商使用默认输出额度，并非无限输出。
-- 若运行时使用 ai_service_plan_task_route 的已发布档位覆盖，必须通过新方案版本取消覆盖，
-- 不能原地更新已发布版本；本脚本不会绕过该发布边界。

UPDATE ai_model_task_route
SET maximum_output_tokens = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE task_code = 'TURN_DIRECTOR'
  AND maximum_output_tokens = 2200;

-- 复跑没有匹配行时不改 updated_at。执行器应记录受影响行数；未命中不代表所有生效档位都已取消限制。
-- 回滚只针对本次实际命中的全局路由，并先确认此后没有新的管理台配置变更：
-- 恢复执行前记录的 maximum_output_tokens / updated_at；不得把所有 NULL 路由批量改回 2200。
