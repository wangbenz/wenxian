-- 管理后台专用表（统一 admin_ 前缀，与 H5 问仙录主表隔离）

create table if not exists admin_user (
    id varchar(64) primary key,
    username varchar(64) not null unique,
    password_hash varchar(128) not null comment 'SHA-256 哈希',
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
    display_name varchar(80) not null,
    secret_ciphertext text,
    active boolean not null default false,
    updated_at timestamp(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
