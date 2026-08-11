-- 统一命卷四槽位与逻辑删除迁移。
-- 发布顺序：先执行本脚本，再部署读取 deleted_at / active_slot_no 的后端。

alter table story_save
    add column active_slot_no smallint null comment '当前命卷列表槽位1至4，逻辑删除后置空并允许复用' after slot_no,
    add column deleted_at datetime(6) null comment '玩家逻辑删除时间，非空记录不再向H5返回' after updated_at,
    add column deleted_by varchar(24) null comment '删除来源：PLAYER、ADMIN或SYSTEM' after deleted_at,
    add column delete_reason varchar(48) null comment '删除原因编码，不保存玩家隐私文本' after deleted_by;

-- 旧封存记录转为逻辑删除；旧死亡状态转成长期可理解的自然终局状态。
update story_save
set deleted_at=coalesce(deleted_at,updated_at), deleted_by='SYSTEM', delete_reason='LEGACY_ARCHIVED',
    status='ACTIVE', active_slot_no=null
where status='ARCHIVED';

update story_save set status='ENDED' where status='DEAD';

create temporary table tmp_story_active_slot as
select id, row_number() over(partition by user_id order by slot_no,id) as active_slot_no
from story_save
where deleted_at is null and status in ('ACTIVE','ENDED');

update story_save target
join tmp_story_active_slot ranked on ranked.id=target.id
set target.active_slot_no=ranked.active_slot_no;

drop temporary table tmp_story_active_slot;

alter table story_save
    add unique key uk_story_save_user_active_slot (user_id,active_slot_no),
    drop index idx_story_save_user_status,
    add index idx_story_save_user_status (user_id,deleted_at,status,updated_at)
        comment '优先读取账号未删除命卷';

update gameplay_config
set config_value='4', updated_at=current_timestamp(6)
where config_key='max_active_saves' and cast(config_value as unsigned) < 4;
