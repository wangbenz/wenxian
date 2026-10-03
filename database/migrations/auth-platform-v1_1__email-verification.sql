-- 注册邮箱验证码挑战表。
-- 前置条件：账号认证基础结构已经存在；本脚本不读取或修改 app_user，不发送邮件，不回填历史账号。
-- 可重复执行：只使用 CREATE TABLE IF NOT EXISTS。回滚优先关闭验证码开关并保留挑战审计，确认无在途注册后再单独评估删表。

create table if not exists auth_email_verification (
    id varchar(64) primary key comment '验证码挑战随机 ID，不包含邮箱或验证码',
    user_id varchar(64) comment '成功注册后绑定的账号；待发送和未完成挑战为空',
    purpose varchar(32) not null comment '验证码用途，当前仅 REGISTER',
    email_digest varchar(128) not null comment '规范化邮箱的带密钥 HMAC 摘要，不保存待注册邮箱明文',
    request_ip_digest varchar(128) not null comment '请求来源 IP 的不可逆摘要，仅用于频率保护',
    code_digest varchar(128) not null comment '验证码与挑战、邮箱绑定后的带密钥 HMAC 摘要，禁止保存明文',
    status varchar(32) not null comment 'PENDING_SEND/SENT/VERIFIED/CONSUMED/SEND_FAILED/SUPERSEDED/EXPIRED/LOCKED',
    attempt_count integer not null default 0 comment '已失败的验证码校验次数',
    max_attempts integer not null comment '该挑战允许的最大失败次数快照',
    expires_at timestamp(6) not null comment '验证码失效时间',
    sent_at timestamp(6) comment '邮件供应商确认接口调用成功的时间',
    consumed_at timestamp(6) comment '成功创建账号并消费验证码的时间',
    failure_code varchar(64) comment '稳定、无敏感信息的发送失败码',
    created_at timestamp(6) not null comment '挑战创建时间',
    updated_at timestamp(6) not null comment '最近状态更新时间',
    index idx_auth_email_verification_user (user_id),
    index idx_auth_email_verification_email_time (email_digest, created_at),
    index idx_auth_email_verification_ip_time (request_ip_digest, created_at),
    index idx_auth_email_verification_status_expiry (status, expires_at),
    constraint fk_auth_email_verification_user foreign key (user_id) references app_user(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
