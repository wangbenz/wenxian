-- 无密码历史账号一次性清理候选。只输出聚合，不输出用户 ID 或邮箱。
set session transaction read only;
set @inactivity_cutoff = coalesce(@inactivity_cutoff, current_timestamp(6)-interval 30 day);

select 'PARAMETER' as section,@inactivity_cutoff as inactivity_cutoff;

select 'NO_PASSWORD_FUNNEL' as section,
       count(*) as no_password,
       sum(coalesce(last_login_at,registered_at)<@inactivity_cutoff) as inactive,
       sum(coalesce(last_login_at,registered_at)<@inactivity_cutoff
           and not exists(select 1 from game_save s where s.user_id=u.id)
           and not exists(select 1 from game_life_archive s where s.user_id=u.id)
           and not exists(select 1 from v014_game_save s where s.user_id=u.id)
           and not exists(select 1 from story_save s where s.user_id=u.id)) as without_any_story
from app_user u
where u.status='ACTIVE' and u.password_hash is null;

with candidate as (
    select u.id,u.registered_at
    from app_user u
    where u.status='ACTIVE'
      and u.password_hash is null
      and coalesce(u.last_login_at,u.registered_at)<@inactivity_cutoff
      and not exists(select 1 from game_save s where s.user_id=u.id)
      and not exists(select 1 from game_life_archive s where s.user_id=u.id)
      and not exists(select 1 from v014_game_save s where s.user_id=u.id)
      and not exists(select 1 from story_save s where s.user_id=u.id)
      and not exists(select 1 from user_ai_credential c where c.user_id=u.id)
      and not exists(select 1 from user_ai_provider_credential c where c.user_id=u.id)
      and not exists(select 1 from turn_credit_grant g where g.user_id=u.id)
      and not exists(select 1 from turn_credit_reservation r where r.user_id=u.id)
      and not exists(select 1 from turn_credit_ledger l where l.user_id=u.id)
      and not exists(select 1 from analytics_event e where e.user_id=u.id)
      and not exists(select 1 from game_analytics_event e where e.user_id=u.id)
      and not exists(select 1 from game_access_whitelist w where w.user_id=u.id)
      and not exists(
          select 1 from user_session s
          where s.user_id=u.id and s.revoked_at is null and s.expires_at>current_timestamp(6)
      )
)
select 'STRICT_CANDIDATE' as section,count(*) as candidate_count,
       min(registered_at) as earliest_registration,max(registered_at) as latest_registration
from candidate;

-- 最终候选还必须排除申诉、管理员人工保留和未完成通知；当前表没有结构化玩家主体，脚本无法替代该门禁。
