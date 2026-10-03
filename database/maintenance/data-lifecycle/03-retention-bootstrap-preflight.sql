-- 持续 30 天删除机制首次启用前的活动重建影响。只输出聚合。
set session transaction read only;
set @policy_effective_at = coalesce(@policy_effective_at,current_timestamp(6));

with activity as (
    select u.id,
           greatest(
               coalesce(u.last_login_at,u.registered_at),
               coalesce((select max(d.last_activity_at) from analytics_user_day d where d.user_id=u.id),u.registered_at),
               coalesce((select max(e.occurred_at) from analytics_event e where e.user_id=u.id and e.data_origin='REALTIME'),u.registered_at),
               coalesce((select max(e.created_at) from game_analytics_event e where e.user_id=u.id),u.registered_at),
               coalesce((select max(s.updated_at) from story_save s where s.user_id=u.id),u.registered_at),
               coalesce((select max(s.updated_at) from v014_game_save s where s.user_id=u.id),u.registered_at),
               coalesce((select max(s.updated_at) from game_save s where s.user_id=u.id),u.registered_at),
               coalesce(u.password_set_at,u.registered_at)
           ) as meaningful_at
    from app_user u
    where u.status='ACTIVE'
), schedule as (
    select id,meaningful_at,
           greatest(meaningful_at+interval 30 day,@policy_effective_at+interval 7 day) as scheduled_deletion_at
    from activity
)
select 'RETENTION_BOOTSTRAP' as section,
       count(*) as active_accounts,
       sum(meaningful_at<@policy_effective_at-interval 30 day) as already_over_30d,
       sum(meaningful_at>=@policy_effective_at-interval 30 day
           and meaningful_at<@policy_effective_at-interval 23 day) as notice_window,
       sum(scheduled_deletion_at<=@policy_effective_at) as immediate_delete_count,
       min(scheduled_deletion_at) as earliest_scheduled_deletion
from schedule;

-- immediate_delete_count 必须为 0；否则说明首次生效没有给既有用户完整通知期。
