-- 统一命卷重置 preflight。只读；调用方可以在执行前覆盖 @cutover_at。
set session transaction read only;
set @cutover_at = coalesce(@cutover_at, current_timestamp(6));
set @archive_purge_cutoff = coalesce(@archive_purge_cutoff,@cutover_at-interval 30 day);

select 'PARAMETER' as section,@cutover_at as cutover_at,@archive_purge_cutoff as archive_purge_cutoff;

with candidate as (
    select id,user_id,status,state_schema_version,last_played_release,active_slot_no
    from story_save
    where deleted_at is null and status in ('ACTIVE','ENDED') and created_at < @cutover_at
)
select 'CANDIDATE' as section,status,state_schema_version,last_played_release,
       count(*) as save_count,count(distinct user_id) as user_count,
       sum(active_slot_no is null) as without_active_slot
from candidate
group by status,state_schema_version,last_played_release
order by status,state_schema_version,last_played_release;

select 'CANDIDATE_TOTAL' as section,count(*) as save_count,count(distinct user_id) as user_count
from story_save
where deleted_at is null and status in ('ACTIVE','ENDED') and created_at < @cutover_at;

with candidate as (
    select id from story_save
    where deleted_at is null and status in ('ACTIVE','ENDED') and created_at < @cutover_at
)
select 'CHILD_IMPACT' as section,
       (select count(*) from story_turn t join candidate c on c.id=t.save_id) as turns,
       (select count(*) from story_turn_diagnostic d join candidate c on c.id=d.save_id) as diagnostics,
       (select count(*) from story_entity e join candidate c on c.id=e.save_id) as entities,
       (select count(*) from story_memory m join candidate c on c.id=m.save_id) as memories,
       (select count(*) from story_conversation v join candidate c on c.id=v.save_id) as conversations,
       (select count(*) from story_state_correction x join candidate c on c.id=x.save_id) as corrections;

with candidate as (
    select id from story_save
    where deleted_at is null and status in ('ACTIVE','ENDED') and created_at < @cutover_at
)
select 'IN_FLIGHT_BLOCKER' as section,count(*) as reservation_count
from turn_credit_reservation r
join candidate c on c.id=r.save_id
where r.status not in ('CONSUMED','RELEASED','EXPIRED','CANCELLED');

with candidate as (
    select id,user_id,parent_save_id from story_save
    where deleted_at is null and status in ('ACTIVE','ENDED') and created_at < @cutover_at
)
select 'CROSS_ACCOUNT_PARENT_BLOCKER' as section,count(*) as relationship_count
from story_save child
join candidate parent on parent.id=child.parent_save_id
where child.user_id<>parent.user_id;

select 'ARCHIVED_PURGE_CANDIDATE' as section,coalesce(delete_reason,'<NONE>') as delete_reason,
       count(*) as save_count,count(distinct user_id) as user_count
from story_save
where status='ARCHIVED' and deleted_at<@archive_purge_cutoff
group by coalesce(delete_reason,'<NONE>')
order by delete_reason;

select 'ARCHIVED_MISSING_DELETED_AT_BLOCKER' as section,count(*) as save_count
from story_save
where status='ARCHIVED' and deleted_at is null;
