-- 统一命卷平台第一版结构。
-- 本脚本只创建中性业务表，不迁移旧数据，也不切换 V0.14 的现有写入路径。

create table if not exists story_engine_catalog (
    engine_code varchar(40) not null comment '故事引擎编码，与产品发布版本无关',
    engine_version integer not null comment '同一引擎的规则版本',
    display_name varchar(80) not null comment '管理台展示名称',
    status varchar(24) not null comment '引擎状态：DRAFT、GRAY、ACTIVE、READ_ONLY或RETIRED',
    min_schema_version integer not null comment '该引擎能够读取的最低状态结构版本',
    current_schema_version integer not null comment '该引擎当前写入的状态结构版本',
    new_save_enabled boolean not null default false comment '是否允许创建新命卷',
    continuation_enabled boolean not null default false comment '是否允许继续已有命卷',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '最后更新时间',
    primary key (engine_code, engine_version)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='故事引擎目录';

create table if not exists story_save (
    id varchar(80) primary key comment '统一命卷ID',
    user_id varchar(64) not null comment '所属玩家账号ID',
    slot_no smallint not null comment '账号内展示槽位',
    status varchar(24) not null comment '命卷状态：ACTIVE、ARCHIVED、DEAD、MIGRATING或MIGRATION_FAILED',
    world_code varchar(40) not null comment '当前世界编码',
    engine_code varchar(40) not null comment '负责解释和推进该命卷的故事引擎编码',
    engine_version integer not null comment '故事引擎规则版本',
    state_schema_version integer not null comment '核心状态JSON结构版本',
    source_type varchar(24) not null comment '来源类型：NATIVE、MIGRATED_V014或FORKED_V013',
    source_edition varchar(32) comment '最初来源产品版本，仅供追溯',
    source_save_id varchar(80) comment '旧系统原始命卷ID',
    parent_save_id varchar(80) comment '承接或复制来源的统一命卷ID',
    state_json longtext not null comment '主角、世界时间、地点和当前场景等核心权威快照',
    revision bigint not null default 1 comment '乐观锁版本号，每次成功写入递增',
    turn_number integer not null default 0 comment '当前正式回合数',
    created_release varchar(32) not null comment '创建命卷时的产品发布版本',
    last_played_release varchar(32) not null comment '最后推进命卷时的产品发布版本',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '最后变化时间',
    unique key uk_story_save_user_slot (user_id, slot_no),
    unique key uk_story_save_source (source_type, source_save_id),
    key idx_story_save_user_status (user_id, status, updated_at),
    key idx_story_save_engine (engine_code, engine_version, status),
    constraint fk_story_save_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_story_save_parent foreign key (parent_save_id) references story_save(id),
    constraint fk_story_save_engine foreign key (engine_code, engine_version)
        references story_engine_catalog(engine_code, engine_version)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷主表';

create table if not exists story_turn (
    id varchar(80) primary key comment '回合记录ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    request_id varchar(80) not null comment '客户端幂等请求号，网络重试必须复用',
    turn_number integer not null comment '命卷内正式回合序号',
    player_action text not null comment '玩家确认提交的行动',
    narrative_text longtext not null comment '本轮生成并已提交的剧情正文',
    choices_json longtext not null comment '本轮结束后的推荐行动JSON',
    state_patch_json longtext not null comment 'Agent输出并经服务端归一化的状态补丁JSON',
    world_time_before bigint not null comment '回合前世界时间分钟数',
    world_time_after bigint not null comment '回合后世界时间分钟数',
    engine_code varchar(40) not null comment '实际处理本轮的故事引擎编码',
    engine_version integer not null comment '实际处理本轮的故事引擎版本',
    prompt_version varchar(64) not null comment '本轮Prompt与上下文策略版本',
    provider varchar(32) not null comment '内部模型供应商编码，仅供运营查询',
    model varchar(120) not null comment '内部模型编码，仅供运营查询',
    credential_source varchar(20) not null comment '密钥来源：PUBLIC或USER',
    prompt_tokens integer not null default 0 comment '输入Token数量',
    completion_tokens integer not null default 0 comment '输出Token数量',
    cache_hit_tokens integer not null default 0 comment '供应商返回的缓存命中Token数量',
    cache_miss_tokens integer not null default 0 comment '供应商返回的缓存未命中Token数量',
    latency_ms bigint not null default 0 comment '模型调用与解析总延迟毫秒数',
    release_version varchar(32) not null comment '产生本轮时的产品发布版本',
    created_at datetime(6) not null comment '成功提交时间',
    unique key uk_story_turn_save_number (save_id, turn_number),
    unique key uk_story_turn_save_request (save_id, request_id),
    key idx_story_turn_save_created (save_id, created_at),
    key idx_story_turn_release_created (release_version, created_at),
    constraint fk_story_turn_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷不可变回合事实表';

create table if not exists story_entity (
    id varchar(80) primary key comment '命卷内稳定实体ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    entity_type varchar(24) not null comment '实体类型：ITEM、ABILITY、CHARACTER、FACTION或LOCATION',
    name varchar(120) not null comment '实体当前名称',
    status varchar(80) not null comment '实体当前自然状态编码或简短状态',
    visible boolean not null default true comment '是否进入玩家当前列表和常规Prompt',
    summary text not null comment '跨回合需要保持一致的当前事实摘要',
    attributes_json longtext not null comment '不同实体的可扩展属性JSON',
    first_turn integer not null comment '首次成为正式实体的回合',
    last_changed_turn integer not null comment '最后发生状态变化的回合',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '最后更新时间',
    key idx_story_entity_save_visible (save_id, visible, entity_type),
    key idx_story_entity_save_name (save_id, name),
    constraint fk_story_entity_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷当前实体表';

create table if not exists story_memory (
    id varchar(80) primary key comment '关键记忆ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    memory_type varchar(24) not null comment '记忆类型：EVENT、PROMISE、RELATIONSHIP或CLUE',
    importance varchar(24) not null comment '重要程度：KEY、IMPORTANT或NORMAL',
    title varchar(160) not null comment '记忆标题',
    summary text not null comment '跨回合需要保留的事实摘要',
    occurred_turn integer not null comment '记忆发生的回合',
    world_time bigint not null comment '记忆发生时的世界时间分钟数',
    status varchar(24) not null comment '记忆状态：ACTIVE、RESOLVED或HISTORICAL',
    created_at datetime(6) not null comment '创建时间',
    key idx_story_memory_save_status (save_id, status, importance),
    constraint fk_story_memory_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷关键记忆表';

create table if not exists story_conversation (
    save_id varchar(80) primary key comment '所属统一命卷ID',
    rolling_summary longtext not null comment '可重建的滚动上下文摘要，不是权威事实',
    summarized_through_turn integer not null default 0 comment '摘要已经覆盖到的回合',
    summary_schema_version integer not null default 1 comment '摘要结构版本',
    updated_at datetime(6) not null comment '最后更新时间',
    constraint fk_story_conversation_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷上下文摘要表';

create table if not exists story_state_correction (
    id varchar(80) primary key comment '命卷修正记录ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    user_id varchar(64) not null comment '执行修正的玩家账号ID',
    instruction_text text not null comment '玩家确认的修正说明',
    correction_type varchar(32) not null comment '修正分类：CORE、ITEM、ABILITY、CHARACTER或OTHER',
    before_json longtext not null comment '修正前结构化值',
    after_json longtext not null comment '修正后结构化值',
    created_at datetime(6) not null comment '修正时间',
    key idx_story_correction_save (save_id, created_at),
    constraint fk_story_correction_save foreign key (save_id) references story_save(id) on delete cascade,
    constraint fk_story_correction_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷玩家修正审计表';

create table if not exists story_save_migration (
    id varchar(80) primary key comment '迁移任务ID',
    source_engine varchar(40) not null comment '原始数据来源标识',
    source_save_id varchar(80) not null comment '原始命卷ID',
    target_engine varchar(40) not null comment '目标故事引擎编码',
    target_save_id varchar(80) comment '成功后生成的统一命卷ID',
    source_schema_version integer not null comment '原始状态结构版本',
    target_schema_version integer not null comment '目标状态结构版本',
    status varchar(24) not null comment '迁移状态：PENDING、RUNNING、SUCCESS、FAILED或HASH_MISMATCH',
    before_hash varchar(128) not null comment '迁移前关键数据哈希',
    after_hash varchar(128) comment '迁移后关键数据哈希',
    error_message varchar(1000) comment '脱敏后的失败原因',
    started_at datetime(6) comment '迁移开始时间',
    completed_at datetime(6) comment '迁移完成时间',
    unique key uk_story_migration_source (source_engine, source_save_id),
    key idx_story_migration_status (status, started_at),
    constraint fk_story_migration_target foreign key (target_save_id) references story_save(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷迁移审计表';

create table if not exists user_model_preference (
    user_id varchar(64) primary key comment '玩家账号ID',
    provider_code varchar(32) not null comment '玩家自备模式选择的供应商编码',
    model_code varchar(80) not null comment '玩家自备模式选择的模型编码',
    credential_source varchar(20) not null comment '当前生成方式：PUBLIC或USER',
    updated_at datetime(6) not null comment '最后更新时间',
    constraint fk_user_model_preference_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_user_model_preference_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='玩家统一模型偏好表';

insert ignore into story_engine_catalog(
    engine_code, engine_version, display_name, status, min_schema_version, current_schema_version,
    new_save_enabled, continuation_enabled, created_at, updated_at
) values (
    'LIGHT_NARRATIVE', 1, '轻叙事引擎', 'DRAFT', 1, 1, false, false,
    current_timestamp(6), current_timestamp(6)
);
