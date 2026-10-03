-- 统一 Story v2 结构归属迁移。
-- 目的：让引擎目录与当前 Java/fixture 的 state schema v2 一致，并给回合补丁、修正快照增加结构版本。
-- 历史 JSON 不能统一猜成 v2，因此已有环境新增列允许为空；新写入由应用强制填写。
-- 本脚本不回填历史 JSON、不迁移命卷、不切换引擎、不删除任何数据，可重复执行。

drop procedure if exists wenxian_add_story_schema_column_if_missing;
delimiter //
create procedure wenxian_add_story_schema_column_if_missing(
    in target_table varchar(64),
    in target_column varchar(64),
    in column_definition varchar(1000)
)
begin
    if exists (
        select 1 from information_schema.tables
        where table_schema=database() and table_name=target_table
    ) and not exists (
        select 1 from information_schema.columns
        where table_schema=database() and table_name=target_table and column_name=target_column
    ) then
        set @story_schema_column_ddl = concat(
            'alter table `', replace(target_table, '`', '``'), '` add column ', column_definition
        );
        prepare story_schema_column_statement from @story_schema_column_ddl;
        execute story_schema_column_statement;
        deallocate prepare story_schema_column_statement;
    end if;
end//
delimiter ;

call wenxian_add_story_schema_column_if_missing(
    'story_turn', 'state_schema_version',
    'state_schema_version integer null comment ''产生本轮补丁时的命卷状态结构版本'' after engine_version'
);
call wenxian_add_story_schema_column_if_missing(
    'story_state_correction', 'state_schema_version',
    'state_schema_version integer null comment ''产生本次修正快照时的命卷状态结构版本'' after correction_type'
);

drop procedure wenxian_add_story_schema_column_if_missing;

-- 只把尚未超过 v2 的同一引擎目录前进到 v2，绝不把未知未来版本降级。
update story_engine_catalog
set min_schema_version=2,
    current_schema_version=2,
    updated_at=current_timestamp(6)
where engine_code='LIGHT_NARRATIVE'
  and engine_version=1
  and min_schema_version<=2
  and current_schema_version<=2
  and (min_schema_version<>2 or current_schema_version<>2);
