-- 账号密码与注册审计字段显式迁移。
-- 前置条件：app_user 已存在。脚本只补齐缺失字段，不修改现有密码、账号状态或登录时间，可重复执行。
-- 迁移完成后 game-api 启动不再通过 ALTER TABLE 自动补列。

drop procedure if exists wenxian_add_auth_column_if_missing;
delimiter //
create procedure wenxian_add_auth_column_if_missing(
    in target_column varchar(64),
    in column_definition varchar(1000)
)
begin
    if not exists (
        select 1
        from information_schema.columns
        where table_schema = database()
          and table_name = 'app_user'
          and column_name = target_column
    ) then
        set @auth_column_ddl = concat('alter table app_user add column ', column_definition);
        prepare auth_column_statement from @auth_column_ddl;
        execute auth_column_statement;
        deallocate prepare auth_column_statement;
    end if;
end//
delimiter ;

call wenxian_add_auth_column_if_missing(
    'password_hash',
    'password_hash varchar(100) null comment ''登录密码哈希，禁止保存明文'' after email'
);
call wenxian_add_auth_column_if_missing(
    'password_set_at',
    'password_set_at timestamp(6) null comment ''最近一次设置密码的时间'' after password_hash'
);
call wenxian_add_auth_column_if_missing(
    'registration_ip_hash',
    'registration_ip_hash varchar(128) null comment ''注册来源网络的不可逆摘要'' after password_set_at'
);

drop procedure wenxian_add_auth_column_if_missing;
