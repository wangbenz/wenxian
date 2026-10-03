-- 旧表退役 preflight。table_rows 为 InnoDB 估算，只用于容量规划，不替代精确对账。
set session transaction read only;

select case
           when table_name in ('game_save','game_turn','game_turn_request','agent_conversation','agent_message','game_life_archive','game_life_turn_archive','game_analytics_event') then 'G1_EARLY'
           when table_name in ('v014_game_save','v014_game_turn','v014_conversation','v014_state_correction','v014_user_model_preference') then 'G2_V014'
           when table_name in ('user_ai_credential','ai_model_config') then 'G3_MODEL_BRIDGE'
       end as retirement_group,
       table_name,table_rows as estimated_rows,
       round((data_length+index_length)/1024/1024,2) as estimated_megabytes
from information_schema.tables
where table_schema=database() and table_name in (
    'game_save','game_turn','game_turn_request','agent_conversation','agent_message','game_life_archive','game_life_turn_archive','game_analytics_event',
    'v014_game_save','v014_game_turn','v014_conversation','v014_state_correction','v014_user_model_preference',
    'user_ai_credential','ai_model_config'
)
order by retirement_group,table_name;

select 'MIGRATION_MAPPING' as section,source_engine,status,count(*) as mapping_count,
       sum(target_save_id is null) as without_target
from story_save_migration
group by source_engine,status
order by source_engine,status;

select 'V014_WITHOUT_MAPPING' as section,s.status,count(*) as save_count
from v014_game_save s
left join story_save_migration m on m.source_engine='V014' and m.source_save_id=s.id
where m.id is null
group by s.status;

select 'FOREIGN_KEY_ORDER' as section,k.table_name,k.column_name,k.referenced_table_name,r.delete_rule
from information_schema.key_column_usage k
join information_schema.referential_constraints r
  on r.constraint_schema=k.constraint_schema and r.constraint_name=k.constraint_name and r.table_name=k.table_name
where k.constraint_schema=database()
  and (k.table_name in ('game_save','game_turn','game_turn_request','agent_conversation','agent_message','game_life_archive','game_life_turn_archive','v014_game_save','v014_game_turn','v014_conversation','v014_state_correction')
       or k.referenced_table_name in ('game_save','agent_conversation','game_life_archive','v014_game_save'))
order by k.referenced_table_name,k.table_name;

-- 代码引用、近30天 SQL 日志、备份恢复和统计对账不是数据库内单条 SQL 可以证明的，必须作为外部证据附在退役审批中。
