-- 管理台模型库第二版：补充配置版本、并发限制和可追溯验证记录。
-- 本脚本只扩展模型目录，不切换当前任务路由，也不把“接口可连接”伪装成任务回放通过。

drop procedure if exists add_model_catalog_column_if_missing;
delimiter //
create procedure add_model_catalog_column_if_missing(
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
        set @model_catalog_ddl = concat(
            'alter table `', replace(target_table, '`', '``'), '` add column ', column_definition
        );
        prepare model_catalog_statement from @model_catalog_ddl;
        execute model_catalog_statement;
        deallocate prepare model_catalog_statement;
    end if;
end//
delimiter ;

call add_model_catalog_column_if_missing(
    'ai_provider_config',
    'config_version',
    'config_version bigint not null default 1 comment ''供应商运行配置版本，Base URL或平台密钥变化时递增'' after user_key_enabled'
);
call add_model_catalog_column_if_missing(
    'ai_provider_model',
    'concurrency_limit',
    'concurrency_limit integer not null default 4 comment ''该模型的平台建议并发上限，不代表供应商实际配额'' after maximum_output_tokens'
);
call add_model_catalog_column_if_missing(
    'ai_provider_model',
    'config_version',
    'config_version bigint not null default 1 comment ''模型运行配置版本，真实API模型名变化时递增'' after user_selectable'
);
drop procedure add_model_catalog_column_if_missing;

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
    constraint fk_model_validation_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code) on delete cascade,
    index idx_model_validation_latest (provider_code, model_code, checked_at desc)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='模型受控验证历史，配置版本变化后旧结果自动失效';
