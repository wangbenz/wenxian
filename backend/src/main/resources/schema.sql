create table if not exists app_user (
    id varchar(64) primary key,
    email varchar(255) not null unique,
    status varchar(32) not null,
    registered_at timestamp(6) not null,
    last_login_at timestamp(6)
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
    expires_at timestamp(6) not null,
    revoked_at timestamp(6),
    created_at timestamp(6) not null,
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
