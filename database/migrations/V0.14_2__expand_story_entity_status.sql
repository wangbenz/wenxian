-- 统一命卷迁移兼容补丁：旧命卷中的物品和能力状态允许保存简短自然语言，
-- 不能按传统枚举长度截断。该变更只扩展字段容量，不修改任何玩家数据。
alter table story_entity
    modify column status varchar(255) not null comment '实体当前自然状态编码或简短自然语言状态';
