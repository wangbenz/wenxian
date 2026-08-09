-- 管理台当前命卷排行榜索引。
-- 仅优化查询，不修改玩家命卷、回合或状态数据。
alter table story_save
    add index idx_story_save_status_turn (status, turn_number, updated_at)
    comment '按生命周期和回合数查询当前命卷排行榜';
