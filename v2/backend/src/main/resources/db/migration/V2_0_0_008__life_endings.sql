create table v2_life_ending (
    save_id varchar(80) primary key,
    protagonist_id varchar(80) not null,
    ending_type varchar(32) not null,
    title varchar(120) not null,
    summary varchar(1000) not null,
    final_realm_code varchar(60) not null,
    final_realm_display_name varchar(120) not null,
    final_location_id varchar(80) not null,
    final_turn_number int not null,
    ended_world_minute bigint not null,
    created_at timestamp(6) not null,
    constraint fk_v2_life_ending_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_life_ending_character foreign key (protagonist_id) references v2_character(id),
    constraint fk_v2_life_ending_location foreign key (final_location_id) references v2_location_definition(id)
);
