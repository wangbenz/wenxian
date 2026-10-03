-- 命卷修正任务接入统一模型路由。
-- 可重复执行；只新增缺失的任务配置，不修改命卷、回合、实体或玩家偏好。

insert ignore into ai_model_task_route(
    task_code, primary_provider_code, primary_model_code, temperature, timeout_seconds,
    maximum_retries, maximum_output_tokens, disable_thinking, fallback_enabled, updated_at
)
select 'STORY_CORRECTION', 'DEEPSEEK', 'deepseek-v4-flash', 0.100, 90,
       0, 2200, true, false, current_timestamp(6)
where exists (
    select 1 from ai_provider_model
    where provider_code='DEEPSEEK' and model_code='deepseek-v4-flash'
);
