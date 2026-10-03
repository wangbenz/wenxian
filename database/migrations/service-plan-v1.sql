-- 三档推演服务方案第一版。
-- 新表只建立版本化配置事实；执行本脚本不会切换 game-api 当前模型路由或玩家扣点路径。

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
    constraint fk_service_plan_based_on foreign key (based_on_version_id)
        references ai_service_plan_version(id),
    unique index uk_service_plan_version_number (version_number),
    unique index uk_service_plan_single_active (active_marker),
    index idx_service_plan_status_created (status, created_at desc)
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
    constraint fk_service_plan_tier_version foreign key (plan_version_id)
        references ai_service_plan_version(id) on delete cascade,
    constraint fk_service_plan_tier_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_service_plan_tier_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code),
    index idx_service_plan_tier_primary (primary_provider_code, primary_model_code),
    index idx_service_plan_tier_fallback (fallback_provider_code, fallback_model_code)
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
    constraint fk_service_plan_task_tier foreign key (plan_version_id, tier_code)
        references ai_service_plan_tier(plan_version_id, tier_code) on delete cascade,
    constraint fk_service_plan_task_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_service_plan_task_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code),
    index idx_service_plan_task_primary (primary_provider_code, primary_model_code),
    index idx_service_plan_task_fallback (fallback_provider_code, fallback_model_code)
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
    constraint fk_service_plan_snapshot_version foreign key (plan_version_id)
        references ai_service_plan_version(id) on delete cascade,
    constraint fk_service_plan_snapshot_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_service_plan_snapshot_validation foreign key (validation_run_id)
        references ai_model_validation_run(id),
    index idx_service_plan_snapshot_validation (validation_run_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='发布方案冻结的模型配置版本与验证证据';
