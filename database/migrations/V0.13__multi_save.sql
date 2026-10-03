-- Execute once against MySQL 8 before releasing V0.13 multi-save support.
alter table game_save add column slot_no smallint not null default 1 after user_id;

-- Keep a dedicated index for the user foreign key before replacing the legacy
-- unique(user_id) index. MySQL refuses to drop an index used by a foreign key.
alter table game_save add index idx_game_save_user_fk (user_id);

set @legacy_unique := (
  select index_name from information_schema.statistics
  where table_schema = database() and table_name = 'game_save' and non_unique = 0
  group by index_name
  having group_concat(column_name order by seq_in_index) = 'user_id'
  limit 1
);
set @drop_legacy := if(@legacy_unique is null, 'select 1',
  concat('alter table game_save drop index `', replace(@legacy_unique, '`', '``'), '`'));
prepare drop_legacy_statement from @drop_legacy;
execute drop_legacy_statement;
deallocate prepare drop_legacy_statement;

alter table game_save
  add unique key uk_game_save_user_slot (user_id, slot_no),
  add index idx_game_save_user_updated (user_id, updated_at);

insert into gameplay_config(config_key, config_value, updated_at)
values ('max_active_saves', '2', current_timestamp(6))
on duplicate key update config_value = config_value;

insert into gameplay_config(config_key, config_value, updated_at)
values ('current_game_version', 'V0.13', current_timestamp(6))
on duplicate key update config_value = values(config_value), updated_at = values(updated_at);

alter table ai_usage_log
  add column game_version varchar(32) not null default 'UNKNOWN' after model;

create table if not exists game_analytics_event (
  id varchar(80) primary key,
  user_id varchar(64) not null,
  save_id varchar(80),
  game_version varchar(32) not null,
  event_type varchar(48) not null,
  metadata_json varchar(1000) not null default '{}',
  created_at datetime(6) not null,
  index idx_analytics_version_event (game_version, event_type, created_at),
  index idx_analytics_user_created (user_id, created_at),
  index idx_analytics_save_created (save_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists game_access_whitelist (
  id varchar(64) primary key,
  user_id varchar(64) not null unique,
  note varchar(200) not null default '',
  created_by varchar(64) not null default 'system',
  created_at datetime(6) not null,
  updated_at datetime(6) not null,
  index idx_access_whitelist_user (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into gameplay_config(config_key, config_value, updated_at) values
  ('game_access_mode', 'OPEN', current_timestamp(6)),
  ('game_access_notice_title', '命卷暂时封存', current_timestamp(6)),
  ('game_access_notice_content', '游戏正在更新中，请稍后再回来。', current_timestamp(6)),
  ('game_access_resume_at', '', current_timestamp(6))
on duplicate key update config_value = config_value;
