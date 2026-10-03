-- 模型调用归属与历史邮箱文本治理 preflight。只输出聚合，不读取原文或玩家标识。
set session transaction read only;

select 'AI_USAGE_CLASSIFICATION' as section,
       count(*) as total_rows,
       sum(save_id is null or save_id='') as pre_save_rows,
       sum(save_id is not null and save_id<>'' and exists(
           select 1 from story_save s where s.id=ai_usage_log.save_id
       )) as current_story_linked,
       sum(save_id is not null and save_id<>'' and exists(
           select 1 from v014_game_save s where s.id=ai_usage_log.save_id
       )) as v014_linked,
       sum(save_id is not null and save_id<>'' and exists(
           select 1 from game_save s where s.id=ai_usage_log.save_id
       )) as early_story_linked,
       sum(save_id is not null and save_id<>''
           and not exists(select 1 from story_save s where s.id=ai_usage_log.save_id)
           and not exists(select 1 from v014_game_save s where s.id=ai_usage_log.save_id)
           and not exists(select 1 from game_save s where s.id=ai_usage_log.save_id)) as orphan_save_rows
from ai_usage_log;

select 'PRE_SAVE_OPERATION' as section,operation,status,game_version,count(*) as row_count,
       min(created_at) as first_at,max(created_at) as last_at
from ai_usage_log
where save_id is null or save_id=''
group by operation,status,game_version
order by row_count desc,operation;

select 'SAVE_ID_OVERLAP' as section,
       (select count(*) from story_save a join v014_game_save b on b.id=a.id) as current_v014,
       (select count(*) from story_save a join game_save b on b.id=a.id) as current_early,
       (select count(*) from v014_game_save a join game_save b on b.id=a.id) as v014_early;

select 'ANONYMIZATION_TEXT_RISK' as section,count(*) as candidate_rows,
       sum(error_detail regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}') as email_shape_rows,
       sum(lower(error_detail) regexp 'bearer[[:space:]]+[[:alnum:]_.-]+') as bearer_shape_rows,
       sum(error_detail regexp 'sk-[[:alnum:]_-]{12,}') as key_shape_rows,
       sum(error_detail regexp 'user-[[:alnum:]-]{8,}') as user_id_shape_rows,
       max(char_length(error_detail)) as maximum_detail_length
from ai_usage_log l
where l.save_id is null or l.save_id=''
   or (not exists(select 1 from story_save s where s.id=l.save_id)
       and not exists(select 1 from v014_game_save s where s.id=l.save_id)
       and not exists(select 1 from game_save s where s.id=l.save_id));

select 'ADMIN_EMAIL_MATCH_CLASS' as section,matched_accounts,count(*) as row_count from (
    select l.id,count(distinct u.id) matched_accounts
    from admin_operation_log l
    left join app_user u on instr(lower(l.detail),lower(u.email))>0
    where l.detail regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}'
    group by l.id
) classified group by matched_accounts order by matched_accounts;

select 'ERROR_EMAIL_MATCH_CLASS' as section,matched_accounts,count(*) as row_count from (
    select e.reference_id,count(distinct u.id) matched_accounts
    from operation_error e
    left join app_user u on instr(lower(e.diagnostic),lower(u.email))>0
    where e.diagnostic regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}'
    group by e.reference_id
) classified group by matched_accounts order by matched_accounts;
