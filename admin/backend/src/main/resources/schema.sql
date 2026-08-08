-- 管理后台专用表（统一 admin_ 前缀，与 H5 问仙录主表隔离）

create table if not exists admin_user (
    id varchar(64) primary key,
    username varchar(64) not null unique,
    password_hash varchar(128) not null comment '管理密码安全哈希（兼容旧数据自动升级）',
    status varchar(16) not null default 'ACTIVE',
    created_at timestamp(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists admin_session (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    token_hash varchar(128) not null unique,
    expires_at timestamp(6) not null,
    created_at timestamp(6) not null,
    index idx_admin_session_token (token_hash),
    constraint fk_admin_session_user foreign key (user_id) references admin_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists admin_invite_code (
    id varchar(64) primary key,
    invite_code_id varchar(64) not null unique comment '关联 invite_code 表 id',
    code_label varchar(32) not null comment '用于管理台展示的明文内测码',
    created_at timestamp(6) not null,
    constraint fk_admin_invite_code foreign key (invite_code_id) references invite_code(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists admin_operation_log (
    id varchar(64) primary key,
    operated_at timestamp(6) not null,
    operation varchar(64) not null comment '操作类型',
    detail text not null comment '操作摘要'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists operation_error (
    reference_id varchar(40) primary key,
    save_id varchar(80),
    operation varchar(120) not null,
    category varchar(32) not null,
    error_code varchar(64) not null,
    http_status integer not null,
    public_message varchar(600) not null,
    diagnostic text not null,
    created_at datetime(6) not null,
    index idx_operation_error_created (created_at),
    index idx_operation_error_save (save_id, created_at),
    index idx_operation_error_code (error_code, created_at),
    index idx_operation_error_category (category, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists ai_usage_log (
    id varchar(80) primary key,
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
    index idx_ai_usage_save (save_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists game_analytics_event (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    save_id varchar(80),
    game_version varchar(32) not null,
    event_type varchar(48) not null,
    metadata_json varchar(1000) not null default '{}',
    created_at datetime(6) not null,
    index idx_analytics_version_event (game_version, event_type, created_at),
    index idx_analytics_user_created (user_id, created_at),
    index idx_analytics_save_created (save_id, created_at)
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
    constraint fk_admin_release_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_admin_release_note foreign key (release_note_id) references release_note(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists ai_model_config (
    model_code varchar(40) primary key,
    provider_code varchar(32) not null default 'DEEPSEEK',
    display_name varchar(80) not null,
    secret_ciphertext text,
    active boolean not null default false,
    updated_at timestamp(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

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
    index idx_admin_access_whitelist_user (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists user_ai_credential (
    user_id varchar(64) primary key,
    provider varchar(32) not null default 'DEEPSEEK',
    api_key_ciphertext text not null,
    masked_suffix varchar(8) not null,
    status varchar(24) not null default 'ACTIVE',
    last_verified_at timestamp(6),
    last_error_code varchar(48),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_admin_user_ai_credential foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists user_daily_quota (
    user_id varchar(64) not null,
    usage_date date not null,
    used_count integer not null default 0,
    updated_at timestamp(6) not null,
    primary key (user_id, usage_date),
    index idx_admin_daily_quota_date (usage_date),
    constraint fk_admin_daily_quota_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

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
