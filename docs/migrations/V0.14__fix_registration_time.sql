-- 修复 app_user.registered_at 被 MySQL 隐式配置为随登录更新的问题。
-- 执行前必须备份数据库。auth_registration 是新注册账号的权威注册事件；
-- 更早的历史账号需在执行本文件前由备份恢复其最早可确认注册时间。

ALTER TABLE app_user
    MODIFY registered_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

UPDATE app_user u
JOIN (
    SELECT user_id, MIN(created_at) AS first_registered_at
    FROM auth_registration
    GROUP BY user_id
) registration ON registration.user_id = u.id
SET u.registered_at = registration.first_registered_at;
