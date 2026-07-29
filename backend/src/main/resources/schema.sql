create table if not exists app_user (
    id varchar(64) primary key,
    email varchar(255) not null unique,
    password_hash varchar(100),
    password_set_at timestamp(6),
    registration_ip_hash varchar(128),
    status varchar(32) not null,
    registered_at timestamp(6) not null,
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
    user_id varchar(64) not null unique,
    state_json longtext not null,
    revision bigint not null,
    updated_at timestamp(6) not null,
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

create table if not exists gameplay_config (
    config_key varchar(64) primary key,
    config_value varchar(255) not null,
    updated_at timestamp(6) not null
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

create table if not exists user_daily_quota (
    user_id varchar(64) not null,
    usage_date date not null,
    used_count integer not null default 0,
    updated_at timestamp(6) not null,
    primary key (user_id, usage_date),
    index idx_daily_quota_date (usage_date),
    constraint fk_daily_quota_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

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
