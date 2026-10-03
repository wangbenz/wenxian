-- V0.13 紧急修复：玩家可见问题编号与运营问题查询。
-- 先执行本迁移，再启动新版游戏后端与管理台。

create table if not exists operation_error (
    reference_id varchar(40) primary key,
    save_id varchar(80),
    operation varchar(120) not null,
    category varchar(32) not null,
    error_code varchar(64) not null,
    http_status integer not null,
    public_message varchar(600) not null,
    diagnostic text not null,
    created_at timestamp(6) not null,
    index idx_operation_error_created (created_at),
    index idx_operation_error_save (save_id, created_at),
    index idx_operation_error_code (error_code, created_at),
    index idx_operation_error_category (category, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
