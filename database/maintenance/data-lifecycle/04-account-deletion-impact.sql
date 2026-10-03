-- 单账号物理删除影响报告。只输出表名和行数，不输出账号、邮箱、剧情或密钥。
-- 调用前设置：set @subject_user_id='user-...';
set session transaction read only;

select 'GUARD' as section,
       case when @subject_user_id is null or @subject_user_id='' then 'BLOCKED_SUBJECT_REQUIRED' else 'READY' end as state,
       (select count(*) from app_user where id=@subject_user_id) as account_count;

select domain_name,row_count from (
    select 'app_user' domain_name,(select count(*) from app_user where id=@subject_user_id) row_count
    union all select 'user_session',(select count(*) from user_session where user_id=@subject_user_id)
    union all select 'auth_registration',(select count(*) from auth_registration where user_id=@subject_user_id)
    union all select 'auth_login_failure',(select count(*) from auth_login_failure f where f.email_hash=(select lower(sha2(email,256)) from app_user where id=@subject_user_id))
    union all select 'user_release_receipt',(select count(*) from user_release_receipt where user_id=@subject_user_id)
    union all select 'game_access_whitelist',(select count(*) from game_access_whitelist where user_id=@subject_user_id)
    union all select 'user_daily_quota',(select count(*) from user_daily_quota where user_id=@subject_user_id)
    union all select 'user_ai_credential',(select count(*) from user_ai_credential where user_id=@subject_user_id)
    union all select 'user_ai_provider_credential',(select count(*) from user_ai_provider_credential where user_id=@subject_user_id)
    union all select 'user_model_preference',(select count(*) from user_model_preference where user_id=@subject_user_id)
    union all select 'v014_user_model_preference',(select count(*) from v014_user_model_preference where user_id=@subject_user_id)
    union all select 'turn_credit_grant',(select count(*) from turn_credit_grant where user_id=@subject_user_id)
    union all select 'turn_credit_reservation',(select count(*) from turn_credit_reservation where user_id=@subject_user_id)
    union all select 'turn_credit_ledger',(select count(*) from turn_credit_ledger where user_id=@subject_user_id)
    union all select 'analytics_event',(select count(*) from analytics_event where user_id=@subject_user_id)
    union all select 'analytics_user_day',(select count(*) from analytics_user_day where user_id=@subject_user_id)
    union all select 'game_analytics_event',(select count(*) from game_analytics_event where user_id=@subject_user_id)
    union all select 'story_save',(select count(*) from story_save where user_id=@subject_user_id)
    union all select 'story_turn',(select count(*) from story_turn where save_id in (select id from story_save where user_id=@subject_user_id))
    union all select 'story_entity',(select count(*) from story_entity where save_id in (select id from story_save where user_id=@subject_user_id))
    union all select 'story_memory',(select count(*) from story_memory where save_id in (select id from story_save where user_id=@subject_user_id))
    union all select 'story_conversation',(select count(*) from story_conversation where save_id in (select id from story_save where user_id=@subject_user_id))
    union all select 'story_state_correction',(select count(*) from story_state_correction where user_id=@subject_user_id)
    union all select 'v014_game_save',(select count(*) from v014_game_save where user_id=@subject_user_id)
    union all select 'game_save',(select count(*) from game_save where user_id=@subject_user_id)
    union all select 'game_life_archive',(select count(*) from game_life_archive where user_id=@subject_user_id)
    union all select 'story_save_migration',(select count(*) from story_save_migration where target_save_id in (select id from story_save where user_id=@subject_user_id) or source_save_id in (
        select id from game_save where user_id=@subject_user_id union select id from v014_game_save where user_id=@subject_user_id
    ))
    union all select 'agent_execution',(select count(*) from agent_execution where save_id in (
        select id from story_save where user_id=@subject_user_id union select id from v014_game_save where user_id=@subject_user_id union select id from game_save where user_id=@subject_user_id
    ))
    union all select 'operation_error',(select count(*) from operation_error where save_id in (
        select id from story_save where user_id=@subject_user_id union select id from v014_game_save where user_id=@subject_user_id union select id from game_save where user_id=@subject_user_id
    ))
    union all select 'ai_usage_log_linked_save',(select count(*) from ai_usage_log where save_id in (
        select id from story_save where user_id=@subject_user_id union select id from v014_game_save where user_id=@subject_user_id union select id from game_save where user_id=@subject_user_id
    ))
    union all select 'admin_log_exact_email_text',(select count(*) from admin_operation_log l where instr(l.detail,(select email from app_user where id=@subject_user_id))>0)
    union all select 'error_exact_email_text',(select count(*) from operation_error e where instr(e.diagnostic,(select email from app_user where id=@subject_user_id))>0)
) impact
order by domain_name;

select 'CROSS_ACCOUNT_PARENT_BLOCKER' as section,count(*) as relationship_count
from story_save child
join story_save parent on parent.id=child.parent_save_id
where parent.user_id=@subject_user_id and child.user_id<>@subject_user_id;

select 'UNATTRIBUTABLE_GLOBAL_BLOCKER' as section,
       (select count(*) from ai_usage_log where save_id is null or save_id='') as ai_usage_without_save,
       (select count(*) from admin_operation_log where detail regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}') as admin_logs_with_email_shape,
       (select count(*) from operation_error where diagnostic regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}') as errors_with_email_shape;
