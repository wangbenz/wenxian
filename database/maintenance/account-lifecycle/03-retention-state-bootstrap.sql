-- 受控存量活动状态回填。只插入缺失账号，不覆盖应用已经写入的 REALTIME 状态，不创建通知或删除任务。
set @execute_confirmed = coalesce(@execute_confirmed,'NO');
set @expected_active_accounts = coalesce(@expected_active_accounts,-1);
set @expected_missing_states = coalesce(@expected_missing_states,-1);

drop procedure if exists wenxian_assert_retention_bootstrap;
delimiter //
create procedure wenxian_assert_retention_bootstrap(in condition_ok boolean,in failure_message varchar(255))
begin
    if not condition_ok then signal sqlstate '45000' set message_text=failure_message; end if;
end//
delimiter ;

call wenxian_assert_retention_bootstrap(
    @execute_confirmed='EXECUTE_RETENTION_BOOTSTRAP_ON_VERIFIED_COPY',
    '未提供存量活动回填确认字符串'
);

drop temporary table if exists tmp_retention_bootstrap;
create temporary table tmp_retention_bootstrap(
    user_id varchar(64) primary key,
    meaningful_at datetime(6) not null
) engine=InnoDB;

insert into tmp_retention_bootstrap(user_id,meaningful_at)
select u.id,greatest(
    coalesce(u.registered_at,'1970-01-01 00:00:00'),
    coalesce(u.last_login_at,u.registered_at),
    coalesce(u.password_set_at,u.registered_at),
    coalesce((select max(e.occurred_at) from analytics_event e where e.user_id=u.id and e.event_type in (
        'USER_REGISTERED','SAVE_CREATED','TURN_SUCCEEDED','BYOK_CREDENTIAL_CONNECTED','BYOK_CREDENTIAL_DELETED',
        'CORRECTION_APPLIED','ENTITY_REMOVED','SAVE_ARCHIVED','SAVE_DELETED'
    )),u.registered_at),
    coalesce((select max(d.last_activity_at) from analytics_user_day d where d.user_id=u.id),u.registered_at),
    coalesce((select max(s.updated_at) from story_save s where s.user_id=u.id),u.registered_at),
    coalesce((select max(s.updated_at) from v014_game_save s where s.user_id=u.id),u.registered_at),
    coalesce((select max(s.updated_at) from game_save s where s.user_id=u.id),u.registered_at),
    coalesce((select max(c.updated_at) from user_ai_provider_credential c where c.user_id=u.id),u.registered_at)
) meaningful_at
from app_user u where u.status='ACTIVE';

set @actual_active_accounts=(select count(*) from tmp_retention_bootstrap);
set @actual_missing_states=(
    select count(*) from tmp_retention_bootstrap b
    left join account_retention_state r on r.user_id=b.user_id where r.user_id is null
);
call wenxian_assert_retention_bootstrap(@actual_active_accounts=@expected_active_accounts,'ACTIVE账号数量变化');
call wenxian_assert_retention_bootstrap(@actual_missing_states=@expected_missing_states,'缺失留存状态数量变化');
call wenxian_assert_retention_bootstrap(
    (select count(*) from tmp_retention_bootstrap where meaningful_at is null)=0,
    '存在无法重建活动时间的账号'
);
call wenxian_assert_retention_bootstrap(
    (select count(*) from account_deletion_notice)=0,
    '回填前已存在通知任务，必须拆分批次'
);
call wenxian_assert_retention_bootstrap(
    (select count(*) from account_deletion_job where reason='INACTIVITY')=0,
    '回填前已存在不活跃删除任务，必须拆分批次'
);

drop procedure if exists wenxian_apply_retention_bootstrap;
delimiter //
create procedure wenxian_apply_retention_bootstrap()
begin
declare exit handler for sqlexception
begin
    rollback;
    resignal;
end;
start transaction;
insert into account_retention_state(
    user_id,last_meaningful_activity_at,activity_source,policy_state,notice_sent_at,scheduled_deletion_at,
    canceled_at,activity_origin,version,updated_at
)
select b.user_id,b.meaningful_at,'LEGACY_BACKFILL','ACTIVE',null,null,null,'BACKFILL',0,current_timestamp(6)
from tmp_retention_bootstrap b
left join account_retention_state r on r.user_id=b.user_id
where r.user_id is null;
set @inserted_states=row_count();
call wenxian_assert_retention_bootstrap(@inserted_states=@expected_missing_states,'实际回填状态数量不符');
commit;
end//
delimiter ;

call wenxian_apply_retention_bootstrap();

select 'RETENTION_STATE_BOOTSTRAP_COMPLETED' section,@actual_active_accounts active_accounts,
       @inserted_states inserted_states,
       (select min(last_meaningful_activity_at) from account_retention_state where activity_origin='BACKFILL') oldest_activity,
       (select max(last_meaningful_activity_at) from account_retention_state where activity_origin='BACKFILL') newest_activity;

drop temporary table tmp_retention_bootstrap;
drop procedure wenxian_apply_retention_bootstrap;
drop procedure wenxian_assert_retention_bootstrap;
