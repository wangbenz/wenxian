-- V0.14 多世界轻状态命卷。先建表，应用部署并完成灰度验证后再由管理台切换 current_game_version。

create table if not exists v014_game_save (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    slot_no smallint not null,
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

-- 供应商、模型目录与玩家供应商级密钥。平台实际模型只在管理台和内部日志可见。
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

insert ignore into ai_provider_config(provider_code, display_name, base_url, adapter_type, enabled, user_key_enabled, updated_at)
values ('DEEPSEEK', 'DeepSeek', 'https://api.deepseek.com', 'OPENAI_COMPATIBLE', true, true, current_timestamp(6));

insert ignore into ai_provider_model(provider_code, model_code, display_name, api_model_name, platform_enabled, user_selectable, updated_at)
values ('DEEPSEEK', 'deepseek-v4-flash', 'Flash', 'deepseek-v4-flash', true, true, current_timestamp(6)),
       ('DEEPSEEK', 'deepseek-v4-pro', 'Pro', 'deepseek-v4-pro', true, true, current_timestamp(6));

-- 沿用现有平台密钥，切换表结构时不要求运营重新粘贴。
update ai_provider_config p
join ai_model_config m on m.provider_code=p.provider_code and m.active=true
set p.platform_secret_ciphertext=m.secret_ciphertext, p.updated_at=current_timestamp(6)
where p.platform_secret_ciphertext is null and m.secret_ciphertext is not null;

-- 兼容已经验证过的 DeepSeek 玩家密钥；旧表保留供 V0.13 回滚。
insert ignore into user_ai_provider_credential(user_id, provider_code, api_key_ciphertext, masked_suffix, status,
                                                last_verified_at, last_error_code, created_at, updated_at)
select user_id, 'DEEPSEEK', api_key_ciphertext, masked_suffix, status,
       last_verified_at, last_error_code, created_at, updated_at
from user_ai_credential
where provider = 'DEEPSEEK';
