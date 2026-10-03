-- 只读 MySQL preflight；需单独授权后在指定环境执行。本文件不连接数据库、不创建或修改任何数据。
-- 第一组在迁移前后均可运行，保存结果及迁移脚本 SHA256。核对 MySQL >= 8.0.16、InnoDB、字符集和完整约束。
select version() as mysql_version, database() as database_name;
select table_name, engine, table_collation, table_comment
from information_schema.tables
where table_schema=database() and (table_name='app_user' or table_name like 'world\_package%');
select table_name, column_name, column_type, is_nullable, collation_name, column_comment
from information_schema.columns
where table_schema=database() and (table_name like 'world\_package%' or (table_name='app_user' and column_name='id'))
order by table_name, ordinal_position;
select table_name, index_name, non_unique, seq_in_index, column_name, index_comment
from information_schema.statistics
where table_schema=database() and table_name like 'world\_package%'
order by table_name,index_name,seq_in_index;
select k.table_name,k.constraint_name,k.column_name,k.referenced_table_name,k.referenced_column_name,r.delete_rule,r.update_rule
from information_schema.key_column_usage k
join information_schema.referential_constraints r on r.constraint_schema=k.constraint_schema and r.constraint_name=k.constraint_name
where k.table_schema=database() and k.table_name like 'world\_package%'
order by k.table_name,k.constraint_name,k.ordinal_position;
select table_name,constraint_name,constraint_type
from information_schema.table_constraints
where table_schema=database() and table_name like 'world\_package%';

-- 第二组仅在结构已经核对完成后单独执行。无按最高号或发布时间选默认的逻辑。
select p.publisher_namespace,p.package_key,r.revision,r.content_hash,r.runtime_contract,r.compiler_version,
       p.status as package_status,r.status as revision_status,a.scope_id,
       case when d.revision_id=r.id then 1 else 0 end as is_explicit_default
from world_package p join world_package_revision r on r.package_id=p.id
left join world_package_availability a on a.revision_id=r.id and a.scope_id='GLOBAL'
left join world_package_creation_default d on d.scope_id=a.scope_id
order by p.publisher_namespace,p.package_key,r.revision;
select count(*) as missing_or_mismatched_asset_bytes
from world_package_asset where octet_length(asset_bytes)<>byte_size or sha2(asset_bytes,256)<>content_hash;
