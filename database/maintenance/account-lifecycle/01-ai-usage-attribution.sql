-- 受控历史模型日志归属/匿名化。必须先执行 account-lifecycle-v1.sql 和只读 06 preflight。
-- 下列期望值必须来自同一目标库、同一维护窗口开始前的最新只读结果。
set @execute_confirmed = coalesce(@execute_confirmed,'NO');
set @expected_unresolved_total = coalesce(@expected_unresolved_total,-1);
set @expected_linkable_rows = coalesce(@expected_linkable_rows,-1);
set @expected_pre_save_rows = coalesce(@expected_pre_save_rows,-1);
set @expected_orphan_rows = coalesce(@expected_orphan_rows,-1);

drop procedure if exists wenxian_assert_account_maintenance;
delimiter //
create procedure wenxian_assert_account_maintenance(in condition_ok boolean,in failure_message varchar(255))
begin
    if not condition_ok then signal sqlstate '45000' set message_text=failure_message; end if;
end//
delimiter ;

call wenxian_assert_account_maintenance(
    @execute_confirmed='EXECUTE_AI_USAGE_ATTRIBUTION_ON_VERIFIED_COPY',
    '未提供模型日志治理确认字符串'
);

drop temporary table if exists tmp_account_lifecycle_save_owner;
create temporary table tmp_account_lifecycle_save_owner(
    save_id varchar(80) primary key,
    user_id varchar(64) not null
) engine=InnoDB;
insert into tmp_account_lifecycle_save_owner(save_id,user_id)
select id,user_id from story_save
union all select id,user_id from v014_game_save
union all select id,user_id from game_save;

set @actual_unresolved_total=(select count(*) from ai_usage_log where attribution_status='UNRESOLVED');
set @actual_linkable_rows=(
    select count(*) from ai_usage_log l join tmp_account_lifecycle_save_owner o on o.save_id=l.save_id
    where l.attribution_status='UNRESOLVED'
);
set @actual_pre_save_rows=(
    select count(*) from ai_usage_log
    where attribution_status='UNRESOLVED' and (save_id is null or save_id='')
);
set @actual_orphan_rows=(
    select count(*) from ai_usage_log l
    left join tmp_account_lifecycle_save_owner o on o.save_id=l.save_id
    where l.attribution_status='UNRESOLVED' and l.save_id is not null and l.save_id<>'' and o.save_id is null
);
set @unsafe_pre_save_operations=(
    select count(*) from ai_usage_log
    where attribution_status='UNRESOLVED' and (save_id is null or save_id='')
      and operation not in ('V014_CHARACTER_ROUTES','CULTIVATION_RECOMMENDATION')
);
set @unsafe_anonymous_text=(
    select count(*) from ai_usage_log l
    left join tmp_account_lifecycle_save_owner o on o.save_id=l.save_id
    where l.attribution_status='UNRESOLVED' and (l.save_id is null or l.save_id='' or o.save_id is null)
      and (l.error_detail regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}'
        or lower(l.error_detail) regexp 'bearer[[:space:]]+[[:alnum:]_.-]+'
        or l.error_detail regexp 'sk-[[:alnum:]_-]{12,}'
        or l.error_detail regexp 'user-[[:alnum:]-]{8,}')
);

call wenxian_assert_account_maintenance(@actual_unresolved_total=@expected_unresolved_total,'未解决日志总数变化');
call wenxian_assert_account_maintenance(@actual_linkable_rows=@expected_linkable_rows,'可归属日志数量变化');
call wenxian_assert_account_maintenance(@actual_pre_save_rows=@expected_pre_save_rows,'命卷创建前日志数量变化');
call wenxian_assert_account_maintenance(@actual_orphan_rows=@expected_orphan_rows,'孤立命卷日志数量变化');
call wenxian_assert_account_maintenance(
    @actual_unresolved_total=@actual_linkable_rows+@actual_pre_save_rows+@actual_orphan_rows,
    '日志分类不能完整覆盖未解决记录'
);
call wenxian_assert_account_maintenance(@unsafe_pre_save_operations=0,'出现未批准的无命卷任务类型');
call wenxian_assert_account_maintenance(@unsafe_anonymous_text=0,'待匿名日志出现可识别文本形态');

drop procedure if exists wenxian_apply_ai_usage_attribution;
delimiter //
create procedure wenxian_apply_ai_usage_attribution()
begin
declare exit handler for sqlexception
begin
    rollback;
    resignal;
end;
start transaction;

update ai_usage_log l
join tmp_account_lifecycle_save_owner o on o.save_id=l.save_id
set l.user_id=o.user_id,l.attribution_status='ACCOUNT_LINKED'
where l.attribution_status='UNRESOLVED';
set @linked_changed=row_count();

update ai_usage_log
set user_id=null,save_id=null,attribution_status='ANONYMOUS_LEGACY',
    error_detail=case when status='SUCCESS' then '' else 'LEGACY_PROVIDER_FAILURE_REDACTED' end
where attribution_status='UNRESOLVED' and (save_id is null or save_id='');
set @pre_save_changed=row_count();

update ai_usage_log l
left join tmp_account_lifecycle_save_owner o on o.save_id=l.save_id
set l.user_id=null,l.save_id=null,l.attribution_status='ANONYMOUS_LEGACY',
    l.error_detail=case when l.status='SUCCESS' then '' else 'LEGACY_PROVIDER_FAILURE_REDACTED' end
where l.attribution_status='UNRESOLVED' and l.save_id is not null and l.save_id<>'' and o.save_id is null;
set @orphan_changed=row_count();

set @remaining_unresolved=(select count(*) from ai_usage_log where attribution_status='UNRESOLVED');
set @invalid_account_link=(select count(*) from ai_usage_log where attribution_status='ACCOUNT_LINKED' and (user_id is null or user_id=''));
set @invalid_anonymous_link=(select count(*) from ai_usage_log where attribution_status='ANONYMOUS_LEGACY' and (user_id is not null or save_id is not null));
call wenxian_assert_account_maintenance(@linked_changed=@expected_linkable_rows,'可归属日志实际更新数不符');
call wenxian_assert_account_maintenance(@pre_save_changed=@expected_pre_save_rows,'命卷创建前日志实际更新数不符');
call wenxian_assert_account_maintenance(@orphan_changed=@expected_orphan_rows,'孤立日志实际更新数不符');
call wenxian_assert_account_maintenance(@remaining_unresolved=0,'仍存在未解决模型日志');
call wenxian_assert_account_maintenance(@invalid_account_link=0,'账号归属记录缺少账号ID');
call wenxian_assert_account_maintenance(@invalid_anonymous_link=0,'匿名记录仍保留账号或命卷ID');

commit;
end//
delimiter ;

call wenxian_apply_ai_usage_attribution();
select 'AI_USAGE_ATTRIBUTION_COMPLETED' section,@linked_changed account_linked,
       @pre_save_changed pre_save_anonymized,@orphan_changed orphan_anonymized,@remaining_unresolved unresolved;

drop temporary table tmp_account_lifecycle_save_owner;
drop procedure wenxian_apply_ai_usage_attribution;
drop procedure wenxian_assert_account_maintenance;
