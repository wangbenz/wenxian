create table if not exists app_user (
    id varchar(64) primary key,
    email varchar(255) not null unique,
    password_hash varchar(100),
    password_set_at timestamp(6),
    registration_ip_hash varchar(128),
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

create table if not exists game_save (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    slot_no smallint not null default 1,
    state_json longtext not null,
    revision bigint not null,
    updated_at timestamp(6) not null,
    unique key uk_game_save_user_slot (user_id, slot_no),
    index idx_game_save_user_updated (user_id, updated_at),
    constraint fk_save_user foreign key (user_id) references app_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists game_turn_request (
    request_id varchar(80) primary key,
    save_id varchar(80) not null,
    created_at timestamp(6) not null,
    index idx_turn_save (save_id),
    constraint fk_turn_save foreign key (save_id) references game_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists agent_conversation (
    id varchar(80) primary key,
    save_id varchar(80) not null unique,
    model varchar(80) not null,
    summary longtext,
    message_count integer not null default 0,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_conversation_save foreign key (save_id) references game_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists agent_message (
    id bigint primary key auto_increment,
    conversation_id varchar(80) not null,
    turn_number integer not null,
    role varchar(24) not null,
    content longtext not null,
    response_id varchar(128),
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    created_at timestamp(6) not null,
    index idx_agent_message_context (conversation_id, id),
    constraint fk_message_conversation foreign key (conversation_id) references agent_conversation(id) on delete cascade
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

create table if not exists game_turn (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    conversation_id varchar(80) not null,
    turn_number integer not null,
    player_action text not null,
    narrative_json longtext not null,
    choices_json longtext not null,
    state_delta_json longtext not null,
    world_time_before bigint not null,
    world_time_after bigint not null,
    model varchar(80) not null,
    response_id varchar(128),
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    latency_ms bigint not null default 0,
    created_at timestamp(6) not null,
    index idx_game_turn_save (save_id, turn_number),
    index idx_game_turn_created (created_at),
    constraint fk_game_turn_save foreign key (save_id) references game_save(id) on delete cascade,
    constraint fk_game_turn_conversation foreign key (conversation_id) references agent_conversation(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists game_life_archive (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    original_save_id varchar(80) not null,
    state_json longtext not null,
    end_reason varchar(24) not null default 'LEGACY',
    ended_at timestamp(6) not null,
    index idx_life_archive_user (user_id, ended_at),
    constraint fk_life_archive_user foreign key (user_id) references app_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists game_life_turn_archive (
    id varchar(80) primary key,
    life_archive_id varchar(80) not null,
    original_save_id varchar(80) not null,
    turn_number integer not null,
    player_action text not null,
    narrative_json longtext not null,
    choices_json longtext not null,
    state_delta_json longtext not null,
    model varchar(80) not null,
    response_id varchar(128),
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    latency_ms bigint not null default 0,
    created_at timestamp(6) not null,
    index idx_life_turn_archive (life_archive_id, turn_number),
    constraint fk_life_turn_archive foreign key (life_archive_id) references game_life_archive(id) on delete cascade
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
    updated_at timestamp(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists ai_provider_model (
    provider_code varchar(32) not null,
    model_code varchar(80) not null,
    display_name varchar(80) not null,
    api_model_name varchar(120) not null,
    platform_enabled boolean not null default true,
    user_selectable boolean not null default true,
    updated_at timestamp(6) not null,
    primary key (provider_code, model_code),
    constraint fk_ai_provider_model_provider foreign key (provider_code) references ai_provider_config(provider_code) on delete cascade
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
    index idx_access_whitelist_user (user_id)
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
    constraint fk_user_ai_credential_user foreign key (user_id) references app_user(id) on delete cascade
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
    grant_id varchar(80) not null comment '实际预占的赠送批次ID',
    amount integer not null default 1 comment '预占数量，当前每个成功剧情回合固定为1',
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
    constraint ck_turn_credit_reservation_amount check (amount > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='平台回合跨进程临时预占表';

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
    operation varchar(120) not null,
    category varchar(32) not null,
    error_code varchar(64) not null,
    http_status integer not null,
    public_message varchar(600) not null,
    diagnostic text not null,
    created_at timestamp(6) not null,
    index idx_operation_error_created (created_at),
    index idx_operation_error_save (save_id, created_at),
    index idx_operation_error_code (error_code, created_at),
    index idx_operation_error_category (category, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists v014_game_save (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    slot_no smallint not null default 1,
    world_code varchar(40) not null,
    state_schema_version integer not null default 14,
    state_json longtext not null,
    revision bigint not null default 1,
    status varchar(24) not null default 'ACTIVE',
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    unique key uk_v014_save_user_slot (user_id, slot_no),
    index idx_v014_save_user_status (user_id, status, updated_at),
    constraint fk_v014_save_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists v014_game_turn (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    request_id varchar(80) not null,
    turn_number integer not null,
    player_action text not null,
    narrative_text longtext not null,
    choices_json longtext not null,
    memory_patch_json longtext not null,
    world_time_before bigint not null,
    world_time_after bigint not null,
    provider varchar(32) not null,
    model varchar(120) not null,
    credential_source varchar(20) not null,
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    latency_ms bigint not null default 0,
    created_at datetime(6) not null,
    unique key uk_v014_turn_save_number (save_id, turn_number),
    unique key uk_v014_turn_save_request (save_id, request_id),
    index idx_v014_turn_save_created (save_id, created_at),
    constraint fk_v014_turn_save foreign key (save_id) references v014_game_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists v014_conversation (
    save_id varchar(80) primary key,
    rolling_summary longtext not null,
    summarized_through_turn integer not null default 0,
    updated_at datetime(6) not null,
    constraint fk_v014_conversation_save foreign key (save_id) references v014_game_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists v014_state_correction (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    user_id varchar(64) not null,
    instruction_text text not null,
    before_json longtext not null,
    after_json longtext not null,
    created_at datetime(6) not null,
    index idx_v014_correction_save (save_id, created_at),
    constraint fk_v014_correction_save foreign key (save_id) references v014_game_save(id) on delete cascade,
    constraint fk_v014_correction_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists v014_user_model_preference (
    user_id varchar(64) primary key,
    provider_code varchar(32) not null default 'DEEPSEEK',
    model_code varchar(80) not null default 'deepseek-v4-flash',
    credential_source varchar(20) not null default 'PUBLIC',
    updated_at datetime(6) not null,
    constraint fk_v014_model_preference_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

-- 统一命卷平台目标结构。当前 V0.14 仍写入旧表，以下表在完成迁移前保持空闲。
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
    status varchar(24) not null comment '命卷生命周期状态：ACTIVE或ENDED',
    world_code varchar(40) not null comment '当前世界编码',
    engine_code varchar(40) not null comment '故事引擎编码',
    engine_version integer not null comment '故事引擎规则版本',
    state_schema_version integer not null comment '核心状态JSON结构版本',
    source_type varchar(24) not null comment '命卷来源类型',
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
) values ('LIGHT_NARRATIVE', 1, '轻叙事引擎', 'DRAFT', 1, 1, false, false,
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
