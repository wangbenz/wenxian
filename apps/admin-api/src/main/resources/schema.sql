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
    detail text not null comment '操作摘要',
    subject_user_id varchar(64) comment '操作明确针对玩家时记录其账号ID，账号删除后清理',
    index idx_admin_operation_log_at (operated_at) comment '支持按时间倒序分页查看全部管理操作',
    index idx_admin_operation_log_operation_at (operation, operated_at) comment '支持按操作类型筛选历史记录',
    index idx_admin_operation_log_subject (subject_user_id, operated_at) comment '按玩家定位并清理管理操作中的可识别信息'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists operation_error (
    reference_id varchar(40) primary key,
    save_id varchar(80),
    subject_user_id varchar(64) comment '可确认时记录关联玩家账号ID，账号删除后清理',
    operation varchar(120) not null,
    category varchar(32) not null,
    error_code varchar(64) not null,
    http_status integer not null,
    public_message varchar(600) not null,
    diagnostic text not null,
    created_at timestamp(6) not null,
    index idx_operation_error_created (created_at),
    index idx_operation_error_save (save_id, created_at),
    index idx_operation_error_subject (subject_user_id, created_at),
    index idx_operation_error_code (error_code, created_at),
    index idx_operation_error_category (category, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists ai_usage_log (
    id varchar(80) primary key,
    request_id varchar(100) comment '同一次业务模型任务的请求编号',
    user_id varchar(64) comment '用户内部编号，不保存邮箱',
    attribution_status varchar(24) not null default 'UNRESOLVED' comment '归属状态：ACCOUNT_LINKED、ANONYMOUS_LEGACY或UNRESOLVED',
    attempt_number integer not null default 1 comment '同一请求内的供应商调用序号',
    fallback_used boolean not null default false comment '本次是否为降级模型调用',
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
    index idx_ai_usage_request (request_id, attempt_number),
    index idx_ai_usage_user_created (user_id, created_at),
    index idx_ai_usage_attribution_created (attribution_status, created_at),
    index idx_ai_usage_save (save_id, created_at)
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

create table if not exists ai_provider_config (
    provider_code varchar(32) primary key comment '供应商稳定编码',
    display_name varchar(80) not null comment '管理台展示名称',
    base_url varchar(255) not null comment '供应商接口基础地址',
    adapter_type varchar(32) not null default 'OPENAI_COMPATIBLE' comment '模型协议适配类型',
    platform_secret_ciphertext text comment '平台密钥密文，禁止明文返回',
    enabled boolean not null default true comment '平台是否启用该供应商',
    user_key_enabled boolean not null default true comment '玩家是否可使用该供应商自备密钥',
    config_version bigint not null default 1 comment '供应商运行配置版本，Base URL或平台密钥变化时递增',
    updated_at timestamp(6) not null comment '最后更新时间'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='模型供应商目录';

create table if not exists ai_provider_model (
    provider_code varchar(32) not null comment '供应商稳定编码',
    model_code varchar(80) not null comment '系统内部模型编码',
    display_name varchar(80) not null comment '管理台展示名称',
    api_model_name varchar(120) not null comment '真实接口模型名称',
    capability_codes varchar(255) not null default 'CHAT,JSON_MODE' comment '已验证能力编码，使用英文逗号分隔',
    context_window integer not null default 64000 comment '模型上下文窗口Token上限',
    maximum_output_tokens integer not null default 8000 comment '单次请求最大输出Token上限',
    concurrency_limit integer not null default 4 comment '该模型的平台建议并发上限，不代表供应商实际配额',
    platform_enabled boolean not null default true comment '是否允许平台任务调用',
    user_selectable boolean not null default true comment '是否允许玩家自备模式选择',
    config_version bigint not null default 1 comment '模型运行配置版本，真实API模型名变化时递增',
    updated_at timestamp(6) not null comment '最后更新时间',
    primary key (provider_code, model_code),
    constraint fk_admin_provider_model_provider foreign key (provider_code)
        references ai_provider_config(provider_code) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='模型供应商下的可路由模型目录';

create table if not exists ai_model_task_route (
    task_code varchar(48) primary key comment '稳定模型任务编码',
    primary_provider_code varchar(32) not null comment '主模型供应商编码',
    primary_model_code varchar(80) not null comment '主模型编码',
    fallback_provider_code varchar(32) comment '备用模型供应商编码，可为空',
    fallback_model_code varchar(80) comment '备用模型编码，可为空',
    temperature decimal(4,3) not null default 0.700 comment '任务默认生成温度',
    timeout_seconds integer not null default 90 comment '单次模型调用超时秒数',
    maximum_retries integer not null default 0 comment '客户端内部重试次数',
    maximum_output_tokens integer comment '任务输出Token上限，为空时使用模型默认值',
    disable_thinking boolean not null default true comment '是否要求关闭模型思考模式',
    fallback_enabled boolean not null default false comment '是否允许任务使用备用模型',
    updated_at datetime(6) not null comment '最后更新时间',
    constraint fk_admin_task_route_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_admin_task_route_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='按业务任务配置的平台模型路由';

create table if not exists ai_model_validation_run (
    id varchar(64) primary key comment '验证记录稳定ID',
    provider_code varchar(32) not null comment '被验证模型的供应商编码',
    model_code varchar(80) not null comment '被验证模型的内部编码',
    provider_config_version bigint not null comment '验证时冻结的供应商配置版本',
    model_config_version bigint not null comment '验证时冻结的模型配置版本',
    status varchar(24) not null comment '验证结果：PASSED或FAILED',
    capability_codes varchar(255) not null default '' comment '本次受控验证实际覆盖的能力编码',
    latency_ms integer comment '验证请求耗时毫秒，未发出请求时为空',
    error_code varchar(80) comment '脱敏后的稳定错误码',
    error_summary varchar(500) comment '不包含密钥和原始响应的错误摘要',
    checked_by varchar(64) not null comment '发起验证的管理员或系统标识',
    checked_at timestamp(6) not null comment '验证完成时间',
    constraint fk_admin_model_validation_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code) on delete cascade,
    index idx_admin_model_validation_latest (provider_code, model_code, checked_at desc)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='模型受控验证历史，配置版本变化后旧结果自动失效';

create table if not exists ai_service_plan_counter (
    counter_key varchar(32) primary key comment '计数器稳定编码',
    next_value bigint not null comment '下一可分配方案版本号'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='推演服务方案版本号计数器';

insert ignore into ai_service_plan_counter(counter_key, next_value) values ('SERVICE_PLAN', 1);

create table if not exists ai_service_plan_version (
    id varchar(64) primary key comment '方案版本稳定ID',
    version_number bigint not null comment '运营可读的单调递增版本号',
    status varchar(16) not null comment '版本状态：DRAFT、ACTIVE或RETIRED',
    revision bigint not null default 0 comment '草稿乐观锁版本，每次保存递增',
    default_tier_code varchar(16) not null default 'BALANCED' comment '玩家未选择时使用的默认档位编码',
    change_note varchar(500) not null default '' comment '本版本变更说明',
    based_on_version_id varchar(64) comment '本版本克隆来源，可为空',
    created_by varchar(64) not null comment '创建管理员或系统标识',
    created_at timestamp(6) not null comment '创建时间',
    published_by varchar(64) comment '发布管理员标识',
    published_at timestamp(6) comment '正式发布时间',
    retired_at timestamp(6) comment '退出生效时间',
    active_marker tinyint generated always as (case when status='ACTIVE' then 1 else null end) stored comment '单一生效版本保护位',
    constraint fk_admin_service_plan_based_on foreign key (based_on_version_id)
        references ai_service_plan_version(id),
    unique index uk_admin_service_plan_version_number (version_number),
    unique index uk_admin_service_plan_single_active (active_marker),
    index idx_admin_service_plan_status_created (status, created_at desc)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='不可变发布的三档推演服务方案版本';

create table if not exists ai_service_plan_tier (
    plan_version_id varchar(64) not null comment '所属方案版本ID',
    tier_code varchar(16) not null comment '稳定档位编码：LIGHT、BALANCED或DEEP',
    display_name varchar(40) not null comment '玩家可见档位名称',
    description varchar(240) not null default '' comment '玩家可见档位说明',
    point_cost integer not null comment '一次正式成功回合消耗的推演点',
    enabled boolean not null default true comment '该版本内档位是否可供选择',
    primary_provider_code varchar(32) comment '档位默认主模型供应商，草稿未配置时可为空',
    primary_model_code varchar(80) comment '档位默认主模型，草稿未配置时可为空',
    fallback_provider_code varchar(32) comment '档位默认回退模型供应商，可为空',
    fallback_model_code varchar(80) comment '档位默认回退模型，可为空',
    fallback_enabled boolean not null default false comment '是否允许使用档位默认回退模型',
    primary key (plan_version_id, tier_code),
    constraint fk_admin_service_plan_tier_version foreign key (plan_version_id)
        references ai_service_plan_version(id) on delete cascade,
    constraint fk_admin_service_plan_tier_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_admin_service_plan_tier_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code),
    index idx_admin_service_plan_tier_primary (primary_provider_code, primary_model_code),
    index idx_admin_service_plan_tier_fallback (fallback_provider_code, fallback_model_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='方案版本内固定的轻量、均衡、深度三档';

create table if not exists ai_service_plan_task_route (
    plan_version_id varchar(64) not null comment '所属方案版本ID',
    tier_code varchar(16) not null comment '所属档位编码',
    task_code varchar(48) not null comment '覆盖的稳定模型任务编码',
    primary_provider_code varchar(32) not null comment '任务覆盖主模型供应商编码',
    primary_model_code varchar(80) not null comment '任务覆盖主模型编码',
    fallback_provider_code varchar(32) comment '任务覆盖回退模型供应商，可为空',
    fallback_model_code varchar(80) comment '任务覆盖回退模型，可为空',
    temperature decimal(4,3) not null default 0.700 comment '任务生成温度',
    timeout_seconds integer not null default 90 comment '单次模型调用超时秒数',
    maximum_retries integer not null default 0 comment '基础设施内部重试次数，范围0至2',
    maximum_output_tokens integer comment '任务输出Token上限，为空时使用模型上限',
    disable_thinking boolean not null default true comment '是否要求关闭模型思考模式',
    fallback_enabled boolean not null default false comment '主模型最终失败时是否允许回退',
    primary key (plan_version_id, tier_code, task_code),
    constraint fk_admin_service_plan_task_tier foreign key (plan_version_id, tier_code)
        references ai_service_plan_tier(plan_version_id, tier_code) on delete cascade,
    constraint fk_admin_service_plan_task_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_admin_service_plan_task_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code),
    index idx_admin_service_plan_task_primary (primary_provider_code, primary_model_code),
    index idx_admin_service_plan_task_fallback (fallback_provider_code, fallback_model_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='三档方案内按任务覆盖的模型路由参数';

create table if not exists ai_service_plan_model_snapshot (
    plan_version_id varchar(64) not null comment '已发布方案版本ID',
    provider_code varchar(32) not null comment '发布时选中的供应商编码',
    model_code varchar(80) not null comment '发布时选中的模型编码',
    provider_config_version bigint not null comment '发布校验通过时的供应商配置版本',
    model_config_version bigint not null comment '发布校验通过时的模型配置版本',
    validation_run_id varchar(64) not null comment '发布时采用的受控验证记录ID',
    captured_at timestamp(6) not null comment '证据快照时间',
    primary key (plan_version_id, provider_code, model_code),
    constraint fk_admin_service_plan_snapshot_version foreign key (plan_version_id)
        references ai_service_plan_version(id) on delete cascade,
    constraint fk_admin_service_plan_snapshot_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_admin_service_plan_snapshot_validation foreign key (validation_run_id)
        references ai_model_validation_run(id),
    index idx_admin_service_plan_snapshot_validation (validation_run_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='发布方案冻结的模型配置版本与验证证据';

create table if not exists user_service_tier_preference (
    user_id varchar(64) primary key comment '玩家账号ID',
    tier_code varchar(16) not null comment '稳定档位编码：LIGHT、BALANCED或DEEP',
    updated_at timestamp(6) not null comment '最后一次选择档位的时间',
    constraint fk_admin_user_service_tier_preference_user foreign key (user_id)
        references app_user(id) on delete cascade,
    constraint ck_admin_user_service_tier_preference_code check (tier_code in ('LIGHT','BALANCED','DEEP'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='玩家跨方案版本保留的推演服务档位偏好';

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

create table if not exists user_daily_quota (
    user_id varchar(64) not null,
    usage_date date not null,
    used_count integer not null default 0,
    updated_at timestamp(6) not null,
    primary key (user_id, usage_date),
    index idx_admin_daily_quota_date (usage_date),
    constraint fk_admin_daily_quota_user foreign key (user_id) references app_user(id) on delete cascade
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
    constraint fk_admin_turn_credit_grant_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint ck_admin_turn_credit_grant_nonnegative check (
        granted_count >= 0 and available_count >= 0 and reserved_count >= 0 and consumed_count >= 0
        and expired_count >= 0 and revoked_count >= 0
    ),
    constraint ck_admin_turn_credit_grant_conservation check (
        granted_count = available_count + reserved_count + consumed_count + expired_count + revoked_count
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='账号平台回合赠送批次表';

create table if not exists turn_credit_reservation (
    id varchar(80) primary key comment '平台回合预占ID',
    user_id varchar(64) not null comment '预占玩家账号ID',
    save_id varchar(80) not null comment '发起行动的命卷ID，不依赖具体版本命卷表外键',
    request_id varchar(80) not null comment 'H5行动幂等请求号',
    grant_id varchar(80) not null comment '兼容期首个赠送批次ID，完整来源以预占分摊表为准',
    amount integer not null default 1 comment '请求开始时冻结的推演点总数',
    service_plan_version_id varchar(64) comment '请求开始时冻结的三档方案版本ID；旧回合兼容记录为空',
    tier_code varchar(16) comment '请求开始时冻结的档位编码：LIGHT、BALANCED或DEEP；旧回合兼容记录为空',
    status varchar(24) not null comment '预占状态：RESERVED、CONSUMED、RELEASED或EXPIRED',
    expires_at datetime(6) not null comment '异常中断后允许自动释放的时间',
    turn_id varchar(80) comment '成功消费后关联的正式回合ID',
    created_at datetime(6) not null comment '预占时间',
    updated_at datetime(6) not null comment '最后状态变化时间',
    unique key uk_turn_credit_reservation_request (user_id, save_id, request_id),
    index idx_turn_credit_reservation_expiry (status, expires_at)
        comment '清理超时预占，避免进程异常永久占用余额',
    index idx_turn_credit_reservation_grant (grant_id, status),
    constraint fk_admin_turn_credit_reservation_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_admin_turn_credit_reservation_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint fk_admin_turn_credit_reservation_tier foreign key (service_plan_version_id, tier_code)
        references ai_service_plan_tier(plan_version_id, tier_code),
    constraint ck_admin_turn_credit_reservation_amount check (amount > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='平台推演点跨进程临时预占表';

create table if not exists turn_credit_reservation_allocation (
    reservation_id varchar(80) not null comment '所属推演点预占ID',
    grant_id varchar(80) not null comment '实际提供点数的赠送批次ID',
    amount integer not null comment '从该批次预占的推演点数量',
    primary key (reservation_id, grant_id),
    index idx_admin_turn_credit_allocation_grant (grant_id, reservation_id),
    constraint fk_admin_turn_credit_allocation_reservation foreign key (reservation_id)
        references turn_credit_reservation(id) on delete cascade,
    constraint fk_admin_turn_credit_allocation_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint ck_admin_turn_credit_allocation_amount check (amount > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
  comment='一次推演点预占在多个赠送批次上的不可变分摊';

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
    constraint fk_admin_turn_credit_ledger_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_admin_turn_credit_ledger_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint fk_admin_turn_credit_ledger_reservation foreign key (reservation_id) references turn_credit_reservation(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='平台回合不可变审计流水表';

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
