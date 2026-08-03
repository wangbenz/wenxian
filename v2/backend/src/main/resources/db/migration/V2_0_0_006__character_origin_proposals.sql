create table v2_origin_proposal (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    request_fingerprint varchar(64) not null,
    candidates_json text not null,
    expires_at timestamp(6) not null,
    consumed_at timestamp(6),
    created_at timestamp(6) not null,
    constraint fk_v2_origin_proposal_user foreign key (user_id) references app_user(id) on delete cascade
);

create index idx_v2_origin_proposal_user_time on v2_origin_proposal(user_id, created_at);

create table v2_character_origin (
    character_id varchar(80) primary key,
    save_id varchar(80) not null,
    fate_title varchar(80) not null,
    public_identity varchar(160) not null,
    opening_situation varchar(500) not null,
    unpaid_debt varchar(300) not null,
    created_at timestamp(6) not null,
    constraint fk_v2_character_origin_character foreign key (character_id) references v2_character(id) on delete cascade,
    constraint fk_v2_character_origin_save foreign key (save_id) references v2_game_save(id) on delete cascade
);

create index idx_v2_character_origin_save on v2_character_origin(save_id);
