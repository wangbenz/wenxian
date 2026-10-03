-- 多模型基础设施第一版迁移。
-- 执行前先备份 RDS；本脚本只扩展中性模型表，不修改玩家命卷和回合事实。

-- MySQL 8.0.36 不支持 `add column if not exists`，使用临时过程保证迁移可重复执行。
drop procedure if exists add_model_column_if_missing;
delimiter //
create procedure add_model_column_if_missing(
    in target_table varchar(64),
    in target_column varchar(64),
    in column_definition varchar(1000)
)
begin
    if not exists (
        select 1
        from information_schema.columns
        where table_schema = database()
          and table_name = target_table
          and column_name = target_column
    ) then
        set @model_column_ddl = concat(
            'alter table `', replace(target_table, '`', '``'), '` add column ', column_definition
        );
        prepare model_column_statement from @model_column_ddl;
        execute model_column_statement;
        deallocate prepare model_column_statement;
    end if;
end//
delimiter ;

call add_model_column_if_missing(
    'ai_provider_model',
    'capability_codes',
    'capability_codes varchar(255) not null default ''CHAT,JSON_MODE'' comment ''已验证能力编码，使用英文逗号分隔'' after api_model_name'
);
call add_model_column_if_missing(
    'ai_provider_model',
    'context_window',
    'context_window integer not null default 64000 comment ''模型上下文窗口Token上限'' after capability_codes'
);
call add_model_column_if_missing(
    'ai_provider_model',
    'maximum_output_tokens',
    'maximum_output_tokens integer not null default 8000 comment ''单次请求最大输出Token上限'' after context_window'
);
drop procedure add_model_column_if_missing;

alter table ai_provider_model comment='模型供应商下的可路由模型目录';

-- DeepSeek 当前已在项目真实调用中验证普通对话、JSON、思考关闭和缓存用量返回。
update ai_provider_model
set capability_codes='CHAT,JSON_MODE,THINKING_CONTROL,CACHE_USAGE',
    context_window=128000,
    maximum_output_tokens=8000
where provider_code='DEEPSEEK';

create table if not exists ai_model_task_route (
    task_code varchar(48) primary key comment '稳定模型任务编码',
    primary_provider_code varchar(32) not null comment '主模型供应商编码',
    primary_model_code varchar(80) not null comment '主模型编码',
    fallback_provider_code varchar(32) comment '备用模型供应商编码，可为空',
    fallback_model_code varchar(80) comment '备用模型编码，可为空',
    temperature decimal(4,3) not null default 0.700 comment '任务默认生成温度',
    timeout_seconds integer not null default 90 comment '单次模型调用超时秒数',
    maximum_retries integer not null default 0 comment '客户端内部重试次数，范围0至2',
    maximum_output_tokens integer comment '任务输出Token上限，为空时使用模型默认值',
    disable_thinking boolean not null default true comment '是否要求关闭模型思考模式',
    fallback_enabled boolean not null default false comment '主模型临时失败时是否允许使用备用模型',
    updated_at datetime(6) not null comment '最后更新时间',
    constraint fk_task_route_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_task_route_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='按业务任务配置的平台模型路由';

-- 只在已存在 DeepSeek Flash 目录时创建默认任务路由；后续由管理台逐任务调整。
insert ignore into ai_model_task_route(
    task_code, primary_provider_code, primary_model_code, temperature, timeout_seconds,
    maximum_retries, maximum_output_tokens, disable_thinking, fallback_enabled, updated_at
)
select task_code, 'DEEPSEEK', 'deepseek-v4-flash', temperature, 90, 0, maximum_output_tokens,
       true, false, current_timestamp(6)
from (
    select 'CHARACTER_CREATION' task_code, 0.700 temperature, 2400 maximum_output_tokens union all
    select 'CULTIVATION_DESIGN', 0.700, 2400 union all
    select 'STORY_CORRECTION', 0.100, 2200 union all
    select 'TURN_DIRECTOR', 0.300, 2200 union all
    select 'NARRATIVE_WRITER', 0.800, 3200 union all
    select 'CONTEXT_SUMMARY', 0.200, 1800 union all
    select 'FORMAT_REPAIR', 0.000, 1800
) defaults
where exists (
    select 1 from ai_provider_model
    where provider_code='DEEPSEEK' and model_code='deepseek-v4-flash'
);

-- 将已有玩家模型偏好复制到中性表；不覆盖已经迁移或之后修改的新偏好。
insert ignore into user_model_preference(user_id, provider_code, model_code, credential_source, updated_at)
select user_id, provider_code, model_code, credential_source, updated_at
from v014_user_model_preference;
