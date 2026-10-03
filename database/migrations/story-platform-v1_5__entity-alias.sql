-- V0.15 可信实体身份：稳定实体改名后保留长期别名，避免同一人物、灵宠或物品被重复创建。
-- 本迁移不回写旧正文，也不自动合并历史同名实体；历史数据只能在可信回放或显式修正后补充别名。

create table if not exists story_entity_alias (
    id varchar(80) primary key comment '实体长期别名记录ID',
    save_id varchar(80) not null comment '所属统一命卷ID',
    entity_id varchar(80) not null comment '别名对应的稳定实体ID，改名不会改变该ID',
    alias_name varchar(160) not null comment '玩家或已提交剧情确认的原始别名',
    normalized_alias varchar(160) not null comment '仅用于同命卷精确归一查找的别名',
    alias_type varchar(32) not null comment '别名类型：ORIGINAL_NAME、RENAMED_FROM、TITLE或SOURCE_NAME',
    source_turn integer comment '首次确认该别名的正式回合；旧数据可为空',
    active boolean not null default true comment '是否仍允许用于实体解析',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '最后更新时间',
    unique key uk_story_entity_alias_entity_name (save_id, entity_id, normalized_alias),
    key idx_story_entity_alias_lookup (save_id, normalized_alias, active),
    constraint fk_story_entity_alias_save foreign key (save_id) references story_save(id) on delete cascade,
    constraint fk_story_entity_alias_entity foreign key (entity_id) references story_entity(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='统一命卷实体长期别名表';
