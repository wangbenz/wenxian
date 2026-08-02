create table app_user (
    id varchar(64) primary key,
    email varchar(255) not null unique,
    password_hash varchar(100),
    status varchar(32) not null,
    registered_at timestamp(6) not null,
    last_login_at timestamp(6)
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

create table game_access_whitelist (
    id varchar(64) primary key,
    user_id varchar(64) not null unique,
    note varchar(200) not null default '',
    created_by varchar(64) not null default 'system',
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null
);
