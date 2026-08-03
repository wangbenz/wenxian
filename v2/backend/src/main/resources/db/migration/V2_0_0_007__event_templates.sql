create table v2_event_template (
    id varchar(80) primary key,
    content_package_id varchar(80) not null,
    template_code varchar(80) not null,
    title varchar(160) not null,
    event_scale varchar(24) not null,
    initial_stage varchar(32) not null,
    description text not null,
    default_transition_minutes bigint,
    active boolean not null default true,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    unique (content_package_id, template_code),
    constraint fk_v2_event_template_package foreign key (content_package_id)
        references v2_content_package(id)
);

insert into v2_event_template(
    id,content_package_id,template_code,title,event_scale,initial_stage,description,
    default_transition_minutes,active,created_at,updated_at)
values (
    'event-template-qingqi-mine-disturbance','content-qingqi-020',
    'QINGQI_MINE_DISTURBANCE','照骨矿异动','REGIONAL','SEED',
    '照骨矿深处的异常会随统一世界时间自行发展；玩家可以介入，也可以离开。',
    10080,true,current_timestamp,current_timestamp);
