-- 推演点多批次预占兼容迁移。
-- 物理表名暂时保留 turn_credit_*，避免在结算能力升级时同时承担全量改名风险。
-- 前置条件：已执行 service-plan-v1.sql 与 story-platform-v1_3__turn-credit.sql，且本脚本尚未执行。
-- 本脚本包含 ALTER TABLE 和历史分摊回填，不可重复执行；执行前必须确认最近可恢复点。

alter table turn_credit_reservation
    add column service_plan_version_id varchar(64) null
        comment '请求开始时冻结的三档方案版本ID；旧回合兼容记录为空' after amount,
    add column tier_code varchar(16) null
        comment '请求开始时冻结的档位编码：LIGHT、BALANCED或DEEP；旧回合兼容记录为空' after service_plan_version_id,
    add constraint fk_turn_credit_reservation_tier
        foreign key (service_plan_version_id, tier_code)
        references ai_service_plan_tier(plan_version_id, tier_code);

create table turn_credit_reservation_allocation (
    reservation_id varchar(80) not null comment '所属推演点预占ID',
    grant_id varchar(80) not null comment '实际提供点数的赠送批次ID',
    amount integer not null comment '从该批次预占的推演点数量',
    primary key (reservation_id, grant_id),
    index idx_turn_credit_allocation_grant (grant_id, reservation_id)
        comment '按赠送批次追踪尚在预占中的点数来源',
    constraint fk_turn_credit_allocation_reservation foreign key (reservation_id)
        references turn_credit_reservation(id) on delete cascade,
    constraint fk_turn_credit_allocation_grant foreign key (grant_id)
        references turn_credit_grant(id),
    constraint ck_turn_credit_allocation_amount check (amount > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
  comment='一次推演点预占在多个赠送批次上的不可变分摊';

-- 历史预占的 grant_id 是原来的唯一资金来源，先回填为一条分摊再切换新代码。
insert into turn_credit_reservation_allocation(reservation_id, grant_id, amount)
select id, grant_id, amount from turn_credit_reservation;
