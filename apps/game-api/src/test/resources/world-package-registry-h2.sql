-- 世界包注册表；前置：MySQL 8.0.16+、app_user 存在且 ID 为 varchar(64) utf8mb4_unicode_ci。
-- 仅新增表，不发布、不启用、不修改命卷。已有环境先只读 preflight；本脚本不允许整体重复执行。
-- DDL 非事务性；中断后核对已建结构，仅继续缺失语句。回滚保留新增表，不自动删除内容。
create table world_package (
    id varchar(64) primary key,
    publisher_namespace varchar(128) not null,
    package_key varchar(128) not null,
    owner_user_id varchar(64),
    source_type varchar(16) not null,
    visibility varchar(16) not null,
    status varchar(16) not null,
    latest_revision bigint not null default 0,
    created_at timestamp(6) not null default current_timestamp(6),
    updated_at timestamp(6) not null default current_timestamp(6),
    constraint uk_world_package_identity unique (publisher_namespace, package_key),
    constraint fk_world_package_owner foreign key (owner_user_id) references app_user(id) on delete set null,
    constraint ck_world_package_revision check (latest_revision >= 0),
    constraint ck_world_package_source check (source_type in ('OFFICIAL','USER','IMPORTED')),
    constraint ck_world_package_visibility check (visibility in ('PRIVATE','UNLISTED','PUBLIC')),
    constraint ck_world_package_status check (status in ('ACTIVE','WITHDRAWN','SUSPENDED'))
);

create table world_package_revision (
    id varchar(64) primary key,
    package_id varchar(64) not null,
    revision bigint not null,
    format_version integer not null,
    runtime_contract varchar(80) not null,
    required_capabilities_json longtext not null,
    content_hash char(64) not null,
    compiled_hash char(64) not null,
    canonical_json longtext not null,
    compiled_json longtext not null,
    compiler_version varchar(64) not null,
    status varchar(16) not null,
    created_by varchar(96) not null,
    published_at timestamp(6) not null default current_timestamp(6),
    constraint uk_world_revision_number unique (package_id, revision),
    constraint uk_world_revision_content unique (package_id, content_hash),
    constraint uk_world_revision_package unique (id, package_id),
    constraint fk_world_revision_package foreign key (package_id) references world_package(id),
    constraint ck_world_revision_number check (revision > 0),
    constraint ck_world_revision_status check (status in ('PUBLISHED','WITHDRAWN','SUSPENDED'))
);

create table world_package_asset (
    revision_id varchar(64) not null,
    asset_path varchar(240) not null,
    media_type varchar(64) not null,
    byte_size bigint not null,
    content_hash char(64) not null,
    asset_bytes blob not null,
    primary key (revision_id, asset_path),
    constraint fk_world_asset_revision foreign key (revision_id) references world_package_revision(id),
    constraint ck_world_asset_size check (byte_size > 0)
);

create table world_package_publication_request (
    package_id varchar(64) not null,
    principal_key varchar(96) not null,
    idempotency_key varchar(128) not null,
    request_hash char(64) not null,
    revision_id varchar(64) not null,
    created_at timestamp(6) not null default current_timestamp(6),
    primary key (package_id, principal_key, idempotency_key),
    constraint fk_world_request_result foreign key (revision_id, package_id) references world_package_revision(id, package_id)
);

create table world_package_installation (
    user_id varchar(64) not null,
    revision_id varchar(64) not null,
    installed_at timestamp(6) not null default current_timestamp(6),
    primary key (user_id, revision_id),
    constraint fk_world_install_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_world_install_revision foreign key (revision_id) references world_package_revision(id)
);

create table world_package_creation_scope (
    id varchar(80) primary key,
    user_id varchar(64),
    scope_type varchar(8) not null,
    constraint uk_world_scope_user unique (user_id),
    constraint fk_world_scope_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint ck_world_scope check ((scope_type='GLOBAL' and id='GLOBAL' and user_id is null) or (scope_type='USER' and id<>'GLOBAL' and user_id is not null))
);

create table world_package_availability (
    scope_id varchar(80) not null,
    revision_id varchar(64) not null,
    enabled_at timestamp(6) not null default current_timestamp(6),
    primary key (scope_id, revision_id),
    constraint fk_world_available_scope foreign key (scope_id) references world_package_creation_scope(id) on delete cascade,
    constraint fk_world_available_revision foreign key (revision_id) references world_package_revision(id)
);

create table world_package_creation_default (
    scope_id varchar(80) primary key,
    revision_id varchar(64) not null,
    updated_at timestamp(6) not null default current_timestamp(6),
    constraint fk_world_default_available foreign key (scope_id, revision_id) references world_package_availability(scope_id, revision_id) on delete cascade
);

create table world_package_audit (
    id varchar(64) primary key,
    actor_key varchar(96) not null,
    action varchar(32) not null,
    scope_id varchar(80),
    revision_id varchar(64),
    created_at timestamp(6) not null default current_timestamp(6),
    constraint fk_world_audit_revision foreign key (revision_id) references world_package_revision(id)
);
