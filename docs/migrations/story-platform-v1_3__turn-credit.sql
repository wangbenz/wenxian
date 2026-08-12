-- 平台回合账户第一阶段：仅新增中性赠送、预占和流水表，不迁移旧额度，也不切换线上扣减路径。
-- 正式发布时必须先执行只读余额预演，再在受控停写窗口生成 LEGACY_OPENING 期初批次。

create table if not exists turn_credit_grant (
    id varchar(80) primary key comment '平台回合赠送批次ID',
    user_id varchar(64) not null comment '获得回合的玩家账号ID，四份命卷共享',
    credit_class varchar(24) not null comment '回合类别：SPONSORED赠送或PURCHASED未来购买',
    grant_type varchar(32) not null comment '发放来源：INITIAL、PLAYTEST、FEEDBACK、ACTIVITY、ADMIN等',
    campaign_code varchar(64) comment '运营活动或测试批次编码，可为空',
    source_reference varchar(160) comment '反馈编号、活动编号或未来订单号等外部来源',
    idempotency_key varchar(180) not null comment '发放幂等键，同一业务奖励只能成功一次',
    granted_count integer not null comment '本批最初发放总数',
    available_count integer not null comment '当前可预占数量',
    reserved_count integer not null default 0 comment '正在生成但尚未正式提交的预占数量',
    consumed_count integer not null default 0 comment '已经随正式成功回合消耗的数量',
    expired_count integer not null default 0 comment '到期未使用数量',
    revoked_count integer not null default 0 comment '被运营撤销且未使用的数量',
    status varchar(24) not null comment '批次状态：ACTIVE、EXHAUSTED、EXPIRED或REVOKED',
    expires_at datetime(6) comment '到期时间；为空表示长期有效',
    reason varchar(240) not null comment '不含隐私的发放原因',
    created_by varchar(64) not null comment 'SYSTEM或执行操作的管理员ID',
    created_at datetime(6) not null comment '发放时间',
    updated_at datetime(6) not null comment '最后状态变化时间',
    unique key uk_turn_credit_grant_idempotency (idempotency_key),
    index idx_turn_credit_grant_user_available (user_id, status, expires_at, available_count)
        comment '按账号和到期时间选择下一份可用回合',
    index idx_turn_credit_grant_campaign (campaign_code, created_at)
        comment '按测试或运营批次统计发放情况',
    constraint fk_turn_credit_grant_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint ck_turn_credit_grant_nonnegative check (
        granted_count >= 0 and available_count >= 0 and reserved_count >= 0 and consumed_count >= 0
        and expired_count >= 0 and revoked_count >= 0
    ),
    constraint ck_turn_credit_grant_conservation check (
        granted_count = available_count + reserved_count + consumed_count + expired_count + revoked_count
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='账号平台回合赠送批次表';

create table if not exists turn_credit_reservation (
    id varchar(80) primary key comment '平台回合预占ID',
    user_id varchar(64) not null comment '预占玩家账号ID',
    save_id varchar(80) not null comment '发起行动的命卷ID，不依赖具体版本命卷表外键',
    request_id varchar(80) not null comment 'H5行动幂等请求号',
    grant_id varchar(80) not null comment '实际预占的赠送批次ID',
    amount integer not null default 1 comment '预占数量，当前每个成功剧情回合固定为1',
    status varchar(24) not null comment '预占状态：RESERVED、CONSUMED、RELEASED或EXPIRED',
    expires_at datetime(6) not null comment '异常中断后允许自动释放的时间',
    turn_id varchar(80) comment '成功消费后关联的正式回合ID',
    created_at datetime(6) not null comment '预占时间',
    updated_at datetime(6) not null comment '最后状态变化时间',
    unique key uk_turn_credit_reservation_request (user_id, save_id, request_id),
    index idx_turn_credit_reservation_expiry (status, expires_at)
        comment '清理超时预占，避免进程异常永久占用余额',
    index idx_turn_credit_reservation_grant (grant_id, status),
    constraint fk_turn_credit_reservation_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_turn_credit_reservation_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint ck_turn_credit_reservation_amount check (amount > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='平台回合跨进程临时预占表';

create table if not exists turn_credit_ledger (
    id varchar(80) primary key comment '平台回合流水ID',
    user_id varchar(64) not null comment '流水所属玩家账号ID',
    grant_id varchar(80) not null comment '关联赠送批次ID',
    reservation_id varchar(80) comment '关联预占ID；直接发放或撤销时为空',
    event_type varchar(24) not null comment 'GRANT、RESERVE、RELEASE、CONSUME、EXPIRE或REVOKE',
    event_key varchar(200) not null comment '流水业务幂等键，同一状态变化只能记录一次',
    available_delta integer not null default 0 comment '本次对可用数量的变化',
    reserved_delta integer not null default 0 comment '本次对预占数量的变化',
    consumed_delta integer not null default 0 comment '本次对已消费数量的变化',
    balance_after integer not null comment '该事件完成后的账号可用平台回合数',
    business_reference varchar(160) comment '关联回合、批次或外部业务编号',
    operator_id varchar(64) not null comment 'SYSTEM或执行操作的管理员ID',
    remark varchar(240) not null comment '不含隐私的变动说明',
    created_at datetime(6) not null comment '流水发生时间',
    unique key uk_turn_credit_ledger_event (event_key),
    index idx_turn_credit_ledger_user_time (user_id, created_at)
        comment '玩家档案按时间查询回合变动',
    index idx_turn_credit_ledger_grant_time (grant_id, created_at),
    constraint fk_turn_credit_ledger_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_turn_credit_ledger_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint fk_turn_credit_ledger_reservation foreign key (reservation_id) references turn_credit_reservation(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='平台回合不可变审计流水表';
