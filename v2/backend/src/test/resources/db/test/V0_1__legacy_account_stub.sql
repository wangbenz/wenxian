create table app_user (
    id varchar(64) primary key,
    email varchar(255) not null unique,
    password_hash varchar(100),
    password_set_at timestamp(6),
    registration_ip_hash varchar(128),
    status varchar(32) not null,
    registered_at timestamp(6) not null,
    last_login_at timestamp(6)
);

create table auth_registration (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    ip_hash varchar(128) not null,
    created_at timestamp(6) not null,
    constraint fk_v2_test_registration_user foreign key (user_id) references app_user(id)
);

create table auth_login_failure (
    id varchar(64) primary key,
    email_hash varchar(128) not null,
    ip_hash varchar(128) not null,
    created_at timestamp(6) not null
);

create table user_session (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    token_hash varchar(128) not null unique,
    expires_at timestamp(6) not null,
    revoked_at timestamp(6),
    created_at timestamp(6) not null,
    constraint fk_v2_test_session_user foreign key (user_id) references app_user(id)
);

create table gameplay_config (
    config_key varchar(64) primary key,
    config_value varchar(255) not null,
    updated_at timestamp(6) not null
);

create table release_note (
    id varchar(80) primary key,
    version varchar(32) not null,
    title varchar(120) not null,
    content clob not null,
    status varchar(24) not null default 'DRAFT',
    announce_all boolean not null default false,
    show_on_login boolean not null default false,
    published_at timestamp(6),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null
);

create table user_release_receipt (
    user_id varchar(64) not null,
    release_note_id varchar(80) not null,
    read_at timestamp(6) not null,
    primary key (user_id, release_note_id)
);

create table user_daily_quota (
    user_id varchar(64) not null,
    usage_date date not null,
    used_count integer not null default 0,
    updated_at timestamp(6) not null,
    primary key (user_id, usage_date)
);

create table game_save (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    slot_no smallint not null default 1,
    state_json clob not null,
    revision bigint not null,
    updated_at timestamp(6) not null
);

create table game_turn (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    turn_number integer not null,
    player_action clob not null,
    narrative_json clob not null,
    created_at timestamp(6) not null
);

create table game_life_archive (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    original_save_id varchar(80) not null,
    state_json clob not null,
    end_reason varchar(24) not null default 'LEGACY',
    ended_at timestamp(6) not null
);

create table game_life_turn_archive (
    id varchar(80) primary key,
    life_archive_id varchar(80) not null,
    original_save_id varchar(80) not null,
    turn_number integer not null,
    player_action clob not null,
    narrative_json clob not null,
    created_at timestamp(6) not null
);

create table game_access_whitelist (
    id varchar(64) primary key,
    user_id varchar(64) not null unique,
    note varchar(200) not null default '',
    created_by varchar(64) not null default 'system',
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null
);

create table admin_user (
    id varchar(64) primary key,
    username varchar(64) not null unique,
    password_hash varchar(128) not null,
    status varchar(16) not null default 'ACTIVE',
    created_at timestamp(6) not null
);

create table admin_session (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    token_hash varchar(128) not null unique,
    expires_at timestamp(6) not null,
    created_at timestamp(6) not null,
    constraint fk_v2_test_admin_session_user foreign key (user_id) references admin_user(id)
);
