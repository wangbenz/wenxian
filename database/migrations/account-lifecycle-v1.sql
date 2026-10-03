-- 账号30天生命周期、物理删除任务和日志主体归属迁移。
-- 前置条件：app_user、operation_error 已存在；ai_usage_log 已先执行 model-observability-v1.sql 并具备 request_id；
-- admin_operation_log 可选。不能在缺少 request_id 的旧 ai_usage_log 上直接执行本脚本。
-- 本脚本只新增结构，不回填活动时间、不创建删除候选、不发送通知、不删除任何数据，可重复执行。
-- 现有账号的活动重建必须使用单独的受控回填脚本，并在代表性副本演练后另行授权。

drop procedure if exists wenxian_add_lifecycle_column_if_missing;
drop procedure if exists wenxian_add_lifecycle_index_if_missing;
delimiter //
create procedure wenxian_add_lifecycle_column_if_missing(
    in target_table varchar(64),
    in target_column varchar(64),
    in column_definition varchar(1000)
)
begin
    if exists (
        select 1 from information_schema.tables
        where table_schema=database() and table_name=target_table
    ) and not exists (
        select 1 from information_schema.columns
        where table_schema=database() and table_name=target_table and column_name=target_column
    ) then
        set @lifecycle_column_ddl = concat(
            'alter table `', replace(target_table, '`', '``'), '` add column ', column_definition
        );
        prepare lifecycle_column_statement from @lifecycle_column_ddl;
        execute lifecycle_column_statement;
        deallocate prepare lifecycle_column_statement;
    end if;
end//

create procedure wenxian_add_lifecycle_index_if_missing(
    in target_table varchar(64),
    in target_index varchar(64),
    in index_definition varchar(1000)
)
begin
    if exists (
        select 1 from information_schema.tables
        where table_schema=database() and table_name=target_table
    ) and not exists (
        select 1 from information_schema.statistics
        where table_schema=database() and table_name=target_table and index_name=target_index
    ) then
        set @lifecycle_index_ddl = concat(
            'create index `', replace(target_index, '`', '``'), '` on `',
            replace(target_table, '`', '``'), '` ', index_definition
        );
        prepare lifecycle_index_statement from @lifecycle_index_ddl;
        execute lifecycle_index_statement;
        deallocate prepare lifecycle_index_statement;
    end if;
end//
delimiter ;

call wenxian_add_lifecycle_column_if_missing(
    'ai_usage_log', 'user_id',
    'user_id varchar(64) null comment ''用户内部编号，不保存邮箱'' after request_id'
);
call wenxian_add_lifecycle_column_if_missing(
    'ai_usage_log', 'attribution_status',
    'attribution_status varchar(24) not null default ''UNRESOLVED'' comment ''归属状态：ACCOUNT_LINKED、ANONYMOUS_LEGACY或UNRESOLVED'' after user_id'
);
call wenxian_add_lifecycle_index_if_missing(
    'ai_usage_log', 'idx_ai_usage_user_created', '(user_id, created_at)'
);
call wenxian_add_lifecycle_index_if_missing(
    'ai_usage_log', 'idx_ai_usage_attribution_created', '(attribution_status, created_at)'
);
call wenxian_add_lifecycle_column_if_missing(
    'operation_error', 'subject_user_id',
    'subject_user_id varchar(64) null comment ''可确认时记录关联玩家账号ID，账号删除后清理'' after save_id'
);
call wenxian_add_lifecycle_index_if_missing(
    'operation_error', 'idx_operation_error_subject', '(subject_user_id, created_at)'
);
call wenxian_add_lifecycle_column_if_missing(
    'admin_operation_log', 'subject_user_id',
    'subject_user_id varchar(64) null comment ''操作明确针对玩家时记录其账号ID，账号删除后清理'' after detail'
);
call wenxian_add_lifecycle_index_if_missing(
    'admin_operation_log', 'idx_admin_operation_log_subject', '(subject_user_id, operated_at)'
);

drop procedure wenxian_add_lifecycle_column_if_missing;
drop procedure wenxian_add_lifecycle_index_if_missing;

create table if not exists account_retention_state (
    user_id varchar(64) primary key comment '玩家账号ID',
    last_meaningful_activity_at datetime(6) not null comment '最近一次有意义活动时间，不包含会话保活和静态读取',
    activity_source varchar(40) not null comment '最近活动来源',
    policy_state varchar(24) not null default 'ACTIVE' comment '留存状态：ACTIVE、NOTICE_DUE、NOTICE_SENT、DELETE_DUE、DELETING或RETRYABLE_FAILED',
    notice_sent_at datetime(6) comment '最近一次删除预通知成功时间',
    scheduled_deletion_at datetime(6) comment '满足活动期限和最短通知期后的最早删除时间',
    canceled_at datetime(6) comment '最近一次因玩家恢复活动而取消删除计划的时间',
    activity_origin varchar(16) not null default 'REALTIME' comment '活动时间来源：REALTIME或BACKFILL',
    version bigint not null default 0 comment '留存状态乐观锁版本',
    updated_at datetime(6) not null comment '最后更新时间',
    constraint fk_account_retention_user foreign key (user_id) references app_user(id) on delete cascade,
    index idx_account_retention_policy_due (policy_state, scheduled_deletion_at),
    index idx_account_retention_activity (last_meaningful_activity_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='账号30天生命周期的权威活动和通知状态';

create table if not exists account_deletion_job (
    id varchar(80) primary key comment '删除任务ID',
    user_id varchar(64) comment '待删除账号ID；完成或取消后清空',
    subject_digest varchar(128) not null comment '加盐不可逆账号摘要，不保存邮箱',
    reason varchar(32) not null comment '删除原因：USER_REQUEST、INACTIVITY或LEGACY_CLEANUP',
    status varchar(24) not null comment '任务状态',
    expected_activity_before datetime(6) comment '自动删除取得任务时冻结的活动截止点',
    lease_owner varchar(80) comment '定时删除任务租约持有者',
    lease_expires_at datetime(6) comment '定时删除任务租约到期时间',
    attempt_count integer not null default 0 comment '删除尝试次数',
    last_error_code varchar(80) comment '最近一次失败的稳定错误码',
    delete_counts_json longtext not null comment '各数据域删除行数，不保存玩家内容',
    requested_at datetime(6) not null comment '任务首次创建时间',
    updated_at datetime(6) not null comment '任务最后更新时间',
    completed_at datetime(6) comment '删除完成或取消时间',
    index idx_account_deletion_user_status (user_id, status),
    index idx_account_deletion_due (status, lease_expires_at, requested_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='单账号幂等物理删除任务；完成后不保留可逆主体标识';

create table if not exists account_deletion_audit (
    id varchar(80) primary key comment '匿名删除审计ID',
    subject_digest varchar(128) not null comment '加盐不可逆账号摘要',
    reason varchar(32) not null comment '删除原因',
    delete_counts_json longtext not null comment '各数据域删除行数汇总，不保存玩家内容',
    completed_at datetime(6) not null comment '物理删除完成时间',
    index idx_account_deletion_audit_time (completed_at),
    index idx_account_deletion_audit_subject (subject_digest, completed_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='不含邮箱、账号ID、剧情或密钥的删除完成审计';

create table if not exists account_deletion_notice (
    id varchar(80) primary key comment '删除预通知任务ID',
    user_id varchar(64) comment '待通知账号ID；取消后清空',
    activity_version bigint not null comment '创建通知时冻结的账号活动版本',
    scheduled_deletion_at datetime(6) not null comment '通知中明确告知的最早删除时间',
    status varchar(24) not null comment '通知状态：PENDING、SENDING、SENT、RETRYABLE_FAILED或CANCELED',
    attempt_count integer not null default 0 comment '真实发送尝试次数',
    next_attempt_at datetime(6) comment '失败后的下一次允许重试时间',
    lease_owner varchar(80) comment '发送租约持有者',
    lease_expires_at datetime(6) comment '发送租约到期时间',
    last_error_code varchar(80) comment '最近一次发送失败的稳定错误码',
    created_at datetime(6) not null comment '通知任务创建时间',
    updated_at datetime(6) not null comment '通知任务最后更新时间',
    sent_at datetime(6) comment '通知实际发送成功时间',
    canceled_at datetime(6) comment '因账号恢复活动而取消时间',
    unique key uk_account_deletion_notice_activity (user_id, activity_version),
    index idx_account_deletion_notice_due (status, next_attempt_at, lease_expires_at),
    constraint fk_account_deletion_notice_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='账号30天删除的可取消预通知任务；不保存邮箱或正文';
