-- 增加可信回合诊断，不改变命卷权威事实和玩家接口。
-- 该表只保存稳定引用与变化概要，禁止保存完整 Prompt、剧情正文、邮箱和密钥。

create table if not exists story_turn_diagnostic (
    id varchar(80) primary key comment '可信回合诊断ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    request_id varchar(80) not null comment '客户端幂等请求号，用于关联成功回合或最终失败',
    turn_number integer not null comment '本次尝试准备写入的回合序号',
    expected_revision bigint not null comment '玩家提交行动时携带的命卷revision',
    prompt_version varchar(64) not null comment '上下文与Prompt策略版本',
    status varchar(24) not null comment '诊断状态：PREPARING、GENERATED、REVIEWED、REPAIRING、REDUCED、COMMITTED或FAILED',
    current_stage varchar(48) not null comment '当前或最终处理阶段编码',
    context_manifest_json longtext not null comment '本轮加载的稳定实体、事实、记忆和摘要来源清单，不保存完整Prompt',
    action_contract_json longtext not null comment '行动解析产生的引用、前置条件与玩家授权边界概要',
    proposal_trace_json longtext not null comment '首次及修复提案的结构化变化概要，不保存剧情正文',
    review_trace_json longtext not null comment '独立审阅通过、拒绝、原因和恢复方式概要',
    commit_summary_json longtext not null comment 'Reducer最终接受及成功提交的事实变化概要',
    failure_code varchar(64) not null default '' comment '最终失败类别，不保存异常栈',
    failure_detail varchar(1000) not null default '' comment '脱敏并截断的诊断说明',
    created_at datetime(6) not null comment '诊断开始时间',
    updated_at datetime(6) not null comment '最后阶段更新时间',
    unique key uk_story_turn_diagnostic_request (save_id, request_id),
    key idx_story_turn_diagnostic_save_turn (save_id, turn_number),
    key idx_story_turn_diagnostic_status_time (status, updated_at),
    constraint fk_story_turn_diagnostic_save foreign key (save_id) references story_save(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='可信回合上下文、审阅与事实提交诊断表';
