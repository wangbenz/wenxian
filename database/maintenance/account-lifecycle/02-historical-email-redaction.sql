-- 受控历史邮箱文本归属和脱敏。只处理能与一个现存账号精确匹配的记录。
set @execute_confirmed = coalesce(@execute_confirmed,'NO');
set @expected_admin_rows = coalesce(@expected_admin_rows,-1);
set @expected_error_rows = coalesce(@expected_error_rows,-1);

drop procedure if exists wenxian_assert_email_redaction;
delimiter //
create procedure wenxian_assert_email_redaction(in condition_ok boolean,in failure_message varchar(255))
begin
    if not condition_ok then signal sqlstate '45000' set message_text=failure_message; end if;
end//
delimiter ;

call wenxian_assert_email_redaction(
    @execute_confirmed='EXECUTE_EMAIL_REDACTION_ON_VERIFIED_COPY',
    '未提供历史邮箱脱敏确认字符串'
);

drop temporary table if exists tmp_admin_email_subject;
create temporary table tmp_admin_email_subject(id varchar(64) primary key,user_id varchar(64) not null) engine=InnoDB;
insert into tmp_admin_email_subject(id,user_id)
select l.id,min(u.id)
from admin_operation_log l join app_user u on instr(lower(l.detail),lower(u.email))>0
where l.detail regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}'
group by l.id having count(distinct u.id)=1;

drop temporary table if exists tmp_error_email_subject;
create temporary table tmp_error_email_subject(reference_id varchar(40) primary key,user_id varchar(64) not null) engine=InnoDB;
insert into tmp_error_email_subject(reference_id,user_id)
select e.reference_id,min(u.id)
from operation_error e join app_user u on instr(lower(e.diagnostic),lower(u.email))>0
where e.diagnostic regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}'
group by e.reference_id having count(distinct u.id)=1;

set @admin_email_rows=(select count(*) from admin_operation_log where detail regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}');
set @error_email_rows=(select count(*) from operation_error where diagnostic regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}');
set @matched_admin_rows=(select count(*) from tmp_admin_email_subject);
set @matched_error_rows=(select count(*) from tmp_error_email_subject);
call wenxian_assert_email_redaction(@admin_email_rows=@expected_admin_rows,'管理日志邮箱样式数量变化');
call wenxian_assert_email_redaction(@error_email_rows=@expected_error_rows,'错误日志邮箱样式数量变化');
call wenxian_assert_email_redaction(@matched_admin_rows=@admin_email_rows,'存在无法唯一归属的管理日志邮箱');
call wenxian_assert_email_redaction(@matched_error_rows=@error_email_rows,'存在无法唯一归属的错误日志邮箱');

drop procedure if exists wenxian_apply_email_redaction;
delimiter //
create procedure wenxian_apply_email_redaction()
begin
declare exit handler for sqlexception
begin
    rollback;
    resignal;
end;
start transaction;
update admin_operation_log l join tmp_admin_email_subject s on s.id=l.id
set l.subject_user_id=s.user_id,l.detail='玩家访问名单历史操作（邮箱已脱敏）';
set @admin_changed=row_count();
update operation_error e join tmp_error_email_subject s on s.reference_id=e.reference_id
set e.subject_user_id=s.user_id,e.diagnostic='历史诊断中的玩家邮箱已按账号生命周期规则脱敏';
set @error_changed=row_count();

set @remaining_admin_email=(select count(*) from admin_operation_log where detail regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}');
set @remaining_error_email=(select count(*) from operation_error where diagnostic regexp '[[:alnum:]._%+-]+@[[:alnum:].-]+\\.[[:alpha:]]{2,}');
call wenxian_assert_email_redaction(@admin_changed=@expected_admin_rows,'管理日志实际更新数不符');
call wenxian_assert_email_redaction(@error_changed=@expected_error_rows,'错误日志实际更新数不符');
call wenxian_assert_email_redaction(@remaining_admin_email=0,'管理日志仍有邮箱样式文本');
call wenxian_assert_email_redaction(@remaining_error_email=0,'错误日志仍有邮箱样式文本');
commit;
end//
delimiter ;

call wenxian_apply_email_redaction();

select 'HISTORICAL_EMAIL_REDACTION_COMPLETED' section,@admin_changed admin_rows,@error_changed error_rows;
drop temporary table tmp_admin_email_subject;
drop temporary table tmp_error_email_subject;
drop procedure wenxian_apply_email_redaction;
drop procedure wenxian_assert_email_redaction;
