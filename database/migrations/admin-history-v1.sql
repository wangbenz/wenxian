-- 为管理操作记录补齐历史分页和操作类型筛选所需索引。
-- 前置条件：admin_operation_log 已存在，且以下索引尚未创建；脚本不修改或删除任何审计记录。
alter table admin_operation_log
    add index idx_admin_operation_log_at (operated_at)
        comment '支持按时间倒序分页查看全部管理操作',
    add index idx_admin_operation_log_operation_at (operation, operated_at)
        comment '支持按操作类型筛选历史记录';
