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
