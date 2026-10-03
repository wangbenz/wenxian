-- M2 生产数据生命周期快照：只输出聚合数字，不读取玩家明细。
set session transaction read only;

select 'ENVIRONMENT' as section, database() as database_name, version() as mysql_version,
       current_timestamp(6) as observed_at,
       (select count(*) from information_schema.tables where table_schema=database()) as table_count;

select 'ACCOUNT' as section, status, count(*) as account_count,
       sum(password_hash is null) as no_password_count,
       sum(coalesce(last_login_at,registered_at) < current_timestamp(6)-interval 30 day) as login_age_over_30d
from app_user
group by status
order by status;

select 'STORY' as section, status, state_schema_version, count(*) as save_count
from story_save
group by status,state_schema_version
order by status,state_schema_version;

select 'STORY_LIFECYCLE' as section,
       sum(status='ACTIVE' and active_slot_no is null) as active_without_slot,
       sum(status='ARCHIVED' and active_slot_no is not null) as archived_with_slot,
       sum(status='ARCHIVED' and deleted_at is null) as archived_without_deleted_at
from story_save;

select 'SAVE_FAMILY' as section,
       (select count(*) from game_save) as early_save_count,
       (select count(*) from game_life_archive) as early_archive_count,
       (select count(*) from v014_game_save) as v014_save_count,
       (select count(*) from story_save) as unified_save_count;
