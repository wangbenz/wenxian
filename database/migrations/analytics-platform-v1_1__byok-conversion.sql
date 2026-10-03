-- 玩家自备服务转化统计迁移。
-- 前置条件：已执行 analytics-platform-v1.sql、story-platform-v1.sql 和 model-platform-v1.sql。
-- 本脚本不会读取或复制玩家密钥，只回填当前仍存在凭证的首次接入事实并补充管理台查询索引。
-- 非幂等：每个目标库只执行一次；重复执行前必须先核对三个新增索引是否已经存在。

insert ignore into analytics_event(
    id, event_key, event_type, user_id, save_id, release_version, engine_code,
    data_origin, metadata_json, occurred_at, recorded_at
)
select concat('analytics-byok-', md5(concat(c.user_id, ':', c.provider_code))),
       concat('BYOK_CREDENTIAL_CONNECTED:', c.user_id, ':', c.provider_code),
       'BYOK_CREDENTIAL_CONNECTED', c.user_id, null, 'UNKNOWN', '',
       'BACKFILL', json_object('providerCode', c.provider_code), c.created_at, current_timestamp(6)
from user_ai_provider_credential c;

alter table user_ai_provider_credential
    add index idx_user_credential_provider_status (provider_code, status, updated_at)
        comment '管理台按供应商和凭证状态查询当前脱敏台账',
    add index idx_user_credential_created (created_at)
        comment '管理台按首次成功接入时间统计玩家Key转化';

alter table story_turn
    add index idx_story_turn_credential_created (credential_source, created_at)
        comment '管理台按凭证来源和日期统计成功自备推演回合';
