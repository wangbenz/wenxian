-- 账号生命周期匿名事件与调度运行观测。
-- 前置条件：account-lifecycle-v1.sql 已完成，game-api 仍保持三个生产行为开关关闭。
-- 本脚本只新增两张不含账号ID、邮箱、摘要、剧情、密钥或异常原文的观测表。
-- 不回填历史事件、不创建删除候选、不发送通知、不执行账号删除，可重复执行。

create table if not exists account_lifecycle_event (
    id varchar(80) primary key comment '匿名生命周期事件ID',
    event_key varchar(200) not null comment '状态变化幂等键，同一任务同一阶段只记录一次',
    event_type varchar(48) not null comment '通知或删除流程的稳定事件类型',
    reason varchar(32) comment '删除原因：USER_REQUEST、INACTIVITY或LEGACY_CLEANUP',
    reference_id varchar(80) not null comment '随机通知或删除任务ID，不保存账号ID',
    error_code varchar(80) comment '失败时的稳定错误码，不保存原始异常文本',
    metadata_json longtext not null comment '尝试次数或分域计数等非个人元数据',
    occurred_at datetime(6) not null comment '事件发生时间',
    unique key uk_account_lifecycle_event_key (event_key),
    index idx_account_lifecycle_event_type_time (event_type, occurred_at),
    index idx_account_lifecycle_event_reason_time (reason, occurred_at),
    index idx_account_lifecycle_event_reference (reference_id, occurred_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='不含可逆玩家标识的账号生命周期长期事件';

create table if not exists account_retention_run (
    id varchar(80) primary key comment '留存调度运行ID',
    trigger_source varchar(24) not null comment '触发来源：SCHEDULED或MANUAL',
    status varchar(24) not null comment '运行状态：RUNNING、SUCCEEDED、FAILED或SKIPPED',
    policy_evaluation_enabled boolean not null comment '本次运行观察到的策略评估开关',
    notification_delivery_enabled boolean not null comment '本次运行观察到的通知投递开关',
    automatic_deletion_enabled boolean not null comment '本次运行观察到的自动删除开关',
    mail_gateway_ready boolean not null comment '本次运行是否发现可用邮件网关',
    notice_after_days integer not null comment '本次运行使用的预通知活动天数',
    delete_after_days integer not null comment '本次运行使用的删除活动天数',
    minimum_notice_days integer not null comment '本次运行使用的最短通知天数',
    batch_size integer not null comment '本次运行的单阶段最大处理数量',
    notices_prepared integer not null default 0 comment '本次创建的通知任务数',
    notices_delivered integer not null default 0 comment '本次发送成功的通知数',
    deletion_jobs_prepared integer not null default 0 comment '本次创建的到期删除任务数',
    error_code varchar(80) comment '运行失败的稳定错误码，不保存异常原文',
    started_at datetime(6) not null comment '运行开始时间',
    finished_at datetime(6) comment '运行结束时间',
    index idx_account_retention_run_started (started_at),
    index idx_account_retention_run_status_started (status, started_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='不含账号列表的留存调度运行记录';
