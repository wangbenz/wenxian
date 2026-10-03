-- 三档推演服务运行时选择迁移。
-- 前置条件：已执行 service-plan-v1.sql；执行后仍需部署支持该表的 game-api 并显式开启推演点账本。

create table if not exists user_service_tier_preference (
    user_id varchar(64) primary key comment '玩家账号ID',
    tier_code varchar(16) not null comment '跨方案版本复用的稳定档位编码：LIGHT、BALANCED或DEEP',
    updated_at timestamp(6) not null comment '最近选择时间',
    constraint fk_user_service_tier_preference_user foreign key (user_id)
        references app_user(id) on delete cascade,
    constraint ck_user_service_tier_preference_code check (tier_code in ('LIGHT','BALANCED','DEEP'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
  comment='玩家平台推演档位偏好，不绑定具体方案版本';
