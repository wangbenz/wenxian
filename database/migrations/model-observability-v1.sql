-- 中性模型调用观测迁移。
-- 执行前确认 RDS 可恢复点；本脚本只增加调用关联字段和查询索引，不修改命卷、回合和账号事实。
-- 阿里云 RDS MySQL 8.0.36 不支持 `add column if not exists`，因此通过 information_schema 保持可重复执行。

drop procedure if exists add_observability_column_if_missing;
drop procedure if exists add_observability_index_if_missing;
delimiter //
create procedure add_observability_column_if_missing(
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
        set @observability_column_ddl = concat(
            'alter table `', replace(target_table, '`', '``'), '` add column ', column_definition
        );
        prepare observability_column_statement from @observability_column_ddl;
        execute observability_column_statement;
        deallocate prepare observability_column_statement;
    end if;
end//

create procedure add_observability_index_if_missing(
    in target_table varchar(64),
    in target_index varchar(64),
    in index_definition varchar(1000)
)
begin
    if not exists (
        select 1
        from information_schema.statistics
        where table_schema = database()
          and table_name = target_table
          and index_name = target_index
    ) then
        set @observability_index_ddl = concat(
            'create index `', replace(target_index, '`', '``'), '` on `',
            replace(target_table, '`', '``'), '` ', index_definition
        );
        prepare observability_index_statement from @observability_index_ddl;
        execute observability_index_statement;
        deallocate prepare observability_index_statement;
    end if;
end//
delimiter ;

call add_observability_column_if_missing(
    'ai_usage_log', 'request_id',
    'request_id varchar(100) null comment ''同一次业务模型任务的请求编号'' after id'
);
call add_observability_column_if_missing(
    'ai_usage_log', 'user_id',
    'user_id varchar(64) null comment ''用户内部编号，不保存邮箱'' after request_id'
);
call add_observability_column_if_missing(
    'ai_usage_log', 'attempt_number',
    'attempt_number integer not null default 1 comment ''同一请求内的供应商调用序号'' after user_id'
);
call add_observability_column_if_missing(
    'ai_usage_log', 'fallback_used',
    'fallback_used boolean not null default false comment ''本次是否为降级模型调用'' after attempt_number'
);

call add_observability_index_if_missing(
    'ai_usage_log', 'idx_ai_usage_request', '(request_id, attempt_number)'
);
call add_observability_index_if_missing(
    'ai_usage_log', 'idx_ai_usage_user_created', '(user_id, created_at)'
);

drop procedure add_observability_column_if_missing;
drop procedure add_observability_index_if_missing;
