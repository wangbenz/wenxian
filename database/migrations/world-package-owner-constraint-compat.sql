-- 世界包所有者约束的 MySQL 兼容修正，仅用于已经存在注册表的环境。
-- 新库直接执行修正后的 world-package-registry-v1.sql，不需要执行本脚本。
-- 前置：只读核对 world_package、app_user、fk_world_package_owner 及字段排序规则；
-- 如此前为绕过建表失败而人工改过外键，本脚本恢复约定的 ON DELETE SET NULL。
-- 移除 CHECK 不授予发布权限：OFFICIAL 的空 owner 由可信身份登记保证，玩家发布仍检查 USER 和所有者。
-- DDL 隐式提交且需要元数据锁；暂停世界包写入和账号删除后在维护窗口执行。
-- 外键修正分开 DROP/ADD，同名 DROP/ADD 在一个 ALTER 中会被 MySQL 拒绝；中断后先核对再续跑。
-- 只改约束，不改内容、默认项或命卷；重复执行跳过正确结构，回滚不恢复冲突 CHECK。
set @wenxian_world_owner_ddl = if(exists(select 1 from information_schema.table_constraints
              where constraint_schema=database() and table_name='world_package'
                and constraint_name='ck_world_package_official_owner' and constraint_type='CHECK'),
    'alter table world_package drop check ck_world_package_official_owner', 'select 1'
);
prepare wenxian_world_owner_statement from @wenxian_world_owner_ddl;
execute wenxian_world_owner_statement;
deallocate prepare wenxian_world_owner_statement;

set @wenxian_world_owner_ddl = if(exists(select 1 from information_schema.referential_constraints
              where constraint_schema=database() and table_name='world_package'
                and constraint_name='fk_world_package_owner' and delete_rule<>'SET NULL'),
    'alter table world_package drop foreign key fk_world_package_owner', 'select 1'
);
prepare wenxian_world_owner_statement from @wenxian_world_owner_ddl;
execute wenxian_world_owner_statement;
deallocate prepare wenxian_world_owner_statement;

set @wenxian_world_owner_ddl = if(not exists(select 1 from information_schema.referential_constraints
              where constraint_schema=database() and table_name='world_package'
                and constraint_name='fk_world_package_owner'),
    'alter table world_package add constraint fk_world_package_owner foreign key (owner_user_id) references app_user(id) on delete set null',
    'select 1'
);
prepare wenxian_world_owner_statement from @wenxian_world_owner_ddl;
execute wenxian_world_owner_statement;
deallocate prepare wenxian_world_owner_statement;
