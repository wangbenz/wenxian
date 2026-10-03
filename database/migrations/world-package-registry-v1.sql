-- 世界包注册表；前置：MySQL 8.0.16+、app_user 存在且 ID 为 varchar(64) utf8mb4_unicode_ci。
-- 仅新增表，不发布、不启用、不修改命卷。已有环境先只读 preflight；本脚本不允许整体重复执行。
-- DDL 非事务性；中断后核对已建结构，仅继续缺失语句。回滚保留新增表，不自动删除内容。
-- MySQL 不允许 SET NULL 外键字段同时参与 CHECK。官方包 owner 为空由可信登记服务保证；
-- 玩家发布权限始终同时检查 source_type=USER 与所有者，不能仅凭 owner 授权。
create table world_package (
    id varchar(64) primary key comment '服务端签发的内部包身份',
    publisher_namespace varchar(128) collate utf8mb4_bin not null comment '发布者稳定命名空间，区分大小写',
    package_key varchar(128) collate utf8mb4_bin not null comment '包稳定标识，区分大小写',
    owner_user_id varchar(64) comment '玩家所有者，账号删除后置空且不再授予发布权',
    source_type varchar(16) not null comment '治理来源：OFFICIAL或USER或IMPORTED',
    visibility varchar(16) not null comment 'PRIVATE或UNLISTED或PUBLIC',
    status varchar(16) not null comment 'ACTIVE或WITHDRAWN或SUSPENDED',
    latest_revision bigint not null default 0 comment '最新发布号，仅用于分配与目录，绝非默认项',
    created_at timestamp(6) not null default current_timestamp(6) comment '身份登记时间',
    updated_at timestamp(6) not null default current_timestamp(6) comment '最近治理或发布时间',
    unique key uk_world_package_identity (publisher_namespace, package_key) comment '阻止同一可移植包身份重复登记',
    key idx_world_package_owner (owner_user_id) comment '所有权查询及账号删除外键',
    constraint fk_world_package_owner foreign key (owner_user_id) references app_user(id) on delete set null,
    constraint ck_world_package_revision check (latest_revision >= 0),
    constraint ck_world_package_source check (source_type in ('OFFICIAL','USER','IMPORTED')),
    constraint ck_world_package_visibility check (visibility in ('PRIVATE','UNLISTED','PUBLIC')),
    constraint ck_world_package_status check (status in ('ACTIVE','WITHDRAWN','SUSPENDED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='世界包稳定身份与可变治理信息';

create table world_package_revision (
    id varchar(64) primary key comment '不可变修订内部ID，未来命卷引用目标',
    package_id varchar(64) not null comment '所属包身份',
    revision bigint not null comment '包内修订号，发布时在包行锁内分配',
    format_version integer not null comment '文档格式版本',
    runtime_contract varchar(80) collate utf8mb4_bin not null comment '解释合同',
    required_capabilities_json longtext not null comment '声明式能力集合JSON',
    content_hash char(64) collate utf8mb4_bin not null comment '规范内容SHA256',
    compiled_hash char(64) collate utf8mb4_bin not null comment '确定性编译快照SHA256',
    canonical_json longtext not null comment '不可变规范文档，保留精确身份',
    compiled_json longtext not null comment '不可变编译快照',
    compiler_version varchar(64) collate utf8mb4_bin not null comment '确定性编译器版本',
    status varchar(16) not null comment 'PUBLISHED或WITHDRAWN或SUSPENDED，仅治理状态可变',
    created_by varchar(96) collate utf8mb4_bin not null comment '可信发布主体审计标识，不来自包正文',
    published_at timestamp(6) not null default current_timestamp(6) comment '发布时间',
    unique key uk_world_revision_number (package_id, revision) comment '禁止同一修订号覆盖',
    unique key uk_world_revision_content (package_id, content_hash) comment '禁止相同内容重复增加修订号',
    unique key uk_world_revision_package (id, package_id) comment '幂等结果必须属于同一包的复合外键目标',
    constraint fk_world_revision_package foreign key (package_id) references world_package(id),
    constraint ck_world_revision_number check (revision > 0),
    constraint ck_world_revision_status check (status in ('PUBLISHED','WITHDRAWN','SUSPENDED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='世界包不可变内容与确定性编译修订';

create table world_package_asset (
    revision_id varchar(64) not null comment '所属精确修订',
    asset_path varchar(240) collate utf8mb4_bin not null comment '规范素材路径，非本机或远程地址',
    media_type varchar(64) not null comment '经签名检查的声明MIME，不代表已消毒',
    byte_size bigint not null comment '原件字节数',
    content_hash char(64) collate utf8mb4_bin not null comment '原件SHA256',
    asset_bytes mediumblob not null comment '受限原件，与修订同事务保存，禁止直接公开渲染',
    primary key (revision_id, asset_path) comment '同一修订的素材路径不可重复',
    constraint fk_world_asset_revision foreign key (revision_id) references world_package_revision(id),
    constraint ck_world_asset_size check (byte_size > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='精确世界包素材原件，事务失败无外部孤儿对象';

create table world_package_publication_request (
    package_id varchar(64) not null comment '请求所属包',
    principal_key varchar(96) collate utf8mb4_bin not null comment '可信主体键',
    idempotency_key varchar(128) collate utf8mb4_bin not null comment '调用方重试保持不变的请求键',
    request_hash char(64) collate utf8mb4_bin not null comment '操作模式、精确号及内容哈希摘要',
    revision_id varchar(64) not null comment '已提交结果修订',
    created_at timestamp(6) not null default current_timestamp(6) comment '提交时间',
    primary key (package_id, principal_key, idempotency_key) comment '同包同主体的幂等键只能对应一次请求',
    key idx_world_request_result (revision_id, package_id) comment '保证幂等结果属于请求包',
    constraint fk_world_request_result foreign key (revision_id, package_id) references world_package_revision(id, package_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='世界包发布持久幂等结果，与修订同事务提交';

create table world_package_installation (
    user_id varchar(64) not null comment '持有修订的玩家',
    revision_id varchar(64) not null comment '安装的精确修订',
    installed_at timestamp(6) not null default current_timestamp(6) comment '安装时间，不推导默认项',
    primary key (user_id, revision_id) comment '玩家不能重复安装同一修订',
    key idx_world_install_revision (revision_id) comment '保护被玩家持有的修订',
    constraint fk_world_install_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_world_install_revision foreign key (revision_id) references world_package_revision(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='玩家世界包安装，不等于发布权或创角启用';

create table world_package_creation_scope (
    id varchar(80) primary key comment 'GLOBAL或服务端签发的玩家范围ID',
    user_id varchar(64) comment 'GLOBAL为空，玩家范围绑定账号',
    scope_type varchar(8) not null comment 'GLOBAL或USER',
    unique key uk_world_scope_user (user_id) comment '每个玩家最多一个创角范围',
    constraint fk_world_scope_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint ck_world_scope check ((scope_type='GLOBAL' and id='GLOBAL' and user_id is null) or (scope_type='USER' and id<>'GLOBAL' and user_id is not null))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='独立创角范围与并发默认指针锁';

create table world_package_availability (
    scope_id varchar(80) not null comment '显式启用的范围',
    revision_id varchar(64) not null comment '显式启用的精确修订',
    enabled_at timestamp(6) not null default current_timestamp(6) comment '启用时间',
    primary key (scope_id, revision_id) comment '范围内每个修订仅启用一次，也是默认指针的外键目标',
    key idx_world_available_revision (revision_id) comment '修订引用保护',
    constraint fk_world_available_scope foreign key (scope_id) references world_package_creation_scope(id) on delete cascade,
    constraint fk_world_available_revision foreign key (revision_id) references world_package_revision(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='存在即创角启用，发布和安装均不会自动写入';

create table world_package_creation_default (
    scope_id varchar(80) primary key comment '每个范围最多一个默认项',
    revision_id varchar(64) not null comment '该范围已经启用的精确修订',
    updated_at timestamp(6) not null default current_timestamp(6) comment '显式切换时间',
    constraint fk_world_default_available foreign key (scope_id, revision_id) references world_package_availability(scope_id, revision_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='显式创角默认指针，不随最新修订变化';

create table world_package_audit (
    id varchar(64) primary key comment '随机审计ID',
    actor_key varchar(96) collate utf8mb4_bin not null comment '可信操作主体',
    action varchar(32) not null comment '启用、停用、切换默认等操作',
    scope_id varchar(80) comment '范围标识快照，账号删除不影响审计',
    revision_id varchar(64) comment '操作修订，清空默认可为空',
    created_at timestamp(6) not null default current_timestamp(6) comment '操作提交时间',
    key idx_world_audit_scope_time (scope_id, created_at) comment '追踪默认切换与可用性变化',
    constraint fk_world_audit_revision foreign key (revision_id) references world_package_revision(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='世界包可用性操作审计，与变更同事务';
