create table if not exists app_user (
    id varchar(64) primary key,
    email varchar(255) not null unique,
    password_hash varchar(100) comment '登录密码哈希；为空表示旧账号尚未设置密码',
    password_set_at timestamp(6) comment '最近一次设置登录密码的时间',
    registration_ip_hash varchar(128) comment '注册来源 IP 的不可逆哈希，用于限流与审计',
    status varchar(32) not null,
    registered_at timestamp(6) not null default current_timestamp(6),
    last_login_at timestamp(6),
    index idx_app_user_status_login (status, last_login_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists auth_registration (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    ip_hash varchar(128) not null,
    created_at timestamp(6) not null,
    index idx_auth_registration_ip_time (ip_hash, created_at),
    constraint fk_auth_registration_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists auth_login_failure (
    id varchar(64) primary key,
    email_hash varchar(128) not null,
    ip_hash varchar(128) not null,
    created_at timestamp(6) not null,
    index idx_auth_login_failure_lookup (email_hash, ip_hash, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists auth_email_verification (
    id varchar(64) primary key comment '验证码挑战随机 ID，不包含邮箱或验证码',
    user_id varchar(64) comment '成功注册后绑定的账号；待发送和未完成挑战为空',
    purpose varchar(32) not null comment '验证码用途，当前仅 REGISTER',
    email_digest varchar(128) not null comment '规范化邮箱的带密钥 HMAC 摘要，不保存待注册邮箱明文',
    request_ip_digest varchar(128) not null comment '请求来源 IP 的不可逆摘要，仅用于频率保护',
    code_digest varchar(128) not null comment '验证码与挑战、邮箱绑定后的带密钥 HMAC 摘要，禁止保存明文',
    status varchar(32) not null comment 'PENDING_SEND/SENT/VERIFIED/CONSUMED/SEND_FAILED/SUPERSEDED/EXPIRED/LOCKED',
    attempt_count integer not null default 0 comment '已失败的验证码校验次数',
    max_attempts integer not null comment '该挑战允许的最大失败次数快照',
    expires_at timestamp(6) not null comment '验证码失效时间',
    sent_at timestamp(6) comment '邮件供应商确认接口调用成功的时间',
    consumed_at timestamp(6) comment '成功创建账号并消费验证码的时间',
    failure_code varchar(64) comment '稳定、无敏感信息的发送失败码',
    created_at timestamp(6) not null comment '挑战创建时间',
    updated_at timestamp(6) not null comment '最近状态更新时间',
    index idx_auth_email_verification_user (user_id),
    index idx_auth_email_verification_email_time (email_digest, created_at),
    index idx_auth_email_verification_ip_time (request_ip_digest, created_at),
    index idx_auth_email_verification_status_expiry (status, expires_at),
    constraint fk_auth_email_verification_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists invite_code (
    id varchar(64) primary key,
    code_hash varchar(128) not null unique,
    status varchar(32) not null,
    max_uses integer not null,
    used_count integer not null default 0,
    expires_at timestamp(6),
    created_at timestamp(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists user_session (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    token_hash varchar(128) not null unique,
    expires_at datetime(6) not null,
    revoked_at datetime(6),
    created_at datetime(6) not null,
    index idx_session_user (user_id),
    constraint fk_session_user foreign key (user_id) references app_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists account_retention_state (
    user_id varchar(64) primary key comment '玩家账号ID',
    last_meaningful_activity_at datetime(6) not null comment '最近一次有意义活动时间，不包含会话保活和静态读取',
    activity_source varchar(40) not null comment '最近活动来源：玩家成功操作或受控LEGACY_BACKFILL',
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
    status varchar(24) not null comment '任务状态：NOTICE_DUE、NOTICE_SENT、DELETE_DUE、DELETING、DELETED、CANCELED或RETRYABLE_FAILED',
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

create table if not exists ai_usage_log (
    id varchar(80) primary key,
    request_id varchar(100) comment '同一次业务模型任务的请求编号',
    user_id varchar(64) comment '用户内部编号，不保存邮箱',
    attribution_status varchar(24) not null default 'UNRESOLVED' comment '归属状态：ACCOUNT_LINKED、ANONYMOUS_LEGACY或UNRESOLVED',
    attempt_number integer not null default 1 comment '同一请求内的供应商调用序号',
    fallback_used boolean not null default false comment '本次是否为降级模型调用',
    save_id varchar(80),
    turn_number integer,
    operation varchar(40) not null,
    provider varchar(32) not null default 'DEEPSEEK',
    credential_source varchar(20) not null,
    model varchar(120) not null,
    game_version varchar(32) not null default 'UNKNOWN',
    status varchar(20) not null,
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    cache_hit_tokens integer not null default 0,
    cache_miss_tokens integer not null default 0,
    latency_ms bigint not null default 0,
    error_detail varchar(1000) not null default '',
    created_at datetime(6) not null,
    index idx_ai_usage_created (created_at),
    index idx_ai_usage_source_created (credential_source, created_at),
    index idx_ai_usage_provider_model (provider, model, created_at),
    index idx_ai_usage_request (request_id, attempt_number),
    index idx_ai_usage_user_created (user_id, created_at),
    index idx_ai_usage_attribution_created (attribution_status, created_at),
    index idx_ai_usage_save (save_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists release_note (
    id varchar(80) primary key,
    version varchar(32) not null,
    title varchar(120) not null,
    content longtext not null,
    status varchar(24) not null default 'DRAFT',
    announce_all boolean not null default false,
    show_on_login boolean not null default false,
    published_at timestamp(6),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    index idx_release_note_status (status, published_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists user_release_receipt (
    user_id varchar(64) not null,
    release_note_id varchar(80) not null,
    read_at timestamp(6) not null,
    primary key (user_id, release_note_id),
    constraint fk_release_receipt_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_release_receipt_note foreign key (release_note_id) references release_note(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists ai_model_config (
    model_code varchar(40) primary key,
    provider_code varchar(32) not null default 'DEEPSEEK',
    display_name varchar(80) not null,
    secret_ciphertext text,
    active boolean not null default false,
    updated_at timestamp(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists ai_provider_config (
    provider_code varchar(32) primary key,
    display_name varchar(80) not null,
    base_url varchar(255) not null,
    adapter_type varchar(32) not null default 'OPENAI_COMPATIBLE',
    platform_secret_ciphertext text,
    enabled boolean not null default true,
    user_key_enabled boolean not null default true,
    config_version bigint not null default 1 comment '供应商运行配置版本，Base URL或平台密钥变化时递增',
    updated_at timestamp(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists ai_provider_model (
    provider_code varchar(32) not null,
    model_code varchar(80) not null,
    display_name varchar(80) not null,
    api_model_name varchar(120) not null,
    capability_codes varchar(255) not null default 'CHAT,JSON_MODE' comment '已验证能力编码，使用英文逗号分隔',
    context_window integer not null default 64000 comment '模型上下文窗口Token上限',
    maximum_output_tokens integer not null default 8000 comment '单次请求最大输出Token上限',
    concurrency_limit integer not null default 4 comment '该模型的平台建议并发上限，不代表供应商实际配额',
    platform_enabled boolean not null default true,
    user_selectable boolean not null default true,
    config_version bigint not null default 1 comment '模型运行配置版本，真实API模型名变化时递增',
    updated_at timestamp(6) not null,
    primary key (provider_code, model_code),
    constraint fk_ai_provider_model_provider foreign key (provider_code) references ai_provider_config(provider_code) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='模型供应商下的可路由模型目录';

create table if not exists ai_model_task_route (
    task_code varchar(48) primary key comment '稳定模型任务编码',
    primary_provider_code varchar(32) not null comment '主模型供应商编码',
    primary_model_code varchar(80) not null comment '主模型编码',
    fallback_provider_code varchar(32) comment '备用模型供应商编码，可为空',
    fallback_model_code varchar(80) comment '备用模型编码，可为空',
    temperature decimal(4,3) not null default 0.700 comment '任务默认生成温度',
    timeout_seconds integer not null default 90 comment '单次模型调用超时秒数',
    maximum_retries integer not null default 0 comment '客户端内部重试次数，范围0至2',
    maximum_output_tokens integer comment '任务输出Token上限，为空时使用模型默认值',
    disable_thinking boolean not null default true comment '是否要求关闭模型思考模式',
    fallback_enabled boolean not null default false comment '主模型临时失败时是否允许使用备用模型',
    updated_at datetime(6) not null comment '最后更新时间',
    constraint fk_task_route_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_task_route_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='按业务任务配置的平台模型路由';

create table if not exists ai_model_validation_run (
    id varchar(64) primary key comment '验证记录稳定ID',
    provider_code varchar(32) not null comment '被验证模型的供应商编码',
    model_code varchar(80) not null comment '被验证模型的内部编码',
    provider_config_version bigint not null comment '验证时冻结的供应商配置版本',
    model_config_version bigint not null comment '验证时冻结的模型配置版本',
    status varchar(24) not null comment '验证结果：PASSED或FAILED',
    capability_codes varchar(255) not null default '' comment '本次受控验证实际覆盖的能力编码',
    latency_ms integer comment '验证请求耗时毫秒，未发出请求时为空',
    error_code varchar(80) comment '脱敏后的稳定错误码',
    error_summary varchar(500) comment '不包含密钥和原始响应的错误摘要',
    checked_by varchar(64) not null comment '发起验证的管理员或系统标识',
    checked_at timestamp(6) not null comment '验证完成时间',
    constraint fk_model_validation_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code) on delete cascade,
    index idx_model_validation_latest (provider_code, model_code, checked_at desc)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='模型受控验证历史，配置版本变化后旧结果自动失效';

create table if not exists ai_service_plan_counter (
    counter_key varchar(32) primary key comment '计数器稳定编码',
    next_value bigint not null comment '下一可分配方案版本号'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='推演服务方案版本号计数器';

insert ignore into ai_service_plan_counter(counter_key, next_value) values ('SERVICE_PLAN', 1);

create table if not exists ai_service_plan_version (
    id varchar(64) primary key comment '方案版本稳定ID',
    version_number bigint not null comment '运营可读的单调递增版本号',
    status varchar(16) not null comment '版本状态：DRAFT、ACTIVE或RETIRED',
    revision bigint not null default 0 comment '草稿乐观锁版本，每次保存递增',
    default_tier_code varchar(16) not null default 'BALANCED' comment '玩家未选择时使用的默认档位编码',
    change_note varchar(500) not null default '' comment '本版本变更说明',
    based_on_version_id varchar(64) comment '本版本克隆来源，可为空',
    created_by varchar(64) not null comment '创建管理员或系统标识',
    created_at timestamp(6) not null comment '创建时间',
    published_by varchar(64) comment '发布管理员标识',
    published_at timestamp(6) comment '正式发布时间',
    retired_at timestamp(6) comment '退出生效时间',
    active_marker tinyint generated always as (case when status='ACTIVE' then 1 else null end) stored comment '单一生效版本保护位',
    constraint fk_game_service_plan_based_on foreign key (based_on_version_id)
        references ai_service_plan_version(id),
    unique index uk_game_service_plan_version_number (version_number),
    unique index uk_game_service_plan_single_active (active_marker),
    index idx_game_service_plan_status_created (status, created_at desc)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='不可变发布的三档推演服务方案版本';

create table if not exists ai_service_plan_tier (
    plan_version_id varchar(64) not null comment '所属方案版本ID',
    tier_code varchar(16) not null comment '稳定档位编码：LIGHT、BALANCED或DEEP',
    display_name varchar(40) not null comment '玩家可见档位名称',
    description varchar(240) not null default '' comment '玩家可见档位说明',
    point_cost integer not null comment '一次正式成功回合消耗的推演点',
    enabled boolean not null default true comment '该版本内档位是否可供选择',
    primary_provider_code varchar(32) comment '档位默认主模型供应商，草稿未配置时可为空',
    primary_model_code varchar(80) comment '档位默认主模型，草稿未配置时可为空',
    fallback_provider_code varchar(32) comment '档位默认回退模型供应商，可为空',
    fallback_model_code varchar(80) comment '档位默认回退模型，可为空',
    fallback_enabled boolean not null default false comment '是否允许使用档位默认回退模型',
    primary key (plan_version_id, tier_code),
    constraint fk_game_service_plan_tier_version foreign key (plan_version_id)
        references ai_service_plan_version(id) on delete cascade,
    constraint fk_game_service_plan_tier_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_game_service_plan_tier_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code),
    index idx_game_service_plan_tier_primary (primary_provider_code, primary_model_code),
    index idx_game_service_plan_tier_fallback (fallback_provider_code, fallback_model_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='方案版本内固定的轻量、均衡、深度三档';

create table if not exists ai_service_plan_task_route (
    plan_version_id varchar(64) not null comment '所属方案版本ID',
    tier_code varchar(16) not null comment '所属档位编码',
    task_code varchar(48) not null comment '覆盖的稳定模型任务编码',
    primary_provider_code varchar(32) not null comment '任务覆盖主模型供应商编码',
    primary_model_code varchar(80) not null comment '任务覆盖主模型编码',
    fallback_provider_code varchar(32) comment '任务覆盖回退模型供应商，可为空',
    fallback_model_code varchar(80) comment '任务覆盖回退模型，可为空',
    temperature decimal(4,3) not null default 0.700 comment '任务生成温度',
    timeout_seconds integer not null default 90 comment '单次模型调用超时秒数',
    maximum_retries integer not null default 0 comment '基础设施内部重试次数，范围0至2',
    maximum_output_tokens integer comment '任务输出Token上限，为空时使用模型上限',
    disable_thinking boolean not null default true comment '是否要求关闭模型思考模式',
    fallback_enabled boolean not null default false comment '主模型最终失败时是否允许回退',
    primary key (plan_version_id, tier_code, task_code),
    constraint fk_game_service_plan_task_tier foreign key (plan_version_id, tier_code)
        references ai_service_plan_tier(plan_version_id, tier_code) on delete cascade,
    constraint fk_game_service_plan_task_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_game_service_plan_task_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code),
    index idx_game_service_plan_task_primary (primary_provider_code, primary_model_code),
    index idx_game_service_plan_task_fallback (fallback_provider_code, fallback_model_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='三档方案内按任务覆盖的模型路由参数';

create table if not exists ai_service_plan_model_snapshot (
    plan_version_id varchar(64) not null comment '已发布方案版本ID',
    provider_code varchar(32) not null comment '发布时选中的供应商编码',
    model_code varchar(80) not null comment '发布时选中的模型编码',
    provider_config_version bigint not null comment '发布校验通过时的供应商配置版本',
    model_config_version bigint not null comment '发布校验通过时的模型配置版本',
    validation_run_id varchar(64) not null comment '发布时采用的受控验证记录ID',
    captured_at timestamp(6) not null comment '证据快照时间',
    primary key (plan_version_id, provider_code, model_code),
    constraint fk_service_plan_snapshot_version foreign key (plan_version_id)
        references ai_service_plan_version(id) on delete cascade,
    constraint fk_service_plan_snapshot_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_service_plan_snapshot_validation foreign key (validation_run_id)
        references ai_model_validation_run(id),
    index idx_service_plan_snapshot_validation (validation_run_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='发布方案冻结的模型配置版本与验证证据';

create table if not exists user_service_tier_preference (
    user_id varchar(64) primary key comment '玩家账号ID',
    tier_code varchar(16) not null comment '稳定档位编码：LIGHT、BALANCED或DEEP',
    updated_at timestamp(6) not null comment '最后一次选择档位的时间',
    constraint fk_user_service_tier_preference_user foreign key (user_id)
        references app_user(id) on delete cascade,
    constraint ck_user_service_tier_preference_code check (tier_code in ('LIGHT','BALANCED','DEEP'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='玩家跨方案版本保留的推演服务档位偏好';

create table if not exists gameplay_config (
    config_key varchar(64) primary key,
    config_value varchar(255) not null,
    updated_at timestamp(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists game_access_whitelist (
    id varchar(64) primary key,
    user_id varchar(64) not null unique,
    note varchar(200) not null default '',
    created_by varchar(64) not null default 'system',
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    index idx_access_whitelist_user (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists user_ai_provider_credential (
    user_id varchar(64) not null,
    provider_code varchar(32) not null,
    api_key_ciphertext text not null,
    masked_suffix varchar(8) not null,
    status varchar(24) not null default 'ACTIVE',
    last_verified_at datetime(6),
    last_error_code varchar(48),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (user_id, provider_code),
    index idx_user_credential_provider_status (provider_code, status, updated_at) comment '管理台按供应商和凭证状态查询当前脱敏台账',
    index idx_user_credential_created (created_at) comment '管理台按首次成功接入时间统计玩家Key转化',
    constraint fk_user_provider_credential_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_user_provider_credential_provider foreign key (provider_code) references ai_provider_config(provider_code) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists user_daily_quota (
    user_id varchar(64) not null,
    usage_date date not null,
    used_count integer not null default 0,
    updated_at timestamp(6) not null,
    primary key (user_id, usage_date),
    index idx_daily_quota_date (usage_date),
    constraint fk_daily_quota_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists turn_credit_grant (
    id varchar(80) primary key comment '平台回合赠送批次ID',
    user_id varchar(64) not null comment '获得回合的玩家账号ID，四份命卷共享',
    credit_class varchar(24) not null comment '回合类别：SPONSORED赠送或PURCHASED未来购买',
    grant_type varchar(32) not null comment '发放来源：INITIAL、PLAYTEST、FEEDBACK、ACTIVITY、ADMIN等',
    campaign_code varchar(64) comment '运营活动或测试批次编码，可为空',
    source_reference varchar(160) comment '反馈编号、活动编号或未来订单号等外部来源',
    idempotency_key varchar(180) not null comment '发放幂等键，同一业务奖励只能成功一次',
    granted_count integer not null comment '本批最初发放总数',
    available_count integer not null comment '当前可预占数量',
    reserved_count integer not null default 0 comment '正在生成但尚未正式提交的预占数量',
    consumed_count integer not null default 0 comment '已经随正式成功回合消耗的数量',
    expired_count integer not null default 0 comment '到期未使用数量',
    revoked_count integer not null default 0 comment '被运营撤销且未使用的数量',
    status varchar(24) not null comment '批次状态：ACTIVE、EXHAUSTED、EXPIRED或REVOKED',
    expires_at datetime(6) comment '到期时间；为空表示长期有效',
    reason varchar(240) not null comment '不含隐私的发放原因',
    created_by varchar(64) not null comment 'SYSTEM或执行操作的管理员ID',
    created_at datetime(6) not null comment '发放时间',
    updated_at datetime(6) not null comment '最后状态变化时间',
    unique key uk_turn_credit_grant_idempotency (idempotency_key),
    index idx_turn_credit_grant_user_available (user_id, status, expires_at, available_count)
        comment '按账号和到期时间选择下一份可用回合',
    index idx_turn_credit_grant_campaign (campaign_code, created_at)
        comment '按测试或运营批次统计发放情况',
    constraint fk_turn_credit_grant_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint ck_turn_credit_grant_nonnegative check (
        granted_count >= 0 and available_count >= 0 and reserved_count >= 0 and consumed_count >= 0
        and expired_count >= 0 and revoked_count >= 0
    ),
    constraint ck_turn_credit_grant_conservation check (
        granted_count = available_count + reserved_count + consumed_count + expired_count + revoked_count
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='账号平台回合赠送批次表';

create table if not exists turn_credit_reservation (
    id varchar(80) primary key comment '平台回合预占ID',
    user_id varchar(64) not null comment '预占玩家账号ID',
    save_id varchar(80) not null comment '发起行动的命卷ID，不依赖具体版本命卷表外键',
    request_id varchar(80) not null comment 'H5行动幂等请求号',
    grant_id varchar(80) not null comment '兼容期首个赠送批次ID，完整来源以预占分摊表为准',
    amount integer not null default 1 comment '请求开始时冻结的推演点总数',
    service_plan_version_id varchar(64) comment '请求开始时冻结的三档方案版本ID；旧回合兼容记录为空',
    tier_code varchar(16) comment '请求开始时冻结的档位编码：LIGHT、BALANCED或DEEP；旧回合兼容记录为空',
    status varchar(24) not null comment '预占状态：RESERVED、CONSUMED、RELEASED或EXPIRED',
    expires_at datetime(6) not null comment '异常中断后允许自动释放的时间',
    turn_id varchar(80) comment '成功消费后关联的正式回合ID',
    created_at datetime(6) not null comment '预占时间',
    updated_at datetime(6) not null comment '最后状态变化时间',
    unique key uk_turn_credit_reservation_request (user_id, save_id, request_id),
    index idx_turn_credit_reservation_expiry (status, expires_at)
        comment '清理超时预占，避免进程异常永久占用余额',
    index idx_turn_credit_reservation_grant (grant_id, status),
    constraint fk_turn_credit_reservation_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_turn_credit_reservation_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint fk_turn_credit_reservation_tier foreign key (service_plan_version_id, tier_code)
        references ai_service_plan_tier(plan_version_id, tier_code),
    constraint ck_turn_credit_reservation_amount check (amount > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='平台推演点跨进程临时预占表';

create table if not exists turn_credit_reservation_allocation (
    reservation_id varchar(80) not null comment '所属推演点预占ID',
    grant_id varchar(80) not null comment '实际提供点数的赠送批次ID',
    amount integer not null comment '从该批次预占的推演点数量',
    primary key (reservation_id, grant_id),
    index idx_turn_credit_allocation_grant (grant_id, reservation_id)
        comment '按赠送批次追踪尚在预占中的点数来源',
    constraint fk_turn_credit_allocation_reservation foreign key (reservation_id)
        references turn_credit_reservation(id) on delete cascade,
    constraint fk_turn_credit_allocation_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint ck_turn_credit_allocation_amount check (amount > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
  comment='一次推演点预占在多个赠送批次上的不可变分摊';

create table if not exists turn_credit_ledger (
    id varchar(80) primary key comment '平台回合流水ID',
    user_id varchar(64) not null comment '流水所属玩家账号ID',
    grant_id varchar(80) not null comment '关联赠送批次ID',
    reservation_id varchar(80) comment '关联预占ID；直接发放或撤销时为空',
    event_type varchar(24) not null comment 'GRANT、RESERVE、RELEASE、CONSUME、EXPIRE或REVOKE',
    event_key varchar(200) not null comment '流水业务幂等键，同一状态变化只能记录一次',
    available_delta integer not null default 0 comment '本次对可用数量的变化',
    reserved_delta integer not null default 0 comment '本次对预占数量的变化',
    consumed_delta integer not null default 0 comment '本次对已消费数量的变化',
    balance_after integer not null comment '该事件完成后的账号可用平台回合数',
    business_reference varchar(160) comment '关联回合、批次或外部业务编号',
    operator_id varchar(64) not null comment 'SYSTEM或执行操作的管理员ID',
    remark varchar(240) not null comment '不含隐私的变动说明',
    created_at datetime(6) not null comment '流水发生时间',
    unique key uk_turn_credit_ledger_event (event_key),
    index idx_turn_credit_ledger_user_time (user_id, created_at)
        comment '玩家档案按时间查询回合变动',
    index idx_turn_credit_ledger_grant_time (grant_id, created_at),
    constraint fk_turn_credit_ledger_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_turn_credit_ledger_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint fk_turn_credit_ledger_reservation foreign key (reservation_id) references turn_credit_reservation(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='平台回合不可变审计流水表';

create table if not exists agent_execution (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    turn_number integer not null,
    model varchar(80) not null,
    attempt_number integer not null,
    status varchar(24) not null,
    detail text not null,
    response_id varchar(128),
    latency_ms bigint not null default 0,
    created_at timestamp(6) not null,
    index idx_agent_execution_save (save_id, turn_number, created_at),
    index idx_agent_execution_status_created (status, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists operation_error (
    reference_id varchar(40) primary key,
    save_id varchar(80),
    subject_user_id varchar(64) comment '可确认时记录关联玩家账号ID，账号删除后清理',
    operation varchar(120) not null,
    category varchar(32) not null,
    error_code varchar(64) not null,
    http_status integer not null,
    public_message varchar(600) not null,
    diagnostic text not null,
    created_at timestamp(6) not null,
    index idx_operation_error_created (created_at),
    index idx_operation_error_save (save_id, created_at),
    index idx_operation_error_subject (subject_user_id, created_at),
    index idx_operation_error_code (error_code, created_at),
    index idx_operation_error_category (category, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

-- 统一命卷平台当前结构。
create table if not exists story_engine_catalog (
    engine_code varchar(40) not null comment '故事引擎编码，与产品发布版本无关',
    engine_version integer not null comment '同一引擎的规则版本',
    display_name varchar(80) not null comment '管理台展示名称',
    status varchar(24) not null comment '引擎状态',
    min_schema_version integer not null comment '可读取的最低状态结构版本',
    current_schema_version integer not null comment '当前写入的状态结构版本',
    new_save_enabled boolean not null default false comment '是否允许创建新命卷',
    continuation_enabled boolean not null default false comment '是否允许继续已有命卷',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '最后更新时间',
    primary key (engine_code, engine_version)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='故事引擎目录';

create table if not exists story_save (
    id varchar(80) primary key comment '统一命卷ID',
    user_id varchar(64) not null comment '所属玩家账号ID',
    slot_no smallint not null comment '账号内只增历史序号，不因删除复用',
    active_slot_no smallint comment '当前命卷列表槽位1至4，逻辑删除后置空并允许新命卷复用',
    status varchar(24) not null comment '命卷生命周期状态：ACTIVE可继续、ENDED已终局、ARCHIVED已逻辑删除',
    world_code varchar(40) not null comment '当前世界编码',
    engine_code varchar(40) not null comment '故事引擎编码',
    engine_version integer not null comment '故事引擎规则版本',
    state_schema_version integer not null comment '核心状态JSON结构版本',
    source_type varchar(24) not null comment '来源类型：NATIVE、MIGRATION、FORK或IMPORT',
    source_edition varchar(32) comment '最初来源产品版本，仅供追溯',
    source_save_id varchar(80) comment '旧系统原始命卷ID',
    parent_save_id varchar(80) comment '承接或复制来源的统一命卷ID',
    state_json longtext not null comment '核心权威快照JSON',
    revision bigint not null default 1 comment '乐观锁版本号',
    turn_number integer not null default 0 comment '当前正式回合数',
    created_release varchar(32) not null comment '创建时产品发布版本',
    last_played_release varchar(32) not null comment '最后游玩时产品发布版本',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '最后变化时间',
    deleted_at datetime(6) comment '玩家逻辑删除时间，非空记录不再向H5返回',
    deleted_by varchar(24) comment '删除来源：PLAYER、ADMIN或SYSTEM',
    delete_reason varchar(48) comment '删除原因编码，不保存玩家隐私文本',
    unique key uk_story_save_user_slot (user_id, slot_no),
    unique key uk_story_save_user_active_slot (user_id, active_slot_no),
    unique key uk_story_save_source (source_type, source_save_id),
    key idx_story_save_user_status (user_id, deleted_at, status, updated_at),
    key idx_story_save_engine (engine_code, engine_version, status),
    key idx_story_save_status_turn (status, turn_number, updated_at) comment '按生命周期和回合数查询当前命卷排行榜',
    constraint fk_story_save_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_story_save_parent foreign key (parent_save_id) references story_save(id),
    constraint fk_story_save_engine foreign key (engine_code, engine_version)
        references story_engine_catalog(engine_code, engine_version)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷主表';

create table if not exists story_turn (
    id varchar(80) primary key comment '回合记录ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    request_id varchar(80) not null comment '客户端幂等请求号',
    turn_number integer not null comment '命卷内正式回合序号',
    player_action text not null comment '玩家确认提交的行动',
    narrative_text longtext not null comment '本轮剧情正文',
    choices_json longtext not null comment '本轮推荐行动JSON',
    state_patch_json longtext not null comment '归一化状态补丁JSON',
    world_time_before bigint not null comment '回合前世界时间分钟数',
    world_time_after bigint not null comment '回合后世界时间分钟数',
    engine_code varchar(40) not null comment '实际处理本轮的引擎编码',
    engine_version integer not null comment '实际处理本轮的引擎版本',
    state_schema_version integer not null comment '产生本轮补丁时的命卷状态结构版本',
    prompt_version varchar(64) not null comment 'Prompt与上下文策略版本',
    provider varchar(32) not null comment '内部模型供应商编码',
    model varchar(120) not null comment '内部模型编码',
    credential_source varchar(20) not null comment '密钥来源：PUBLIC或USER',
    prompt_tokens integer not null default 0 comment '输入Token数量',
    completion_tokens integer not null default 0 comment '输出Token数量',
    cache_hit_tokens integer not null default 0 comment '缓存命中Token数量',
    cache_miss_tokens integer not null default 0 comment '缓存未命中Token数量',
    latency_ms bigint not null default 0 comment '调用与解析总延迟毫秒数',
    release_version varchar(32) not null comment '产生本轮时的产品发布版本',
    created_at datetime(6) not null comment '成功提交时间',
    unique key uk_story_turn_save_number (save_id, turn_number),
    unique key uk_story_turn_save_request (save_id, request_id),
    key idx_story_turn_save_created (save_id, created_at),
    key idx_story_turn_release_created (release_version, created_at),
    key idx_story_turn_credential_created (credential_source, created_at) comment '管理台按凭证来源和日期统计成功自备推演回合',
    constraint fk_story_turn_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷不可变回合事实表';

create table if not exists story_turn_diagnostic (
    id varchar(80) primary key comment '可信回合诊断ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    request_id varchar(80) not null comment '客户端幂等请求号，用于关联成功回合或最终失败',
    turn_number integer not null comment '本次尝试准备写入的回合序号',
    expected_revision bigint not null comment '玩家提交行动时携带的命卷revision',
    prompt_version varchar(64) not null comment '上下文与Prompt策略版本',
    status varchar(24) not null comment '诊断状态：PREPARING、GENERATED、REVIEWED、REPAIRING、REDUCED、COMMITTED或FAILED',
    current_stage varchar(48) not null comment '当前或最终处理阶段编码',
    context_manifest_json longtext not null comment '本轮加载的稳定实体、事实、记忆和摘要来源清单，不保存完整Prompt',
    action_contract_json longtext not null comment '行动解析产生的引用、前置条件与玩家授权边界概要',
    proposal_trace_json longtext not null comment '首次及修复提案的结构化变化概要，不保存剧情正文',
    review_trace_json longtext not null comment '独立审阅通过、拒绝、原因和恢复方式概要',
    commit_summary_json longtext not null comment 'Reducer最终接受及成功提交的事实变化概要',
    failure_code varchar(64) not null default '' comment '最终失败类别，不保存异常栈',
    failure_detail varchar(1000) not null default '' comment '脱敏并截断的诊断说明',
    created_at datetime(6) not null comment '诊断开始时间',
    updated_at datetime(6) not null comment '最后阶段更新时间',
    unique key uk_story_turn_diagnostic_request (save_id, request_id),
    key idx_story_turn_diagnostic_save_turn (save_id, turn_number),
    key idx_story_turn_diagnostic_status_time (status, updated_at),
    constraint fk_story_turn_diagnostic_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='可信回合上下文、审阅与事实提交诊断表';

create table if not exists story_entity (
    id varchar(80) primary key comment '命卷内稳定实体ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    entity_type varchar(24) not null comment '实体类型',
    name varchar(120) not null comment '实体当前名称',
    status varchar(80) not null comment '实体当前状态',
    visible boolean not null default true comment '是否进入玩家当前列表和常规Prompt',
    summary text not null comment '当前事实摘要',
    attributes_json longtext not null comment '可扩展属性JSON',
    first_turn integer not null comment '首次成为正式实体的回合',
    last_changed_turn integer not null comment '最后变化回合',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '最后更新时间',
    key idx_story_entity_save_visible (save_id, visible, entity_type),
    key idx_story_entity_save_name (save_id, name),
    constraint fk_story_entity_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷当前实体表';

create table if not exists story_entity_alias (
    id varchar(80) primary key comment '实体长期别名记录ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    entity_id varchar(80) not null comment '别名对应的稳定实体ID，改名不会改变该ID',
    alias_name varchar(160) not null comment '玩家或已提交剧情确认的原始别名',
    normalized_alias varchar(160) not null comment '仅用于同命卷精确归一查找的别名',
    alias_type varchar(32) not null comment '别名类型：ORIGINAL_NAME、RENAMED_FROM、TITLE或SOURCE_NAME',
    source_turn integer comment '首次确认该别名的正式回合；旧数据可为空',
    active boolean not null default true comment '是否仍允许用于实体解析',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '最后更新时间',
    unique key uk_story_entity_alias_entity_name (save_id, entity_id, normalized_alias),
    key idx_story_entity_alias_lookup (save_id, normalized_alias, active),
    constraint fk_story_entity_alias_save foreign key (save_id) references story_save(id) on delete cascade,
    constraint fk_story_entity_alias_entity foreign key (entity_id) references story_entity(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷实体长期别名表';

create table if not exists story_memory (
    id varchar(80) primary key comment '关键记忆ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    memory_type varchar(24) not null comment '记忆类型',
    importance varchar(24) not null comment '重要程度',
    title varchar(160) not null comment '记忆标题',
    summary text not null comment '事实摘要',
    occurred_turn integer not null comment '发生回合',
    world_time bigint not null comment '发生时世界时间分钟数',
    status varchar(24) not null comment '记忆状态',
    created_at datetime(6) not null comment '创建时间',
    key idx_story_memory_save_status (save_id, status, importance),
    constraint fk_story_memory_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷关键记忆表';

create table if not exists story_conversation (
    save_id varchar(80) primary key comment '所属统一命卷ID',
    rolling_summary longtext not null comment '可重建的滚动摘要，不是权威事实',
    summarized_through_turn integer not null default 0 comment '已总结到的回合',
    summary_schema_version integer not null default 1 comment '摘要结构版本',
    updated_at datetime(6) not null comment '最后更新时间',
    constraint fk_story_conversation_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷上下文摘要表';

create table if not exists story_state_correction (
    id varchar(80) primary key comment '命卷修正记录ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    user_id varchar(64) not null comment '执行修正的玩家账号ID',
    instruction_text text not null comment '玩家确认的修正说明',
    correction_type varchar(32) not null comment '修正分类',
    state_schema_version integer not null comment '产生本次修正快照时的命卷状态结构版本',
    before_json longtext not null comment '修正前结构化值',
    after_json longtext not null comment '修正后结构化值',
    created_at datetime(6) not null comment '修正时间',
    key idx_story_correction_save (save_id, created_at),
    constraint fk_story_correction_save foreign key (save_id) references story_save(id) on delete cascade,
    constraint fk_story_correction_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷玩家修正审计表';

create table if not exists story_save_migration (
    id varchar(80) primary key comment '迁移任务ID',
    source_engine varchar(40) not null comment '原始数据来源标识',
    source_save_id varchar(80) not null comment '原始命卷ID',
    target_engine varchar(40) not null comment '目标故事引擎编码',
    target_save_id varchar(80) comment '成功后生成的统一命卷ID',
    source_schema_version integer not null comment '原始结构版本',
    target_schema_version integer not null comment '目标结构版本',
    status varchar(24) not null comment '迁移状态：RUNNING、READY、SUCCESS、FAILED或HASH_MISMATCH',
    before_hash varchar(128) not null comment '迁移前关键数据哈希',
    after_hash varchar(128) comment '迁移后关键数据哈希',
    error_message varchar(1000) comment '脱敏后的失败原因',
    started_at datetime(6) comment '迁移开始时间',
    completed_at datetime(6) comment '迁移完成时间',
    unique key uk_story_migration_source (source_engine, source_save_id),
    key idx_story_migration_status (status, started_at),
    constraint fk_story_migration_target foreign key (target_save_id) references story_save(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷迁移审计表';

create table if not exists user_model_preference (
    user_id varchar(64) primary key comment '玩家账号ID',
    provider_code varchar(32) not null comment '玩家自备模式供应商编码',
    model_code varchar(80) not null comment '玩家自备模式模型编码',
    credential_source varchar(20) not null comment '当前生成方式：PUBLIC或USER',
    updated_at datetime(6) not null comment '最后更新时间',
    constraint fk_user_model_preference_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_user_model_preference_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='玩家统一模型偏好表';

insert ignore into story_engine_catalog(
    engine_code, engine_version, display_name, status, min_schema_version, current_schema_version,
    new_save_enabled, continuation_enabled, created_at, updated_at
) values ('LIGHT_NARRATIVE', 1, '轻叙事引擎', 'DRAFT', 2, 2, false, false,
          current_timestamp(6), current_timestamp(6));

create table if not exists analytics_event (
    id varchar(80) primary key comment '统计事件ID',
    event_key varchar(180) not null comment '业务幂等键，同一事实只能记录一次',
    event_type varchar(48) not null comment '事件类型',
    user_id varchar(64) not null comment '玩家稳定ID，不保存邮箱',
    save_id varchar(80) comment '关联命卷ID，可为空',
    release_version varchar(32) not null default 'UNKNOWN' comment '事件发生时产品发布版本',
    engine_code varchar(48) not null default '' comment '处理事件的故事引擎编码',
    data_origin varchar(16) not null default 'REALTIME' comment 'REALTIME实时采集或BACKFILL历史回算',
    metadata_json longtext comment '不含隐私、Prompt和剧情正文的扩展维度',
    occurred_at datetime(6) not null comment '业务事实发生时间',
    recorded_at datetime(6) not null comment '统计系统记录时间',
    unique key uk_analytics_event_key (event_key),
    index idx_analytics_event_type_time (event_type, occurred_at) comment '按事件和时间聚合',
    index idx_analytics_event_user_time (user_id, occurred_at) comment '按玩家计算活跃与留存',
    index idx_analytics_event_save_time (save_id, occurred_at) comment '按命卷排查行为'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='独立产品统计事件事实表';

create table if not exists analytics_user_day (
    activity_date date not null comment '北京时间自然日',
    user_id varchar(64) not null comment '玩家稳定ID',
    created_save_count integer not null default 0 comment '当日创建命卷数',
    successful_turn_count integer not null default 0 comment '当日成功写入回合数',
    correction_count integer not null default 0 comment '当日修正命卷次数',
    entity_removal_count integer not null default 0 comment '当日整理物品或人物次数',
    archived_save_count integer not null default 0 comment '当日封存命卷数',
    first_activity_at datetime(6) not null comment '当日首次有效活动时间',
    last_activity_at datetime(6) not null comment '当日最后有效活动时间',
    first_release_version varchar(32) not null default 'UNKNOWN' comment '当日首次活动版本',
    last_release_version varchar(32) not null default 'UNKNOWN' comment '当日最后活动版本',
    data_origin varchar(16) not null default 'REALTIME' comment 'REALTIME实时采集或BACKFILL历史回算',
    updated_at datetime(6) not null comment '最后聚合时间',
    primary key (activity_date, user_id),
    index idx_analytics_user_day_user_date (user_id, activity_date) comment '按玩家计算留存和回流',
    index idx_analytics_user_day_date_activity (activity_date, last_activity_at) comment '按日期统计活跃'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='玩家每日有效活动汇总表';

create table if not exists analytics_daily_metric (
    metric_date date not null comment '北京时间指标日期',
    metric_code varchar(64) not null comment '稳定指标编码',
    dimension_type varchar(32) not null default 'ALL' comment '维度类型，ALL表示全局',
    dimension_value varchar(120) not null default 'ALL' comment '维度值',
    metric_value decimal(24,4) not null default 0 comment '指标数值',
    data_origin varchar(16) not null default 'REALTIME' comment 'REALTIME实时采集或BACKFILL历史回算',
    calculation_version integer not null default 1 comment '指标计算口径版本',
    calculated_at datetime(6) not null comment '最后计算时间',
    primary key (metric_date, metric_code, dimension_type, dimension_value),
    index idx_analytics_metric_code_date (metric_code, metric_date) comment '管理台趋势查询'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='管理台每日指标汇总表';

create table if not exists analytics_retention_cohort (
    cohort_date date not null comment '北京时间注册批次日期',
    retention_day smallint not null comment '注册后第几日，首版支持1、3、7、14',
    registered_users integer not null default 0 comment '该批次注册账号数',
    activated_users integer not null default 0 comment '该批次完成首回合账号数',
    retained_users integer not null default 0 comment '对应日期再次有效活跃账号数',
    data_origin varchar(16) not null default 'REALTIME' comment 'REALTIME实时采集或BACKFILL历史回算',
    calculation_version integer not null default 1 comment '留存口径版本',
    calculated_at datetime(6) not null comment '最后计算时间',
    primary key (cohort_date, retention_day),
    index idx_analytics_retention_day_date (retention_day, cohort_date) comment '留存趋势查询'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='注册批次留存汇总表';

-- 世界包注册表；前置：MySQL 8.0.16+、app_user 存在且 ID 为 varchar(64) utf8mb4_unicode_ci。
-- 仅新增表，不发布、不启用、不修改命卷。已有环境先只读 preflight；本脚本不允许整体重复执行。
-- DDL 非事务性；中断后核对已建结构，仅继续缺失语句。回滚保留新增表，不自动删除内容。
create table if not exists world_package (
    id varchar(64) primary key comment '服务端签发的内部包身份',
    publisher_namespace varchar(128) collate utf8mb4_bin not null comment '发布者稳定命名空间，区分大小写',
    package_key varchar(128) collate utf8mb4_bin not null comment '包稳定标识，区分大小写',
    owner_user_id varchar(64) comment '玩家所有者，账号删除后置空且不再授予发布权',
    source_type varchar(16) not null comment '治理来源：OFFICIAL或USER或IMPORTED',
    visibility varchar(16) not null comment 'PRIVATE或UNLISTED或PUBLIC',
    status varchar(16) not null comment 'ACTIVE或WITHDRAWN或SUSPENDED',
    latest_revision bigint not null default 0 comment '最新发布号，仅用于分配与目录，绝非默认项',
    created_at timestamp(6) not null default current_timestamp(6) comment '身份登记时间',
    updated_at timestamp(6) not null default current_timestamp(6) comment '最近治理或发布时间',
    unique key uk_world_package_identity (publisher_namespace, package_key) comment '阻止同一可移植包身份重复登记',
    key idx_world_package_owner (owner_user_id) comment '所有权查询及账号删除外键',
    constraint fk_world_package_owner foreign key (owner_user_id) references app_user(id) on delete set null,
    constraint ck_world_package_revision check (latest_revision >= 0),
    constraint ck_world_package_source check (source_type in ('OFFICIAL','USER','IMPORTED')),
    constraint ck_world_package_visibility check (visibility in ('PRIVATE','UNLISTED','PUBLIC')),
    constraint ck_world_package_status check (status in ('ACTIVE','WITHDRAWN','SUSPENDED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='世界包稳定身份与可变治理信息';

create table if not exists world_package_revision (
    id varchar(64) primary key comment '不可变修订内部ID，未来命卷引用目标',
    package_id varchar(64) not null comment '所属包身份',
    revision bigint not null comment '包内修订号，发布时在包行锁内分配',
    format_version integer not null comment '文档格式版本',
    runtime_contract varchar(80) collate utf8mb4_bin not null comment '解释合同',
    required_capabilities_json longtext not null comment '声明式能力集合JSON',
    content_hash char(64) collate utf8mb4_bin not null comment '规范内容SHA256',
    compiled_hash char(64) collate utf8mb4_bin not null comment '确定性编译快照SHA256',
    canonical_json longtext not null comment '不可变规范文档，保留精确身份',
    compiled_json longtext not null comment '不可变编译快照',
    compiler_version varchar(64) collate utf8mb4_bin not null comment '确定性编译器版本',
    status varchar(16) not null comment 'PUBLISHED或WITHDRAWN或SUSPENDED，仅治理状态可变',
    created_by varchar(96) collate utf8mb4_bin not null comment '可信发布主体审计标识，不来自包正文',
    published_at timestamp(6) not null default current_timestamp(6) comment '发布时间',
    unique key uk_world_revision_number (package_id, revision) comment '禁止同一修订号覆盖',
    unique key uk_world_revision_content (package_id, content_hash) comment '禁止相同内容重复增加修订号',
    unique key uk_world_revision_package (id, package_id) comment '幂等结果必须属于同一包的复合外键目标',
    constraint fk_world_revision_package foreign key (package_id) references world_package(id),
    constraint ck_world_revision_number check (revision > 0),
    constraint ck_world_revision_status check (status in ('PUBLISHED','WITHDRAWN','SUSPENDED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='世界包不可变内容与确定性编译修订';

create table if not exists world_package_asset (
    revision_id varchar(64) not null comment '所属精确修订',
    asset_path varchar(240) collate utf8mb4_bin not null comment '规范素材路径，非本机或远程地址',
    media_type varchar(64) not null comment '经签名检查的声明MIME，不代表已消毒',
    byte_size bigint not null comment '原件字节数',
    content_hash char(64) collate utf8mb4_bin not null comment '原件SHA256',
    asset_bytes mediumblob not null comment '受限原件，与修订同事务保存，禁止直接公开渲染',
    primary key (revision_id, asset_path) comment '同一修订的素材路径不可重复',
    constraint fk_world_asset_revision foreign key (revision_id) references world_package_revision(id),
    constraint ck_world_asset_size check (byte_size > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='精确世界包素材原件，事务失败无外部孤儿对象';

create table if not exists world_package_publication_request (
    package_id varchar(64) not null comment '请求所属包',
    principal_key varchar(96) collate utf8mb4_bin not null comment '可信主体键',
    idempotency_key varchar(128) collate utf8mb4_bin not null comment '调用方重试保持不变的请求键',
    request_hash char(64) collate utf8mb4_bin not null comment '操作模式、精确号及内容哈希摘要',
    revision_id varchar(64) not null comment '已提交结果修订',
    created_at timestamp(6) not null default current_timestamp(6) comment '提交时间',
    primary key (package_id, principal_key, idempotency_key) comment '同包同主体的幂等键只能对应一次请求',
    key idx_world_request_result (revision_id, package_id) comment '保证幂等结果属于请求包',
    constraint fk_world_request_result foreign key (revision_id, package_id) references world_package_revision(id, package_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='世界包发布持久幂等结果，与修订同事务提交';

create table if not exists world_package_installation (
    user_id varchar(64) not null comment '持有修订的玩家',
    revision_id varchar(64) not null comment '安装的精确修订',
    installed_at timestamp(6) not null default current_timestamp(6) comment '安装时间，不推导默认项',
    primary key (user_id, revision_id) comment '玩家不能重复安装同一修订',
    key idx_world_install_revision (revision_id) comment '保护被玩家持有的修订',
    constraint fk_world_install_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_world_install_revision foreign key (revision_id) references world_package_revision(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='玩家世界包安装，不等于发布权或创角启用';

create table if not exists world_package_creation_scope (
    id varchar(80) primary key comment 'GLOBAL或服务端签发的玩家范围ID',
    user_id varchar(64) comment 'GLOBAL为空，玩家范围绑定账号',
    scope_type varchar(8) not null comment 'GLOBAL或USER',
    unique key uk_world_scope_user (user_id) comment '每个玩家最多一个创角范围',
    constraint fk_world_scope_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint ck_world_scope check ((scope_type='GLOBAL' and id='GLOBAL' and user_id is null) or (scope_type='USER' and id<>'GLOBAL' and user_id is not null))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='独立创角范围与并发默认指针锁';

create table if not exists world_package_availability (
    scope_id varchar(80) not null comment '显式启用的范围',
    revision_id varchar(64) not null comment '显式启用的精确修订',
    enabled_at timestamp(6) not null default current_timestamp(6) comment '启用时间',
    primary key (scope_id, revision_id) comment '范围内每个修订仅启用一次，也是默认指针的外键目标',
    key idx_world_available_revision (revision_id) comment '修订引用保护',
    constraint fk_world_available_scope foreign key (scope_id) references world_package_creation_scope(id) on delete cascade,
    constraint fk_world_available_revision foreign key (revision_id) references world_package_revision(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='存在即创角启用，发布和安装均不会自动写入';

create table if not exists world_package_creation_default (
    scope_id varchar(80) primary key comment '每个范围最多一个默认项',
    revision_id varchar(64) not null comment '该范围已经启用的精确修订',
    updated_at timestamp(6) not null default current_timestamp(6) comment '显式切换时间',
    constraint fk_world_default_available foreign key (scope_id, revision_id) references world_package_availability(scope_id, revision_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='显式创角默认指针，不随最新修订变化';

create table if not exists world_package_audit (
    id varchar(64) primary key comment '随机审计ID',
    actor_key varchar(96) collate utf8mb4_bin not null comment '可信操作主体',
    action varchar(32) not null comment '启用、停用、切换默认等操作',
    scope_id varchar(80) comment '范围标识快照，账号删除不影响审计',
    revision_id varchar(64) comment '操作修订，清空默认可为空',
    created_at timestamp(6) not null default current_timestamp(6) comment '操作提交时间',
    key idx_world_audit_scope_time (scope_id, created_at) comment '追踪默认切换与可用性变化',
    constraint fk_world_audit_revision foreign key (revision_id) references world_package_revision(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='世界包可用性操作审计，与变更同事务';
